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

    // 'navigate to X' / 'directions to X' / 'take me to X' — case-insensitive, captures original casing
    private static final Pattern NAVIGATE_TO_PATTERN = Pattern.compile(
            ".*\\b(?:navigate|directions|take\\s+me)\\b.*?\\bto\\b\\s+(.+)$",
            Pattern.CASE_INSENSITIVE
    );

    // bare 'navigate X' (no 'to')
    private static final Pattern NAVIGATE_BARE_PATTERN = Pattern.compile(
            "^navigate\\s+(.+)$",
            Pattern.CASE_INSENSITIVE
    );

    // 'event <title> [tomorrow|today] at <time>' — case-insensitive, captures original title casing
    private static final Pattern CREATE_EVENT_PATTERN = Pattern.compile(
            "\\bevent\\s+(?:called\\s+)?(.+?)\\s+(?:(tomorrow|today)\\s+)?at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b.*",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DURATION_PART_PATTERN = Pattern.compile(
            "(\\d{1,6})\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b"
    );

    private static final Pattern ALARM_TIME_PATTERN = Pattern.compile(
            "\\b(?:at|for)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b"
    );

    private static final Pattern VOLUME_NUMBER_PATTERN = Pattern.compile(
            "\\b(\\d{1,3})\\s*(?:%|percent)?"
    );

    private static final long MAX_TIMER_SECONDS = 24L * 60L * 60L;

    private VisionActionParser() { }

    public static VisionAction parse(String request) {
        if (request == null) {
            return new VisionAction(VisionAction.Type.UNKNOWN, "", "");
        }
        String trimmed = request.trim();
        if (trimmed.isEmpty()) {
            return new VisionAction(VisionAction.Type.UNKNOWN, request, "");
        }

        Matcher dial = Pattern.compile("^(?:call|dial)\\s+(\\+[0-9]{8,15})$", Pattern.CASE_INSENSITIVE).matcher(trimmed);
        if (dial.matches()) return new VisionAction(VisionAction.Type.OPEN_DIALER, request, dial.group(1));
        // Call-like requests that do not match must not fall through into unrelated actions.
        if (trimmed.matches("(?is)^(?:call|dial)\\b.*")) return new VisionAction(VisionAction.Type.UNKNOWN, request, "");
        Matcher reminder = Pattern.compile("^remind me (.{1,70}?) at (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$", Pattern.CASE_INSENSITIVE).matcher(trimmed);
        if (reminder.matches()) {
            String label = reminder.group(1).trim();
            String time = extractAlarmTime("alarm at " + reminder.group(2)
                    + (reminder.group(3) != null ? ":" + reminder.group(3) : "")
                    + (reminder.group(4) != null ? " " + reminder.group(4) : ""));
            // Dates, relative days and recurrence require a different reminder implementation.
            if (time != null && isReminderLabel(label))
                return new VisionAction(VisionAction.Type.SET_REMINDER, request, time, label);
        }
        if (trimmed.matches("(?is)^remind\\b.*")) return new VisionAction(VisionAction.Type.UNKNOWN, request, "");

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

        if (normalized.matches("(?:help|commands|show commands|show capabilities|what can you do)[?!.]?")) {
            return new VisionAction(VisionAction.Type.SHOW_HELP, request, "capabilities");
        }

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
                || normalized.matches(".*\\b(network|internet|wifi|wi-fi|connection|connectivity)\\b.*\\b(status|state|speed)\\b.*")
                || normalized.matches(".*\\bam\\s+i\\s+online\\b.*")) {
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

        // 2b. Daily-driver intents: timer, alarm, media transport, volume, torch,
        // navigation, calendar event creation. Evaluated after the status reads and
        // before OPEN_APP so app-word matching cannot hijack these phrasings.
        if (normalized.matches(".*\\btimer\\b.*")) {
            long seconds = parseDurationSeconds(normalized);
            if (seconds > 0 && seconds <= MAX_TIMER_SECONDS) {
                return new VisionAction(VisionAction.Type.SET_TIMER, request, formatCanonicalDuration(seconds));
            }
        }
        if (normalized.matches(".*\\b(set\\s+)?(an?\\s+)?alarm\\b.*")
                || normalized.matches(".*\\bwake\\s+me\\s+up\\b.*")) {
            String canonical = extractAlarmTime(normalized);
            if (canonical != null) {
                return new VisionAction(VisionAction.Type.SET_ALARM, request, canonical);
            }
        }
        if (normalized.matches(".*\\b(pause|stop)\\b.*\\b(music|song|songs|track|tracks|media|playback)\\b.*")) {
            return new VisionAction(VisionAction.Type.MEDIA_CONTROL, request,
                    normalized.matches(".*\\bstop\\b.*") ? "stop" : "pause");
        }
        if (normalized.matches(".*\\bprevious\\b.*\\b(song|track)\\b.*")) {
            return new VisionAction(VisionAction.Type.MEDIA_CONTROL, request, "previous");
        }
        if (normalized.matches(".*\\b(skip|next)\\b.*\\b(song|track)\\b.*")) {
            return new VisionAction(VisionAction.Type.MEDIA_CONTROL, request, "next");
        }
        if (normalized.matches(".*\\b(play|resume)\\b.*\\b(music|song|songs|track|tracks|media|playback)\\b.*")) {
            return new VisionAction(VisionAction.Type.MEDIA_CONTROL, request, "play");
        }
        if (normalized.matches(".*\\bvolume\\b.*") || normalized.matches(".*\\b(?:un)?mute\\b.*")) {
            String volume = extractVolumeTarget(normalized);
            if (volume != null) {
                return new VisionAction(VisionAction.Type.SET_VOLUME, request, volume);
            }
        }
        if (normalized.matches(".*\\b(flashlight|torch)\\b.*")) {
            return new VisionAction(VisionAction.Type.TOGGLE_TORCH, request,
                    normalized.matches(".*\\boff\\b.*") ? "off" : "on");
        }
        Matcher navigateToMatcher = NAVIGATE_TO_PATTERN.matcher(trimmed);
        if (navigateToMatcher.matches()) {
            String place = stripTrailingPunctuation(navigateToMatcher.group(1).trim());
            if (isValidPlaceName(place)) {
                return new VisionAction(VisionAction.Type.NAVIGATE_TO, request, place);
            }
        }
        Matcher navigateBareMatcher = NAVIGATE_BARE_PATTERN.matcher(trimmed);
        if (navigateBareMatcher.matches()) {
            String place = stripTrailingPunctuation(navigateBareMatcher.group(1).trim());
            // 'navigate to' with no destination leaves the bare preposition; a dangling
            // 'to' must not dispatch a maps search for the literal word.
            if (isValidPlaceName(place) && !"to".equalsIgnoreCase(place)) {
                return new VisionAction(VisionAction.Type.NAVIGATE_TO, request, place);
            }
        }
        if (normalized.matches(".*\\b(create|schedule|make|add)\\b.*\\bevent\\b.*")) {
            VisionAction eventAction = parseCreateEventAction(normalized, request);
            if (eventAction != null) {
                return eventAction;
            }
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

    // ===================== Daily-driver intent helpers =====================

    /** Sums every '&lt;n&gt; &lt;unit&gt;' pair in the phrase; 0 when none is present or a part overflows. */
    public static boolean isReminderLabel(String label) {
        return label != null && !label.trim().isEmpty() && label.length() <= 70
                && !label.matches("(?s).*\\p{Cntrl}.*")
                && !label.toLowerCase(Locale.US).matches(".*\\b(today|tomorrow|tonight|daily|every|weekly|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b.*");
    }

    private static long parseDurationSeconds(String normalized) {
        long total = 0;
        Matcher m = DURATION_PART_PATTERN.matcher(normalized);
        while (m.find()) {
            try {
                long n = Long.parseLong(m.group(1));
                String unit = m.group(2);
                long scaled = unit.startsWith("h") ? n * 3600
                        : unit.startsWith("m") ? n * 60 : n;
                if (scaled < 0 || total > Long.MAX_VALUE - scaled) {
                    return 0; // overflow: fail closed to UNKNOWN, never crash
                }
                total += scaled;
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return total;
    }

    /** Canonical duration rendering: 3900 -> "1h 5m", 600 -> "10m", 45 -> "45s". */
    private static String formatCanonicalDuration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append('h');
        if (m > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(m).append('m');
        }
        if (s > 0 || sb.length() == 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(s).append('s');
        }
        return sb.toString();
    }

    /** True for canonical durations the parser itself emits ("1h 5m", "10m", "45s"), 1s..24h. */
    public static boolean isValidCanonicalDuration(String value) {
        if (value == null) return false;
        if (!value.matches("\\d+h( \\d+m)?( \\d+s)?|\\d+m( \\d+s)?|\\d+s")) return false;
        try {
            long seconds = canonicalDurationSeconds(value);
            return seconds > 0 && seconds <= MAX_TIMER_SECONDS;
        } catch (Exception e) {
            return false;
        }
    }

    /** Parses a canonical duration ("1h 5m", "10m", "45s") into seconds; throws on malformed input. */
    public static long canonicalDurationSeconds(String canonical) {
        long total = 0;
        for (String token : canonical.trim().split("\\s+")) {
            char unit = token.charAt(token.length() - 1);
            long n = Long.parseLong(token.substring(0, token.length() - 1));
            if (unit == 'h') total += n * 3600;
            else if (unit == 'm') total += n * 60;
            else total += n;
        }
        return total;
    }

    /** Extracts 'at/for &lt;time&gt;' into canonical 24-hour "HH:mm"; null when absent or invalid. */
    private static String extractAlarmTime(String normalized) {
        Matcher m = ALARM_TIME_PATTERN.matcher(normalized);
        if (!m.find()) return null;
        int hour = Integer.parseInt(m.group(1));
        int minute = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
        String ampm = m.group(3);
        if (ampm != null) {
            if (hour < 1 || hour > 12) return null;
            if ("pm".equalsIgnoreCase(ampm) && hour < 12) hour += 12;
            if ("am".equalsIgnoreCase(ampm) && hour == 12) hour = 0;
        }
        if (hour > 23 || minute > 59) return null;
        return String.format(Locale.US, "%02d:%02d", hour, minute);
    }

    /** True for canonical alarm times ("07:30", "20:00"). */
    public static boolean isValidCanonicalAlarmTime(String value) {
        return value != null && value.matches("^([01]\\d|2[0-3]):[0-5]\\d$");
    }

    private static String extractVolumeTarget(String normalized) {
        if (normalized.matches(".*\\bunmute\\b.*")) return "unmute";
        if (normalized.matches(".*\\bmute\\b.*")) return "mute";
        Matcher m = VOLUME_NUMBER_PATTERN.matcher(normalized);
        if (m.find()) {
            int value = Integer.parseInt(m.group(1));
            if (value >= 0 && value <= 100) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /** True for canonical volume targets: "mute", "unmute", or "0".."100". */
    public static boolean isValidCanonicalVolumeTarget(String value) {
        if (value == null) return false;
        if ("mute".equals(value) || "unmute".equals(value)) return true;
        if (!value.matches("\\d{1,3}")) return false;
        int v = Integer.parseInt(value);
        return v >= 0 && v <= 100;
    }

    private static String stripTrailingPunctuation(String s) {
        return s.replaceAll("[.!?]+$", "");
    }

    /**
     * Parses 'create/schedule ... event &lt;title&gt; [tomorrow|today] at &lt;time&gt;'.
     * A 'today' time that has already passed shifts to tomorrow. The canonical event
     * time is carried in the action's text field ("yyyy-MM-dd HH:mm", local time).
     */
    private static VisionAction parseCreateEventAction(String normalized, String request) {
        Matcher m = CREATE_EVENT_PATTERN.matcher(request != null ? request.trim() : "");
        if (!m.find()) return null;
        String title = m.group(1).trim();
        String day = m.group(2);
        int hour = Integer.parseInt(m.group(3));
        int minute = m.group(4) != null ? Integer.parseInt(m.group(4)) : 0;
        String ampm = m.group(5);
        if (ampm != null) {
            if (hour < 1 || hour > 12) return null;
            if ("pm".equalsIgnoreCase(ampm) && hour < 12) hour += 12;
            if ("am".equalsIgnoreCase(ampm) && hour == 12) hour = 0;
        }
        if (hour > 23 || minute > 59) return null;
        if (title.isEmpty() || title.length() > 70 || !isValidEventTitle(title)) return null;

        java.time.LocalDate date = java.time.LocalDate.now();
        if ("tomorrow".equalsIgnoreCase(day)) {
            date = date.plusDays(1);
        } else {
            java.time.LocalDateTime when = date.atTime(hour, minute);
            if (!when.isAfter(java.time.LocalDateTime.now())) {
                date = date.plusDays(1);
            }
        }
        String canonicalTime = String.format(Locale.US, "%04d-%02d-%02d %02d:%02d",
                date.getYear(), date.getMonthValue(), date.getDayOfMonth(), hour, minute);
        return new VisionAction(VisionAction.Type.CREATE_CALENDAR_EVENT, request, title, canonicalTime);
    }

    /** Event titles: 1..70 chars, letters/digits/space and common title punctuation only. */
    public static boolean isValidEventTitle(String value) {
        if (value == null || value.isEmpty() || value.length() > 70) return false;
        return value.matches("[\\p{L}\\p{N} .,'\\-()&+]+");
    }

    /** True for canonical event times ("2026-09-10 09:30", local time); impossible dates are rejected. */
    public static boolean isValidCanonicalEventTime(String value) {
        if (value == null || !value.matches("^\\d{4}-\\d{2}-\\d{2} ([01]\\d|2[0-3]):[0-5]\\d$")) return false;
        try {
            java.time.LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
                    .withResolverStyle(java.time.format.ResolverStyle.STRICT));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** True for navigation destinations: 1..70 chars, letters/digits/space and address punctuation. */
    public static boolean isValidPlaceName(String value) {
        if (value == null || value.isEmpty() || value.length() > 70) return false;
        return value.matches("[\\p{L}\\p{N} .,'\\-/&()+]+");
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
