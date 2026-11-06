package com.vision.app;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
    // One validation-feedback retry may follow a rejected first proposal, but only when the
    // first proposal never formed a valid typed intent (malformed/missing/extra keys, unknown
    // type, or oversize fields on a confirmation-tier type). A proposal that already carries
    // a valid type but violates that type's field rules is NEVER retried with corrective
    // feedback: fixing its fields could launder a wrong intent into a valid SAFE-tier action
    // that executes without confirmation. A retry-corrected proposal is accepted only when
    // it requires user confirmation under the existing risk policy, so a retry can never
    // turn a formerly refused request into an automatic action.
    //
    // RETRY_TOTAL_BUDGET_MS is a COOPERATIVE budget measured from provider-body entry (after
    // monitor acquisition) and consulted only at Java-side checkpoints; it does not preempt
    // work between checkpoints. Queue wait before entry and blocking bridge calls (model
    // load, chat formatting, generation) are NOT forcibly bounded by it, so this is not a
    // hard deadline: AsyncReasoningRunner's own timeout and late-result suppression remain
    // authoritative. An interrupted or expired request is abandoned at the next checkpoint,
    // never mid native call.
    public static final long RETRY_TOTAL_BUDGET_MS = 14000L;
    public static final long MIN_RETRY_BUDGET_MS = 1500L;

    /** Time source for the cooperative per-request budget; injectable for tests. */
    interface ClockSource {
        long elapsedRealtimeMs();
    }

    private static final ClockSource SYSTEM_CLOCK = new ClockSource() {
        @Override public long elapsedRealtimeMs() { return SystemClock.elapsedRealtime(); }
    };

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are an on-device Android assistant. Convert the user command into a single JSON object with exact keys \"type\", \"target\", \"text\", \"channel\".";

    private final Context context;
    private final ModelManager modelManager;
    private final LlamaBridgeInterface bridge;
    private final Handler idleHandler;
    private final ExecutorService modelExecutor;
    private final ClockSource clock;
    private final AtomicBoolean isUnloading = new AtomicBoolean(false);

    private volatile long nativeHandle = 0;
    private volatile long lastGenerationTimeMs = 0;
    private String cachedGrammar = null;

    private final Runnable idleUnloadRunnable = new Runnable() {
        @Override
        public void run() {
            if (nativeHandle != 0 && !modelManager.isModelInUse()) {
                long idleTime = SystemClock.elapsedRealtime() - lastGenerationTimeMs;
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
        this(context, modelManager, bridge, handler, executor, SYSTEM_CLOCK);
    }

    LocalModelProvider(Context context, ModelManager modelManager, LlamaBridgeInterface bridge, Handler handler, ExecutorService executor, ClockSource clock) {
        this.context = context.getApplicationContext();
        this.modelManager = modelManager;
        this.bridge = bridge;
        this.idleHandler = handler;
        this.modelExecutor = executor != null ? executor : Executors.newSingleThreadExecutor();
        this.clock = clock != null ? clock : SYSTEM_CLOCK;
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
        // A worker interrupted while queued (cancelled or superseded before entering this
        // monitor) must not start setup or generation for a stale request.
        if (Thread.currentThread().isInterrupted()) {
            Log.w(TAG, "Request interrupted before setup; no proposal");
            return null;
        }
        // Cooperative per-request budget anchored at provider-body entry (after monitor
        // acquisition), BEFORE any setup (model load, chat formatting). It is consulted
        // only at Java-side checkpoints and does not preempt work between them; queue wait
        // before entry and blocking bridge calls (load, formatting, generation) are NOT
        // forcibly bounded, so this is not a hard deadline - AsyncReasoningRunner's own
        // timeout and late-result suppression stay authoritative. Cancellation is honored
        // between phases via interrupt checks; the interrupt flag is never cleared here.
        long requestStartMs = clock.elapsedRealtimeMs();
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

            if (Thread.currentThread().isInterrupted()) {
                Log.w(TAG, "Request interrupted after model load; no generation");
                return null;
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

            if (Thread.currentThread().isInterrupted()) {
                Log.w(TAG, "Request interrupted before first generation; no generation");
                return null;
            }
            long firstBudgetMs = Math.min(TIMEOUT_MS, remainingMs(requestStartMs));
            if (firstBudgetMs <= 0) {
                Log.w(TAG, "Request deadline spent during setup; no generation attempted");
                return null;
            }
            String rawOutput = generateOnce(nativeHandle, formattedPrompt, grammar, firstBudgetMs);
            if (rawOutput == null) {
                Log.w(TAG, "Local model generation failed or timed out");
                return null;
            }
            // A blocking native call can return after the cooperative budget is spent;
            // an expired first result is no proposal, never a late acceptance.
            if (remainingMs(requestStartMs) <= 0) {
                Log.w(TAG, "First generation returned after the request budget expired; no proposal");
                return null;
            }

            String trimmed = rawOutput.trim();
            // Plan arrays are validated downstream by ReasoningPlanValidator; the single-action
            // retry below only applies to flat objects. The shipped grammar forbids arrays anyway.
            // Acceptance checkpoint: an expired budget means no proposal, even for arrays.
            if (trimmed.startsWith("[")) {
                if (remainingMs(requestStartMs) <= 0) {
                    Log.w(TAG, "Plan-array output arrived past the request budget; no proposal");
                    return null;
                }
                return trimmed;
            }

            // Self-correction: validate the first proposal against the same fail-closed
            // contract the coordinator applies. If it was rejected for a retryable reason,
            // allow exactly one retry whose prompt carries the violated rule, then validate
            // again. The validator remains the only gate; a failed or disallowed retry
            // returns the original output unchanged, which the coordinator rejects exactly
            // as before.
            java.util.Map<String, String> fields = StrictJson.parseObject(trimmed);
            ReasoningProposalValidator.ValidationResult first =
                    fields == null ? null : ReasoningProposalValidator.validate(fields);
            if (first != null && first.action != null) {
                // Acceptance checkpoint: parsing/validation may resume after the budget
                // expired; an expired first proposal is no proposal, never a late acceptance.
                if (remainingMs(requestStartMs) <= 0) {
                    Log.w(TAG, "First proposal validated past the request budget; no proposal");
                    return null;
                }
                return trimmed;
            }
            // An explicit UNKNOWN is a truthful refusal: honor it without a retry, never
            // coerce a refused request into an unrelated action confirmation dialog.
            String firstType = fields == null ? null : fields.get("type");
            if (firstType != null && "UNKNOWN".equalsIgnoreCase(firstType.trim())) {
                return trimmed;
            }
            ReasoningProposalValidator.RejectionReason reason =
                    first != null ? first.reason : ReasoningProposalValidator.RejectionReason.MALFORMED_JSON;
            if (!isRetryable(reason)) {
                Log.w(TAG, "Proposal rejected with non-retryable reason " + reason + "; no retry");
                return trimmed;
            }
            // The runner cancels by interrupting the worker thread. Native generate() is
            // blocking and not guaranteed interruptible, so cancellation is honored between
            // phases and the interrupt flag is never cleared here.
            if (Thread.currentThread().isInterrupted()) {
                Log.w(TAG, "Request interrupted after first generation; no retry");
                return trimmed;
            }
            long retryBudgetMs = remainingMs(requestStartMs);
            if (retryBudgetMs < MIN_RETRY_BUDGET_MS) {
                Log.w(TAG, "No retry budget left after rejected proposal; returning original output");
                return trimmed;
            }

            String retryPrompt = bridge.formatChat(nativeHandle, activeModel.templateHint,
                    systemPrompt, retryMessage(userRequest.trim(), reason));
            if (retryPrompt == null) {
                Log.e(TAG, "Chat template formatting failed for retry; returning original output");
                return trimmed;
            }
            retryBudgetMs = remainingMs(requestStartMs);
            if (retryBudgetMs < MIN_RETRY_BUDGET_MS) {
                Log.w(TAG, "Retry budget spent during formatting; returning original output");
                return trimmed;
            }
            if (Thread.currentThread().isInterrupted()) {
                Log.w(TAG, "Request interrupted before retry generation; returning original output");
                return trimmed;
            }
            String retryOutput = generateOnce(nativeHandle, retryPrompt, grammar,
                    Math.min(TIMEOUT_MS, retryBudgetMs));
            if (retryOutput == null) {
                return trimmed;
            }
            // An expired retry result can never replace the original output.
            if (remainingMs(requestStartMs) <= 0) {
                Log.w(TAG, "Retry generation returned after the request budget expired; returning original output");
                return trimmed;
            }
            String retryTrimmed = retryOutput.trim();
            if (retryTrimmed.startsWith("[")) {
                return trimmed;
            }
            java.util.Map<String, String> retryFields = StrictJson.parseObject(retryTrimmed);
            ReasoningProposalValidator.ValidationResult second =
                    retryFields == null ? null : ReasoningProposalValidator.validate(retryFields);
            // Safety invariant: a retry-corrected proposal is accepted ONLY if it requires
            // user confirmation under the existing risk policy. A retry can never turn a
            // formerly refused request into an automatic SAFE-tier action; corrected
            // SAFE-tier proposals are discarded and the request fails closed as before.
            if (second != null && second.action != null
                    && VisionRiskPolicy.requiresConfirmation(second.action.type)) {
                // Acceptance checkpoint: an expired corrected proposal can never replace the
                // rejected original, even when it would have been valid.
                if (remainingMs(requestStartMs) <= 0) {
                    Log.w(TAG, "Corrected proposal validated past the request budget; returning original output");
                    return trimmed;
                }
                Log.i(TAG, "Local model self-corrected a rejected proposal on one bounded retry (confirmation-tier)");
                return retryTrimmed;
            }
            return trimmed;
        } finally {
            modelManager.setModelInUse(false);
        }
    }

    private String generateOnce(long handle, String prompt, String grammar, long timeoutMs) {
        String out = null;
        try {
            out = bridge.generate(handle, prompt, grammar, MAX_TOKENS, timeoutMs);
        } catch (Throwable t) {
            Log.e(TAG, "Local model generation threw exception", t);
        } finally {
            lastGenerationTimeMs = SystemClock.elapsedRealtime();
            scheduleIdleUnload();
        }
        return out;
    }

    private long remainingMs(long requestStartMs) {
        return RETRY_TOTAL_BUDGET_MS - (clock.elapsedRealtimeMs() - requestStartMs);
    }

    /**
     * Only rejections that never formed a valid typed intent may be retried.
     * INVALID_TARGET / INVALID_TEXT / INVALID_CHANNEL mean the model already committed to a
     * type and broke its field rules; feeding those rules back could repair a wrong intent
     * into a valid SAFE-tier action, so those proposals fail closed without a retry.
     * SIZE_LIMIT can only occur on text-bearing (confirmation-tier) types, so retrying it
     * cannot produce an unconfirmed automatic action.
     */
    private static boolean isRetryable(ReasoningProposalValidator.RejectionReason reason) {
        switch (reason) {
            case MALFORMED_JSON:
            case MISSING_KEYS:
            case EXTRA_KEYS:
            case INVALID_TYPE:
            case SIZE_LIMIT:
                return true;
            default:
                return false;
        }
    }

    /** Feedback for the single bounded retry: the violated contract rule, never user data. */
    private static String retryMessage(String request,
                                       ReasoningProposalValidator.RejectionReason reason) {
        String hint;
        switch (reason) {
            case MISSING_KEYS:
            case EXTRA_KEYS:
                hint = "Use exactly these keys: \"type\", \"target\", \"text\", \"channel\".";
                break;
            case INVALID_TYPE:
                hint = "type must be one of: " + supportedTypeList() + ".";
                break;
            case SIZE_LIMIT:
                hint = "target must be at most 70 characters and text at most 2000 characters.";
                break;
            case MALFORMED_JSON:
            default:
                hint = "Reply with exactly one JSON object.";
                break;
        }
        return request + "\nYour previous JSON was rejected (" + reason.name() + "). " + hint
                + " Reply with one corrected JSON object only.";
    }

    private static String supportedTypeList() {
        StringBuilder sb = new StringBuilder();
        java.util.List<VisionToolRegistry.Tool> tools = VisionToolRegistry.all();
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(tools.get(i).type.name());
        }
        return sb.toString();
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
