package com.vision.app;

/**
 * Pure sequential-execution policy for multi-step plans. No Android dependencies.
 *
 * A plan advances strictly one step at a time. The next step runs only when the
 * current step reached SUCCEEDED or COMPOSER_OPENED; any DENIED or FAILED step
 * halts the plan and the remaining steps are never executed.
 */
public final class VisionPlanExecutor {
    private VisionPlanExecutor() { }

    /** True when a step has reached a final outcome and the plan can be advanced or halted. */
    public static boolean isStepTerminal(VisionAction.State state) {
        return state == VisionAction.State.SUCCEEDED
                || state == VisionAction.State.COMPOSER_OPENED
                || state == VisionAction.State.FAILED
                || state == VisionAction.State.DENIED;
    }

    /** True only for outcomes that permit the next step to be offered for execution. */
    public static boolean shouldProceedToNextStep(VisionAction.State state) {
        return state == VisionAction.State.SUCCEEDED
                || state == VisionAction.State.COMPOSER_OPENED;
    }
}
