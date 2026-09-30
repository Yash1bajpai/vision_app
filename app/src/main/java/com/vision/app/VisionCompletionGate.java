package com.vision.app;

/**
 * One-shot completion latch for dialog-driven flows. Dialog buttons, dismissal callbacks
 * and lifecycle events can all try to complete the same action or plan step; only the
 * first fire runs, so a cancel can never double-advance a plan or double-report a result.
 * Pure JVM logic with no Android dependencies, unit-tested on the JVM.
 */
public final class VisionCompletionGate {
    private boolean fired;

    public boolean hasFired() {
        return fired;
    }

    /**
     * Runs completion on the first call only; later calls are ignored.
     * The gate latches even if completion throws, so a failed step is never retried implicitly.
     *
     * @return true on the first (effective) fire, false on every later call
     */
    public boolean fireOnce(Runnable completion) {
        if (fired) return false;
        fired = true;
        if (completion != null) completion.run();
        return true;
    }
}
