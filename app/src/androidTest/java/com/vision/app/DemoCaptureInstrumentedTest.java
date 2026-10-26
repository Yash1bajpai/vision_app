package com.vision.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Temporary capture driver. Real production UI and dispatch, no product-code changes. */
@RunWith(AndroidJUnit4.class)
public class DemoCaptureInstrumentedTest {
    private final StringBuilder evidence = new StringBuilder();
    private File directory;

    private void pause(long ms) { SystemClock.sleep(ms); }
    private void screenshot(String name) throws Exception {
        VisionUi.settle();
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("Real display screenshot", bitmap);
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        bitmap.recycle();
    }
    private void type(ActivityScenario<MainActivity> scenario, String command) {
        scenario.onActivity(activity -> VisionUi.inputField(activity).setText(command));
        pause(2200);
        scenario.onActivity(activity -> VisionUi.sendButton(activity).performClick());
        VisionUi.settle();
    }
    @Test public void captureRealOfflineFlow() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory = new File(context.getExternalFilesDir(null), "demo");
        assertTrue(directory.mkdirs() || directory.isDirectory());
        assertEquals("Fresh emulator must not grant calendar writes", PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            pause(6500);
            screenshot("00-opening");
            type(scenario, "what time is it");
            scenario.onActivity(activity -> {
                String actual = VisionUi.activityText(activity).getText().toString();
                String expected = new SimpleDateFormat("h:mm a", Locale.US).format(new Date());
                assertTrue(actual, actual.startsWith("SUCCEEDED") && actual.contains("It is ") && actual.contains(expected));
                evidence.append("TIME: ").append(actual).append('\n');
            });
            pause(2000); screenshot("01-time"); pause(10000);
            type(scenario, "open Telegram and then open Gmail");
            scenario.onActivity(activity -> {
                String actual = VisionUi.activityText(activity).getText().toString();
                assertTrue(actual, actual.startsWith("MULTIPLE ACTIONS NOT SUPPORTED"));
                assertTrue(actual, actual.contains("did not run any of them"));
                assertNull(VisionUi.activeDialog(activity));
                assertTrue("Activity remains foreground", activity.hasWindowFocus());
                evidence.append("REFUSAL: ").append(actual).append('\n');
            });
            pause(2000); screenshot("02-multi-action-refusal"); pause(12000);
            type(scenario, "create event Demo rehearsal tomorrow at 3 pm");
            scenario.onActivity(activity -> {
                AlertDialog dialog = VisionUi.activeDialog(activity);
                assertNotNull(dialog); assertTrue(dialog.isShowing());
                assertEquals("Create event", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            });
            pause(1500); screenshot("03-calendar-confirmation"); pause(6500);
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            VisionUi.settle();
            scenario.onActivity(activity -> {
                assertNull("Back must dismiss managed confirmation", VisionUi.activeDialog(activity));
                String actual = VisionUi.activityText(activity).getText().toString();
                assertTrue(actual, actual.startsWith("CANCELLED"));
                assertTrue(actual, actual.contains("Demo rehearsal"));
                assertTrue(actual, actual.contains("Confirmation was dismissed."));
                assertEquals("No calendar permission requested/granted", PackageManager.PERMISSION_DENIED,
                        activity.checkSelfPermission(Manifest.permission.WRITE_CALENDAR));
                evidence.append("CANCELLATION: ").append(actual).append('\n');
                evidence.append("No WRITE_CALENDAR grant; confirmation dismissed before permission/write path.\n");
            });
            pause(1800); screenshot("04-cancelled"); pause(18000);
        } finally {
            try (FileOutputStream out = new FileOutputStream(new File(directory, "evidence.txt"))) {
                out.write(evidence.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
