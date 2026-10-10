package com.vision.app;

import java.util.Locale;

/**
 * Typed settings for the reasoning route: "provider status" and "provider use <mode>".
 * Returns null when the text is not a provider command. Changing the route never sends
 * anything and never approves any action.
 */
public final class ProviderCommands {
    private ProviderCommands() { }

    public static String handle(String command, ProviderSelector selector) {
        if (command == null) return null;
        String c = command.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (c.equals("provider status")) {
            return status(selector);
        }
        if (c.startsWith("provider use ")) {
            String name = c.substring("provider use ".length()).trim();
            ProviderMode mode = parseMode(name);
            if (mode == null) {
                return "PROVIDER NOT CHANGED\n\nUnknown mode. Use: provider use deterministic, provider use on-device or provider use cloud.";
            }
            if (!selector.select(mode)) {
                return "PROVIDER NOT CHANGED\n\n" + label(mode) + " is not available in this build. Mode stays: " + label(selector.mode()) + ".";
            }
            return "PROVIDER SET\n\nMode: " + label(mode) + ". Every proposal is still validated and each action needs your confirmation.";
        }
        return null;
    }

    private static ProviderMode parseMode(String name) {
        switch (name) {
            case "deterministic": case "deterministic only": case "off": return ProviderMode.DETERMINISTIC_ONLY;
            case "on-device": case "on device": case "ondevice": return ProviderMode.ON_DEVICE;
            case "cloud": return ProviderMode.CLOUD;
            default: return null;
        }
    }

    static String label(ProviderMode mode) {
        switch (mode) {
            case ON_DEVICE: return "on-device";
            case CLOUD: return "cloud";
            default: return "deterministic only";
        }
    }

    private static String status(ProviderSelector s) {
        return "PROVIDER\n\nMode: " + label(s.mode())
                + "\nOn-device: " + (s.isAvailable(ProviderMode.ON_DEVICE) ? "available" : "not available in this build")
                + "\nCloud: " + (s.isAvailable(ProviderMode.CLOUD) ? "available" : "not available in this build")
                + "\nNo model fallback happens automatically.";
    }
}
