package com.vision.app;

import android.app.AlertDialog;
import android.os.Looper;
import android.widget.EditText;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Plans are injected via reflection only in tests; production still uses NoOpReasoningProvider. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class VisionPlanLifecycleTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    @Before public void setup() { controller = Robolectric.buildActivity(MainActivity.class).setup(); activity = controller.get(); }
    @After public void cleanup() { controller.pause().stop().destroy(); }
    private Object get(String name) throws Exception {
        Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity);
    }
    private void invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = MainActivity.class.getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(activity, args);
    }
    private VisionPlan plan(VisionAction first) throws Exception {
        VisionPlan p = new VisionPlan("test", Arrays.asList(first,
                new VisionAction(VisionAction.Type.READ_TIME, "time", "")));
        invoke("startPlan", new Class<?>[]{VisionPlan.class, EditText.class}, p, get("inputField"));
        return p;
    }
    private VisionAction event() { return new VisionAction(VisionAction.Type.CREATE_CALENDAR_EVENT,
            "test", "Synthetic event", "2027-01-01T10:00"); }
    private void cancel() throws Exception { invoke("stopPlan", new Class<?>[]{String.class, boolean.class}, "PLAN CANCELLED", true); }
    @Test public void cancelDismissesConfirmationAndNeverExecutesNextStep() throws Exception {
        VisionPlan p = plan(event()); AlertDialog dialog = (AlertDialog) get("activeDialog");
        cancel(); shadowOf(Looper.getMainLooper()).idle();
        assertNull(get("pendingPlan")); assertFalse(dialog.isShowing());
        assertEquals(VisionAction.State.PROPOSED, p.step(1).state);
    }
    @Test public void timeoutClearsConfirmationAndRemainingSteps() throws Exception {
        VisionPlan p = plan(event());
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS);
        assertNull(get("pendingPlan")); assertNull(get("activeDialog"));
        assertEquals(VisionAction.State.PROPOSED, p.step(1).state);
        assertTrue(((android.widget.TextView)get("activityText")).getText().toString().startsWith("PLAN TIMED OUT"));
    }
    @Test public void delayedAllowAfterCancelCannotApproveOrStartPermissionFlow() throws Exception {
        VisionPlan p = plan(event()); AlertDialog dialog = (AlertDialog)get("activeDialog"); cancel();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(VisionAction.State.DENIED, p.step(0).state);
        assertNull(get("pendingEventWriteAction")); assertNull(get("pendingPlan"));
    }
    @Test public void oldConfirmationCannotAffectReplacementPlan() throws Exception {
        plan(event()); AlertDialog old = (AlertDialog)get("activeDialog"); cancel();
        VisionPlan replacement = plan(event());
        old.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); shadowOf(Looper.getMainLooper()).idle();
        assertSame(replacement, get("pendingPlan"));
        assertEquals(VisionAction.State.PROPOSED, replacement.step(0).state);
        assertNull(get("pendingEventWriteAction"));
    }
    @Test public void latePermissionAfterCancelDoesNotAdvance() throws Exception {
        VisionPlan p = plan(new VisionAction(VisionAction.Type.READ_CALENDAR, "calendar", "calendar"));
        assertNotNull(get("pendingCalendarAction")); int code = (Integer)get("activePlanPermissionCode"); cancel();
        activity.onRequestPermissionsResult(code, new String[]{android.Manifest.permission.READ_CALENDAR}, new int[]{0});
        assertNull(get("pendingPlan")); assertNull(get("pendingCalendarAction"));
        assertEquals(VisionAction.State.PROPOSED, p.step(1).state);
    }
    @Test public void oldPermissionCannotResumeReplacementPlan() throws Exception {
        plan(new VisionAction(VisionAction.Type.READ_CALENDAR, "calendar", "calendar"));
        int oldCode = (Integer)get("activePlanPermissionCode"); cancel();
        // Android permits only one live permission dialog. Finish the abandoned
        // platform prompt before starting another, then replay its stale result.
        activity.onRequestPermissionsResult(oldCode, new String[]{android.Manifest.permission.READ_CALENDAR}, new int[]{-1});
        org.robolectric.util.ReflectionHelpers.setField(activity, "mHasCurrentPermissionsRequest", false);
        VisionPlan replacement = plan(new VisionAction(VisionAction.Type.READ_CALENDAR, "calendar", "calendar"));
        int newCode = (Integer)get("activePlanPermissionCode"); assertNotEquals(oldCode, newCode);
        assertSame("replacement should be waiting: " + ((android.widget.TextView)get("activityText")).getText(), replacement, get("pendingPlan"));
        activity.onRequestPermissionsResult(oldCode, new String[]{android.Manifest.permission.READ_CALENDAR}, new int[]{0});
        assertSame(replacement, get("pendingPlan")); assertNotNull(get("pendingCalendarAction"));
        assertEquals(VisionAction.State.PROPOSED, replacement.step(1).state);
    }
    @Test public void expiredPermissionCannotExecuteOrAdvance() throws Exception {
        VisionPlan p = plan(new VisionAction(VisionAction.Type.READ_CALENDAR, "calendar", "calendar"));
        int code = (Integer)get("activePlanPermissionCode");
        org.robolectric.shadows.ShadowSystemClock.advanceBy(60, TimeUnit.SECONDS);
        activity.onRequestPermissionsResult(code, new String[]{android.Manifest.permission.READ_CALENDAR}, new int[]{0});
        assertNull(get("pendingPlan")); assertNull(get("pendingCalendarAction"));
        assertEquals(VisionAction.State.PROPOSED, p.step(1).state);
    }
    @Test public void recreationDoesNotSaveOrRestorePlan() throws Exception {
        plan(event()); android.os.Bundle state = new android.os.Bundle();
        controller.saveInstanceState(state); controller.pause().stop().destroy();
        controller = Robolectric.buildActivity(MainActivity.class).create(state).start().resume().visible();
        activity = controller.get(); assertNull(get("pendingPlan")); assertNull(get("activeDialog"));
        assertTrue(((VisionSessionContext)get("sessionContext")).snapshot(android.os.SystemClock.elapsedRealtime()).isEmpty());
    }
    @Test public void completedPlanRemovesDeadline() throws Exception {
        VisionPlan p = plan(new VisionAction(VisionAction.Type.READ_TIME, "time", ""));
        assertNull(get("pendingPlan")); assertNull(get("planTimeout"));
        assertEquals(VisionAction.State.SUCCEEDED, p.step(1).state);
    }
    @Test public void backgroundClearsPlanAndContext() throws Exception {
        VisionPlan p = plan(event()); VisionSessionContext c = (VisionSessionContext)get("sessionContext");
        c.remember("private hint", android.os.SystemClock.elapsedRealtime());
        controller.pause().stop();
        assertNull(get("pendingPlan")); assertTrue(c.snapshot(android.os.SystemClock.elapsedRealtime()).isEmpty());
        assertEquals(VisionAction.State.PROPOSED, p.step(1).state);
    }
    @Test public void backCancelsInsteadOfAdvancing() throws Exception {
        VisionPlan p = plan(event()); activity.onBackPressed();
        assertNull(get("pendingPlan")); assertEquals(VisionAction.State.PROPOSED, p.step(1).state);
    }
    @Test public void expiredAllowIsRejectedBeforeTimeoutCallbackRuns() throws Exception {
        VisionPlan p = plan(event()); AlertDialog dialog = (AlertDialog)get("activeDialog");
        org.robolectric.shadows.ShadowSystemClock.advanceBy(60, TimeUnit.SECONDS);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); shadowOf(Looper.getMainLooper()).idle();
        assertNull(get("pendingEventWriteAction")); assertNull(get("pendingPlan"));
        assertEquals(VisionAction.State.DENIED, p.step(0).state);
    }
}
