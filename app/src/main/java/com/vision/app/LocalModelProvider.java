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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On-device local reasoning provider backed by embedded llama.cpp runtime.
 *
 * Lock discipline:
 * Lock order is always LocalModelProvider before ModelManager. ModelManager methods must
 * never call into LocalModelProvider (no callbacks under the ModelManager monitor); callers
 * must not hold the ModelManager monitor while calling synchronized provider methods.
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
    private final ExecutorService modelExecutor;
    private final AtomicBoolean isUnloading = new AtomicBoolean(false);

    private volatile long nativeHandle = 0;
    private volatile long lastGenerationTimeMs = 0;
    private String cachedGrammar = null;

    private final Runnable idleUnloadRunnable = new Runnable() {
        @Override
        public void run() {
            if (nativeHandle != 0 && !modelManager.isModelInUse()) {
                long idleTime = System.currentTimeMillis() - lastGenerationTimeMs;
                if (idleTime >= IDLE_UNLOAD_MS) {
                    Log.i(TAG, "Unloading resident local model due to 5-minute inactivity");
                    unloadAsync(null);
                } else if (idleHandler != null) {
                    idleHandler.postDelayed(this, IDLE_UNLOAD_MS - idleTime);
                }
            }
        }
    };

    public LocalModelProvider(Context context) {
        this(context, new ModelManager(context), new LlamaBridge(), new Handler(Looper.getMainLooper()), Executors.newSingleThreadExecutor());
    }

    public LocalModelProvider(Context context, ModelManager modelManager, LlamaBridgeInterface bridge, Handler handler) {
        this(context, modelManager, bridge, handler, Executors.newSingleThreadExecutor());
    }

    public LocalModelProvider(Context context, ModelManager modelManager, LlamaBridgeInterface bridge, Handler handler, ExecutorService executor) {
        this.context = context.getApplicationContext();
        this.modelManager = modelManager;
        this.bridge = bridge;
        this.idleHandler = handler;
        this.modelExecutor = executor != null ? executor : Executors.newSingleThreadExecutor();
    }

    public ExecutorService getExecutor() {
        return modelExecutor;
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

        if (isUnloading.get()) {
            Log.w(TAG, "Model is unloading; rejecting new request fail-closed");
            return null;
        }
        // Close load/check gap: mark in-use before active-model check and native load so
        // switch/delete requests correctly see model busy during the entire load phase.
        modelManager.setModelInUse(true);
        try {
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
                // N4: When nativeFormatChat returns null, do NOT silently fall back to plain formatting.
                // Treat as generation setup failure: log LOGE, return null fail-closed.
                Log.e(TAG, "Chat template formatting failed for model: " + activeModel.displayName);
                return null;
            }

            String grammar = loadGrammar();

            String rawOutput = null;
            try {
                rawOutput = bridge.generate(nativeHandle, formattedPrompt, grammar, MAX_TOKENS, TIMEOUT_MS);
            } catch (Throwable t) {
                Log.e(TAG, "Local model generation threw exception", t);
            } finally {
                lastGenerationTimeMs = System.currentTimeMillis();
                scheduleIdleUnload();
            }

            if (rawOutput == null) {
                Log.w(TAG, "Local model generation failed or timed out");
                return null;
            }

            return rawOutput.trim();
        } finally {
            modelManager.setModelInUse(false);
        }
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

    // N3: Unload at >= TRIM_MEMORY_UI_HIDDEN; ALSO unload at TRIM_MEMORY_RUNNING_CRITICAL when idle
    public void onTrimMemory(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                || (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && !modelManager.isModelInUse())) {
            Log.i(TAG, "Unloading local model due to trim memory level: " + level);
            unloadAsync(null);
        }
    }

    public void onAppBackgrounded() {
        Log.i(TAG, "Unloading local model because app moved to background");
        unloadAsync(null);
    }

    public void unloadAsync(Runnable onComplete) {
        isUnloading.set(true);
        modelExecutor.execute(() -> {
            try {
                unload();
            } finally {
                isUnloading.set(false);
                if (onComplete != null) onComplete.run();
            }
        });
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

    public void shutdown() {
        unloadAsync(() -> modelExecutor.shutdown());
    }
}
