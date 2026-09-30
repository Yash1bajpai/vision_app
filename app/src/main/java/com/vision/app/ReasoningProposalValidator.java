package com.vision.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Validates a flat field map from a reasoning provider into a VisionAction or a
 * rejection reason.
 *
 * Providers are untrusted: proposals are accepted only when they carry exactly
 * the expected four keys, name a currently supported action type, and satisfy
 * the per-type target, text, and channel rules. Anything else is rejected
 * fail-closed; a rejected proposal can never become an action.
 */
public final class ReasoningProposalValidator {

    /** Why a proposal was rejected. */
    public enum RejectionReason {
        MALFORMED_JSON,
        MISSING_KEYS,
        EXTRA_KEYS,
        INVALID_TYPE,
        INVALID_TARGET,
        INVALID_TEXT,
        INVALID_CHANNEL,
        SIZE_LIMIT
    }

    /** Outcome of validation: exactly one of action or reason is non-null. */
    public static final class ValidationResult {
        public final VisionAction action;      // null when rejected
        public final RejectionReason reason;   // null when accepted

        private ValidationResult(VisionAction action, RejectionReason reason) {
            this.action = action;
            this.reason = reason;
        }
    }

    private static final Set<String> REQUIRED_KEYS = new HashSet<String>(Arrays.asList(
            "type", "target", "text", "channel"));

    private static final String[] CANONICAL_APPS = {
            "WhatsApp", "WhatsApp Business", "Telegram", "Gmail", "Messages", "Calendar"
    };

    private static final String[] VALID_CHANNELS = {
            "sms", "whatsapp", "whatsapp_business", "email", "telegram"
    };

    private static final int MAX_TARGET_LENGTH = 70;
    private static final int MAX_TEXT_LENGTH = 2000;

    private ReasoningProposalValidator() { }

    public static ValidationResult validate(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            return rejected(RejectionReason.MALFORMED_JSON);
        }
        for (String key : REQUIRED_KEYS) {
            if (!fields.containsKey(key)) {
                return rejected(RejectionReason.MISSING_KEYS);
            }
        }
        for (String key : fields.keySet()) {
            if (!REQUIRED_KEYS.contains(key)) {
                return rejected(RejectionReason.EXTRA_KEYS);
            }
        }
        for (String key : REQUIRED_KEYS) {
            if (fields.get(key) == null) {
                return rejected(RejectionReason.MALFORMED_JSON);
            }
        }
        VisionAction.Type type = parseType(fields.get("type"));
        if (type == null) {
            return rejected(RejectionReason.INVALID_TYPE);
        }
        String target = fields.get("target").trim();
        String text = fields.get("text").trim();
        String channel = fields.get("channel").trim();
        switch (type) {
            case SHOW_HELP:
                return validateStatusRead(type, target, text, channel);
            case READ_NOTIFICATION:
                return validateRead(target, text, channel);
            case READ_BATTERY:
            case READ_NETWORK:
            case READ_TIME:
            case READ_CALENDAR:
                return validateStatusRead(type, target, text, channel);
            case SET_TIMER:
                return validateTimer(target, text, channel);
            case SET_ALARM:
                return validateAlarm(target, text, channel);
            case NAVIGATE_TO:
                return validateNavigate(target, text, channel);
            case MEDIA_CONTROL:
                return validateMediaCommand(target, text, channel);
            case SET_VOLUME:
                return validateVolume(target, text, channel);
            case TOGGLE_TORCH:
                return validateTorch(target, text, channel);
            case CREATE_CALENDAR_EVENT:
                return validateEventCreate(target, text, channel);
            case OPEN_APP:
                return validateOpenApp(target, text, channel);
            case REPLY_NOTIFICATION:
                return validateReply(target, text, channel);
            default:
                return validateDirect(target, text, channel);
        }
    }

    /**
     * Device-status reads accept the canonical single-word target for their type (or empty,
     * normalized to it), no text, and no channel — mirroring READ_NOTIFICATION's strictness.
     */
    private static ValidationResult validateStatusRead(VisionAction.Type type, String target, String text, String channel) {
        String canonical = canonicalStatusTarget(type);
        String normalizedTarget = target.isEmpty() ? canonical : target;
        if (!normalizedTarget.equalsIgnoreCase(canonical)) {
            return rejected(RejectionReason.INVALID_TARGET);
        }
        if (!text.isEmpty()) {
            return rejected(RejectionReason.INVALID_TEXT);
        }
        if (!channel.isEmpty()) {
            return rejected(RejectionReason.INVALID_CHANNEL);
        }
        return accepted(type, canonical, text, channel);
    }

    private static String canonicalStatusTarget(VisionAction.Type type) {
        if (type == VisionAction.Type.SHOW_HELP) return "capabilities";
        if (type == VisionAction.Type.READ_BATTERY) return "battery";
        if (type == VisionAction.Type.READ_NETWORK) return "network";
        if (type == VisionAction.Type.READ_TIME) return "time";
        return "calendar";
    }

    // ===================== Daily-driver intent validation =====================

    private static ValidationResult validateTimer(String target, String text, String channel) {
        if (!VisionActionParser.isValidCanonicalDuration(target)) {
            return rejected(RejectionReason.INVALID_TARGET);
        }
        if (!text.isEmpty()) {
            return rejected(RejectionReason.INVALID_TEXT);
        }
        if (!channel.isEmpty()) {
            return rejected(RejectionReason.INVALID_CHANNEL);
        }
        return accepted(VisionAction.Type.SET_TIMER, target, text, channel);
    }

    private static ValidationResult validateAlarm(String target, String text, String channel) {
        if (!VisionActionParser.isValidCanonicalAlarmTime(target)) {
            return rejected(RejectionReason.INVALID_TARGET);
        }
        if (!text.isEmpty()) {
            return rejected(RejectionReason.INVALID_TEXT);
        }
        if (!channel.isEmpty()) {
            return rejected(RejectionReason.INVALID_CHANNEL);
        }
        return accepted(VisionAction.Type.SET_ALARM, target, text, channel);
    }

    private static ValidationResult validateNavigate(String target, String text, String channel) {
        if (!VisionActionParser.isValidPlaceName(target)) {
            return rejected(RejectionReason.INVALID_TARGET);
        }
        if (!text.isEmpty()) {
            return rejected(RejectionReason.INVALID_TEXT);
        }
        if (!channel.isEmpty()) {
            return rejected(RejectionReason.INVALID_CHANNEL);
        }
        return accepted(VisionAction.Type.NAVIGATE_TO, target, text, channel);
    }

    private static ValidationResult validateMediaCommand(String target, String text, String channel) {
        if (!"play".equals(target) && !"pause".equals(target) && !"next".equals(target)
                && !"previous".equals(target) && !"stop".equals(target)) {
            return rejected(RejectionReason.INVALID_TARGET);
        }
        if (!text.isEmpty()) {
            return rejected(RejectionReason.INVALID_TEXT);
        }
        if (!channel.isEmpty()) {
            return rejected(RejectionReason.INVALID_CHANNEL);
        }
        return accepted(VisionAction.Type.MEDIA_CONTROL, target, text, channel);
    }

    private static ValidationResult validateVolume(String target, String text, String channel) {
        if (!VisionActionParser.isValidCanonicalVolumeTarget(target)) {
            return rejected(RejectionReason.INVALID_TARGET);
        }
        if (!text.isEmpty()) {
            return rejected(RejectionReason.INVALID_TEXT);
        }
        if (!channel.isEmpty()) {
            return rejected(RejectionReason.INVALID_CHANNEL);
        }
        return accepted(VisionAction.Type.SET_VOLUME, target, text, channel);
    }

    private static ValidationResult validateTorch(String target, String text, String channel) {
        if (!"on".equals(target) && !"off".equals(target)) {
            return rejected(RejectionReason.INVALID_TARGET);
        }
        if (!text.isEmpty()) {
            return rejected(RejectionReason.INVALID_TEXT);
        }
        if (!channel.isEmpty()) {
            return rejected(RejectionReason.INVALID_CHANNEL);
        }
        return accepted(VisionAction.Type.TOGGLE_TORCH, target, text, channel);
    }

    /**
     * Event creation carries the title in target and the canonical local event time
     * ("yyyy-MM-dd HH:mm") in text — the one daily-driver type where text is required.
     */
    private static ValidationResult validateEventCreate(String target, String text, String channel) {
        if (!VisionActionParser.isValidEventTitle(target)) {
            return rejected(RejectionReason.INVALID_TARGET);
        }
        if (!VisionActionParser.isValidCanonicalEventTime(text)) {
            return rejected(RejectionReason.INVALID_TEXT);
        }
        if (!channel.isEmpty()) {
            return rejected(RejectionReason.INVALID_CHANNEL);
        }
        return accepted(VisionAction.Type.CREATE_CALENDAR_EVENT, target, text, channel);
    }

    private static ValidationResult validateRead(String target, String text, String channel) {
        if (!target.isEmpty() && !"latest notification".equalsIgnoreCase(target)) {
            return rejected(RejectionReason.INVALID_TARGET);
        }
        if (!text.isEmpty()) {
            return rejected(RejectionReason.INVALID_TEXT);
        }
        if (!channel.isEmpty()) {
            return rejected(RejectionReason.INVALID_CHANNEL);
        }
        String normalizedTarget = "latest notification";
        return accepted(VisionAction.Type.READ_NOTIFICATION, normalizedTarget, text, channel);
    }

    private static ValidationResult validateOpenApp(String target, String text, String channel) {
        String canonicalApp = canonicalApp(target);
        if (canonicalApp == null) {
            return rejected(RejectionReason.INVALID_TARGET);
        }
        if (!text.isEmpty()) {
            return rejected(RejectionReason.INVALID_TEXT);
        }
        if (!channel.isEmpty()) {
            return rejected(RejectionReason.INVALID_CHANNEL);
        }
        return accepted(VisionAction.Type.OPEN_APP, canonicalApp, text, channel);
    }

    private static ValidationResult validateReply(String target, String text, String channel) {
        if (target.isEmpty() || target.length() > MAX_TARGET_LENGTH || !isValidReplyTarget(target)) {
            return rejected(RejectionReason.INVALID_TARGET);
        }
        if (text.isEmpty()) {
            return rejected(RejectionReason.INVALID_TEXT);
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            return rejected(RejectionReason.SIZE_LIMIT);
        }
        if (!channel.isEmpty()) {
            return rejected(RejectionReason.INVALID_CHANNEL);
        }
        return accepted(VisionAction.Type.REPLY_NOTIFICATION, target, text, channel);
    }

    private static ValidationResult validateDirect(String target, String text, String channel) {
        if (!isValidChannel(channel)) {
            return rejected(RejectionReason.INVALID_CHANNEL);
        }
        if (target.isEmpty() || target.length() > MAX_TARGET_LENGTH
                || !VisionActionParser.isValidDirectDestination(channel, target)) {
            return rejected(RejectionReason.INVALID_TARGET);
        }
        if (text.isEmpty()) {
            return rejected(RejectionReason.INVALID_TEXT);
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            return rejected(RejectionReason.SIZE_LIMIT);
        }
        return accepted(VisionAction.Type.SEND_MESSAGE_DIRECT, target, text, channel);
    }

    private static boolean isValidReplyTarget(String target) {
        if (VisionActionParser.isValidContactName(target)) {
            return true;
        }
        if ("latest notification".equalsIgnoreCase(target)) {
            return true;
        }
        return canonicalApp(target) != null;
    }

    private static boolean isValidChannel(String channel) {
        for (String valid : VALID_CHANNELS) {
            if (valid.equals(channel)) {
                return true;
            }
        }
        return false;
    }

    private static String canonicalApp(String value) {
        for (String app : CANONICAL_APPS) {
            if (app.equalsIgnoreCase(value)) {
                return app;
            }
        }
        return null;
    }

    private static VisionAction.Type parseType(String raw) {
        VisionToolRegistry.Tool tool = VisionToolRegistry.find(raw);
        if (tool != null) return tool.type;
        return null;
    }

    private static ValidationResult accepted(VisionAction.Type type, String target, String text, String channel) {
        return new ValidationResult(new VisionAction(type, "", target, text, channel), null);
    }

    private static ValidationResult rejected(RejectionReason reason) {
        return new ValidationResult(null, reason);
    }
}
