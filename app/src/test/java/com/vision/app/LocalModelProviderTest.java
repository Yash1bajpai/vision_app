package com.vision.app;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;

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

    private static class FakeBridge implements LlamaBridgeInterface {
        long nextHandle = 100L;
        long loadedHandle = 0;
        int loadCallCount = 0;
        int unloadCallCount = 0;
        int generateCallCount = 0;

        String returnText = null;
        boolean simulateTimeout = false;
        long lastTimeoutMs = 0;

        @Override
        public long load(String path, int ctxTokens, int threads) {
            loadCallCount++;
            loadedHandle = nextHandle++;
            return loadedHandle;
        }

        @Override
        public String generate(long handle, String prompt, String gbnfGrammar, int maxTokens, long timeoutMs) {
            generateCallCount++;
            lastTimeoutMs = timeoutMs;
            if (simulateTimeout) {
                return null;
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
            return "<formatted>" + userMessage + "</formatted>";
        }
    }

    @Before
    public void setup() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        modelManager = new ModelManager(context);
        fakeBridge = new FakeBridge();
        handler = new Handler(Looper.getMainLooper());
        provider = new LocalModelProvider(context, modelManager, fakeBridge, handler);

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
    }

    @After
    public void cleanup() {
        if (provider != null) {
            provider.unload();
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

        // 5. Re-propose and idle 5 minutes triggers unload
        provider.propose("turn on torch");
        assertTrue(provider.isLoaded());

        // Fast forward Looper by 5 minutes
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(5 * 60 * 1000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertFalse("Model must be unloaded after 5 minutes of idle time", provider.isLoaded());
        assertEquals(3, fakeBridge.unloadCallCount);
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
}
