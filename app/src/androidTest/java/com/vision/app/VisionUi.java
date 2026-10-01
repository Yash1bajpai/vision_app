package com.vision.app;

import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;

/**
 * Test-only access to MainActivity internals. Production code is unchanged; instrumented
 * tests read the same private fields the activity itself uses, so they verify the real
 * wiring instead of a test-only seam.
 */
final class VisionUi {
    private VisionUi() { }

    @SuppressWarnings("unchecked")
    private static <T> T field(MainActivity activity, String name) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(activity);
        } catch (Exception e) {
            throw new AssertionError("Missing MainActivity field: " + name, e);
        }
    }

    static EditText inputField(MainActivity activity) {
        return field(activity, "inputField");
    }

    static TextView activityText(MainActivity activity) {
        return field(activity, "activityText");
    }

    static AlertDialog activeDialog(MainActivity activity) {
        return field(activity, "activeDialog");
    }

    /** The composer row is the input field's parent; the send button is its Button sibling. */
    static Button sendButton(MainActivity activity) {
        ViewGroup composer = (ViewGroup) inputField(activity).getParent();
        for (int i = 0; i < composer.getChildCount(); i++) {
            View child = composer.getChildAt(i);
            if (child instanceof Button) return (Button) child;
        }
        throw new AssertionError("No send button found in the composer row");
    }

    /** Types a command and taps the send arrow, exactly as the user would. */
    static void typeAndSend(MainActivity activity, String command) {
        inputField(activity).setText(command);
        sendButton(activity).performClick();
    }

    /**
     * Blocks until the main looper has drained. Dialog button clicks and dismiss callbacks
     * are posted messages, so state they own (the managed dialog slot, the status text) is
     * only observable after the looper runs. Must not be called from the main thread.
     */
    static void settle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
