package com.vision.app;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Device tests: a chained request shows the refusal and opens no confirmation. */
@RunWith(AndroidJUnit4.class)
public class MultiActionInstrumentedTest {

    @Test public void chainedOpenAppsShowRefusal() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                VisionUi.typeAndSend(activity, "open Telegram and then open Gmail");
                String shown = VisionUi.activityText(activity).getText().toString();
                assertTrue("Refusal headline shown, was: " + shown,
                        shown.startsWith("MULTIPLE ACTIONS NOT SUPPORTED"));
                assertTrue("States nothing ran", shown.contains("did not run any of them"));
                assertNull("No confirmation dialog for a refused request",
                        VisionUi.activeDialog(activity));
                assertEquals("Composer clears after send",
                        "", VisionUi.inputField(activity).getText().toString());
            });
        }
    }

    @Test public void chainedTorchStatesShowRefusal() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                VisionUi.typeAndSend(activity, "turn the torch off and then on again");
                String shown = VisionUi.activityText(activity).getText().toString();
                assertTrue("Refusal headline shown, was: " + shown,
                        shown.startsWith("MULTIPLE ACTIONS NOT SUPPORTED"));
                assertNull(VisionUi.activeDialog(activity));
            });
        }
    }

    @Test public void harmlessAndDoesNotShowRefusal() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                VisionUi.typeAndSend(activity, "set a timer for 1 hour and 30 minutes");
                String shown = VisionUi.activityText(activity).getText().toString();
                assertFalse("Compound duration must not be refused, was: " + shown,
                        shown.startsWith("MULTIPLE ACTIONS NOT SUPPORTED"));
            });
        }
    }
}
