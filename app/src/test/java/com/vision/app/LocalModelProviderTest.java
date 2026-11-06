package com.vision.app;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.robolectric.RuntimeEnvironment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LocalModelProviderTest {
    private Context context;
    private ModelManager modelManager;
    private FakeBridge fakeBridge;
    private Handler handler;
    private LocalModelProvider provider;
    // Set when a bounded cleanup join left a provider worker alive; the @After must then
    // skip monitor-acquiring provider teardown or it would hang behind the survivor.
    private volatile boolean workerSurvivedCleanup;
    private DirectExecutorService directExecutor;
    private MutableClock clock;

    private static class MutableClock implements LocalModelProvider.ClockSource {
        long nowMs = 0;
        long advancePerReadMs = 0; // simulates descheduling between checkpoints
        @Override public long elapsedRealtimeMs() {
            long t = nowMs;
            nowMs += advancePerReadMs;
            return t;
        }
    }

    private static class DirectExecutorService extends java.util.concurrent.AbstractExecutorService {
        private volatile boolean shutdown = false;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            shutdown = true;
            return java.util.Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new java.util.concurrent.RejectedExecutionException("Executor is shut down");
            }
            command.run();
        }
    }
    private static class FakeBridge implements LlamaBridgeInterface {
        long nextHandle = 100L;
        long loadedHandle = 0;
        int loadCallCount = 0;
        int unloadCallCount = 0;
        int generateCallCount = 0;

        String returnText = null;
        String returnFormatChat = "<formatted>";
        boolean simulateTimeout = false;
        boolean interruptOnNextGenerate = false;
        boolean interruptOnLoad = false;
        long lastTimeoutMs = 0;
        MutableClock clock = null;
        long advanceClockOnNextGenerateMs = 0;
        long advanceClockOnLoadMs = 0;
        final java.util.List<String> returnTextQueue = new java.util.ArrayList<>();
        volatile boolean blockOnFirstGenerate = false;
        final java.util.concurrent.CountDownLatch firstGenerateEntered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseFirstGenerate = new java.util.concurrent.CountDownLatch(1);
        final java.util.List<String> seenPrompts = new java.util.ArrayList<>();

        @Override
        public long load(String path, int ctxTokens, int threads) {
            loadCallCount++;
            if (interruptOnLoad) {
                interruptOnLoad = false;
                Thread.currentThread().interrupt();
            }
            if (advanceClockOnLoadMs > 0 && clock != null) {
                clock.nowMs += advanceClockOnLoadMs;
                advanceClockOnLoadMs = 0;
            }
            loadedHandle = nextHandle++;
            return loadedHandle;
        }

        @Override
        public String generate(long handle, String prompt, String gbnfGrammar, int maxTokens, long timeoutMs) {
            generateCallCount++;
            lastTimeoutMs = timeoutMs;
            seenPrompts.add(prompt);
            if (blockOnFirstGenerate && generateCallCount == 1) {
                firstGenerateEntered.countDown();
                // Only the latch controls the generation's return; cancellation interrupts
                // are remembered and restored after the wait, like a blocking native call
                // that reports interruption without returning early.
                boolean interruptedWhileWaiting = false;
                boolean released = false;
                while (!released) {
                    try {
                        released = releaseFirstGenerate.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        interruptedWhileWaiting = true;
                    }
                }
                if (interruptedWhileWaiting) {
                    Thread.currentThread().interrupt();
                }
            }
            if (interruptOnNextGenerate) {
                interruptOnNextGenerate = false;
                Thread.currentThread().interrupt();
            }
            if (advanceClockOnNextGenerateMs > 0 && clock != null) {
                clock.nowMs += advanceClockOnNextGenerateMs;
                advanceClockOnNextGenerateMs = 0;
            }
            if (simulateTimeout) {
                return null;
            }
            if (!returnTextQueue.isEmpty()) {
                return returnTextQueue.remove(0);
            }
            return returnText;
        }

        @Override
        public void unload(long handle) {
            unloadCallCount++;
            if (loadedHandle == handle) {
                loadedHandle = 0;
            }
        }

        @Override
        public String formatChat(long handle, String templateHint, String systemPrompt, String userMessage) {
            if (returnFormatChat == null) return null;
            return returnFormatChat + userMessage + "</formatted>";
        }
    }

    @Before
    public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        modelManager = new ModelManager(context);
        fakeBridge = new FakeBridge();
        handler = new Handler(Looper.getMainLooper());
        directExecutor = new DirectExecutorService();
        clock = new MutableClock();
        provider = new LocalModelProvider(context, modelManager, fakeBridge, handler, directExecutor, clock);

        // Install a dummy active model
        File dummyFile = new File(modelManager.getModelsDir(), "active-model.gguf");
        try (FileOutputStream fos = new FileOutputStream(dummyFile)) {
            fos.write(new byte[]{0x47, 0x47, 0x55, 0x46});
        }
        ModelInfo info = new ModelInfo(
                "active-1", "Active Test Model", "active-model.gguf",
                dummyFile.length(), null, "import", "custom",
                System.currentTimeMillis(), true, true, "llama3"
        );
        List<ModelInfo> list = new ArrayList<>();
        list.add(info);
        modelManager.saveInstalledModels(list);
        modelManager.setActiveModelId("active-1");
        fakeBridge.clock = clock;
    }

    @After
    public void cleanup() {
        // Skip monitor-acquiring teardown when a bounded cleanup join left a worker alive:
        // shutdown would block behind the survivor. The survivor was already reported by
        // the test's cleanup; remaining teardown here is independent and non-blocking.
        if (provider != null && !workerSurvivedCleanup) {
            provider.shutdown();
        }
        if (directExecutor != null) {
            directExecutor.shutdown();
        }
        File dummy = new File(modelManager.getModelsDir(), "active-model.gguf");
        if (dummy.exists()) dummy.delete();
    }

    // 8. LocalModelProvider timeout -> null (simulated timeout)
    @Test
    public void localModelProviderTimeoutReturnsNull() {
        fakeBridge.simulateTimeout = true;
        String result = provider.propose("turn on torch");
        assertNull("Simulated timeout must yield null proposal", result);
        assertEquals(8000L, fakeBridge.lastTimeoutMs);
    }

    // 9. Lifecycle unload triggers (background / 5-min idle / pressure)
    @Test
    public void lifecycleUnloadTriggers() {
        fakeBridge.returnText = "{\"type\":\"TOGGLE_TORCH\",\"target\":\"on\",\"text\":\"\",\"channel\":\"\"}";

        // 1. First propose lazy-loads the model
        assertFalse(provider.isLoaded());
        String out = provider.propose("turn on torch");
        assertNotNull(out);
        assertTrue(provider.isLoaded());
        assertEquals(1, fakeBridge.loadCallCount);

        // 2. App backgrounded triggers unload
        provider.onAppBackgrounded();
        assertFalse("Model must be unloaded when app is backgrounded", provider.isLoaded());
        assertEquals(1, fakeBridge.unloadCallCount);

        // 3. Re-propose lazy-loads again
        provider.propose("turn on torch");
        assertTrue(provider.isLoaded());
        assertEquals(2, fakeBridge.loadCallCount);

        // 4. Memory pressure (moderate and above) triggers unload
        provider.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE);
        assertFalse("Model must be unloaded on TRIM_MEMORY_MODERATE", provider.isLoaded());
        assertEquals(2, fakeBridge.unloadCallCount);

        // 4b. N3: TRIM_MEMORY_RUNNING_CRITICAL unloads when idle, but NOT when in use
        provider.propose("turn on torch");
        assertTrue(provider.isLoaded());
        modelManager.setModelInUse(true);
        provider.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL);
        assertTrue("TRIM_MEMORY_RUNNING_CRITICAL must NOT unload model while in use", provider.isLoaded());
        modelManager.setModelInUse(false);
        provider.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL);
        assertFalse("TRIM_MEMORY_RUNNING_CRITICAL must unload model when idle", provider.isLoaded());
        assertEquals(3, fakeBridge.unloadCallCount);

        // 5. Re-propose and idle 5 minutes triggers unload
        provider.propose("turn on torch");
        assertTrue(provider.isLoaded());

        // Fast forward Looper by 5 minutes
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(5 * 60 * 1000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertFalse("Model must be unloaded after 5 minutes of idle time", provider.isLoaded());
        assertEquals(4, fakeBridge.unloadCallCount);
    }

    // 10. ProviderSelector ON_DEVICE registration rules
    @Test
    public void providerSelectorRegistrationRules() {
        ProviderSelector selector = new ProviderSelector();
        assertFalse(selector.isAvailable(ProviderMode.ON_DEVICE));
        assertFalse(selector.select(ProviderMode.ON_DEVICE));
        assertEquals(ProviderMode.DETERMINISTIC_ONLY, selector.mode());

        selector.register(ProviderMode.ON_DEVICE, provider);
        assertTrue(selector.isAvailable(ProviderMode.ON_DEVICE));
        assertTrue(selector.select(ProviderMode.ON_DEVICE));
        assertEquals(ProviderMode.ON_DEVICE, selector.mode());
        assertSame(provider, selector.active());

        selector.unregister(ProviderMode.ON_DEVICE);
        assertFalse(selector.isAvailable(ProviderMode.ON_DEVICE));
        assertEquals("Unregistering active mode resets to DETERMINISTIC_ONLY",
                ProviderMode.DETERMINISTIC_ONLY, selector.mode());
    }

    // 11. No-model message exact text
    @Test
    public void noModelInstalledMessageExactText() {
        // Clear all active models
        modelManager.setActiveModelId(null);
        File dummy = new File(modelManager.getModelsDir(), "active-model.gguf");
        if (dummy.exists()) dummy.delete();

        String expected = "No model installed. Open Settings -> On-device model to download or import one.";

        // propose returns null when no model is installed
        assertNull(provider.propose("turn on torch"));

        // Verify the exact string constant expected across the app
        assertEquals("No model installed. Open Settings -> On-device model to download or import one.", expected);
    }

    // 12. GBNF output format parses through StrictJson and ReasoningProposalValidator
    @Test
    public void representativeGbnfOutputsValidateCorrectly() {
        // Valid 4-field output
        String validGbnf = "{\"type\":\"TOGGLE_TORCH\",\"target\":\"on\",\"text\":\"\",\"channel\":\"\"}";
        Map<String, String> fields = StrictJson.parseObject(validGbnf);
        assertNotNull(fields);
        ReasoningProposalValidator.ValidationResult res = ReasoningProposalValidator.validate(fields);
        assertNotNull(res.action);
        assertEquals(VisionAction.Type.TOGGLE_TORCH, res.action.type);

        // Missing keys rejected
        String missingKeys = "{\"type\":\"TOGGLE_TORCH\",\"target\":\"on\"}";
        Map<String, String> fieldsMissing = StrictJson.parseObject(missingKeys);
        assertEquals(ReasoningProposalValidator.RejectionReason.MISSING_KEYS,
                ReasoningProposalValidator.validate(fieldsMissing).reason);

        // Extra keys rejected
        String extraKeys = "{\"type\":\"TOGGLE_TORCH\",\"target\":\"on\",\"text\":\"\",\"channel\":\"\",\"extra\":\"evil\"}";
        Map<String, String> fieldsExtra = StrictJson.parseObject(extraKeys);
        assertEquals(ReasoningProposalValidator.RejectionReason.EXTRA_KEYS,
                ReasoningProposalValidator.validate(fieldsExtra).reason);

        // Malformed JSON returns null from StrictJson
        String malformed = "{not json}";
        assertNull(StrictJson.parseObject(malformed));
    }

    // N4: formatChat failure returns null without falling back to plain format
    @Test
    public void formatChatFailureFailsClosedWithoutPlainFallback() {
        fakeBridge.returnFormatChat = null;
        String result = provider.propose("turn on torch");
        assertNull("formatChat failure must fail closed and return null", result);
        assertEquals(0, fakeBridge.generateCallCount);
    }

    // 13. One bounded self-correction retry: rejected first proposal, valid confirmation-tier retry
    @Test
    public void retryCorrectsRejectedProposal() {
        fakeBridge.returnTextQueue.add("{\"type\":\"REMIND\",\"target\":\"18:15\",\"text\":\"call\",\"channel\":\"\"}");
        String corrected = "{\"type\":\"SET_REMINDER\",\"target\":\"18:15\",\"text\":\"call\",\"channel\":\"\"}";
        fakeBridge.returnTextQueue.add(corrected);

        String result = provider.propose("do not let me forget the 18:15 call");
        assertEquals("Valid confirmation-tier retry output must be returned", corrected, result);
        assertEquals(2, fakeBridge.generateCallCount);
        assertTrue("Retry prompt must carry the violated rule",
                fakeBridge.seenPrompts.get(1).contains("type must be one of"));
        assertTrue("Retry prompt must repeat the user request",
                fakeBridge.seenPrompts.get(1).contains("do not let me forget the 18:15 call"));
    }

    // 13b. SAFETY INVARIANT: a retry that corrects into a SAFE-tier (automatic) type is
    // discarded - a retry can never turn a formerly refused request into an automatic action
    @Test
    public void retryIntoSafeTierTypeIsDiscarded() {
        String first = "{\"type\":\"PLAY_MUSIC\",\"target\":\"x\",\"text\":\"\",\"channel\":\"\"}";
        fakeBridge.returnTextQueue.add(first);
        fakeBridge.returnTextQueue.add("{\"type\":\"TOGGLE_TORCH\",\"target\":\"on\",\"text\":\"\",\"channel\":\"\"}");

        String result = provider.propose("play some music");
        assertEquals("SAFE-tier retry output must be discarded; original returned for refusal",
                first, result);
        assertEquals(2, fakeBridge.generateCallCount);
        assertNull(ReasoningProposalValidator.validate(StrictJson.parseObject(result)).action);
    }

    // 14. Retry exhausted: both proposals rejected -> original output returned unchanged
    @Test
    public void retryExhaustedReturnsOriginalOutput() {
        String first = "{\"type\":\"PLAY_MUSIC\",\"target\":\"x\",\"text\":\"\",\"channel\":\"\"}";
        String second = "{\"type\":\"SING\",\"target\":\"\",\"text\":\"la\",\"channel\":\"\"}";
        fakeBridge.returnTextQueue.add(first);
        fakeBridge.returnTextQueue.add(second);

        String result = provider.propose("play some music");
        assertEquals("Failed retry must return the original output for the coordinator to reject",
                first, result);
        assertEquals(2, fakeBridge.generateCallCount);
    }

    // 15. Valid first proposal: no retry
    @Test
    public void validFirstProposalSkipsRetry() {
        String valid = "{\"type\":\"READ_BATTERY\",\"target\":\"battery\",\"text\":\"\",\"channel\":\"\"}";
        fakeBridge.returnText = valid;
        String result = provider.propose("how much battery");
        assertEquals(valid, result);
        assertEquals(1, fakeBridge.generateCallCount);
    }

    // 16. Plan-array output is left to the downstream plan validator; no retry
    @Test
    public void planArrayOutputSkipsRetry() {
        String plan = "[{\"type\":\"TOGGLE_TORCH\",\"target\":\"on\",\"text\":\"\",\"channel\":\"\"}]";
        fakeBridge.returnText = plan;
        String result = provider.propose("turn on torch then off");
        assertEquals(plan, result);
        assertEquals(1, fakeBridge.generateCallCount);
    }

    // 17a. REGRESSION counterexample: a valid-typed proposal that violates field rules
    // (pause-music intent mapped to TOGGLE_TORCH with an invalid target) must NOT be retried
    // with corrective feedback - repairing its fields could launder a wrong intent into a
    // valid SAFE-tier action that executes without confirmation.
    @Test
    public void fieldRuleRejectionOnValidTypeSkipsRetry() {
        String wrongIntent = "{\"type\":\"TOGGLE_TORCH\",\"target\":\"\",\"text\":\"\",\"channel\":\"\"}";
        fakeBridge.returnTextQueue.add(wrongIntent);

        String result = provider.propose("pause music");
        assertEquals("Field-rule rejection on a valid type must return the original output",
                wrongIntent, result);
        assertEquals("No retry may fire for field-rule violations", 1, fakeBridge.generateCallCount);
        // Downstream the coordinator still rejects it fail-closed
        Map<String, String> fields = StrictJson.parseObject(result);
        assertNull(ReasoningProposalValidator.validate(fields).action);
    }

    // 17b. Field-rule rejections of other kinds also never retry
    @Test
    public void invalidTextAndChannelRejectionsSkipRetry() {
        fakeBridge.returnTextQueue.add("{\"type\":\"SET_ALARM\",\"target\":\"07:30\",\"text\":\"wake\",\"channel\":\"\"}");
        String result = provider.propose("set alarm for 7:30");
        assertEquals(1, fakeBridge.generateCallCount);
        assertNull(ReasoningProposalValidator.validate(StrictJson.parseObject(result)).action);
    }

    // 17c. SIZE_LIMIT can only occur on confirmation-tier text fields; retry is allowed
    // and a corrected shorter message is accepted (it still requires user confirmation)
    @Test
    public void sizeLimitRetriesOnce() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 210; i++) longText.append("0123456789");
        String tooLong = "{\"type\":\"SEND_MESSAGE_DIRECT\",\"target\":\"+15555550123\",\"text\":\""
                + longText + "\",\"channel\":\"sms\"}";
        String corrected = "{\"type\":\"SEND_MESSAGE_DIRECT\",\"target\":\"+15555550123\",\"text\":\"on my way\",\"channel\":\"sms\"}";
        fakeBridge.returnTextQueue.add(tooLong);
        fakeBridge.returnTextQueue.add(corrected);

        String result = provider.propose("send sms to +15555550123 saying I am on my way");
        assertEquals(corrected, result);
        assertEquals(2, fakeBridge.generateCallCount);
    }

    // 17. Truthful refusal shape (explicit UNKNOWN type) is honored WITHOUT a retry:
    // a refused request must never be coerced into an unrelated action confirmation dialog
    @Test
    public void explicitUnknownRefusalSkipsRetry() {
        String unknown = "{\"type\":\"UNKNOWN\",\"target\":\"\",\"text\":\"\",\"channel\":\"\"}";
        fakeBridge.returnTextQueue.add(unknown);

        String result = provider.propose("tell me a joke");
        assertEquals("Refusal-shaped output must pass through for the fail-closed coordinator",
                unknown, result);
        assertEquals("Explicit UNKNOWN refusal must not trigger a retry", 1, fakeBridge.generateCallCount);
        // Coordinator-side check: UNKNOWN still fails closed through the real validator
        Map<String, String> fields = StrictJson.parseObject(result);
        assertNull(ReasoningProposalValidator.validate(fields).action);
    }

    // 19. Cancellation: interrupt after the first generation skips the retry and the
    // interrupt flag is left set for the caller (never cleared by the provider)
    @Test
    public void interruptBetweenPhasesSkipsRetry() {
        try {
            String invalid = "{\"type\":\"REMIND\",\"target\":\"18:15\",\"text\":\"call\",\"channel\":\"\"}";
            fakeBridge.returnTextQueue.add(invalid);
            fakeBridge.interruptOnNextGenerate = true;

            String result = provider.propose("do not let me forget the 18:15 call");
            assertEquals("Interrupted request must return the original output without retry",
                    invalid, result);
            assertEquals("No retry may fire after interrupt", 1, fakeBridge.generateCallCount);
            assertTrue("Interrupt flag must remain set for the caller",
                    Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); // clear flag so the shared runner thread stays usable
        }
    }

    // 20. Queued-then-interrupted worker: an interrupt already pending at entry must
    // prevent all setup and generation for the stale request
    @Test
    public void queuedInterruptSkipsSetupAndGeneration() {
        try {
            Thread.currentThread().interrupt();
            String result = provider.propose("turn on torch");
            assertNull("Interrupted queued request must produce no proposal", result);
            assertEquals(0, fakeBridge.loadCallCount);
            assertEquals(0, fakeBridge.generateCallCount);
        } finally {
            Thread.interrupted();
        }
    }

    // 21. Interrupt raised during model load: no generation is started
    @Test
    public void interruptDuringLoadSkipsGeneration() {
        try {
            fakeBridge.interruptOnLoad = true;
            String result = provider.propose("turn on torch");
            assertNull(result);
            assertEquals(1, fakeBridge.loadCallCount);
            assertEquals(0, fakeBridge.generateCallCount);
        } finally {
            Thread.interrupted();
        }
    }

    // 22. Expired first result: a blocking generation that returns after the cooperative
    // budget is spent yields NO proposal, even when the output would have been valid
    @Test
    public void expiredFirstResultIsNoProposal() {
        fakeBridge.returnText = "{\"type\":\"TOGGLE_TORCH\",\"target\":\"on\",\"text\":\"\",\"channel\":\"\"}";
        fakeBridge.advanceClockOnNextGenerateMs = LocalModelProvider.RETRY_TOTAL_BUDGET_MS + 1;
        String result = provider.propose("turn on torch");
        assertNull("Expired first result must be no proposal", result);
        assertEquals(1, fakeBridge.generateCallCount);
    }

    // 23. Expired retry result: first result on time, retry generation crosses the budget
    @Test
    public void expiredRetryStrict() {
        final String first = "{\"type\":\"REMIND\",\"target\":\"18:15\",\"text\":\"call\",\"channel\":\"\"}";
        final String corrected = "{\"type\":\"SET_REMINDER\",\"target\":\"18:15\",\"text\":\"call\",\"channel\":\"\"}";
        fakeBridge.returnTextQueue.add(first);
        fakeBridge.returnTextQueue.add(corrected);
        // Advance the clock past the budget only on the second generate call
        fakeBridge = replaceBridgeWithExpiringRetry(fakeBridge, clock);
        String result = provider.propose("do not let me forget the 18:15 call");
        assertEquals("Expired retry result must not replace the original output", first, result);
        assertNull(ReasoningProposalValidator.validate(StrictJson.parseObject(result)).action);
    }

    // 24. Final-acceptance expiry: a valid first proposal that only crosses the budget
    // during post-generation parsing/validation is NO proposal, never a late acceptance
    @Test
    public void expiredAtFirstAcceptanceIsNoProposal() {
        fakeBridge.returnText = "{\"type\":\"TOGGLE_TORCH\",\"target\":\"on\",\"text\":\"\",\"channel\":\"\"}";
        clock.advancePerReadMs = 5000; // anchor 0, budget 9000, post-gen 4000, pre-accept -1000
        String result = provider.propose("turn on torch");
        assertNull("Proposal validated past the budget must be no proposal", result);
        assertEquals(1, fakeBridge.generateCallCount);
    }

    // 25. Final-acceptance expiry on the retry path: a corrected proposal that crosses the
    // budget during validation can never replace the rejected original
    @Test
    public void expiredAtRetryAcceptanceReturnsOriginal() {
        String first = "{\"type\":\"REMIND\",\"target\":\"18:15\",\"text\":\"call\",\"channel\":\"\"}";
        String corrected = "{\"type\":\"SET_REMINDER\",\"target\":\"18:15\",\"text\":\"call\",\"channel\":\"\"}";
        fakeBridge.returnTextQueue.add(first);
        fakeBridge.returnTextQueue.add(corrected);
        clock.advancePerReadMs = 2500; // stays positive until the pre-acceptance checkpoint
        String result = provider.propose("do not let me forget the 18:15 call");
        assertEquals("Expired corrected proposal must not replace the original output", first, result);
        assertNull(ReasoningProposalValidator.validate(StrictJson.parseObject(result)).action);
    }

    // 26. Setup budget consumption: time spent in model load/formatting counts against the
    // same cooperative budget; when it is spent, no generation is attempted
    @Test
    public void setupConsumptionSkipsGeneration() {
        fakeBridge.advanceClockOnLoadMs = LocalModelProvider.RETRY_TOTAL_BUDGET_MS + 1;
        String result = provider.propose("turn on torch");
        assertNull("Budget spent during setup must yield no proposal", result);
        assertEquals(1, fakeBridge.loadCallCount);
        assertEquals(0, fakeBridge.generateCallCount);
    }

    private FakeBridge replaceBridgeWithExpiringRetry(FakeBridge old, MutableClock clock) {
        // Rebuild the provider with a bridge that expires the budget during the retry call
        FakeBridge b = new FakeBridge() {
            @Override
            public String generate(long handle, String prompt, String gbnfGrammar, int maxTokens, long timeoutMs) {
                String out = super.generate(handle, prompt, gbnfGrammar, maxTokens, timeoutMs);
                if (generateCallCount == 2) {
                    clock.nowMs += LocalModelProvider.RETRY_TOTAL_BUDGET_MS + 1;
                }
                return out;
            }
        };
        b.clock = clock;
        b.returnTextQueue.addAll(old.returnTextQueue);
        provider = new LocalModelProvider(context, modelManager, b, handler, directExecutor, clock);
        return b;
    }

    // 18. Timeout on first generation: no retry, null as before
    @Test
    public void timeoutSkipsRetry() {
        fakeBridge.simulateTimeout = true;
        String result = provider.propose("turn on torch");
        assertNull(result);
        assertEquals(1, fakeBridge.generateCallCount);
    }
    // 25. End-to-end through the real AsyncReasoningRunner: malformed first output is
    // retried inside the provider and exactly one corrected CONFIRMED-tier proposal is
    // delivered to the runner callback
    @Test
    public void retryCorrectionFlowsThroughRealRunnerOnce() throws Exception {
        String corrected = "{\"type\":\"SET_REMINDER\",\"target\":\"18:15\",\"text\":\"call\",\"channel\":\"\"}";
        fakeBridge.returnTextQueue.add("{\"type\":\"REMIND\",\"target\":\"18:15\",\"text\":\"call\",\"channel\":\"\"}");
        fakeBridge.returnTextQueue.add(corrected);

        AsyncReasoningRunner runner = new AsyncReasoningRunner(10000, 200, 500);
        try {
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            final ProviderOutcome[] box = new ProviderOutcome[1];
            runner.submit(provider, "do not let me forget the 18:15 call", null,
                    new AsyncReasoningRunner.Callback() {
                        @Override public void onOutcome(long id, ProviderOutcome o) {
                            calls.incrementAndGet();
                            box[0] = o;
                            done.countDown();
                        }
                    });
            assertTrue("runner outcome expected", done.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(ProviderOutcome.Status.PROPOSAL, box[0].status);
            assertEquals("runner must deliver the corrected proposal", corrected, box[0].raw);
            assertEquals("exactly one retry generation", 2, fakeBridge.generateCallCount);
            Thread.sleep(200);
            assertEquals("outcome delivered exactly once", 1, calls.get());
        } finally {
            runner.shutdown();
        }
    }

    // 26. Queued cancellation and supersession with worker identity. A holds the provider
    // monitor inside a latch-blocked first generation; B is submitted (superseding A) and
    // its specific worker is captured by the wrapper BEFORE it blocks on the monitor. B is
    // cancelled while queued; only then is A's generation released. Both workers are joined
    // with bounds before any count assertion. The counts prove no additional bridge load or
    // generation occurs after the queued cancellation (the entry and post-load interrupt
    // checks together provide this; the test does not isolate which one fires).
    @Test
    public void queuedRequestCancelledWhileBlockedPerformsNoBridgeWork() throws Exception {
        fakeBridge.blockOnFirstGenerate = true;
        fakeBridge.returnTextQueue.add("{\"type\":\"REMIND\",\"target\":\"18:15\",\"text\":\"call\",\"channel\":\"\"}");
        fakeBridge.returnTextQueue.add("{\"type\":\"SET_REMINDER\",\"target\":\"18:15\",\"text\":\"call\",\"channel\":\"\"}");

        AsyncReasoningRunner runner = new AsyncReasoningRunner(60000, 200, 500);
        WorkerCapturingProvider capture = new WorkerCapturingProvider(provider);
        try {
            final java.util.concurrent.CountDownLatch doneA = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.CountDownLatch doneB = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.CountDownLatch doneC = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicInteger callsA = new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicInteger callsB = new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicInteger callsC = new java.util.concurrent.atomic.AtomicInteger();
            final ProviderOutcome[] boxA = new ProviderOutcome[1];
            final ProviderOutcome[] boxB = new ProviderOutcome[1];
            final ProviderOutcome[] boxC = new ProviderOutcome[1];

            runner.submit(capture, "do not let me forget the 18:15 call", null,
                    new AsyncReasoningRunner.Callback() {
                        @Override public void onOutcome(long id, ProviderOutcome o) {
                            callsA.incrementAndGet(); boxA[0] = o; doneA.countDown();
                        }
                    });
            assertTrue("first generation entered with the provider monitor held",
                    fakeBridge.firstGenerateEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(capture.awaitWorkers(1, 5000));
            Thread workerA = capture.worker(0);

            // Supersede: A is cancelled in flight, B queues behind the provider monitor.
            runner.submit(capture, "what time is it", null,
                    new AsyncReasoningRunner.Callback() {
                        @Override public void onOutcome(long id, ProviderOutcome o) {
                            callsB.incrementAndGet(); boxB[0] = o; doneB.countDown();
                        }
                    });
            assertTrue("superseded request must be cancelled", doneA.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(ProviderOutcome.Status.CANCELLED, boxA[0].status);
            assertTrue("replacement worker captured", capture.awaitWorkers(2, 5000));
            Thread workerB = capture.worker(1);

            // The specific replacement worker is parked on the provider monitor.
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && workerB.getState() != Thread.State.BLOCKED) {
                Thread.sleep(10);
            }
            assertEquals("replacement worker must be BLOCKED on the provider monitor",
                    Thread.State.BLOCKED, workerB.getState());
            assertEquals("no generation beyond the blocked first one yet", 1, fakeBridge.generateCallCount);

            // Cancel B while it is queued; interrupting must not release monitor contention.
            runner.cancel();
            assertTrue("queued request must be cancelled", doneB.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(ProviderOutcome.Status.CANCELLED, boxB[0].status);
            assertEquals("cancel must not release the queued worker from the monitor",
                    Thread.State.BLOCKED, workerB.getState());

            // Only now release A's generation.
            fakeBridge.releaseFirstGenerate.countDown();
            workerA.join(5000);
            assertFalse("superseded worker must finish its delivery attempt", workerA.isAlive());
            workerB.join(5000);
            assertFalse("cancelled queued worker must finish", workerB.isAlive());

            assertEquals("superseded request reported exactly once", 1, callsA.get());
            assertEquals("queued request reported exactly once", 1, callsB.get());
            assertEquals("A's interrupted output must not retry; B cancelled before entry must never generate",
                    1, fakeBridge.generateCallCount);
            assertEquals("B must never reach model load", 1, fakeBridge.loadCallCount);

            // The provider remains usable: a fresh request completes normally.
            fakeBridge.returnTextQueue.clear();
            String timeOut = "{\"type\":\"READ_TIME\",\"target\":\"\",\"text\":\"\",\"channel\":\"\"}";
            fakeBridge.returnText = timeOut;
            runner.submit(capture, "what time is it", null,
                    new AsyncReasoningRunner.Callback() {
                        @Override public void onOutcome(long id, ProviderOutcome o) {
                            callsC.incrementAndGet(); boxC[0] = o; doneC.countDown();
                        }
                    });
            assertTrue("fresh outcome expected", doneC.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(ProviderOutcome.Status.PROPOSAL, boxC[0].status);
            assertEquals(timeOut, boxC[0].raw);
            assertTrue(capture.awaitWorkers(3, 5000));
            Thread workerC = capture.worker(2);
            workerC.join(5000);
            assertFalse(workerC.isAlive());
            assertEquals(1, callsC.get());
            assertEquals(2, fakeBridge.generateCallCount);
        } finally {
            // Release latches first, then interrupt-driven shutdown, then bounded-join every
            // worker the capture recorded (not only locals assigned before a failure),
            // collecting join interruptions and termination failures. Interrupt status is
            // restored in the outermost finally even if any cleanup step throws.
            boolean[] wasInterrupted = { false };
            StringBuilder cleanupProblems = new StringBuilder();
            try {
                fakeBridge.releaseFirstGenerate.countDown();
                runner.shutdown();
                for (Thread wkr : capture.capturedWorkers()) {
                    try {
                        wkr.join(5000);
                    } catch (InterruptedException ie) {
                        wasInterrupted[0] = true;
                        cleanupProblems.append("join interrupted; ");
                    }
                }
                for (Thread wkr : capture.capturedWorkers()) {
                    if (wkr.isAlive()) {
                        workerSurvivedCleanup = true;
                        cleanupProblems.append("worker survived bounded join; ");
                    }
                }
            } finally {
                if (wasInterrupted[0]) {
                    Thread.currentThread().interrupt();
                }
            }
            if (cleanupProblems.length() > 0) {
                throw new AssertionError("cleanup: " + cleanupProblems);
            }
        }
    }
}
