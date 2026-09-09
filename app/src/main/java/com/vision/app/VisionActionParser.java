package com.vision.app;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps command interpretation predictable until the offline model phase. */
public final class VisionActionParser {
    // Requires a colon delimiter when 'to <target>' is specified (e.g. 'reply to Alice: text' or 'reply to: text')
    private static final Pattern REPLY_TO_PATTERN = Pattern.compile(
            "^(?:please\\s+)?(?:send\\s+)?(?:reply|answer)\\s+to\\b(?:\\s*:\\s*|\\s+([^:]+?)\\s*:\\s*)(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // Matches 'reply <text>' or 'reply: <text>' without 'to <target>'
    private static final Pattern REPLY_NO_TO_PATTERN = Pattern.compile(
            "^(?:please\\s+)?(?:send\\s+)?(?:reply|answer)(?:\\s*:\\s*|\\s+)(?!to\\b)(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern DIRECT_MESSAGE_PATTERN = Pattern.compile(
            "^(?:please\\s+)?send(?:\\s+(?:a|an|the))?(?:\\s+new)?\\s+(?:(sms|text|email|whatsapp(?:\\s+business)?|telegram)\\s+)?(?:message\\s+)?to\\s+([^:]+?)\\s*:\\s*(.+)$",
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

        Matcher directMatcher = DIRECT_MESSAGE_PATTERN.matcher(trimmed);
        if (directMatcher.matches()) {
            String requestedChannel = directMatcher.group(1);
            String destination = directMatcher.group(2) != null ? directMatcher.group(2).trim() : "";
            String body = directMatcher.group(3) != null ? directMatcher.group(3).trim() : "";
            if (!destination.isEmpty() && !body.isEmpty()) {
                String channel = requestedChannel != null ? requestedChannel.toLowerCase(Locale.US) : inferChannel(destination);
                if ("text".equals(channel)) channel = "sms";
                if ("whatsapp business".equals(channel)) channel = "whatsapp_business";
                if (isValidDirectDestination(channel, destination)) {
                    return new VisionAction(VisionAction.Type.SEND_MESSAGE_DIRECT, request,
                            destination, body, channel);
                }
            }
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

        // 2a. Device status reads (SAFE tier): battery, network, calendar, time.
        // Calendar is matched before time so 'what time is my next appointment' resolves to
        // READ_CALENDAR instead of being hijacked by the time grammar.
        if (normalized.matches(".*\\b(read|check|checking|show|what|whats|how|tell|get|see)\\b.*\\b(battery|charge|charging level)\\b.*")
                || normalized.matches(".*\\bbattery\\b.*\\b(status|level|percentage|percent|charge)\\b.*")) {
            return new VisionAction(VisionAction.Type.READ_BATTERY, request, "battery");
        }
        if (normalized.matches(".*\\b(read|check|checking|show|what|whats|how|tell|get|see)\\b.*\\b(network|internet|wifi|wi-fi|connection|connectivity|signal)\\b.*")
                || normalized.matches(".*\\b(network|internet|wifi|wi-fi|connection|connectivity)\\b.*\\b(status|state|speed)\\b.*")) {
            return new VisionAction(VisionAction.Type.READ_NETWORK, request, "network");
        }
        if (normalized.matches(".*\\b(read|check|checking|show|what|whats|see|get)\\b.*\\b(appointment|appointments|event|events|schedule|calendar|agenda|meeting|meetings)\\b.*")
                || normalized.matches(".*\\b(next|upcoming|today's|todays)\\b.*\\b(appointment|event|meeting|schedule|calendar|agenda)\\b.*")) {
            return new VisionAction(VisionAction.Type.READ_CALENDAR, request, "calendar");
        }
        if (normalized.matches("^.*\\bwhat\\b.*\\btime\\b.*$")
                || normalized.matches(".*\\b(read|tell|show|say|check|get)\\b.*\\btime\\b.*")
                || normalized.equals("time")
                || normalized.equals("the time")) {
            return new VisionAction(VisionAction.Type.READ_TIME, request, "time");
        }

        // 3. OPEN_APP matching
        if (normalized.matches(".*\\b(open|launch|start)\\b.*\\b(whatsapp(\\s+business)?|w4b|wa|tg|telegram|gmail|mail|messages?|sms|calendar)\\b.*")) {
            String target;
            if (normalized.contains("whatsapp business") || normalized.contains("w4b")) {
                target = "WhatsApp Business";
            } else if (normalized.contains("whatsapp") || hasWordToken(normalized, "wa")) {
                target = "WhatsApp";
            } else if (normalized.contains("telegram") || hasWordToken(normalized, "tg")) {
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

    /**
     * True only when {@code token} appears as a whole word delimited by characters outside
     * Java's word set ({@code [a-z0-9_]} on the lowercased input), exactly mirroring the
     * {@code \b} boundaries of the OPEN_APP gate. Short aliases like "wa" cannot match inside
     * longer words ("swan") or underscore-suffixed tokens ("wa_"), while trailing punctuation
     * ("open wa.") still matches instead of falling through to the Messages default.
     */
    private static boolean hasWordToken(String normalized, String token) {
        for (String segment : normalized.split("[^a-z0-9_]+")) {
            if (segment.equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static String inferChannel(String destination) {
        if (destination.matches("^\\+?[0-9][0-9 .()-]{5,}$")) {
            return "sms";
        }
        if (destination.contains("@")) {
            return "email";
        }
        if (isValidContactName(destination)) {
            return "sms";
        }
        return "";
    }

    public static boolean isValidDirectDestination(String channel, String destination) {
        if ("sms".equals(channel) || "whatsapp".equals(channel) || "whatsapp_business".equals(channel)) {
            if (isExplicitInternationalPhone(destination)) {
                return true;
            }
            return isValidContactName(destination);
        }
        if ("email".equals(channel)) {
            return destination.matches("^[A-Za-z0-9.!#$%&'*+^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");
        }
        if ("telegram".equals(channel)) {
            return destination.matches("^@?[A-Za-z][A-Za-z0-9_]{4,31}$");
        }
        return false;
    }

    public static boolean isExplicitInternationalPhone(String destination) {
        if (destination == null) return false;
        String trimmed = destination.trim();
        String digits = trimmed.replaceAll("[^0-9]", "");
        return trimmed.startsWith("+")
                && trimmed.matches("^\\+?[0-9][0-9 .()-]*$")
                && digits.length() >= 7 && digits.length() <= 15;
    }

    public static boolean isValidContactName(String destination) {
        if (destination == null) return false;
        String trimmed = destination.trim();
        if (trimmed.isEmpty() || trimmed.length() > 70) return false;
        boolean hasLetter = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (!Character.isDigit(c) && c != ' ' && c != '.' && c != '-' && c != '\'') {
                return false;
            }
        }
        return hasLetter;
    }
}
