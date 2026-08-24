package com.vision.app;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps command interpretation predictable until the offline model phase. */
public final class VisionActionParser {
    // Requires a colon delimiter when 'to <target>' is specified (e.g. 'reply to Alice: text' or 'reply to: text')
    private static final Pattern REPLY_TO_PATTERN = Pattern.compile(
            "^(?:please\\s+)?(?:send\\s+)?(?:reply|answer)\\s+to(?:\\s*:\\s*|\\s+([^:]+?)\\s*:\\s*)(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // Matches 'reply <text>' or 'reply: <text>' without 'to <target>'
    private static final Pattern REPLY_NO_TO_PATTERN = Pattern.compile(
            "^(?:please\\s+)?(?:send\\s+)?(?:reply|answer)(?:\\s*:\\s*|\\s+)(?!to\\b)(.+)$",
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

        // 1. Reply parsing with strict target colon requirement
        Matcher replyToMatcher = REPLY_TO_PATTERN.matcher(trimmed);
        if (replyToMatcher.matches()) {
            String targetGroup = replyToMatcher.group(1);
            String replyBody = replyToMatcher.group(2);
            String target = (targetGroup != null && !targetGroup.trim().isEmpty())
                    ? targetGroup.trim()
                    : "latest notification";
            String replyText = replyBody != null ? replyBody.trim() : "";
            if (!replyText.isEmpty()) {
                return new VisionAction(VisionAction.Type.REPLY_NOTIFICATION, request, target, replyText);
            }
        }

        Matcher replyNoToMatcher = REPLY_NO_TO_PATTERN.matcher(trimmed);
        if (replyNoToMatcher.matches()) {
            String replyBody = replyNoToMatcher.group(1);
            String replyText = replyBody != null ? replyBody.trim() : "";
            if (!replyText.isEmpty()) {
                return new VisionAction(VisionAction.Type.REPLY_NOTIFICATION, request, "latest notification", replyText);
            }
        }

        String normalized = trimmed.replaceAll("\\s+", " ").toLowerCase(Locale.US);

        // 2. READ_NOTIFICATION evaluated before OPEN_APP to handle gerunds like 'start reading my messages'
        if (normalized.matches(".*\\b(read|reading|show|check|checking|get|see)\\b.*\\b(notification|notifications|message|messages)\\b.*")) {
            return new VisionAction(VisionAction.Type.READ_NOTIFICATION, request, "latest notification");
        }

        // 3. OPEN_APP matching
        if (normalized.matches(".*\\b(open|launch|start)\\b.*\\b(whatsapp(\\s+business)?|w4b|telegram|gmail|mail|messages?|sms|calendar)\\b.*")) {
            String target;
            if (normalized.contains("whatsapp business") || normalized.contains("w4b") || normalized.matches(".*\\bwhatsapp\\s+business\\b.*")) {
                target = "WhatsApp Business";
            } else if (normalized.contains("whatsapp") || normalized.contains("wa")) {
                target = "WhatsApp";
            } else if (normalized.contains("telegram") || normalized.contains("tg")) {
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

        return new VisionAction(VisionAction.Type.UNKNOWN, request, "");
    }
}
