package com.vision.app;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps command interpretation predictable until the offline model phase. */
public final class VisionActionParser {
    private static final Pattern REPLY_PATTERN = Pattern.compile(
            "^(?:please\\s+)?(?:send\\s+)?(?:reply|answer)(?:\\s+to\\s+([^:]+?))?(?:\\s*:\\s*|\\s+)(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private VisionActionParser() { }

    public static VisionAction parse(String request) {
        if (request == null) {
            return new VisionAction(VisionAction.Type.UNKNOWN, "", "");
        }
        String trimmed = request.trim();
        if (trimmed.isEmpty()) {
            return new VisionAction(VisionAction.Type.UNKNOWN, request, "");
        }

        Matcher replyMatcher = REPLY_PATTERN.matcher(trimmed);
        if (replyMatcher.matches()) {
            String targetGroup = replyMatcher.group(1);
            String replyBody = replyMatcher.group(2);
            String target = (targetGroup != null && !targetGroup.trim().isEmpty())
                    ? targetGroup.trim()
                    : "latest notification";
            String replyText = replyBody != null ? replyBody.trim() : "";
            if (!replyText.isEmpty()) {
                return new VisionAction(VisionAction.Type.REPLY_NOTIFICATION, request, target, replyText);
            }
        }

        String normalized = trimmed.replaceAll("\\s+", " ").toLowerCase(Locale.US);

        if (normalized.matches(".*\\b(open|launch|start)\\b.*\\b(whatsapp(\\s+business)?|telegram|gmail|messages?|sms|calendar)\\b.*")) {
            String target;
            if (normalized.contains("whatsapp")) {
                target = "WhatsApp";
            } else if (normalized.contains("telegram")) {
                target = "Telegram";
            } else if (normalized.contains("gmail") || normalized.contains("mail")) {
                target = "Gmail";
            } else if (normalized.contains("calendar")) {
                target = "Calendar";
            } else {
                target = "Messages";
            }
            return new VisionAction(VisionAction.Type.OPEN_APP, request, target);
        }

        if (normalized.matches(".*\\b(read|show|check|get|see)\\b.*\\b(notification|notifications|message|messages)\\b.*")) {
            return new VisionAction(VisionAction.Type.READ_NOTIFICATION, request, "latest notification");
        }

        return new VisionAction(VisionAction.Type.UNKNOWN, request, "");
    }
}
