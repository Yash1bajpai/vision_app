package com.vision.app;

import java.util.Map;

/** Local explicit enum only. Never supplies model input, recipients or action authority. */
public final class SavedPreferenceMemory {
    public static final long TTL_MILLIS = 30L * 24 * 60 * 60 * 1000;
    interface Storage {
        String read() throws Exception;
        void write(String record) throws Exception;
        void clear() throws Exception;
    }
    private final Storage storage;
    private boolean enabled;
    private String style;
    private long savedAt, expiresAt, observedAt;
    private boolean failed;

    SavedPreferenceMemory(Storage storage) { this.storage = storage; }

    private void reset() { enabled = false; style = null; savedAt = expiresAt = observedAt = 0; }
    private boolean failure() {
        reset(); failed = true;
        try { storage.clear(); } catch (Exception ignored) { /* no value is usable */ }
        return false;
    }
    private static long timestamp(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,15}")) throw new IllegalArgumentException();
        return Long.parseLong(value);
    }
    private String record() {
        return "{\"version\":\"1\",\"enabled\":\"true\",\"response_style\":\""
                + (style == null ? "" : style) + "\",\"saved_at\":\"" + savedAt
                + "\",\"expires_at\":\"" + expiresAt + "\",\"observed_at\":\"" + observedAt + "\"}";
    }
    private boolean write() {
        try { storage.write(record()); failed = false; return true; }
        catch (Exception e) { return failure(); }
    }
    private boolean refresh(long now) {
        if (failed) return false;
        if (now < 0 || now > Long.MAX_VALUE - TTL_MILLIS) return failure();
        try {
            String raw = storage.read();
            if (raw == null) { reset(); return !failed; }
            if (raw.length() > 512) return failure();
            Map<String, String> r = StrictJson.parseObject(raw);
            if (r == null || r.size() != 6 || !"1".equals(r.get("version"))
                    || !"true".equals(r.get("enabled")) || !r.containsKey("response_style")) return failure();
            String value = r.get("response_style");
            long saved = timestamp(r.get("saved_at")), expiry = timestamp(r.get("expires_at")), seen = timestamp(r.get("observed_at"));
            if (seen > now || seen < saved) return failure();
            if (value.isEmpty()) {
                if (saved != 0 || expiry != 0) return failure();
            } else if ((!"concise".equals(value) && !"standard".equals(value))
                    || saved > Long.MAX_VALUE - TTL_MILLIS || expiry != saved + TTL_MILLIS) return failure();
            enabled = true; style = value.isEmpty() ? null : value;
            savedAt = saved; expiresAt = expiry; observedAt = seen;
            if (style != null && now >= expiresAt) { style = null; savedAt = expiresAt = 0; }
            // Persist the high-water clock, not a new retention period. Detect rollback after restart.
            if (now != seen || (style == null && !value.isEmpty())) { observedAt = now; return write(); }
            failed = false;
            return true;
        } catch (Exception e) { return failure(); }
    }
    private String unavailable() {
        return "SAVED PREFERENCE MEMORY UNAVAILABLE\n\nNo saved value will be used. Encrypted storage, record or clock validation failed. Enable again to retry, or use 60-second session preference memory.";
    }
    public String handle(String command, long now) {
        if (command == null) return null;
        String c = command.trim().toLowerCase(java.util.Locale.ROOT);
        boolean control = c.startsWith("enable saved preference") || c.startsWith("disable saved preference")
                || c.startsWith("show saved preferences") || c.startsWith("clear saved preferences")
                || c.startsWith("forget saved preference") || c.startsWith("save preference");
        if (!control) return null;
        if (c.equals("disable saved preference memory")) return disable();
        if (c.equals("enable saved preference memory") && failed) {
            try { storage.clear(); reset(); failed = false; }
            catch (Exception e) { return unavailable(); }
        }
        if (!refresh(now) && !c.equals("enable saved preference memory")) return unavailable();
        if (c.equals("enable saved preference memory")) {
            if (now < 0 || now > Long.MAX_VALUE - TTL_MILLIS) return unavailable();
            // Enabling does not copy the session value or renew an existing saved record.
            if (!enabled) { enabled = true; style = null; savedAt = expiresAt = 0; observedAt = now; }
            return write() ? "SAVED PREFERENCE MEMORY ON\n\nSeparate device opt-in. Only response_style concise/standard; encrypted locally for 30 days from explicit save. No chat history, model input or action approvals. No session value copied.\nUse save preference response_style concise (or standard). View: show preferences. Delete: forget preference response_style or clear preferences. Disable: disable saved preference memory. Cloud keys are separate."
                    : unavailable();
        }
        if (c.equals("show saved preferences")) return inspect(now);
        if (c.equals("clear saved preferences") || c.equals("forget saved preference response_style")) return clear(now);
        if (c.equals("save preference response_style concise") || c.equals("save preference response_style standard")) {
            if (!enabled) return "SAVED PREFERENCE MEMORY OFF\n\nType enable saved preference memory first. Session preferences are not copied.";
            style = c.endsWith(" concise") ? "concise" : "standard";
            savedAt = observedAt = now; expiresAt = now + TTL_MILLIS;
            return write() ? "PREFERENCE SAVED ON DEVICE\n\nresponse_style = " + style
                    + "\nEncrypted locally; expires " + date(expiresAt) + ". Reads and restarts do not extend expiry. Battery/network/time wording only."
                    : unavailable();
        }
        return "PREFERENCE NOT SAVED\n\nOnly save preference response_style concise or standard is supported. No free-text memory, recipients, approvals or tool results.";
    }
    private static String date(long timestamp) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.ROOT);
        return f.format(new java.util.Date(timestamp));
    }
    public String inspect(long now) {
        if (!refresh(now)) return unavailable();
        return "SAVED DEVICE PREFERENCES\n\nMemory: " + (enabled ? "on" : "off")
                + "\nStorage: local encrypted only\nresponse_style: " + (style == null ? "not set" : style)
                + "\nExpiry: " + (style == null ? "none" : date(expiresAt))
                + "\n30 days from explicit save; reads/restarts do not extend. No model input or action approvals. Cloud keys unchanged.";
    }
    public String failureNotice() { return failed ? "\n\n" + unavailable() : ""; }
    public String style(long now) { return refresh(now) && enabled ? style : null; }
    public String clear(long now) {
        if (!refresh(now)) return unavailable();
        style = null; savedAt = expiresAt = 0; observedAt = now;
        if (enabled && !write()) return unavailable();
        return "SAVED PREFERENCES CLEARED\n\nresponse_style removed; saved opt-in unchanged. Cloud keys unchanged.";
    }
    public String disable() {
        reset();
        try { storage.clear(); failed = false;
            return "SAVED PREFERENCE MEMORY OFF\n\nSaved values and opt-in deleted. Cloud keys unchanged.";
        } catch (Exception e) { failure(); return unavailable(); }
    }
}
