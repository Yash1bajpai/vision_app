package com.vision.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import static org.junit.Assert.*;

/**
 * Real-model single-prompt JNI/schema smoke with validator-level action-fit
 * checks on the CI emulator - NOT a usability or generation-quality claim.
 * Downloads the pinned catalog model, verifies its bytes against the catalog
 * sha256, loads it through the real JNI bridge, and generates grammar-constrained
 * proposals for two commands; each proposal must parse through the app's own
 * StrictJson, be accepted by ReasoningProposalValidator, and carry the expected
 * action (TOGGLE_TORCH with target "on"; READ_TIME after an unload/reload cycle).
 * Scoped evidence: x86_64 emulator ABI, greedy+grammar generation; arm64 coverage
 * is an unverified route not selected here.
 *
 * One further case (test4) is the production-timeout diagnostic: it runs only
 * in the workflow's opt-in production_smoke configuration (optimized x86_64
 * native build) and skips itself otherwise. Its measured scope is a single
 * bridge.generate call on a resident handle, bounded by the production
 * constant LocalModelProvider.TIMEOUT_MS - it does NOT measure the provider's
 * full request path (queueing, model load, prompt formatting, retry budget,
 * validation are all excluded), and it is not a warm-KV measurement: the
 * native bridge resets the KV state on every generate call, so the warm-up
 * below only reflects production's kept-resident weights/context, never
 * prompt KV reuse. All of load, warm-up, measured leg and unload happen in
 * one body with a single try/finally so the handle can never leak across
 * tests. Structured evidence (elapsed, raw proposal, validator outcome) is
 * printed BEFORE any assertion so over-budget or null results are preserved
 * in the log either way; the asserts gate the budget only - this stage is a
 * latency diagnostic, not semantic or production-readiness evidence.
 *
 * Budget: the workflow's outer `timeout -k 30 1200 gradle` is the only hard
 * process bound (native calls do not honor thread interrupts reliably, and a
 * native worker can outlive its JUnit timeout). The per-test JUnit timeouts
 * below (300+200+200+200+200=1100s worst case) are accounting that stays under
 * that bound, not enforcement. test5 exercises the real provider and
 * coordinator (not direct bridge) for positive, unsupported, ambiguous and
 * compound requests with exactly one provider call per request. The
 * production generation (8s) and retry (14s) budgets are COOPERATIVE and do
 * not bound model load or chat formatting, which are unbounded in the
 * source; the workflow's outer timeout remains the only hard bound. Inside test4 the warm-up generate carries its
 * own 60s bound (about 3x the warm ~20s measured on the Debug SIMD build in
 * run 42348802184: 11.5s prefill + ~1.06s/token), reserving the rest of the
 * 200s for the measured leg; the 8s production deadline is llama.cpp's
 * COOPERATIVE abort callback, not an absolute wall-clock guarantee.
 * Cleanup: teardown deletes only the unique import-model/.part names this
 * class's import could create that were absent before the class ran, so a
 * failure between the import file move and the metadata save still leaves no
 * orphan;
 * the shared metadata files (models.json, models.json.tmp) and any
 * pre-existing file are never deleted.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class RealModelInstrumentedTest {
    private static final String CATALOG_ID = "qwen2.5-1.5b-instruct-q4_k_m";
    // The identical prompt production uses: built from the registry by the provider.
    private static final String SYSTEM_PROMPT = LocalModelProvider.buildSystemPrompt();
    // Thread count for real-model loads in these diagnostics: every processor the
    // test device exposes (the CI workflow sizes the emulator per dispatch and
    // fail-closed checks the guest count, so this matches the intended
    // configuration). Production derives its own count in LocalModelProvider.
    // Each native load logs its received value (VisionLlama nativeLoad line).
    private static final int DIAGNOSTIC_LOAD_THREADS =
            Math.max(2, Runtime.getRuntime().availableProcessors());

    private static ModelInfo installed;
    private static File installedFile;
    private static String templateHint;
    private static Set<String> preexistingFiles;
    private static String expectedSafeBase;
    private static String preexistingActiveModelId;
    // Set only when the test5 teardown barrier observed a PROVEN unload
    // (callback fired AND the provider reports not loaded). AfterClass deletes
    // the imported model file only on proof; on any ambiguous outcome the file
    // is preserved rather than deleted under a live handle.
    private static volatile boolean modelUnloadProven = false;
    // Normal completion markers for every model-touching test. A test that
    // timed out or failed before its final statement has no marker, so
    // AfterClass treats the run as ambiguous and preserves the model file -
    // a timed-out native worker can outlive its test (cooperative deadlines)
    // and the file must not be deleted from under it.
    private static final java.util.Set<String> completedModelTests =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    @BeforeClass
    public static void snapshotModelsDir() {
        ModelManager mm = new ModelManager(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        String[] names = mm.getModelsDir().list();
        preexistingFiles = new HashSet<>(names != null ? Arrays.asList(names) : java.util.Collections.<String>emptyList());
        preexistingActiveModelId = mm.getActiveModelId();
        // Java-side evidence of the thread argument these diagnostics pass; the
        // authoritative applied value is each nativeLoad log's threads= field.
        System.out.println("RealModelSmoke diagnostic load threads (Java argument): "
                + DIAGNOSTIC_LOAD_THREADS + " on "
                + Runtime.getRuntime().availableProcessors() + " guest processors");
    }

    private static org.json.JSONObject catalogEntry() throws Exception {
        InputStream is = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getAssets().open("models/catalog.json");
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, "UTF-8"));
        }
        is.close();
        org.json.JSONArray arr = new org.json.JSONArray(sb.toString());
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject e = arr.getJSONObject(i);
            if (CATALOG_ID.equals(e.getString("id"))) {
                return e;
            }
        }
        throw new AssertionError("catalog entry not found: " + CATALOG_ID);
    }

    private static String sha256Of(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = new FileInputStream(f)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = is.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test(timeout = 300_000)
    public void test1_catalogModelDownloadsWithVerifiedBytes() throws Exception {
        org.json.JSONObject entry = catalogEntry();
        String catalogSha256 = entry.getString("sha256");
        templateHint = entry.getString("templateHint");
        long expectedSize = entry.getLong("sizeBytes");

        ModelManager mm = new ModelManager(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        HttpURLConnection conn = (HttpURLConnection) new URL(entry.getString("downloadUrl")).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(60_000);
        conn.setInstanceFollowRedirects(true);
        // Class-run-unique basename: a concurrent import of the same catalog
        // display name can never share this UUID, so the teardown sweep below
        // can only ever match files this class's import created.
        String base = entry.getString("fileName").replaceAll("[^a-zA-Z0-9.-]", "_");
        if (base.endsWith(".gguf")) {
            base = base.substring(0, base.length() - ".gguf".length());
        }
        expectedSafeBase = base + "-" + java.util.UUID.randomUUID() + ".gguf";
        try {
            try (InputStream is = conn.getInputStream()) {
                installed = mm.importModel(is, expectedSafeBase, expectedSize);
            }
        } finally {
            conn.disconnect();
        }
        installedFile = new File(mm.getModelsDir(), installed.fileName);
        assertTrue("downloaded model file must exist", installedFile.exists());
        assertEquals("size must match the catalog pin", expectedSize, installedFile.length());
        assertEquals("sha256 must match the catalog pin", catalogSha256, sha256Of(installedFile));
        completedModelTests.add("test1");
    }

    @Test(timeout = 200_000)
    public void test2_torchCommandYieldsValidatorAcceptedTorchOn() throws Exception {
        assertNotNull("test1 must install the model first", installedFile);
        String grammar = readAsset("grammar/action_proposal.gbnf");
        LlamaBridge bridge = new LlamaBridge();
        long handle = bridge.load(installedFile.getAbsolutePath(), 1024, DIAGNOSTIC_LOAD_THREADS);
        assertTrue("real model must load through the JNI bridge", handle != 0L);
        try {
            String prompt = bridge.formatChat(handle, templateHint, SYSTEM_PROMPT, "turn on the torch");
            assertNotNull("formatChat must produce a prompt", prompt);
            String raw = bridge.generate(handle, prompt, grammar, 256, 90_000);
            assertNotNull("generate must return output within the bound", raw);
            System.out.println("RealModelSmoke raw proposal: " + raw);
            Map<String, String> fields = StrictJson.parseObject(raw);
            assertNotNull("proposal must parse through the app strict JSON parser", fields);
            ReasoningProposalValidator.ValidationResult vr =
                    ReasoningProposalValidator.validate(fields);
            assertNotNull("validation must run", vr);
            assertNotNull("the app's own validator must accept the whole proposal"
                    + (vr.reason != null ? "; rejected: " + vr.reason : ""), vr.action);
            assertEquals("a torch command must map to the torch action",
                    VisionAction.Type.TOGGLE_TORCH, vr.action.type);
            assertEquals("turn on must carry target on", "on", vr.action.target);
        } finally {
            bridge.unload(handle);
        }
        completedModelTests.add("test2");
    }

    @Test(timeout = 200_000)
    public void test3_timeQueryAfterUnloadReloadYieldsValidatorAcceptedReadTime() throws Exception {
        assertNotNull("test1 must install the model first", installedFile);
        String grammar = readAsset("grammar/action_proposal.gbnf");
        LlamaBridge bridge = new LlamaBridge();
        long first = bridge.load(installedFile.getAbsolutePath(), 1024, DIAGNOSTIC_LOAD_THREADS);
        assertTrue(first != 0L);
        bridge.unload(first);
        long second = bridge.load(installedFile.getAbsolutePath(), 1024, DIAGNOSTIC_LOAD_THREADS);
        assertTrue("reload after unload must succeed", second != 0L);
        try {
            String prompt = bridge.formatChat(second, templateHint, SYSTEM_PROMPT, "what time is it");
            String raw = bridge.generate(second, prompt, grammar, 256, 90_000);
            assertNotNull("generate after reload must return output", raw);
            Map<String, String> fields = StrictJson.parseObject(raw);
            assertNotNull("proposal must parse through the app strict JSON parser", fields);
            ReasoningProposalValidator.ValidationResult vr =
                    ReasoningProposalValidator.validate(fields);
            assertNotNull("validation must run", vr);
            assertNotNull("the app's own validator must accept the whole proposal"
                    + (vr.reason != null ? "; rejected: " + vr.reason : ""), vr.action);
            assertEquals("a time query must map to the time action",
                    VisionAction.Type.READ_TIME, vr.action.type);
        } finally {
            bridge.unload(second);
        }
        completedModelTests.add("test3");
    }

    @Test(timeout = 200_000)
    public void test4_warmGenerationMeasuredAgainstProductionTimeout() throws Exception {
        org.junit.Assume.assumeTrue(
                "production-timeout diagnostic requires -PvisionProductionSmoke",
                "true".equals(InstrumentationRegistry.getArguments()
                        .getString("visionProductionSmoke")));
        assertNotNull("test1 must install the model first", installedFile);
        String grammar = readAsset("grammar/action_proposal.gbnf");
        LlamaBridge bridge = new LlamaBridge();
        long handle = bridge.load(installedFile.getAbsolutePath(), 1024, DIAGNOSTIC_LOAD_THREADS);
        assertTrue("real model must load through the JNI bridge", handle != 0L);
        try {
            // Warm-up on the same resident handle (resident weights/context
            // only; the bridge resets KV state every call, so this is not
            // prompt KV reuse), bounded well inside this test's 200s so the
            // measured leg below has reserved time: 60s is roughly 3x the
            // warm cost measured on the Debug SIMD build in run 42348802184
            // logcat (11.5s prefill + ~1.06s/token x 8). A warm-up failure
            // fails the case closed - no measured claim.
            String warmupPrompt = bridge.formatChat(handle, templateHint, SYSTEM_PROMPT, "turn on the torch");
            assertNotNull("formatChat must produce a prompt", warmupPrompt);
            assertNotNull("warm-up generation must complete within its 60s bound",
                    bridge.generate(handle, warmupPrompt, grammar, 8, 60_000));

            String prompt = bridge.formatChat(handle, templateHint, SYSTEM_PROMPT, "turn on the torch");
            assertNotNull("formatChat must produce a prompt", prompt);
            long startNanos = System.nanoTime();
            String raw = bridge.generate(handle, prompt, grammar, 256, LocalModelProvider.TIMEOUT_MS);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            // Structured evidence FIRST, before any assertion, so a null or
            // over-budget-but-non-null result is preserved in the log.
            System.out.println("RealModelSmoke production-timeout leg: elapsedMs=" + elapsedMs
                    + " budgetMs=" + LocalModelProvider.TIMEOUT_MS
                    + " returned=" + (raw != null ? "non-null" : "null"));
            if (raw != null) {
                System.out.println("RealModelSmoke raw proposal: "
                        + raw.replace('\n', ' ').replace('\r', ' '));
                Map<String, String> fields = StrictJson.parseObject(raw);
                if (fields != null) {
                    ReasoningProposalValidator.ValidationResult vr =
                            ReasoningProposalValidator.validate(fields);
                    System.out.println("RealModelSmoke validator outcome: type=" + fields.get("type")
                            + " accepted=" + (vr != null && vr.action != null)
                            + (vr != null && vr.reason != null ? " reason=" + vr.reason : ""));
                } else {
                    System.out.println("RealModelSmoke validator outcome: unparseable proposal");
                }
            }
            // Budget gate only (evidence above is unconditional).
            assertNotNull("generation returned null: output did not complete within the production "
                    + "timeout (" + LocalModelProvider.TIMEOUT_MS + "ms) on the optimized build", raw);
            assertTrue("wall-clock " + elapsedMs + "ms exceeded the production timeout "
                    + LocalModelProvider.TIMEOUT_MS + "ms", elapsedMs <= LocalModelProvider.TIMEOUT_MS);
        } finally {
            bridge.unload(handle);
        }
        completedModelTests.add("test4");
    }

    /** Capturing wrapper: the logged raw IS the single result the coordinator consumed. */
    private static final class CapturingProvider implements ReasoningProvider {
        private final LocalModelProvider real;
        String raw;
        int calls;
        CapturingProvider(LocalModelProvider real) {
            this.real = real;
        }
        @Override public String propose(String request) {
            calls++;
            raw = real.propose(request);
            return raw;
        }
        @Override public String propose(String request, java.util.List<String> recent) {
            calls++;
            raw = real.propose(request, recent);
            return raw;
        }
    }

    @Test(timeout = 200_000)
    public void test5_realProviderPositiveAndRefusalEvidence() throws Exception {
        // Real-provider evidence (NOT direct bridge): positives assert the
        // provider's single propose() result re-validated through the app
        // strict parser + validator with full field checks; refusals go
        // through the coordinator with a capturing wrapper (exactly one
        // provider call per request; the printed raw is what the coordinator
        // consumed); a parser-recognized compound must be refused with zero
        // provider calls. A null provider result is printed as "null" with no
        // cause claim - per LocalModelProvider's source, null has several
        // fail-closed causes (budget, setup, formatting, interrupt) and this
        // log line alone does not establish which.
        assertNotNull("test1 must install the model first", installedFile);
        android.content.Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ModelManager mm = new ModelManager(ctx);
        LocalModelProvider provider = new LocalModelProvider(ctx, mm, new LlamaBridge(),
                new android.os.Handler(android.os.Looper.getMainLooper()));
        try {
            provider.setForcedLoadThreadsForTesting(DIAGNOSTIC_LOAD_THREADS);
            mm.setActiveModelId(installed.id);

            String rawTorch = provider.propose("turn on the torch");
            System.out.println("RealModelSmoke provider raw [turn on the torch]: "
                    + (rawTorch == null ? "null" : rawTorch.replace('\n', ' ').replace('\r', ' ')));
            assertNotNull("real provider must return a proposal for a torch command", rawTorch);
            Map<String, String> torchFields = StrictJson.parseObject(rawTorch);
            assertNotNull("torch proposal must parse through the app strict JSON parser", torchFields);
            ReasoningProposalValidator.ValidationResult torchVr =
                    ReasoningProposalValidator.validate(torchFields);
            assertNotNull("the app validator must accept the torch proposal"
                    + (torchVr.reason != null ? "; rejected: " + torchVr.reason : ""), torchVr.action);
            assertEquals(VisionAction.Type.TOGGLE_TORCH, torchVr.action.type);
            assertEquals("on", torchVr.action.target);
            assertEquals("torch text must be empty", "", torchFields.get("text"));
            assertEquals("torch channel must be empty", "", torchFields.get("channel"));

            String rawTime = provider.propose("what time is it");
            System.out.println("RealModelSmoke provider raw [what time is it]: "
                    + (rawTime == null ? "null" : rawTime.replace('\n', ' ').replace('\r', ' ')));
            assertNotNull("real provider must return a proposal for a time query", rawTime);
            Map<String, String> timeFields = StrictJson.parseObject(rawTime);
            assertNotNull(timeFields);
            ReasoningProposalValidator.ValidationResult timeVr =
                    ReasoningProposalValidator.validate(timeFields);
            assertNotNull("the app validator must accept the time proposal"
                    + (timeVr.reason != null ? "; rejected: " + timeVr.reason : ""), timeVr.action);
            assertEquals(VisionAction.Type.READ_TIME, timeVr.action.type);
            assertEquals("time text must be empty", "", timeFields.get("text"));
            assertEquals("time channel must be empty", "", timeFields.get("channel"));

            for (String request : new String[]{"order a pizza", "do it"}) {
                CapturingProvider capturing = new CapturingProvider(provider);
                ReasoningCoordinator.CoordinationResult result =
                        ReasoningCoordinator.coordinateFull(request, capturing);
                assertEquals("exactly one provider call per request", 1, capturing.calls);
                System.out.println("RealModelSmoke coordinator-consumed raw [" + request + "]: "
                        + (capturing.raw == null ? "null"
                           : capturing.raw.replace('\n', ' ').replace('\r', ' ')));
                assertNotNull("model must emit a refusal proposal for: " + request, capturing.raw);
                Map<String, String> refusal = StrictJson.parseObject(capturing.raw);
                assertNotNull("refusal must parse through the app strict JSON parser", refusal);
                // Key-set exactness, not only value checks: a raw refusal with
                // missing keys could otherwise be masked by the coordinator's
                // null-fallback reads, and extra keys must not slip through.
                assertEquals("refusal must carry exactly the keys {type,target,text,channel}",
                        new HashSet<>(Arrays.asList("type", "target", "text", "channel")),
                        refusal.keySet());
                assertEquals("model refusal must be UNKNOWN", "UNKNOWN", refusal.get("type"));
                assertEquals("refusal target must be empty", "", refusal.get("target"));
                assertEquals("refusal text must be empty", "", refusal.get("text"));
                assertEquals("refusal channel must be empty", "", refusal.get("channel"));
                assertEquals("coordinator must fail closed as UNKNOWN",
                        VisionAction.Type.UNKNOWN, result.action.type);
                assertNull("no plan may come from an unsupported/ambiguous request", result.plan);
            }

            CapturingProvider compoundCapture = new CapturingProvider(provider);
            ReasoningCoordinator.CoordinationResult compound = ReasoningCoordinator.coordinateFull(
                    "open Telegram and then open Gmail", compoundCapture);
            assertEquals("parser-recognized compound must not reach the provider",
                    0, compoundCapture.calls);
            assertEquals(VisionAction.Type.UNSUPPORTED_MULTI_ACTION, compound.action.type);
            assertNull(compound.plan);
        } finally {
            // provider.shutdown() only queues work; the unloadAsync callback is
            // the completion barrier, and it must pass BEFORE teardown may
            // delete the imported model file. Per LocalModelProvider.unloadAsync
            // source the callback fires from a finally block even if unload()
            // throws, so a fired latch alone is not proof: unload is proven only
            // when the latch fired AND the provider reports not loaded. On any
            // ambiguous outcome (timeout, interrupt, still-loaded) the model
            // file is PRESERVED rather than deleted under a possibly live
            // handle. shutdown() runs unconditionally.
            final java.util.concurrent.CountDownLatch unloaded = new java.util.concurrent.CountDownLatch(1);
            boolean proven = false;
            try {
                provider.unloadAsync(new Runnable() {
                    @Override public void run() {
                        unloaded.countDown();
                    }
                });
                // isLoaded() alone would not prove native disposal:
                // LlamaBridge.unload clears the provider's handle after a call
                // that can time out its bounded wait and DELEGATE disposal to a
                // running operation's exit path. Here the proof chain is
                // source-grounded: every propose() above has returned (the
                // provider is synchronized and single-threaded in this test),
                // so no operation is in flight, the bridge's tryLock acquires
                // immediately, and nativeUnload runs synchronously before the
                // callback. The delegated path requires an in-flight operation,
                // which cannot exist at this point. Native disposal failing via
                // a catchable Java exception is not a real path (the JNI unload
                // does not throw; a native crash would kill the process and
                // fail the run loudly). Timeout/interrupt/still-loaded remain
                // ambiguous and preserve the file.
                proven = unloaded.await(60, java.util.concurrent.TimeUnit.SECONDS)
                        && !provider.isLoaded();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                modelUnloadProven = proven;
                provider.shutdown();
            }
            if (!proven) {
                System.out.println("RealModelSmoke teardown: unload NOT proven; "
                        + "imported model file will be preserved");
            }
        }
        completedModelTests.add("test5");
    }

    private static String readAsset(String name) throws Exception {
        InputStream is = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getAssets().open(name);
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, "UTF-8"));
        }
        is.close();
        return sb.toString();
    }

    @AfterClass
    public static void removeOwnedArtifacts() throws Exception {
        ModelManager mm = new ModelManager(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        // Restore the exact pre-existing active id, including null.
        mm.setActiveModelId(preexistingActiveModelId);
        // Delete the imported model only when test5 PROVED the provider unload;
        // an ambiguous teardown preserves the file (and its catalog entry)
        // rather than deleting under a possibly live native handle.
        boolean allCompleted = completedModelTests.containsAll(java.util.Arrays.asList(
                "test1", "test2", "test3", "test4", "test5"));
        if (!modelUnloadProven || !allCompleted) {
            System.out.println("RealModelSmoke cleanup: unloadProven=" + modelUnloadProven
                    + " completedModelTests=" + completedModelTests
                    + " - ambiguous cleanup; preserving imported model file");
            return;
        }
        if (installed != null) {
            mm.deleteModel(installed);
        }
        // Exact-ownership sweep: delete only the unique names this class's import
        // could create (its "import-*-<safeBase>" file or a ".part" remainder if it
        // failed between the rename and the metadata save), and only when the name
        // was not present before this class ran. models.json and models.json.tmp are
        // shared publisher files (see ModelManager's metadata lock), never
        // class-owned, so they are never deleted here; deleteModel handles the
        // metadata when installed != null.
        if (expectedSafeBase == null) {
            return;
        }
        File[] files = mm.getModelsDir().listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            String n = f.getName();
            boolean owned = n.startsWith("import-")
                    && (n.endsWith("-" + expectedSafeBase)
                        || n.endsWith("-" + expectedSafeBase + ".part"));
            if (owned && !preexistingFiles.contains(n)) {
                f.delete();
            }
        }
    }
}
