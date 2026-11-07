package com.vision.app;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JNI wrapper for the embedded llama.cpp runtime library ("vision_llama").
 *
 * Handle lifecycle protocol:
 * - load() returns an opaque identity TOKEN, never the native pointer. Tokens
 *   come from a monotonic sequence and are never reused, so the raw-pointer ABA
 *   problem cannot occur: if a freed native address is recycled by a later
 *   load, the old token still maps to nothing and a stale caller can never
 *   reach the new model. The native pointer lives only inside the registered
 *   HandleState; the private native entry points receive that stored pointer,
 *   never a caller-supplied value.
 * - load() registers every live handle in HANDLE_STATES. Wrapper operations on
 *   an unknown handle (never loaded through load(), or already unloaded) are
 *   rejected before any JNI entry, so a fabricated handle value cannot reach
 *   the native reinterpret cast.
 * - Each handle has a fair lock that generate() and formatChat() hold for the
 *   whole native call: llama contexts are not safe for concurrent decode, so
 *   operations on one handle serialize.
 * - unload() sets the volatile closing flag FIRST, so operations that are
 *   queued on the lock reject on admission and never run after closing starts;
 *   it then removes the state atomically, so exactly one concurrent unloader
 *   proceeds to disposal. The unloader waits a bounded time (tryLock) for at
 *   most the currently-executing operation, never a queue.
 * - If that bounded wait expires, disposal is delegated to the still-running
 *   operation's exit path: the map entry is already gone (no stale growth) and
 *   the operation frees the native resources exactly once when it finishes.
 *   Native resources are never freed while a native call is using them.
 * - Deadlines are cooperative: the native abort callback asks llama_decode to
 *   stop (CPU execution only). It is not an absolute wall-clock bound and not
 *   cancellation. A call passed timeoutMs <= 0 has no deadline; unload's wait
 *   stays bounded regardless through the delegated-disposal fallback.
 * - generate() never returns partial output: a deadline abort returns null.
 *
 * nativeLoad is package-private solely for the invalid-path rejection test
 * seam in this package (which never obtains a valid handle). The other native
 * entry points are private so the lifecycle protocol cannot be bypassed.
 */
public class LlamaBridge implements LlamaBridgeInterface {
    private static volatile boolean libraryLoaded = false;
    private static Throwable loadError = null;

    static {
        try {
            System.loadLibrary("vision_llama");
            libraryLoaded = true;
        } catch (Throwable t) {
            loadError = t;
        }
    }

    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }

    public static Throwable getLoadError() {
        return loadError;
    }

    private static final long UNLOAD_DISPOSAL_WAIT_MS = 30_000;

    // Test seams (package-private, never used in production), mirroring
    // ModelManager.setFileMoverForTesting. Backend hooks are bound to the
    // registered HandleState itself - not to globals - so a test worker that
    // outlives its test can never reach the REAL native entry points through a
    // fabricated handle, and no hook reset can race an in-flight operation.
    // Registered fake states are left to garbage collection rather than
    // unregistered, so a disposal that is still delegated can never lose its
    // state.
    static volatile long unloadDisposalWaitMsForTesting = -1;
    interface NativeUnloadHook { void unload(long handle); }
    interface NativeCountHook { int count(long handle, String text); }
    interface NativeGenerateHook {
        byte[] generate(long handle, String prompt, String gbnfGrammar, int maxTokens, long timeoutMs);
    }

    private static final class HandleState {
        // The native pointer this token stands for. Captured once at load;
        // only this stored value is ever passed to the private native entry
        // points, so caller-supplied or recycled pointer values never cross JNI.
        final long nativePointer;
        final ReentrantLock lock = new ReentrantLock(true);
        volatile boolean closing;
        volatile boolean disposalDelegated;
        boolean freed; // guarded by lock
        // Test-only hooks and admission barrier; null in production.
        final NativeGenerateHook generateHook;
        final NativeUnloadHook unloadHook;
        final NativeCountHook countHook;
        volatile java.util.concurrent.CountDownLatch preLockReachedForTesting;
        volatile java.util.concurrent.CountDownLatch preLockBarrierForTesting;

        HandleState(long nativePointer) {
            this(nativePointer, null, null, null);
        }

        HandleState(long nativePointer, NativeGenerateHook generateHook, NativeUnloadHook unloadHook) {
            this(nativePointer, generateHook, unloadHook, null);
        }

        HandleState(long nativePointer, NativeGenerateHook generateHook, NativeUnloadHook unloadHook,
                NativeCountHook countHook) {
            this.nativePointer = nativePointer;
            this.generateHook = generateHook;
            this.unloadHook = unloadHook;
            this.countHook = countHook;
        }
    }
    private static final ConcurrentHashMap<Long, HandleState> HANDLE_STATES = new ConcurrentHashMap<>();

    // Opaque identity tokens: monotonically increasing and never reused while
    // the sequence stays within its positive range, so a recycled native
    // address can never make a stale token valid again. That "never" is
    // qualified to the positive range: after 2^63-1 mints the sequence would
    // wrap, so the mint then fails closed PERMANENTLY (the sequence is parked
    // at Long.MIN_VALUE) rather than issuing token 0, a negative value, or a
    // wrapped value that could collide with an earlier token.
    private static final java.util.concurrent.atomic.AtomicLong TOKEN_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong(1);

    private static long nextToken() {
        long token = TOKEN_SEQUENCE.getAndIncrement();
        if (token <= 0) {
            TOKEN_SEQUENCE.set(Long.MIN_VALUE);
            throw new IllegalStateException("handle token space exhausted");
        }
        return token;
    }

    static void setTokenSequenceForTesting(long next) {
        TOKEN_SEQUENCE.set(next);
    }

    // The one production registration path: every live native pointer is bound
    // to a freshly minted token here and only here.
    private static long registerLoadedPointer(long nativePointer, NativeGenerateHook generateHook,
            NativeUnloadHook unloadHook) {
        return registerLoadedPointer(nativePointer, generateHook, unloadHook, null);
    }

    private static long registerLoadedPointer(long nativePointer, NativeGenerateHook generateHook,
            NativeUnloadHook unloadHook, NativeCountHook countHook) {
        long token = nextToken();
        HANDLE_STATES.put(token, new HandleState(nativePointer, generateHook, unloadHook, countHook));
        return token;
    }

    static long registerLoadedPointerForTesting(long nativePointer, NativeGenerateHook generateHook,
            NativeUnloadHook unloadHook) {
        return registerLoadedPointerForTesting(nativePointer, generateHook, unloadHook, null);
    }

    static long registerLoadedPointerForTesting(long nativePointer, NativeGenerateHook generateHook,
            NativeUnloadHook unloadHook, NativeCountHook countHook) {
        // Same mint and map as production registration; hooks receive the
        // STORED pointer (exactly what native code would receive), so tests
        // verify pointer identity without any real JNI entry.
        return registerLoadedPointer(nativePointer, generateHook, unloadHook, countHook);
    }

    // Test seam (package-private instance field, null in production): replaces
    // the native model-load backend so tests can drive the REAL load() and
    // token allocator deterministically - including simulated allocator reuse,
    // where the backend hands back the same nonzero pointer for the next load.
    // Being instance-bound, it can never leak into another bridge or be reset
    // under a live worker of another instance.
    static final class LoadedModel {
        final long nativePointer;
        final NativeGenerateHook generateHook;
        final NativeUnloadHook unloadHook;

        LoadedModel(long nativePointer, NativeGenerateHook generateHook, NativeUnloadHook unloadHook) {
            this.nativePointer = nativePointer;
            this.generateHook = generateHook;
            this.unloadHook = unloadHook;
        }
    }
    interface NativeLoadHook {
        LoadedModel load(String path, int ctxTokens, int threads);
    }
    NativeLoadHook nativeLoadHookForTesting = null;

    static int registeredCountForTesting() {
        return HANDLE_STATES.size();
    }

    static boolean isRegisteredForTesting(long handle) {
        return HANDLE_STATES.containsKey(handle);
    }

    static void armAdmissionBarrierForTesting(long handle,
            java.util.concurrent.CountDownLatch reached,
            java.util.concurrent.CountDownLatch admit) {
        HandleState state = HANDLE_STATES.get(handle);
        if (state != null) {
            state.preLockReachedForTesting = reached;
            state.preLockBarrierForTesting = admit;
        }
    }

    private static long disposalWaitMs() {
        long override = unloadDisposalWaitMsForTesting;
        return override >= 0 ? override : UNLOAD_DISPOSAL_WAIT_MS;
    }

    private static void disposeNative(long token, HandleState state) {
        if (state.unloadHook != null) {
            state.unloadHook.unload(state.nativePointer);
            return;
        }
        nativeUnload(state.nativePointer);
    }

    private static byte[] invokeGenerate(long token, HandleState state, String prompt,
            String gbnfGrammar, int maxTokens, long timeoutMs) {
        if (state.generateHook != null) {
            return state.generateHook.generate(state.nativePointer, prompt, gbnfGrammar, maxTokens, timeoutMs);
        }
        return nativeGenerate(state.nativePointer, prompt, gbnfGrammar, maxTokens, timeoutMs);
    }

    // If an unload timed out while this operation was running, disposal was
    // delegated to the operation's exit path: free exactly once here.
    private static void maybeDelegatedDispose(long handle, HandleState state) {
        if (!state.closing || !state.disposalDelegated) {
            return;
        }
        if (!state.lock.tryLock()) {
            return; // another exiting operation owns the disposal
        }
        boolean shouldFree = false;
        try {
            if (!state.freed) {
                state.freed = true;
                shouldFree = true;
            }
        } finally {
            state.lock.unlock();
        }
        if (shouldFree) {
            try {
                disposeNative(handle, state);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public long load(String path, int ctxTokens, int threads) {
        if (!libraryLoaded || path == null) return 0;
        NativeLoadHook hook = nativeLoadHookForTesting;
        try {
            if (hook != null) {
                LoadedModel loaded = hook.load(path, ctxTokens, threads);
                if (loaded == null || loaded.nativePointer == 0) {
                    return 0;
                }
                try {
                    return registerLoadedPointer(loaded.nativePointer, loaded.generateHook, loaded.unloadHook);
                } catch (Throwable registrationFailure) {
                    releaseOwnedPointer(loaded.nativePointer, loaded.unloadHook);
                    return 0;
                }
            }
            long nativePointer = nativeLoad(path, ctxTokens, threads);
            if (nativePointer != 0) {
                try {
                    return registerLoadedPointer(nativePointer, null, null);
                } catch (Throwable registrationFailure) {
                    releaseOwnedPointer(nativePointer, null);
                    return 0;
                }
            }
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    // A pointer whose registration failed (e.g. the token mint refused on
    // sequence wrap) is owned by this call and is disposed here on a
    // best-effort basis: disposal is attempted immediately, through the test
    // hook when one supplied it, but a failing unload is swallowed because it
    // cannot be reported from this path and must never mask the registration
    // failure - release is therefore NOT guaranteed if native unload itself
    // fails.
    private static void releaseOwnedPointer(long nativePointer, NativeUnloadHook unloadHook) {
        try {
            if (unloadHook != null) {
                unloadHook.unload(nativePointer);
            } else {
                nativeUnload(nativePointer);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String generate(long handle, String prompt, String gbnfGrammar, int maxTokens, long timeoutMs) {
        if (!libraryLoaded || handle == 0 || prompt == null) return null;
        HandleState state = HANDLE_STATES.get(handle);
        if (state == null || state.closing) return null;
        // Test-only admission barrier: lets a test deterministically queue an
        // operation between the state fetch and the lock acquisition so the
        // closing admission check below is exercised against a queued caller.
        if (state.preLockReachedForTesting != null) {
            state.preLockReachedForTesting.countDown();
            try {
                state.preLockBarrierForTesting.await(15_000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        state.lock.lock();
        try {
            if (state.closing) return null;
            try {
                byte[] bytes = invokeGenerate(handle, state, prompt, gbnfGrammar, maxTokens, timeoutMs);
                return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
            } catch (Throwable t) {
                return null;
            }
        } finally {
            state.lock.unlock();
            maybeDelegatedDispose(handle, state);
        }
    }

    @Override
    public void unload(long handle) {
        if (!libraryLoaded || handle == 0) return;
        HandleState state = HANDLE_STATES.get(handle);
        if (state == null) return;
        // Closing starts before any lock acquisition: operations queued on the
        // lock reject on admission, and new lookups fail once the state is
        // removed below. The unloader therefore waits for at most the one
        // operation currently executing, never a queue.
        state.closing = true;
        if (!HANDLE_STATES.remove(handle, state)) {
            return; // another concurrent unloader owns the disposal
        }
        boolean acquired = false;
        try {
            acquired = state.lock.tryLock(disposalWaitMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (acquired) {
            boolean shouldFree = false;
            try {
                if (!state.freed) {
                    state.freed = true;
                    shouldFree = true;
                }
            } finally {
                state.lock.unlock();
            }
            if (shouldFree) {
                try {
                    disposeNative(handle, state);
                } catch (Throwable ignored) {
                }
            }
        } else {
            // Bounded wait expired: an operation is still running. Delegate the
            // free to its exit path so cleanup is not lost. The re-check closes
            // the window where the operation finished between the tryLock
            // timeout and the flag being set.
            state.disposalDelegated = true;
            maybeDelegatedDispose(handle, state);
        }
    }

    /**
     * Exact token count for text as nativeGenerate would tokenize it (same
     * llama_tokenize flags). Returns -1 on any failure so callers fail closed.
     */
    @Override
    public int countTokens(long handle, String text) {
        if (!libraryLoaded || handle == 0 || text == null) return -1;
        HandleState state = HANDLE_STATES.get(handle);
        if (state == null || state.closing) return -1;
        // Same admission pattern as generate(): a caller queued before the
        // lock must re-check closing under it.
        if (state.preLockReachedForTesting != null) {
            state.preLockReachedForTesting.countDown();
            try {
                state.preLockBarrierForTesting.await(15_000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        state.lock.lock();
        try {
            if (state.closing) return -1;
            try {
                if (state.countHook != null) {
                    return state.countHook.count(state.nativePointer, text);
                }
                return nativeCountTokens(state.nativePointer, text);
            } catch (Throwable t) {
                return -1;
            }
        } finally {
            state.lock.unlock();
            maybeDelegatedDispose(handle, state);
        }
    }

    @Override
    public String formatChat(long handle, String templateHint, String systemPrompt, String userMessage) {
        if (!libraryLoaded || handle == 0) return null;
        HandleState state = HANDLE_STATES.get(handle);
        if (state == null || state.closing) return null;
        state.lock.lock();
        try {
            if (state.closing) return null;
            try {
                byte[] bytes = nativeFormatChat(state.nativePointer, templateHint, systemPrompt, userMessage);
                return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
            } catch (Throwable t) {
                return null;
            }
        } finally {
            state.lock.unlock();
            maybeDelegatedDispose(handle, state);
        }
    }

    static native long nativeLoad(String path, int ctxTokens, int threads);
    private static native byte[] nativeGenerate(long handle, String prompt, String gbnfGrammar, int maxTokens, long timeoutMs);
    private static native void nativeUnload(long handle);
    private static native byte[] nativeFormatChat(long handle, String templateHint, String systemPrompt, String userMessage);
    private static native int nativeCountTokens(long nativePointer, String text);
}
