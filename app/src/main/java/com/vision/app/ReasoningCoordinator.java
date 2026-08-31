package com.vision.app;

import java.util.Map;

/**
 * Coordinates the deterministic parser with an optional reasoning provider.
 *
 * The deterministic parser is always consulted first and its result is final
 * whenever it understands the command; the provider is consulted only for
 * UNKNOWN requests, and its proposal must survive StrictJson parsing and
 * ReasoningProposalValidator validation before it becomes an action.
 *
 * This coordinator never logs, never executes actions, and never consults
 * VisionRiskPolicy: risk policy is applied downstream, unchanged.
 */
public final class ReasoningCoordinator {

    private ReasoningCoordinator() { }

    public static VisionAction coordinate(String request, ReasoningProvider provider) {
        VisionAction parsed = VisionActionParser.parse(request);
        if (parsed.type != VisionAction.Type.UNKNOWN) {
            return parsed;
        }
        if (provider == null) {
            return parsed;
        }
        String raw = provider.propose(request);
        if (raw == null || raw.trim().isEmpty()) {
            return new VisionAction(VisionAction.Type.UNKNOWN, request, "");
        }
        Map<String, String> fields = StrictJson.parseObject(raw);
        if (fields == null) {
            return new VisionAction(VisionAction.Type.UNKNOWN, request, "");
        }
        ReasoningProposalValidator.ValidationResult result = ReasoningProposalValidator.validate(fields);
        if (result.action != null) {
            return result.action.withRequest(request);
        }
        return new VisionAction(VisionAction.Type.UNKNOWN, request, "");
    }
}
