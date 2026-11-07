package com.vision.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * Exercises the real vision_llama native library on device WITHOUT model weights.
 * Invalid-path rejection is asserted through DIRECT nativeLoad calls: the public
 * wrapper LlamaBridge.load swallows Throwable into 0, so wrapper-level assertions
 * could pass without the native entry ever running. Direct calls surface an
 * UnsatisfiedLinkError if the library failed to load. No DIRECT native call ever
 * receives a fabricated nonzero handle - native code would dereference it - and
 * nativeLoad(null, ...) is not called (jstring null is undefined there). Wrapper
 * methods are exercised with fabricated handles to prove the Java lifecycle map
 * rejects them before any native entry; deterministic lifecycle races use
 * test-registered states whose hooks are bound to the state itself, never to
 * globals, so a test worker can never reach the real native entry points.
 */
@RunWith(AndroidJUnit4.class)
public class NativeBridgeInstrumentedTest {

    @Test
    public void nativeLibraryLoadsOnDeviceAbi() {
        assertTrue("vision_llama must load on this device ABI: " + LlamaBridge.getLoadError(),
                LlamaBridge.isLibraryLoaded());
    }

    @Test
    public void nativeLoadRejectsNonexistentPathDirectly() {
        // Direct native entry: 0 can only come from the real loader rejecting the path.
        assertEquals("missing file must be rejected by the real native loader", 0L,
                LlamaBridge.nativeLoad("/nonexistent/definitely_not_here.gguf", 128, 1));
    }

    @Test
    public void nativeLoadRejectsDirectoryPathDirectly() {
        File dir = InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir();
        assertEquals("a directory must be rejected by the real native loader", 0L,
                LlamaBridge.nativeLoad(dir.getAbsolutePath(), 128, 1));
    }

    @Test
    public void nativeLoadRejectsTruncatedGgufDirectly() throws Exception {
        File dir = InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir();
        File truncated = new File(dir, "truncated_test.gguf");
        try {
            ByteBuffer bb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            bb.put(new byte[]{0x47, 0x47, 0x55, 0x46}); // "GGUF"
            bb.putInt(3);                               // version
            bb.putInt(0xDEADBEEF);                      // garbage where tensor count starts
            try (FileOutputStream fos = new FileOutputStream(truncated)) {
                fos.write(bb.array());
            }
            assertEquals("truncated GGUF must be rejected by the real native loader", 0L,
                    LlamaBridge.nativeLoad(truncated.getAbsolutePath(), 128, 1));
        } finally {
            truncated.delete();
        }
    }

    @Test
    public void wrapperGuardsZeroAndNullInputs() {
        // Wrapper-level API behavior: guards return before any native entry.
        LlamaBridge bridge = new LlamaBridge();
        assertEquals(0L, bridge.load(null, 128, 1));
        assertNull("generate with handle 0 is rejected by the Java guard",
                bridge.generate(0, "hello", null, 8, 1000));
        assertNull("formatChat with handle 0 is rejected by the Java guard",
                bridge.formatChat(0, "chatml", "system", "user"));
        bridge.unload(0); // must be a no-op, no crash
    }

    @Test
    public void wrapperRejectsUnregisteredHandleBeforeNative() {
        // A nonzero handle that never came from load() must be rejected by the
        // lifecycle map before reaching the JNI reinterpret cast.
        LlamaBridge bridge = new LlamaBridge();
        assertNull("generate on an unregistered handle is rejected before native",
                bridge.generate(987654321L, "hello", null, 8, 1000));
        assertNull("formatChat on an unregistered handle is rejected before native",
                bridge.formatChat(987654321L, "chatml", "system", "user"));
    }

    @Test
    public void unloadUnknownHandleIsNoOpAndIdempotent() {
        LlamaBridge bridge = new LlamaBridge();
        bridge.unload(987654321L);
        bridge.unload(987654321L); // second unload: no crash, no double free
    }

    @Test
    public void concurrentUnloadRegisteredHandleDisposesExactlyOnce() throws Exception {
        // Deterministic registered-state race: two barriered unloaders on one
        // registered handle; the atomic state removal must admit exactly one
        // disposal, and a later unload must be a no-op. Hooks are bound to the
        // registered state itself, so no global reset can race a live worker.
        java.util.concurrent.atomic.AtomicInteger disposeCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        // Registration goes through the same production mint as load().
        long handle = LlamaBridge.registerLoadedPointerForTesting(
                0xAAAA0001L, null, h -> disposeCount.incrementAndGet());
        LlamaBridge bridge = new LlamaBridge();
        Thread a = new Thread(() -> { awaitQuietly(start); bridge.unload(handle); });
        Thread b = new Thread(() -> { awaitQuietly(start); bridge.unload(handle); });
        a.start();
        b.start();
        start.countDown();
        a.join(15_000);
        b.join(15_000);
        assertFalse("unloader thread a must finish", a.isAlive());
        assertFalse("unloader thread b must finish", b.isAlive());
        assertEquals("exactly one unloader may dispose native resources", 1, disposeCount.get());
        bridge.unload(handle); // idempotent after disposal
        assertEquals("a later unload must not dispose again", 1, disposeCount.get());
    }

    @Test
    public void operationsRejectedAfterCloseAndDisposalIsSingle() throws Exception {
        // Deterministic admission check with explicit latches, no sleeps:
        // a running operation holds the handle lock; a second operation is
        // deterministically prefetched (state fetched, waiting before the lock)
        // and a third is submitted only after the state is verifiably removed.
        // Both late operations must reject without reaching native code, and
        // disposal must happen exactly once when the running operation ends.
        java.util.concurrent.CountDownLatch opRunning = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseOp = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger generateCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger disposeCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch prefetched = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch admitPrefetch = new java.util.concurrent.CountDownLatch(1);
        LlamaBridge.NativeGenerateHook generateHook = (h, p, g, m, t) -> {
            generateCalls.incrementAndGet();
            opRunning.countDown();
            awaitQuietly(releaseOp);
            return null;
        };
        long handle = LlamaBridge.registerLoadedPointerForTesting(
                0xBBBB0002L, generateHook, h -> disposeCount.incrementAndGet());
        LlamaBridge bridge = new LlamaBridge();
        Thread runningOp = new Thread(() -> bridge.generate(handle, "p", null, 8, 1_000));
        runningOp.start();
        assertTrue("first generate must be running", await(opRunning));

        // Arm the admission barrier on the registered state, then queue the op:
        // it fetches the state, signals the prefetch, and waits before the lock.
        assertTrue("state must be registered", LlamaBridge.isRegisteredForTesting(handle));
        LlamaBridge.armAdmissionBarrierForTesting(handle, prefetched, admitPrefetch);
        Thread queuedOp = new Thread(() -> bridge.generate(handle, "p", null, 8, 1_000));
        queuedOp.start();
        assertTrue("queued op must have prefetched the state", await(prefetched));

        Thread unloader = new Thread(() -> bridge.unload(handle));
        unloader.start();
        // Explicit close ordering: wait until the state is verifiably removed
        // (closing already set), never a sleep.
        assertTrue("unload must remove the state", awaitUnregistered(handle));

        // Late lookup: rejected via the removed map entry, before any barrier.
        assertNull("operation submitted after close must be rejected",
                bridge.generate(handle, "p", null, 8, 1_000));

        // Queued prefetcher: admitted past the barrier only now; it must reject
        // on the closing check under the lock, without reaching native code.
        admitPrefetch.countDown();
        releaseOp.countDown();
        runningOp.join(15_000);
        queuedOp.join(15_000);
        unloader.join(15_000);
        assertFalse("running generate must finish", runningOp.isAlive());
        assertFalse("queued generate must finish", queuedOp.isAlive());
        assertFalse("unloader must finish", unloader.isAlive());
        assertEquals("only the first generate may reach native code", 1, generateCalls.get());
        assertEquals("exactly one disposal", 1, disposeCount.get());
    }

    @Test
    public void staleTokenRejectedAfterSimulatedPointerReuse() {
        // ABA regression through the ACTUAL production load() and token
        // allocator: a load-backend seam hands back the SAME nonzero pointer
        // for two consecutive loads, simulating the native allocator recycling
        // a freed address. The old token must stay dead - its operations and
        // unload rejected - while the new model under its fresh token works,
        // and every native-bound call must carry exactly the stored pointer.
        long reusedPointer = 0x00C0FFEE00L;
        java.util.concurrent.atomic.AtomicInteger loadCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger staleDisposals = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger newModelTouches = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicLong stalePointerSeen = new java.util.concurrent.atomic.AtomicLong(-1);
        java.util.concurrent.atomic.AtomicLong newPointerSeen = new java.util.concurrent.atomic.AtomicLong(-1);

        LlamaBridge bridge = new LlamaBridge();
        bridge.nativeLoadHookForTesting = (path, ctxTokens, threads) -> {
            int n = loadCalls.incrementAndGet();
            if (n == 1) {
                return new LlamaBridge.LoadedModel(reusedPointer, null,
                        h -> { staleDisposals.incrementAndGet(); stalePointerSeen.set(h); });
            }
            return new LlamaBridge.LoadedModel(reusedPointer,
                    (h, p, g, m, t) -> { newModelTouches.incrementAndGet(); newPointerSeen.set(h); return null; },
                    h -> newModelTouches.incrementAndGet());
        };

        long firstToken = bridge.load("/fake/first.gguf", 1024, 2);
        assertNotEquals("first load must mint a token", 0L, firstToken);
        bridge.unload(firstToken); // first model disposed exactly once
        assertEquals(1, staleDisposals.get());
        assertEquals("disposal must pass the stored pointer", reusedPointer, stalePointerSeen.get());

        // Allocator reuse: the second load returns the SAME raw pointer.
        long secondToken = bridge.load("/fake/second.gguf", 1024, 2);
        assertNotEquals("second load must mint a token", 0L, secondToken);
        assertNotEquals("a reused pointer must mint a fresh token", firstToken, secondToken);

        // The stale token must not reach the new model: no operation, no free.
        assertNull("stale token generate must be rejected",
                bridge.generate(firstToken, "p", null, 8, 1_000));
        assertNull("stale token formatChat must be rejected",
                bridge.formatChat(firstToken, "chatml", "s", "u"));
        bridge.unload(firstToken);
        assertEquals("stale token must not dispose again", 1, staleDisposals.get());
        assertEquals("new model must be untouched by the stale token", 0, newModelTouches.get());

        // The new token operates its own model, and the native-bound value is
        // exactly the stored (recycled) pointer.
        bridge.generate(secondToken, "p", null, 8, 1_000);
        assertEquals(1, newModelTouches.get());
        assertEquals("operation must pass the stored pointer", reusedPointer, newPointerSeen.get());
        bridge.unload(secondToken);
        assertEquals(2, newModelTouches.get());
    }

    @Test
    public void loadRegistrationFailureDisposesOwnedPointer() {
        // Force token-sequence exhaustion while the load-backend seam returns
        // a nonzero pointer: registration fails inside the production load()
        // path. The owned pointer must be disposed exactly once, nothing must
        // be registered, and a later load must recover cleanly. The seam stays
        // installed for the whole test and hands back distinct pointers, so
        // no call ever reaches real JNI; the recovered model is unloaded in
        // finally so its disposal happens even if an assertion fails midway.
        LlamaBridge bridge = new LlamaBridge();
        long failedPointer = 0x00D00D00L;
        long recoveredPointer = 0x00D00D01L;
        java.util.concurrent.atomic.AtomicInteger failedDisposals = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger recoveredDisposals = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicLong disposedPointer = new java.util.concurrent.atomic.AtomicLong(-1);
        bridge.nativeLoadHookForTesting = (path, ctxTokens, threads) -> {
            if (path.endsWith("exhausted.gguf")) {
                return new LlamaBridge.LoadedModel(failedPointer, null,
                        h -> { failedDisposals.incrementAndGet(); disposedPointer.set(h); });
            }
            return new LlamaBridge.LoadedModel(recoveredPointer, null,
                    h -> recoveredDisposals.incrementAndGet());
        };

        long recovered = 0;
        try {
            int registeredBefore = LlamaBridge.registeredCountForTesting();
            LlamaBridge.setTokenSequenceForTesting(Long.MIN_VALUE);
            long token;
            try {
                token = bridge.load("/fake/exhausted.gguf", 128, 1);
            } finally {
                // Resume minting well above anything this class run could have
                // issued, so no later token can alias one minted by another test.
                LlamaBridge.setTokenSequenceForTesting(1_000_000_000L);
            }
            assertEquals("registration failure must fail closed", 0L, token);
            assertEquals("owned pointer must be disposed exactly once", 1, failedDisposals.get());
            assertEquals("disposal must receive the loaded pointer", failedPointer, disposedPointer.get());
            assertEquals("a failed load must not register any handle",
                    registeredBefore, LlamaBridge.registeredCountForTesting());

            // Recovery through the same seam: a later load mints a token, and
            // unloading it disposes only its own pointer - never the failed one.
            recovered = bridge.load("/fake/recovered.gguf", 128, 1);
            assertNotEquals("a later load must recover and mint a token", 0L, recovered);
            bridge.unload(recovered);
            recovered = 0;
            assertEquals(1, recoveredDisposals.get());
            assertEquals("the failed pointer must not be disposed again by later models",
                    1, failedDisposals.get());
        } finally {
            if (recovered != 0) {
                bridge.unload(recovered);
            }
            bridge.nativeLoadHookForTesting = null;
        }
    }

    @Test
    public void timedOutUnloadDelegatesDisposalToFinishingOperation() throws Exception {
        // Bounded-wait fallback: if unload's wait expires while an operation is
        // still running, disposal is delegated to that operation's exit path -
        // nothing is freed during use, and cleanup is not lost when it finishes.
        java.util.concurrent.CountDownLatch opRunning = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseOp = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger disposeCount = new java.util.concurrent.atomic.AtomicInteger();
        LlamaBridge.NativeGenerateHook generateHook = (h, p, g, m, t) -> {
            opRunning.countDown();
            awaitQuietly(releaseOp);
            return null;
        };
        long handle = LlamaBridge.registerLoadedPointerForTesting(
                0xCCCC0003L, generateHook, h -> disposeCount.incrementAndGet());
        LlamaBridge.unloadDisposalWaitMsForTesting = 200;
        try {
            LlamaBridge bridge = new LlamaBridge();
            Thread op = new Thread(() -> bridge.generate(handle, "p", null, 8, 1_000));
            op.start();
            assertTrue("generate must be running", await(opRunning));
            bridge.unload(handle); // bounded wait expires while the op still runs
            assertEquals("nothing may be disposed while the operation still runs", 0, disposeCount.get());
            releaseOp.countDown();
            op.join(15_000);
            assertFalse("running generate must finish", op.isAlive());
            assertEquals("the finishing operation must perform the delegated disposal",
                    1, disposeCount.get());
        } finally {
            releaseOp.countDown();
            LlamaBridge.unloadDisposalWaitMsForTesting = -1;
        }
    }

    private static boolean await(java.util.concurrent.CountDownLatch latch) throws InterruptedException {
        return latch.await(10_000, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static boolean awaitUnregistered(long handle) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (!LlamaBridge.isRegisteredForTesting(handle)) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static void awaitQuietly(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await(15_000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
