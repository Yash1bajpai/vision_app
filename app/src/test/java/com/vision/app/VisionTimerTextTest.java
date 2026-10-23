package com.vision.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class VisionTimerTextTest {
    @Test
    public void timerHandoffDescribesRequestNotRunningTimer() {
        String text = VisionTimerText.handoffMessage("10 minutes");
        assertTrue(text.contains("Asked your clock app to start a timer for 10 minutes"));
        assertTrue(text.contains("does not check that it started"));
        assertFalse(text.toLowerCase().contains("countdown is running"));
        assertFalse(text.toLowerCase().contains("timer set for"));
    }
}
