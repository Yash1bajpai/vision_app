package com.vision.app;

/** Volatile, explicit user preference draft. Not a provider input or action authority. */
public final class VisionPreferenceControls {
    public static final long TTL_MILLIS = 60_000L;
    private boolean enabled;
    private String responseStyle;
    private long updatedAt;

    /** Exact local commands only; null means not a preference-control command.
     * Invalid controls are consumed locally, never forwarded to a future provider. */
    public String handle(String command, long now) {
        expire(now);
        if (command == null) return null;
        String c = command.trim().toLowerCase(java.util.Locale.ROOT);
        if (c.equals("enable preference memory")) {
            enabled = true;
            return "PREFERENCE MEMORY ON\n\nSession only. Explicit response_style values only. No disk storage or action approval. Values expire after 60 seconds; backgrounding resets memory to off.";
        }
        if (c.equals("disable preference memory")) {
            reset();
            return "PREFERENCE MEMORY OFF\n\nAll preference values cleared.";
        }
        if (c.equals("show preferences")) return inspect(now);
        if (c.equals("clear preferences")) {
            clearValues();
            return "PREFERENCES CLEARED\n\nOpt-in unchanged; no values retained.";
        }
        if (c.equals("forget preference response_style")) {
            clearValues();
            return "PREFERENCE DELETED\n\nresponse_style removed.";
        }
        if (c.startsWith("remember preference") || c.startsWith("forget preference")
                || c.startsWith("enable preference") || c.startsWith("disable preference")
                || c.startsWith("show preferences") || c.startsWith("clear preferences")) {
            if (c.equals("remember preference response_style concise")
                    || c.equals("remember preference response_style standard")) {
                if (!enabled) return "PREFERENCE MEMORY OFF\n\nType enable preference memory first.";
                if (now < 0) return "PREFERENCE NOT SAVED\n\nInvalid session clock.";
                responseStyle = c.endsWith(" concise") ? "concise" : "standard";
                updatedAt = now;
                return "PREFERENCE SAVED\n\nresponse_style = " + responseStyle
                        + "\nSession only; expires in 60 seconds. Used for battery, network and time responses only.";
            }
            return "PREFERENCE NOT SAVED\n\nOnly response_style concise or standard is supported. No free-text memory, recipients, approvals or tool results.";
        }
        return null;
    }

    public String inspect(long now) {
        expire(now);
        return "SESSION PREFERENCES\n\nMemory: " + (enabled ? "on" : "off")
                + "\nresponse_style: " + (responseStyle == null ? "not set" : responseStyle)
                + "\nExpires 60s after save; resets on background."
                + "\nNo disk. Battery/network/time wording only.";
    }

    /** Only trusted, structured status facts use this style; never arbitrary text truncation. */
    public String statusResponse(String standard, String concise, long now) {
        expire(now);
        return "SUCCEEDED\n\n" + (enabled && "concise".equals(responseStyle) ? concise : standard);
    }

    public void reset() { enabled = false; clearValues(); }
    private void clearValues() { responseStyle = null; updatedAt = 0; }
    void expire(long now) {
        if (responseStyle != null && (now < updatedAt || now - updatedAt >= TTL_MILLIS)) clearValues();
    }
}
