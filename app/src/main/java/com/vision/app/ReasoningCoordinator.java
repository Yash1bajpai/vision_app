package com.vision.app;

import java.util.List;
import java.util.Map;

/**
 * Coordinates the deterministic parser with an optional reasoning provider.
 *
 * The deterministic parser is always consulted first and its result is final
 * whenever it understands the command; the provider is consulted only for
 * UNKNOWN requests. A provider may propose a single action (one flat JSON
 * object) or a bounded plan (one JSON array of flat objects). Both must survive
 * StrictJson parsing and the corresponding fail-closed validator before
 * anything is created.
 *
 * This coordinator never logs, never executes actions, and never consults
 * VisionRiskPolicy: risk policy is applied downstream, unchanged, per action.
 */
public final class ReasoningCoordinator {

    /** Result of coordination: exactly one of action or plan is meaningful. */
    public static final class CoordinationResult {
        /** Never null; UNKNOWN when nothing was understood. */
        public final VisionAction action;
        /** Null unless the provider proposed a valid bounded plan. */
        public final VisionPlan plan;

        private CoordinationResult(VisionAction action, VisionPlan plan) {
            this.action = action;
            this.plan = plan;
        }
    }

    private ReasoningCoordinator() { }

    /** Single-action coordination; behavior identical to prior releases. */
    public static VisionAction coordinate(String request, ReasoningProvider provider) {
        return coordinateFull(request, provider).action;
    }

    /** Full coordination: parser-first, then a single proposal or a bounded plan. */
    public static CoordinationResult coordinateFull(String request, ReasoningProvider provider) {
        return coordinateFull(request, provider, java.util.Collections.emptyList());
    }

    public static CoordinationResult coordinateFull(String request, ReasoningProvider provider,
                                                     java.util.List<String> recentRequests) {
        VisionAction parsed = VisionActionParser.parse(request);
        if (parsed.type != VisionAction.Type.UNKNOWN) {
            return new CoordinationResult(parsed, null);
        }
        if (provider == null) {
            return unknown(request);
        }
        String raw = provider.propose(request, recentRequests == null
                ? java.util.Collections.emptyList()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(recentRequests)));
        if (raw == null || raw.trim().isEmpty()) {
            return unknown(request);
        }
        if (raw.trim().startsWith("[")) {
            List<Map<String, String>> steps = StrictJson.parseArray(raw);
            if (steps == null) {
                return unknown(request);
            }
            ReasoningPlanValidator.PlanValidationResult result =
                    ReasoningPlanValidator.validate(request, steps);
            if (result.plan != null) {
                return new CoordinationResult(
                        new VisionAction(VisionAction.Type.UNKNOWN, request, ""), result.plan);
            }
            return unknown(request);
        }
        Map<String, String> fields = StrictJson.parseObject(raw);
        if (fields == null) {
            return unknown(request);
        }
        ReasoningProposalValidator.ValidationResult result = ReasoningProposalValidator.validate(fields);
        if (result.action != null) {
            return new CoordinationResult(result.action.withRequest(request), null);
        }
        return unknown(request);
    }

    private static CoordinationResult unknown(String request) {
        return new CoordinationResult(
                new VisionAction(VisionAction.Type.UNKNOWN, request, ""), null);
    }
}
