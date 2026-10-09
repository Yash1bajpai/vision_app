package com.vision.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs one provider request off the calling thread with a deadline and cancellation.
 *
 * - One request at a time: a new submit cancels the previous one.
 * - Every request is reported to its callback exactly once, and a cancelled, timed-out or
 *   superseded request can never later deliver a proposal (late results are discarded).
 * - Request and output sizes are bounded. Ordinary provider exceptions become FAILED.
 * - No retries and no fallback to another provider.
 * - The callback runs on a runner thread; the caller must marshal to its own thread.
 * - A blocking provider cannot be forced to stop, only interrupted. At most MAX_LIVE_WORKERS
 *   such threads may exist; beyond that new requests report UNAVAILABLE.
 */
public final class AsyncReasoningRunner {
    public interface Callback { void onOutcome(long requestId, ProviderOutcome outcome); }

    public static final int MAX_LIVE_WORKERS = 2;

    private final long timeoutMillis;
    private final int maxRequestChars;
    private final int maxOutputChars;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "vision-provider-timer");
        t.setDaemon(true);
        return t;
    });
    private final Object lock = new Object();
    private Request current;
    private long nextId = 1;
    private int liveWorkers;
    private boolean closed;

    public AsyncReasoningRunner(long timeoutMillis, int maxRequestChars, int maxOutputChars) {
        if (timeoutMillis <= 0 || maxRequestChars <= 0 || maxOutputChars <= 0) {
            throw new IllegalArgumentException("limits must be positive");
        }
        this.timeoutMillis = timeoutMillis;
        this.maxRequestChars = maxRequestChars;
        this.maxOutputChars = maxOutputChars;
    }

    private final class Request {
        final long id;
        final Callback callback;
        final AtomicBoolean finished = new AtomicBoolean();
        volatile Thread worker;

        Request(long id, Callback callback) {
            this.id = id;
            this.callback = callback;
        }

        /** Delivers at most one outcome; later calls are discarded. */
        boolean finish(ProviderOutcome outcome) {
            if (!finished.compareAndSet(false, true)) {
                return false;
            }
            synchronized (lock) {
                if (current == this) {
                    current = null;
                }
            }
            try {
                callback.onOutcome(id, outcome);
            } catch (RuntimeException ignored) {
                // A faulty callback must not affect later requests.
            }
            return true;
        }
    }

    /** Starts a request and returns its id. The previous request, if any, is cancelled. */
    public long submit(ReasoningProvider provider, String request, List<String> recent, Callback callback) {
        if (provider == null || callback == null) {
            throw new IllegalArgumentException("provider and callback are required");
        }
        Request previous;
        Request req;
        boolean refuse = false;
        synchronized (lock) {
            req = new Request(nextId++, callback);
            previous = current;
            current = req;
            if (closed || liveWorkers >= MAX_LIVE_WORKERS) {
                refuse = true;
            } else {
                liveWorkers++;
            }
        }
        if (previous != null) {
            interrupt(previous, ProviderOutcome.of(ProviderOutcome.Status.CANCELLED));
        }
        if (refuse) {
            req.finish(ProviderOutcome.of(ProviderOutcome.Status.UNAVAILABLE));
            return req.id;
        }
        if (request == null || request.length() > maxRequestChars) {
            releaseWorker();
            req.finish(ProviderOutcome.of(ProviderOutcome.Status.FAILED));
            return req.id;
        }
        final List<String> hints = recent == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(recent));
        Thread worker = new Thread(() -> runWorker(req, provider, request, hints), "vision-provider-worker");
        worker.setDaemon(true);
        req.worker = worker;
        try {
            timer.schedule(() -> interrupt(req, ProviderOutcome.of(ProviderOutcome.Status.TIMED_OUT)),
                    timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            releaseWorker();
            req.finish(ProviderOutcome.of(ProviderOutcome.Status.UNAVAILABLE));
            return req.id;
        }
        worker.start();
        return req.id;
    }

    private void runWorker(Request req, ReasoningProvider provider, String request, List<String> hints) {
        try {
            String raw = provider.propose(request, hints);
            ProviderOutcome outcome;
            if (raw == null || raw.trim().isEmpty()) {
                outcome = ProviderOutcome.of(ProviderOutcome.Status.NO_PROPOSAL);
            } else if (raw.length() > maxOutputChars) {
                outcome = ProviderOutcome.of(ProviderOutcome.Status.FAILED);
            } else {
                outcome = ProviderOutcome.proposal(raw);
            }
            req.finish(outcome);
        } catch (RuntimeException e) {
            req.finish(ProviderOutcome.of(ProviderOutcome.Status.FAILED));
        } catch (Error e) {
            req.finish(ProviderOutcome.of(ProviderOutcome.Status.FAILED));
            throw e;
        } finally {
            releaseWorker();
        }
    }

    private void releaseWorker() {
        synchronized (lock) {
            liveWorkers--;
        }
    }

    private void interrupt(Request req, ProviderOutcome outcome) {
        if (req.finish(outcome)) {
            Thread worker = req.worker;
            if (worker != null) {
                worker.interrupt();
            }
        }
    }

    /** Cancels the in-flight request, if any. Safe to call from any thread, repeatedly. */
    public void cancel() {
        Request req;
        synchronized (lock) {
            req = current;
        }
        if (req != null) {
            interrupt(req, ProviderOutcome.of(ProviderOutcome.Status.CANCELLED));
        }
    }

    /** Cancels and refuses all later requests. */
    public void shutdown() {
        synchronized (lock) {
            closed = true;
        }
        cancel();
        timer.shutdownNow();
    }
}
