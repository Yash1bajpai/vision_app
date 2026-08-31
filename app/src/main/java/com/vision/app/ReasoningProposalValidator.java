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
            case READ_NOTIFICATION:
                return validateRead(target, text, channel);
            case OPEN_APP:
                return validateOpenApp(target, text, channel);
            case REPLY_NOTIFICATION:
                return validateReply(target, text, channel);
            default:
                return validateDirect(target, text, channel);
        }
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
        String normalizedTarget = target.isEmpty() ? "" : "latest notification";
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
        if ("READ_NOTIFICATION".equals(raw)) return VisionAction.Type.READ_NOTIFICATION;
        if ("REPLY_NOTIFICATION".equals(raw)) return VisionAction.Type.REPLY_NOTIFICATION;
        if ("SEND_MESSAGE_DIRECT".equals(raw)) return VisionAction.Type.SEND_MESSAGE_DIRECT;
        if ("OPEN_APP".equals(raw)) return VisionAction.Type.OPEN_APP;
        return null;
    }

    private static ValidationResult accepted(VisionAction.Type type, String target, String text, String channel) {
        return new ValidationResult(new VisionAction(type, "", target, text, channel), null);
    }

    private static ValidationResult rejected(RejectionReason reason) {
        return new ValidationResult(null, reason);
    }
}
