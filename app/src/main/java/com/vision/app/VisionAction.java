package com.vision.app;

/** A deterministic, confirmation-gated action produced from a typed request. */
public final class VisionAction {
    public enum Type {
        READ_NOTIFICATION,
        REPLY_NOTIFICATION,
        SEND_MESSAGE_DIRECT,
        OPEN_APP,
        READ_BATTERY,
        READ_NETWORK,
        READ_TIME,
        READ_CALENDAR,
        UNKNOWN
    }

    public enum State {
        PROPOSED,
        APPROVED,
        DENIED,
        RUNNING,
        SUCCEEDED,
        COMPOSER_OPENED,
        FAILED
    }

    public final Type type;
    public final String request;
    public final String target;
    public final String replyText;
    public final String channel;
    public final String resolvedContactName;
    public final String resolvedNumber;
    public State state = State.PROPOSED;

    public VisionAction(Type type, String request, String target) {
        this(type, request, target, "");
    }

    public VisionAction(Type type, String request, String target, String replyText) {
        this(type, request, target, replyText, "");
    }

    public VisionAction(Type type, String request, String target, String replyText, String channel) {
        this(type, request, target, replyText, channel, "", "");
    }

    public VisionAction(Type type, String request, String target, String replyText, String channel,
                        String resolvedContactName, String resolvedNumber) {
        this.type = type != null ? type : Type.UNKNOWN;
        this.request = request != null ? request : "";
        this.target = target != null ? target : "";
        this.replyText = replyText != null ? replyText : "";
        this.channel = channel != null ? channel : "";
        this.resolvedContactName = resolvedContactName != null ? resolvedContactName : "";
        this.resolvedNumber = resolvedNumber != null ? resolvedNumber : "";
    }

    public VisionAction withResolvedContact(String contactName, String normalizedNumber) {
        VisionAction action = new VisionAction(this.type, this.request, this.target, this.replyText, this.channel,
                contactName, normalizedNumber);
        action.state = this.state;
        return action;
    }

    /** Returns a copy of this action with the given request and all other fields and state preserved. */
    public VisionAction withRequest(String newRequest) {
        VisionAction action = new VisionAction(this.type, newRequest, this.target, this.replyText, this.channel,
                this.resolvedContactName, this.resolvedNumber);
        action.state = this.state;
        return action;
    }

    public boolean isContactDestination() {
        if (type != Type.SEND_MESSAGE_DIRECT) return false;
        if (!"sms".equals(channel) && !"whatsapp".equals(channel) && !"whatsapp_business".equals(channel)) {
            return false;
        }
        String t = target != null ? target.trim() : "";
        if (t.isEmpty()) return false;
        return !t.startsWith("+");
    }

    public String getEffectiveDestination() {
        if (!resolvedNumber.isEmpty()) {
            return resolvedNumber;
        }
        return target;
    }

    public String label() {
        if (type == Type.READ_NOTIFICATION) return "Read the latest supported notification";
        if (type == Type.REPLY_NOTIFICATION) {
            String dest = target.isEmpty() || "latest notification".equalsIgnoreCase(target)
                    ? "the latest notification"
                    : target;
            return "Reply to " + dest;
        }
        if (type == Type.SEND_MESSAGE_DIRECT) return "Send a new message to " + target;
        if (type == Type.OPEN_APP) return "Open " + target;
        if (type == Type.READ_BATTERY) return "Read battery status";
        if (type == Type.READ_NETWORK) return "Read network status";
        if (type == Type.READ_TIME) return "Read the current time";
        if (type == Type.READ_CALENDAR) return "Read the next calendar appointment";
        return "Process this request locally";
    }

    public boolean requiresConfirmation() {
        return VisionRiskPolicy.requiresConfirmation(type);
    }
}
