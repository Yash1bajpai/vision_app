package com.vision.app;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Android-framework simulation of the real RemoteInput/PendingIntent dispatch path.
 * Uses an app-scoped synthetic broadcast, never a messaging app or real recipient.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VisionNotificationDispatchTest {
    private Context context;
    @Before public void setup() {
        VisionNotificationListener.clearLatestNotification();
        context = RuntimeEnvironment.getApplication();
    }
    @After public void cleanup() { VisionNotificationListener.clearLatestNotification(); }

    private VisionNotificationListener.NotificationReplyCapability capability(int request) {
        Intent intent = new Intent("com.vision.app.TEST_REPLY").setPackage(context.getPackageName());
        PendingIntent pending = PendingIntent.getBroadcast(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        RemoteInput input = new RemoteInput.Builder("test_result").setLabel("Synthetic reply").build();
        VisionNotificationListener.NotificationReplyCapability cap =
                new VisionNotificationListener.NotificationReplyCapability("key", "com.whatsapp", "Test sender", pending, input);
        assertTrue(VisionNotificationListener.processPostedNotification("key", "com.whatsapp", "Test sender", "Test text", 1L, 0, cap));
        return cap;
    }
    @Test public void allowDispatchesExactTextOnceAndConsumesCapability() {
        VisionNotificationListener.NotificationReplyCapability cap = capability(1);
        assertEquals(VisionNotificationListener.ReplyResult.SUCCESS,
                VisionNotificationListener.sendBoundReply(context, cap, "Synthetic text only"));
        assertEquals(1, shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents().size());
        Intent dispatched = shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents().get(0);
        assertEquals("Synthetic text only", RemoteInput.getResultsFromIntent(dispatched).getCharSequence("test_result"));
        assertFalse(VisionNotificationListener.isCapabilityActive(cap));
        assertNull(VisionNotificationListener.getLatestReplyCapability());
        assertFalse(VisionNotificationListener.getListenerState().hasReplyCapability);
        assertNotNull("Reading the notification stays available", VisionNotificationListener.getLatestNotification());
        assertEquals(VisionNotificationListener.ReplyResult.STALE_OR_REMOVED,
                VisionNotificationListener.sendBoundReply(context, cap, "Must not dispatch"));
        assertEquals(1, shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents().size());
    }
    @Test public void cancelledPendingIntentDoesNotReportSuccess() {
        VisionNotificationListener.NotificationReplyCapability cap = capability(2);
        cap.pendingIntent.cancel();
        assertEquals(VisionNotificationListener.ReplyResult.FAILED_INTENT,
                VisionNotificationListener.sendBoundReply(context, cap, "No send"));
        assertTrue(shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents().isEmpty());
    }
    @Test public void replacedCapabilityNeverDispatchesOldText() {
        VisionNotificationListener.NotificationReplyCapability old = capability(3);
        capability(4);
        assertEquals(VisionNotificationListener.ReplyResult.STALE_OR_REMOVED,
                VisionNotificationListener.sendBoundReply(context, old, "No send"));
        assertTrue(shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents().isEmpty());
    }
    @Test public void listenerDisconnectClearsCachedContentAndReply() {
        VisionNotificationListener.NotificationReplyCapability cap = capability(5);
        VisionNotificationListener service = Robolectric.buildService(VisionNotificationListener.class).create().get();
        service.onListenerDisconnected();
        assertNull(VisionNotificationListener.getLatestNotification());
        assertNull(VisionNotificationListener.getLatestReplyCapability());
        assertEquals(VisionNotificationListener.ReplyResult.STALE_OR_REMOVED,
                VisionNotificationListener.sendBoundReply(context, cap, "No send"));
    }
    @Test public void listenerDestroyClearsCachedContentAndReply() {
        capability(6);
        Robolectric.buildService(VisionNotificationListener.class).create().destroy();
        assertNull(VisionNotificationListener.getLatestNotification());
        assertNull(VisionNotificationListener.getLatestReplyCapability());
    }
}
