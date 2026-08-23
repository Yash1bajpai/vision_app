package com.vision.app;

/** A deterministic, confirmation-gated action produced from a typed request. */
public final class VisionAction {
    public enum Type {
        READ_NOTIFICATION,
        OPEN_APP,
        UNKNOWN
    }

    public enum State {
        PROPOSED,
        APPROVED,
        DENIED,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public final Type type;
    public final String request;
    public final String target;
    public State state = State.PROPOSED;

    public VisionAction(Type type, String request, String target) {
        this.type = type != null ? type : Type.UNKNOWN;
        this.request = request != null ? request : "";
        this.target = target != null ? target : "";
    }

    public String label() {
        if (type == Type.READ_NOTIFICATION) return "Read the latest supported notification";
        if (type == Type.OPEN_APP) return "Open " + target;
        return "Process this request locally";
    }
}
