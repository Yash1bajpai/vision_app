package com.vision.app;

/** User-facing wording for the alarm handoff. Vision only opens the clock app; it does not verify an alarm was saved. */
final class VisionAlarmText {
    private VisionAlarmText() {}

    static String handoffMessage(String time) {
        return "CLOCK OPENED\n\nAsked your clock app to set an alarm for " + time
                + ".\n\nVision does not check that it was saved. Confirm it in Clock.";
    }
}
