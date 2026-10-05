package com.vision.app;

/** Monotonic total-plan budget and generation token. Old callbacks never belong to a new plan. */
public final class VisionPlanLifetime {
    public static final long TIMEOUT_MILLIS = 60_000L;
    private long generation;
    private long startedAt;
    private boolean active;

    public long start(long now) { generation++; startedAt = now; active = true; return generation; }
    public boolean isCurrent(long token, long now) {
        return active && token == generation && now >= startedAt && now - startedAt < TIMEOUT_MILLIS;
    }
    public void stop() { active = false; generation++; }
}
