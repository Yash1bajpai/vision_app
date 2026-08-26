package com.vision.app;

/** A deterministic, confirmation-gated action produced from a typed request. */
public final class VisionAction {
    public enum Type {
        READ_NOTIFICATION,
        REPLY_NOTIFICATION,
        SEND_MESSAGE_DIRECT,
        OPEN_APP,
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
    public State state = State.PROPOSED;

    public VisionAction(Type type, String request, String target) {
        this(type, request, target, "");
    }

    public VisionAction(Type type, String request, String target, String replyText) {
        this(type, request, target, replyText, "");
    }

    public VisionAction(Type type, String request, String target, String replyText, String channel) {
        this.type = type != null ? type : Type.UNKNOWN;
        this.request = request != null ? request : "";
        this.target = target != null ? target : "";
        this.replyText = replyText != null ? replyText : "";
        this.channel = channel != null ? channel : "";
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
        return "Process this request locally";
    }

    public boolean requiresConfirmation() {
        return VisionRiskPolicy.requiresConfirmation(type);
    }
}
