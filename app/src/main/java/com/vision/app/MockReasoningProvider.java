package com.vision.app;

/**
 * Deterministic test double that is safe to keep in production source.
 *
 * Performs no network access and has no side effects beyond counting calls;
 * always returns the canned response supplied at construction time, including null.
 */
public final class MockReasoningProvider implements ReasoningProvider {
    private final String cannedResponse;

    public int callCount;

    public MockReasoningProvider(String cannedResponse) {
        this.cannedResponse = cannedResponse;
    }

    @Override
    public String propose(String userRequest) {
        callCount++;
        return cannedResponse;
    }
}
