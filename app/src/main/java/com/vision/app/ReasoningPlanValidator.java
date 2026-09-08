package com.vision.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates a parsed array of flat field maps from a reasoning provider into a
 * bounded {@link VisionPlan} or a rejection reason.
 *
 * Plans are untrusted: every step must independently survive the exact same
 * {@link ReasoningProposalValidator} rules as a single proposal — exact four-key
 * schema, supported action types only, per-type target/text/channel rules. There
 * are no plan-level keys and no plan-level risk semantics: a rejected step rejects
 * the whole plan, and an accepted plan can never carry a field that influences
 * risk, confirmation, or approval.
 */
public final class ReasoningPlanValidator {

    /** Why a plan was rejected. */
    public enum RejectionReason {
        NULL_STEPS,
        EMPTY_PLAN,
        SIZE_LIMIT,
        INVALID_STEP
    }

    /** Outcome of validation: exactly one of plan or reason is non-null. */
    public static final class PlanValidationResult {
        public final VisionPlan plan;             // null when rejected
        public final RejectionReason reason;      // null when accepted
        public final int invalidStepIndex;        // -1 unless INVALID_STEP

        private PlanValidationResult(VisionPlan plan, RejectionReason reason, int invalidStepIndex) {
            this.plan = plan;
            this.reason = reason;
            this.invalidStepIndex = invalidStepIndex;
        }
    }

    private ReasoningPlanValidator() { }

    public static PlanValidationResult validate(String request, List<Map<String, String>> steps) {
        if (steps == null) {
            return rejected(RejectionReason.NULL_STEPS, -1);
        }
        if (steps.isEmpty()) {
            return rejected(RejectionReason.EMPTY_PLAN, -1);
        }
        if (steps.size() > VisionPlan.MAX_ACTIONS) {
            return rejected(RejectionReason.SIZE_LIMIT, -1);
        }
        List<VisionAction> actions = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            ReasoningProposalValidator.ValidationResult step =
                    ReasoningProposalValidator.validate(steps.get(i));
            if (step.action == null) {
                return rejected(RejectionReason.INVALID_STEP, i);
            }
            actions.add(step.action.withRequest(request));
        }
        return new PlanValidationResult(new VisionPlan(request, actions), null, -1);
    }

    private static PlanValidationResult rejected(RejectionReason reason, int invalidStepIndex) {
        return new PlanValidationResult(null, reason, invalidStepIndex);
    }
}
