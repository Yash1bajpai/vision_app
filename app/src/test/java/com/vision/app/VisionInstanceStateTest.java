package com.vision.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Looper;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class VisionInstanceStateTest {
    private static final int REQUEST_CODE_READ_CONTACTS = 101;
    private static final int REQUEST_CODE_READ_CALENDAR = 102;
    private static final int REQUEST_CODE_WRITE_CALENDAR = 103;

    private static final String EXPECTED_FAIL_CLOSED_MESSAGE =
            "Permission granted, but the pending action was cleared when the screen was recreated. Please repeat your request.";

    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before
    public void setup() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
    }

    @After
    public void teardown() {
        if (controller != null) {
            controller.pause().stop().destroy();
        }
    }

    private Object getField(MainActivity target, String name) throws Exception {
        Field f = MainActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private void setField(MainActivity target, String name, Object value) throws Exception {
        Field f = MainActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private void invokeMethod(MainActivity target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = MainActivity.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        m.invoke(target, args);
    }

    // 1. After save during a pending message action: outState has NONE of keys
    // pending_action_type, pending_action_request, pending_action_target,
    // pending_action_reply_text, pending_action_channel.
    @Test
    public void pendingMessageActionNotSavedInInstanceState() throws Exception {
        VisionAction messageAction = new VisionAction(
                VisionAction.Type.SEND_MESSAGE_DIRECT,
                "send whatsapp message to Alice: meet me at 5",
                "Alice",
                "meet me at 5",
                "whatsapp"
        );
        messageAction.state = VisionAction.State.PROPOSED;
        setField(activity, "pendingContactAction", messageAction);

        Bundle outState = new Bundle();
        controller.saveInstanceState(outState);

        assertFalse("pending_action_type must not be saved", outState.containsKey("pending_action_type"));
        assertFalse("pending_action_request must not be saved", outState.containsKey("pending_action_request"));
        assertFalse("pending_action_target must not be saved", outState.containsKey("pending_action_target"));
        assertFalse("pending_action_reply_text must not be saved", outState.containsKey("pending_action_reply_text"));
        assertFalse("pending_action_channel must not be saved", outState.containsKey("pending_action_channel"));
        assertFalse("pending_action_state must not be saved", outState.containsKey("pending_action_state"));
    }

    // 2. After save during a pending event write: NONE of
    // pending_event_write_type, pending_event_write_target, pending_event_write_text.
    @Test
    public void pendingEventWriteNotSavedInInstanceState() throws Exception {
        VisionAction eventAction = new VisionAction(
                VisionAction.Type.CREATE_CALENDAR_EVENT,
                "create event Team Sync at 2026-11-03 14:00",
                "Team Sync",
                "2026-11-03 14:00"
        );
        eventAction.state = VisionAction.State.PROPOSED;
        setField(activity, "pendingEventWriteAction", eventAction);

        Bundle outState = new Bundle();
        controller.saveInstanceState(outState);

        assertFalse("pending_event_write_type must not be saved", outState.containsKey("pending_event_write_type"));
        assertFalse("pending_event_write_request must not be saved", outState.containsKey("pending_event_write_request"));
        assertFalse("pending_event_write_target must not be saved", outState.containsKey("pending_event_write_target"));
        assertFalse("pending_event_write_text must not be saved", outState.containsKey("pending_event_write_text"));
        assertFalse("pending_event_write_state must not be saved", outState.containsKey("pending_event_write_state"));
    }

    // 3. After save with content on screen: no activity_text key.
    @Test
    public void activityTextNotSavedInInstanceState() throws Exception {
        TextView activityText = (TextView) getField(activity, "activityText");
        activityText.setText("SUCCEEDED\n\nSensitive user conversation and history details");

        Bundle outState = new Bundle();
        controller.saveInstanceState(outState);

        assertFalse("activity_text must not be saved in instance-state bundle", outState.containsKey("activity_text"));
    }

    // 4. READ_CALENDAR pending: pending_calendar_type == "READ_CALENDAR" is still saved.
    @Test
    public void readCalendarMarkerPreservedInInstanceState() throws Exception {
        VisionAction calendarRead = new VisionAction(VisionAction.Type.READ_CALENDAR, "", "calendar");
        calendarRead.state = VisionAction.State.PROPOSED;
        setField(activity, "pendingCalendarAction", calendarRead);

        Bundle outState = new Bundle();
        controller.saveInstanceState(outState);

        assertTrue("pending_calendar_type must be preserved", outState.containsKey("pending_calendar_type"));
        assertEquals("READ_CALENDAR", outState.getString("pending_calendar_type"));
        assertTrue("pending_calendar_state must be preserved", outState.containsKey("pending_calendar_state"));
        assertEquals("PROPOSED", outState.getString("pending_calendar_state"));

        // Confirm zero user content is saved alongside the marker
        assertFalse(outState.containsKey("pending_action_request"));
        assertFalse(outState.containsKey("pending_action_target"));
        assertFalse(outState.containsKey("pending_action_reply_text"));
        assertFalse(outState.containsKey("activity_text"));
    }

    // 5. After controller.recreate(): pendingContactAction == null,
    // pendingEventWriteAction == null, activeDialog == null, activityText shows default placeholder.
    @Test
    public void recreationClearsPendingActionsAndDialogAndResetsActivityText() throws Exception {
        // Set up pending actions and active dialog on the activity
        VisionAction messageAction = new VisionAction(
                VisionAction.Type.SEND_MESSAGE_DIRECT, "send sms to Alice: hi", "Alice", "hi", "sms");
        VisionAction eventAction = new VisionAction(
                VisionAction.Type.CREATE_CALENDAR_EVENT, "create event Sync at 2026-11-03 10:00", "Sync", "2026-11-03 10:00");
        setField(activity, "pendingContactAction", messageAction);
        setField(activity, "pendingEventWriteAction", eventAction);

        TextView text = (TextView) getField(activity, "activityText");
        text.setText("Some prior activity text");

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Confirm")
                .setMessage("Are you sure?")
                .create();
        invokeMethod(activity, "showManagedDialog", new Class<?>[]{AlertDialog.class}, dialog);
        assertNotNull(getField(activity, "activeDialog"));
        assertTrue(((AlertDialog) getField(activity, "activeDialog")).isShowing());

        // Recreate the activity
        controller.recreate();
        MainActivity recreated = controller.get();
        shadowOf(Looper.getMainLooper()).idle();

        assertNull("pendingContactAction must be null after recreation", getField(recreated, "pendingContactAction"));
        assertNull("pendingEventWriteAction must be null after recreation", getField(recreated, "pendingEventWriteAction"));
        assertNull("activeDialog must be null after recreation", getField(recreated, "activeDialog"));

        TextView recreatedText = (TextView) getField(recreated, "activityText");
        assertNotNull(recreatedText);
        assertTrue("activityText must show default placeholder: " + recreatedText.getText(),
                recreatedText.getText().toString().startsWith("No activity yet"));
    }

    // 6a. onRequestPermissionsResult(REQUEST_CODE_WRITE_CALENDAR, granted) on recreated activity:
    // no calendar insert, no crash, explicit fail-closed message shown.
    @Test
    public void writeCalendarPermissionResultOnRecreatedActivityFailsClosed() throws Exception {
        controller.recreate();
        MainActivity recreated = controller.get();
        shadowOf(Looper.getMainLooper()).idle();

        assertNull(getField(recreated, "pendingEventWriteAction"));

        recreated.onRequestPermissionsResult(
                REQUEST_CODE_WRITE_CALENDAR,
                new String[]{Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR},
                new int[]{PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_GRANTED}
        );
        shadowOf(Looper.getMainLooper()).idle();

        TextView recreatedText = (TextView) getField(recreated, "activityText");
        String message = recreatedText.getText().toString();
        assertTrue("Fail-closed message must be shown: " + message,
                message.contains(EXPECTED_FAIL_CLOSED_MESSAGE));
        assertTrue("Message must follow FAILED prefix format: " + message,
                message.startsWith("FAILED"));
    }

    // 6b. onRequestPermissionsResult(REQUEST_CODE_READ_CONTACTS, granted) on recreated activity:
    // no composer intent started, explicit fail-closed message shown.
    @Test
    public void readContactsPermissionResultOnRecreatedActivityFailsClosed() throws Exception {
        controller.recreate();
        MainActivity recreated = controller.get();
        shadowOf(Looper.getMainLooper()).idle();

        assertNull(getField(recreated, "pendingContactAction"));

        recreated.onRequestPermissionsResult(
                REQUEST_CODE_READ_CONTACTS,
                new String[]{Manifest.permission.READ_CONTACTS},
                new int[]{PackageManager.PERMISSION_GRANTED}
        );
        shadowOf(Looper.getMainLooper()).idle();

        assertNull("No composer intent may be started after recreation",
                shadowOf(recreated).getNextStartedActivity());

        TextView recreatedText = (TextView) getField(recreated, "activityText");
        String message = recreatedText.getText().toString();
        assertTrue("Fail-closed message must be shown: " + message,
                message.contains(EXPECTED_FAIL_CLOSED_MESSAGE));
        assertTrue("Message must follow FAILED prefix format: " + message,
                message.startsWith("FAILED"));
    }

    // 6c. READ_CALENDAR granted on recreated activity: read still executes (regression).
    @Test
    public void readCalendarPermissionResultOnRecreatedActivityExecutesSuccessfully() throws Exception {
        VisionAction calendarRead = new VisionAction(VisionAction.Type.READ_CALENDAR, "", "calendar");
        calendarRead.state = VisionAction.State.PROPOSED;
        setField(activity, "pendingCalendarAction", calendarRead);

        // Recreate: Bundle preserves READ_CALENDAR marker
        controller.recreate();
        MainActivity recreated = controller.get();
        shadowOf(Looper.getMainLooper()).idle();

        VisionAction restored = (VisionAction) getField(recreated, "pendingCalendarAction");
        assertNotNull("pendingCalendarAction must be restored across recreation", restored);
        assertEquals(VisionAction.Type.READ_CALENDAR, restored.type);

        recreated.onRequestPermissionsResult(
                REQUEST_CODE_READ_CALENDAR,
                new String[]{Manifest.permission.READ_CALENDAR},
                new int[]{PackageManager.PERMISSION_GRANTED}
        );
        shadowOf(Looper.getMainLooper()).idle();

        // Pending action is consumed
        assertNull("pendingCalendarAction must be cleared after execution",
                getField(recreated, "pendingCalendarAction"));

        TextView recreatedText = (TextView) getField(recreated, "activityText");
        String message = recreatedText.getText().toString();
        assertTrue("Calendar read must succeed: " + message,
                message.startsWith("SUCCEEDED"));
        assertFalse("Must not show fail-closed cancellation message: " + message,
                message.contains(EXPECTED_FAIL_CLOSED_MESSAGE));
    }
}
