package com.vision.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.util.Base64;
import android.util.Log;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
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
            if (captured) {
                emitEvidenceToLogcat(filename, dest);
            }
            System.out.println("Screenshot " + filename + " saved: " + path + " (success=" + captured + ", size=" + dest.length() + ")");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to capture screenshot " + filename, t);
            System.out.println("Failed to capture screenshot " + filename + ": " + t.getMessage());
        }
    }

    private static void emitEvidenceToLogcat(String filename, File file) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            try (FileInputStream in = new FileInputStream(file)) {
                while ((n = in.read(buf)) > 0) {
                    buffer.write(buf, 0, n);
                }
            }
            String b64 = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP);
            int chunkSize = 3000;
            int total = (b64.length() + chunkSize - 1) / chunkSize;
            Log.i(TAG, "EVIDENCE-BEGIN " + filename + " chunks=" + total);
            for (int i = 0; i < total; i++) {
                int start = i * chunkSize;
                int end = Math.min(b64.length(), start + chunkSize);
                Log.i(TAG, "EVIDENCE " + filename + " " + i + "/" + total + " " + b64.substring(start, end));
            }
            Log.i(TAG, "EVIDENCE-END " + filename);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to emit evidence for " + filename, t);
        }
    }

    private static File evidenceFile(String filename) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, filename);
    }

    /**
     * Renders the activity's own view tree to a bitmap, bypassing the emulator
     * compositor. The three compositor-level captures above returned byte-identical
     * frames across two test methods minutes apart, which indicates the emulator
     * stopped composing new frames after the first; view.draw records what the app
     * itself renders, which is the behavior under qualification.
     */
    private static void renderViewTree(String filename, ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity -> {
            try {
                View root = activity.getWindow().getDecorView();
                int w = root.getWidth();
                int h = root.getHeight();
                if (w <= 0 || h <= 0) {
                    Log.e(TAG, "RENDER-FAIL " + filename + " decorView size " + w + "x" + h);
                    return;
                }
                Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                root.draw(canvas);
                File dest = evidenceFile(filename);
                try (FileOutputStream out = new FileOutputStream(dest)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                Log.i(TAG, "RENDER " + filename + " " + w + "x" + h + " bytes=" + dest.length());
                emitEvidenceToLogcat(filename, dest);
            } catch (Throwable t) {
                Log.e(TAG, "RENDER-FAIL " + filename, t);
            }
        });
    }

    /** Logs every TextView in the window with visibility, on-screen bounds, and text. */
    private static void dumpHierarchy(String label, ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity -> {
            Log.i(TAG, "HIER-BEGIN " + label + " windowFocus=" + activity.hasWindowFocus());
            walkHierarchy(label, activity.getWindow().getDecorView());
            Log.i(TAG, "HIER-END " + label);
        });
    }

    private static void walkHierarchy(String label, View view) {
        if (view instanceof TextView) {
            Rect rect = new Rect();
            boolean onScreen = view.getGlobalVisibleRect(rect);
            String idName;
            try {
                idName = view.getId() == -1 ? "none" : view.getResources().getResourceEntryName(view.getId());
            } catch (Exception e) {
                idName = "unknown";
            }
            String text = ((TextView) view).getText().toString().replace('\n', '|');
            if (text.length() > 120) {
                text = text.substring(0, 120) + "...";
            }
            Log.i(TAG, "HIER " + label + " id=" + idName
                    + " visibility=" + visibilityName(view.getVisibility())
                    + " shown=" + view.isShown()
                    + " onScreen=" + onScreen
                    + " rect=" + rect.toShortString()
                    + " size=" + view.getWidth() + "x" + view.getHeight()
                    + " text=\"" + text + "\"");
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                walkHierarchy(label, group.getChildAt(i));
            }
        }
    }

    private static String visibilityName(int visibility) {
        switch (visibility) {
            case View.VISIBLE: return "VISIBLE";
            case View.INVISIBLE: return "INVISIBLE";
            case View.GONE: return "GONE";
            default: return String.valueOf(visibility);
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
            dumpHierarchy("after-recreate", scenario);
            renderViewTree("after-recreate-render.png", scenario);
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
            dumpHierarchy("fail-closed-message", scenario);
            renderViewTree("fail-closed-message-render.png", scenario);
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

            dumpHierarchy("calendar-read-ok", scenario);
            renderViewTree("calendar-read-ok-render.png", scenario);
            takeScreenshot("calendar-read-ok.png");
        }
    }
}
