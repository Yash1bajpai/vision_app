package com.vision.app;

import java.util.Locale;

/** Keeps command interpretation predictable until the offline model phase. */
public final class VisionActionParser {
    private VisionActionParser() { }

    public static VisionAction parse(String request) {
        if (request == null) {
            return new VisionAction(VisionAction.Type.UNKNOWN, "", "");
        }
        String normalized = request.trim().replaceAll("\\s+", " ").toLowerCase(Locale.US);
        if (normalized.isEmpty()) {
            return new VisionAction(VisionAction.Type.UNKNOWN, request, "");
        }

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
