package com.vision.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Static capability metadata only. No contacts, notification contents or execution authority. */
public final class VisionToolRegistry {
    public static final int SCHEMA_VERSION = 1;

    public static final class Tool {
        public final VisionAction.Type type;
        public final String description, example, targetRule, textRule, channelRule, requirement;
        private Tool(VisionAction.Type type, String description, String example,
                     String targetRule, String textRule, String channelRule, String requirement) {
            this.type = type;
            this.description = description;
            this.example = example;
            this.targetRule = targetRule;
            this.textRule = textRule;
            this.channelRule = channelRule;
            this.requirement = requirement;
        }
        public boolean requiresConfirmation() {
            return VisionRiskPolicy.requiresConfirmation(type);
        }
    }

    private static final List<Tool> TOOLS;
    static {
        List<Tool> tools = new ArrayList<>();
        tools.add(new Tool(VisionAction.Type.OPEN_DIALER,
                "Open phone dialer", "call +15555550123", "+ followed by 8..15 digits", "empty", "empty", "Confirmation required; final call stays in phone app; no contact lookup"));
        tools.add(new Tool(VisionAction.Type.SHOW_HELP,
                "Show supported commands", "help", "capabilities", "empty", "empty", "No permission needed"));
        tools.add(new Tool(VisionAction.Type.READ_NOTIFICATION,
                "Read latest supported notification", "read my latest notification", "latest notification", "empty", "empty", "Notification access needed"));
        tools.add(new Tool(VisionAction.Type.REPLY_NOTIFICATION,
                "Reply to a supported notification", "reply to Alice: On my way", "notification target", "reply body", "empty", "Notification access and an active reply action needed"));
        tools.add(new Tool(VisionAction.Type.SEND_MESSAGE_DIRECT,
                "Open a message composer", "send sms to Alice: On my way", "international phone, contact name, email or Telegram handle", "message body", "sms | whatsapp | whatsapp_business | email | telegram", "Contacts permission for name lookup; compatible app needed; final send stays external"));
        tools.add(new Tool(VisionAction.Type.OPEN_APP,
                "Open a supported app", "open WhatsApp", "WhatsApp | WhatsApp Business | Telegram | Gmail | Messages | Calendar", "empty", "empty", "Requested app must be installed"));
        tools.add(new Tool(VisionAction.Type.READ_BATTERY,
                "Read battery status", "check battery", "battery", "empty", "empty", "No permission needed"));
        tools.add(new Tool(VisionAction.Type.READ_NETWORK,
                "Read network status", "am I online", "network", "empty", "empty", "Network state access"));
        tools.add(new Tool(VisionAction.Type.READ_TIME,
                "Read local time", "what time is it", "time", "empty", "empty", "Uses device timezone"));
        tools.add(new Tool(VisionAction.Type.READ_CALENDAR,
                "Read next appointment", "show my calendar", "calendar", "empty", "empty", "Calendar read permission needed"));
        tools.add(new Tool(VisionAction.Type.SET_TIMER,
                "Open clock timer", "set timer for 5 minutes", "1s..24h canonical duration", "empty", "empty", "Clock app needed; final clock state not verified"));
        tools.add(new Tool(VisionAction.Type.SET_ALARM,
                "Open clock alarm", "set alarm for 7 am", "HH:mm", "empty", "empty", "Clock app needed; final clock state not verified"));
        tools.add(new Tool(VisionAction.Type.NAVIGATE_TO,
                "Open maps destination", "navigate to Central Park", "destination, up to 70 characters", "empty", "empty", "Maps app needed; route review stays external"));
        tools.add(new Tool(VisionAction.Type.MEDIA_CONTROL,
                "Send explicit media command", "pause music", "play | pause | stop | next | previous", "empty", "empty", "Media app may ignore command; playback not verified"));
        tools.add(new Tool(VisionAction.Type.SET_VOLUME,
                "Change music volume", "set volume to 50 percent", "0..100 | mute | unmute", "empty", "empty", "Local music stream only"));
        tools.add(new Tool(VisionAction.Type.TOGGLE_TORCH,
                "Change flashlight state", "turn torch on", "on | off", "empty", "empty", "Flash hardware needed; device may restrict camera access"));
        tools.add(new Tool(VisionAction.Type.CREATE_CALENDAR_EVENT,
                "Create a one-hour calendar event", "create event Team Sync tomorrow at 3 pm", "event title, up to 70 characters", "uuuu-MM-dd HH:mm in device timezone", "empty", "Calendar read/write permissions; primary or first writable calendar"));
        TOOLS = Collections.unmodifiableList(tools);
    }
    private VisionToolRegistry() { }

    public static List<Tool> all() { return TOOLS; }

    /** Exact, case-sensitive allowlist lookup. UNKNOWN and future types fail closed. */
    public static Tool find(String type) {
        for (Tool tool : TOOLS) {
            if (tool.type.name().equals(type)) return tool;
        }
        return null;
    }

    public static String helpText() {
        StringBuilder result = new StringBuilder("SUPPORTED COMMANDS\n\nOffline command parsing. Some actions open another app.\n"
                + "[Confirm] means Vision asks before acting. Permissions may still be needed.\n"
                + "Session controls: cancel plan stops remaining steps; forget context clears recent requests.\n");
        for (Tool tool : TOOLS) {
            result.append("\n");
            if (tool.requiresConfirmation()) result.append("[Confirm] ");
            result.append(tool.description).append("\nTry: ").append(tool.example)
                    .append("\n").append(tool.requirement).append("\n");
        }
        return result.toString();
    }

    /** Versioned, stable, four-field proposal contract for a future local model adapter.
     * Exporting metadata does not connect a model, execute a call or grant approval.
     * Actual proposals still go through StrictJson and ReasoningProposalValidator.
     */
    public static String toJson() {
        StringBuilder result = new StringBuilder("{\"schemaVersion\":1,\"proposalKeys\":[\"type\",\"target\",\"text\",\"channel\"],\"tools\":[");
        for (int i = 0; i < TOOLS.size(); i++) {
            Tool tool = TOOLS.get(i);
            if (i > 0) result.append(',');
            result.append("{\"type\":").append(quote(tool.type.name()))
                    .append(",\"description\":").append(quote(tool.description))
                    .append(",\"example\":").append(quote(tool.example))
                    .append(",\"targetRule\":").append(quote(tool.targetRule))
                    .append(",\"textRule\":").append(quote(tool.textRule))
                    .append(",\"channelRule\":").append(quote(tool.channelRule))
                    .append(",\"requirement\":").append(quote(tool.requirement))
                    .append(",\"requiresConfirmation\":").append(tool.requiresConfirmation()).append('}');
        }
        return result.append("]}").toString();
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '\"') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('\"').toString();
    }
}
