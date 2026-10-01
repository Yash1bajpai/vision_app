package com.vision.app;

import android.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Device tests for confirmation-dialog cancellation. Every dismissal path must stop the
 * action, report it honestly, clear the managed dialog slot, and never report a send.
 * Uses a fictional 555-prefix number; requires an SMS-capable composer app on the device.
 */
@RunWith(AndroidJUnit4.class)
public class DialogCancellationInstrumentedTest {
    private static final String COMMAND = "send sms to +15550101234: hello";
    private static final String TARGET = "+15550101234";

    @Test public void denyButtonStopsMessageAndClearsDialog() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            final AlertDialog[] shown = new AlertDialog[1];
            scenario.onActivity(activity -> {
                VisionUi.typeAndSend(activity, COMMAND);
                AlertDialog dialog = VisionUi.activeDialog(activity);
                assertNotNull("Confirmation dialog should be showing", dialog);
                assertTrue(dialog.isShowing());
                shown[0] = dialog;
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            VisionUi.settle(); // the button handler and the dismiss are posted to the main looper
            scenario.onActivity(activity -> {
                assertFalse(shown[0].isShowing());
                assertNull("Dismissal must clear the managed dialog slot", VisionUi.activeDialog(activity));
                String text = VisionUi.activityText(activity).getText().toString();
                assertTrue(text, text.startsWith("DENIED"));
                assertTrue(text, text.contains(TARGET));
            });
        }
    }

    @Test public void dismissingDialogCancelsMessage() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            final AlertDialog[] shown = new AlertDialog[1];
            scenario.onActivity(activity -> {
                VisionUi.typeAndSend(activity, COMMAND);
                AlertDialog dialog = VisionUi.activeDialog(activity);
                assertNotNull("Confirmation dialog should be showing", dialog);
                shown[0] = dialog;
                dialog.cancel(); // same code path as Back press and outside-tap
            });
            VisionUi.settle(); // Dialog.cancel() posts both the cancel and the dismiss callbacks
            scenario.onActivity(activity -> {
                assertFalse(shown[0].isShowing());
                assertNull(VisionUi.activeDialog(activity));
                String text = VisionUi.activityText(activity).getText().toString();
                assertTrue(text, text.startsWith("CANCELLED"));
                assertTrue(text, text.contains(TARGET));
            });
        }
    }

    @Test public void activityRecreationDuringConfirmationSendsNothing() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                VisionUi.typeAndSend(activity, COMMAND);
                assertNotNull("Confirmation dialog should be showing", VisionUi.activeDialog(activity));
            });
            scenario.recreate(); // rotation/process path: onDestroy dismisses the dialog fail-closed
            scenario.onActivity(activity -> {
                assertNull("Dialog must not survive recreation", VisionUi.activeDialog(activity));
                String text = VisionUi.activityText(activity).getText().toString();
                assertFalse("Nothing may be reported as sent after recreation: " + text,
                        text.startsWith("COMPOSER OPENED"));
                VisionUi.typeAndSend(activity, "help");
                assertEquals("App stays usable after the cancelled flow",
                        VisionToolRegistry.helpText(), VisionUi.activityText(activity).getText().toString());
            });
        }
    }
}
