package com.vision.app;

/** Explicit Android media key codes, never the state-dependent play/pause toggle. */
public final class VisionMediaCommand {
    private VisionMediaCommand() { }

    public static int keyCode(String command) {
        if ("play".equals(command)) return 126; // KEYCODE_MEDIA_PLAY
        if ("pause".equals(command)) return 127; // KEYCODE_MEDIA_PAUSE
        if ("stop".equals(command)) return 86; // KEYCODE_MEDIA_STOP
        if ("next".equals(command)) return 87; // KEYCODE_MEDIA_NEXT
        if ("previous".equals(command)) return 88; // KEYCODE_MEDIA_PREVIOUS
        throw new IllegalArgumentException("Unsupported media command");
    }
}
