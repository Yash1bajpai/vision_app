package com.vision.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class VisionAlarmTextTest {
    @Test
    public void alarmHandoffDoesNotClaimTheAlarmIsSet() {
        String text = VisionAlarmText.handoffMessage("07:30");
        assertTrue(text.startsWith("CLOCK OPENED"));
        assertTrue(text.contains("07:30"));
        assertTrue(text.contains("does not check"));
        assertFalse(text.contains("Alarm set"));
        assertFalse(text.contains("is scheduled"));
    }
}
