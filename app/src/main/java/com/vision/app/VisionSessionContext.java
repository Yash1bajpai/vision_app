package com.vision.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Short-lived user-request hints only. Never stores tool results, approvals or capabilities. */
public final class VisionSessionContext {
    public static final long TTL_MILLIS = 60_000L;
    public static final int MAX_REQUESTS = 2;
    public static final int MAX_REQUEST_LENGTH = 512;
    private final List<String> requests = new ArrayList<>();
    private long updatedAt;

    public void remember(String request, long now) {
        expire(now);
        if (request == null || request.trim().isEmpty()) return;
        String value = request.trim();
        // Oversized input is omitted, not truncated into a different request.
        if (value.length() > MAX_REQUEST_LENGTH) return;
        if (requests.size() == MAX_REQUESTS) requests.remove(0);
        requests.add(value);
        updatedAt = now;
    }

    public List<String> snapshot(long now) {
        expire(now);
        return Collections.unmodifiableList(new ArrayList<>(requests));
    }

    public void clear() { requests.clear(); updatedAt = 0; }

    private void expire(long now) {
        if (!requests.isEmpty() && (now < updatedAt || now - updatedAt >= TTL_MILLIS)) clear();
    }
}
