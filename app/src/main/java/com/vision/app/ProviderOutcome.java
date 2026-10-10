package com.vision.app;

/**
 * Result of one asynchronous provider request. Only PROPOSAL carries raw output, and that
 * output is still untrusted: it must go through ReasoningCoordinator's strict validation.
 */
public final class ProviderOutcome {
    public enum Status { PROPOSAL, NO_PROPOSAL, UNAVAILABLE, TIMED_OUT, CANCELLED, FAILED }

    public final Status status;
    /** Non-null only when status is PROPOSAL. */
    public final String raw;

    private ProviderOutcome(Status status, String raw) {
        this.status = status;
        this.raw = raw;
    }

    static ProviderOutcome proposal(String raw) { return new ProviderOutcome(Status.PROPOSAL, raw); }

    static ProviderOutcome of(Status status) { return new ProviderOutcome(status, null); }
}
