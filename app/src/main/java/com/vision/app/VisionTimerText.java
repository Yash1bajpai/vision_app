package com.vision.app;

/** User-facing wording for the timer handoff. Vision only asks the clock app; it does not verify the timer runs. */
final class VisionTimerText {
    private VisionTimerText() {}

    static String handoffMessage(String target) {
        return "SUCCEEDED\n\nAsked your clock app to start a timer for " + target
                + ".\n\nVision does not check that it started. Confirm it in Clock.";
    }
}
