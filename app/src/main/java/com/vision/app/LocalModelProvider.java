package com.vision.app;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * On-device local reasoning provider backed by embedded llama.cpp runtime.
 */
public class LocalModelProvider implements ReasoningProvider {
    private static final String TAG = "LocalModelProvider";

    public static final int CTX_TOKENS = 1024;
    public static final int MAX_TOKENS = 256;
    public static final long TIMEOUT_MS = 8000L;
    public static final long IDLE_UNLOAD_MS = 5 * 60 * 1000L; // 5 minutes

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are an on-device Android assistant. Convert the user command into a single JSON object with exact keys \"type\", \"target\", \"text\", \"channel\".";

    private final Context context;
    private final ModelManager modelManager;
    private final LlamaBridgeInterface bridge;
    private final Handler idleHandler;

    private long nativeHandle = 0;
    private long lastGenerationTimeMs = 0;
    private String cachedGrammar = null;

    private final Runnable idleUnloadRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (LocalModelProvider.this) {
                if (nativeHandle != 0 && !modelManager.isModelInUse()) {
                    long idleTime = System.currentTimeMillis() - lastGenerationTimeMs;
                    if (idleTime >= IDLE_UNLOAD_MS) {
                        Log.i(TAG, "Unloading resident local model due to 5-minute inactivity");
                        unload();
                    } else {
                        idleHandler.postDelayed(this, IDLE_UNLOAD_MS - idleTime);
                    }
                }
            }
        }
    };

    public LocalModelProvider(Context context) {
        this(context, new ModelManager(context), new LlamaBridge(), new Handler(Looper.getMainLooper()));
    }

    public LocalModelProvider(Context context, ModelManager modelManager, LlamaBridgeInterface bridge, Handler handler) {
        this.context = context.getApplicationContext();
        this.modelManager = modelManager;
        this.bridge = bridge;
        this.idleHandler = handler;
    }

    public synchronized boolean isLoaded() {
        return nativeHandle != 0;
    }

    public synchronized long getNativeHandle() {
        return nativeHandle;
    }

    public ModelManager getModelManager() {
        return modelManager;
    }

    @Override
    public String propose(String userRequest) {
        return propose(userRequest, null);
    }

    @Override
    public synchronized String propose(String userRequest, List<String> recentRequests) {
        if (userRequest == null || userRequest.trim().isEmpty()) {
            return null;
        }

        ModelInfo activeModel = modelManager.getActiveModel();
        File modelFile = modelManager.getActiveModelFile();
        if (activeModel == null || modelFile == null || !modelFile.exists()) {
            Log.w(TAG, "No active local model installed or found on disk");
            return null;
        }

        // Lazy-load model if not currently loaded
        if (nativeHandle == 0) {
            int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
            Log.i(TAG, "Lazy-loading local model: " + modelFile.getName() + " (threads=" + threads + ")");
            nativeHandle = bridge.load(modelFile.getAbsolutePath(), CTX_TOKENS, threads);
            if (nativeHandle == 0) {
                Log.e(TAG, "Failed loading local model into memory");
                return null;
            }
        }

        // Prepare prompt with chat template
        String formattedPrompt = null;
        String systemPrompt = DEFAULT_SYSTEM_PROMPT;
        if (recentRequests != null && !recentRequests.isEmpty()) {
            StringBuilder sb = new StringBuilder(systemPrompt);
            sb.append("\nRecent context:");
            for (String r : recentRequests) {
                if (r != null && !r.trim().isEmpty()) {
                    sb.append("\n- ").append(r.trim());
                }
            }
            systemPrompt = sb.toString();
        }

        formattedPrompt = bridge.formatChat(nativeHandle, activeModel.templateHint, systemPrompt, userRequest.trim());
        if (formattedPrompt == null) {
            // Fallback plain formatting if templating fails
            formattedPrompt = systemPrompt + "\nUser: " + userRequest.trim() + "\nAssistant: ";
        }

        String grammar = loadGrammar();

        modelManager.setModelInUse(true);
        String rawOutput = null;
        try {
            rawOutput = bridge.generate(nativeHandle, formattedPrompt, grammar, MAX_TOKENS, TIMEOUT_MS);
        } catch (Throwable t) {
            Log.e(TAG, "Local model generation threw exception", t);
        } finally {
            modelManager.setModelInUse(false);
            lastGenerationTimeMs = System.currentTimeMillis();
            scheduleIdleUnload();
        }

        if (rawOutput == null) {
            Log.w(TAG, "Local model generation failed or timed out");
            return null;
        }

        return rawOutput.trim();
    }

    private String loadGrammar() {
        if (cachedGrammar != null) {
            return cachedGrammar;
        }
        try (InputStream is = context.getAssets().open("grammar/action_proposal.gbnf")) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            cachedGrammar = baos.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            Log.e(TAG, "Failed loading action_proposal.gbnf grammar", e);
        }
        return cachedGrammar;
    }

    private void scheduleIdleUnload() {
        if (idleHandler != null) {
            idleHandler.removeCallbacks(idleUnloadRunnable);
            idleHandler.postDelayed(idleUnloadRunnable, IDLE_UNLOAD_MS);
        }
    }

    public synchronized void onTrimMemory(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                || level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            Log.i(TAG, "Unloading local model due to trim memory level: " + level);
            unload();
        }
    }

    public synchronized void onAppBackgrounded() {
        Log.i(TAG, "Unloading local model because app moved to background");
        unload();
    }

    public synchronized void unload() {
        if (idleHandler != null) {
            idleHandler.removeCallbacks(idleUnloadRunnable);
        }
        if (nativeHandle != 0) {
            bridge.unload(nativeHandle);
            nativeHandle = 0;
            Log.i(TAG, "Local model unloaded from memory");
        }
    }
}
