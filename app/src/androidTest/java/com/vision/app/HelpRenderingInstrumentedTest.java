package com.vision.app;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Device tests for the help screen: real Activity, real composer, real rendering path. */
@RunWith(AndroidJUnit4.class)
public class HelpRenderingInstrumentedTest {

    @Test public void helpCommandRendersRegistryHelpTextExactly() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                VisionUi.typeAndSend(activity, "help");
                assertEquals(VisionToolRegistry.helpText(),
                        VisionUi.activityText(activity).getText().toString());
                assertEquals("Composer must clear after send",
                        "", VisionUi.inputField(activity).getText().toString());
            });
        }
    }

    @Test public void helpAliasesRenderIdenticalText() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                VisionUi.typeAndSend(activity, "what can you do?");
                String first = VisionUi.activityText(activity).getText().toString();
                VisionUi.typeAndSend(activity, "HELP!");
                String second = VisionUi.activityText(activity).getText().toString();
                assertEquals(VisionToolRegistry.helpText(), first);
                assertEquals(first, second);
                assertTrue(first.startsWith("SUPPORTED COMMANDS"));
            });
        }
    }
}
