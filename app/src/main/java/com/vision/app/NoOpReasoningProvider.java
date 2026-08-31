package com.vision.app;

/**
 * Production default reasoning provider.
 *
 * Keeps Vision fully deterministic and offline: it is consulted only when the
 * deterministic parser returns UNKNOWN and always declines to propose an action.
 */
public final class NoOpReasoningProvider implements ReasoningProvider {
    @Override
    public String propose(String userRequest) {
        return null;
    }
}
