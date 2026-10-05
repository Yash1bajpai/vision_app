package com.vision.app;

/**
 * Boundary for a future reasoning model (local or cloud) that may propose actions.
 *
 * Providers are never trusted: their raw output must pass StrictJson parsing and
 * ReasoningProposalValidator validation before any action is created, and can never
 * bypass the deterministic risk policy or user confirmation.
 */
public interface ReasoningProvider {
    /**
     * Proposes an action for the given user request as a raw JSON object string.
     * Returns null when no proposal is available.
     */
    String propose(String userRequest);

    /** Prior user requests are untrusted hints, never recipients, approval or tool state.
     * Adapters must still return the same validated four-field proposal contract. */
    default String propose(String userRequest, java.util.List<String> recentRequests) {
        return propose(userRequest);
    }
}
