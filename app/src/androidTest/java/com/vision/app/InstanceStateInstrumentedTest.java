package com.vision.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.util.Log;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Real Activity lifecycle tests on the emulator for instance-state fail-closed privacy.
 * Verifies that pending actions carrying user content are dropped across recreation,
 * post-rotation permission grants fail closed with explicit user feedback, and safe
 * calendar read markers survive recreation and execute normally.
 */
@RunWith(AndroidJUnit4.class)
public class InstanceStateInstrumentedTest {
    private static final String TAG = "InstanceStateInstrumentedTest";

    private static Object field(MainActivity activity, String name) {
        try {
            Field f = MainActivity.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(activity);
        } catch (Exception e) {
            throw new AssertionError("Missing MainActivity field: " + name, e);
        }
    }

    private static void setField(MainActivity activity, String name, Object val) {
        try {
            Field f = MainActivity.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(activity, val);
        } catch (Exception e) {
            throw new AssertionError("Failed setting MainActivity field: " + name, e);
        }
    }

    private static void takeScreenshot(String filename) {
        try {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            File dir = context.getExternalFilesDir(null);
            if (dir == null) {
                dir = context.getFilesDir();
            }
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File dest = new File(dir, filename);

            boolean captured = false;
            // 1. Try UiDevice reflection if available on classpath
            try {
                Class<?> uiDeviceClass = Class.forName("androidx.test.uiautomator.UiDevice");
                Method getInstanceMethod = uiDeviceClass.getMethod("getInstance", android.app.Instrumentation.class);
                Object uiDevice = getInstanceMethod.invoke(null, InstrumentationRegistry.getInstrumentation());
                Method takeScreenshotMethod = uiDeviceClass.getMethod("takeScreenshot", File.class);
                captured = (Boolean) takeScreenshotMethod.invoke(uiDevice, dest);
            } catch (Throwable ignored) { }

            // 2. If not captured, use native Android UiAutomation screenshot
            if (!captured) {
                Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                if (bitmap != null) {
                    try (FileOutputStream out = new FileOutputStream(dest)) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                        captured = true;
                    }
                }
            }

            String path = dest.getAbsolutePath();
            Log.i(TAG, "Screenshot " + filename + " saved: " + path + " (success=" + captured + ", size=" + dest.length() + ")");
            System.out.println("Screenshot " + filename + " saved: " + path + " (success=" + captured + ", size=" + dest.length() + ")");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to capture screenshot " + filename, t);
            System.out.println("Failed to capture screenshot " + filename + ": " + t.getMessage());
        }
    }

    @Test
    public void recreationCancelsPendingMessageActionAndGrantFailsClosed() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            VisionAction originalAction = new VisionAction(
                    VisionAction.Type.SEND_MESSAGE_DIRECT,
                    "send whatsapp to Alice: meet at 5",
                    "Alice",
                    "meet at 5",
                    "whatsapp"
            );
            originalAction.state = VisionAction.State.PROPOSED;

            scenario.onActivity(activity -> {
                setField(activity, "pendingContactAction", originalAction);
            });

            scenario.recreate();
            VisionUi.settle();

            scenario.onActivity(activity -> {
                assertNull("pendingContactAction field must be null after recreation",
                        field(activity, "pendingContactAction"));
                assertEquals("inputField must be empty", "",
                        VisionUi.inputField(activity).getText().toString());
            });

            // Capture screenshot before the permission call
            takeScreenshot("after-recreate.png");

            // Invoke recreated.onRequestPermissionsResult on main thread
            scenario.onActivity(activity -> {
                activity.onRequestPermissionsResult(
                        101 /* REQUEST_CODE_READ_CONTACTS */,
                        new String[]{Manifest.permission.READ_CONTACTS},
                        new int[]{PackageManager.PERMISSION_GRANTED}
                );
            });
            VisionUi.settle();

            scenario.onActivity(activity -> {
                String text = VisionUi.activityText(activity).getText().toString();
                assertTrue("activityText must contain 'Please repeat your request': " + text,
                        text.contains("Please repeat your request"));
                assertNull("pendingContactAction must remain null",
                        field(activity, "pendingContactAction"));
                assertEquals("Original action state must remain unchanged",
                        VisionAction.State.PROPOSED, originalAction.state);
            });

            // Capture second screenshot showing fail-closed message
            takeScreenshot("fail-closed-message.png");
        }
    }

    @Test
    public void recreationPreservesCalendarReadMarkerAndReadStillRuns() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            VisionAction calendarAction = new VisionAction(VisionAction.Type.READ_CALENDAR, "", "calendar");
            calendarAction.state = VisionAction.State.PROPOSED;

            scenario.onActivity(activity -> {
                setField(activity, "pendingCalendarAction", calendarAction);
            });

            scenario.recreate();
            VisionUi.settle();

            Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(targetContext.getPackageName(), Manifest.permission.READ_CALENDAR);
            scenario.onActivity(activity -> {
                VisionAction restored = (VisionAction) field(activity, "pendingCalendarAction");
                assertNotNull("pendingCalendarAction must be restored across recreation", restored);
                assertEquals(VisionAction.Type.READ_CALENDAR, restored.type);

                activity.onRequestPermissionsResult(
                        102 /* REQUEST_CODE_READ_CALENDAR */,
                        new String[]{Manifest.permission.READ_CALENDAR},
                        new int[]{PackageManager.PERMISSION_GRANTED}
                );
            });
            VisionUi.settle();

            scenario.onActivity(activity -> {
                assertNull("pendingCalendarAction must be consumed (null)",
                        field(activity, "pendingCalendarAction"));
                String text = VisionUi.activityText(activity).getText().toString();
                assertTrue("activityText must start with 'SUCCEEDED': " + text,
                        text.startsWith("SUCCEEDED"));
            });

            takeScreenshot("calendar-read-ok.png");
        }
    }
}
