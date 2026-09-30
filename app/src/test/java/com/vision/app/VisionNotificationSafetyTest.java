package com.vision.app;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Notification lifecycle regression tests, with synthetic metadata only. */
public class VisionNotificationSafetyTest {
    private static final String APP = "com.whatsapp";
    @Before public void reset() { VisionNotificationListener.clearLatestNotification(); }
    @After public void cleanup() { VisionNotificationListener.clearLatestNotification(); }

    private VisionNotificationListener.NotificationReplyCapability cap(String key, String app) {
        return new VisionNotificationListener.NotificationReplyCapability(key, app, "Test sender", null, null);
    }
    private void post(String key, long time, VisionNotificationListener.NotificationReplyCapability cap) {
        assertTrue(VisionNotificationListener.processPostedNotification(key, APP, "Test sender", "Test text", time, 0, cap));
    }
    @Test public void wrongKeyCapabilityIsRejectedWithoutOverwritingState() {
        post("first", 1L, null);
        assertFalse(VisionNotificationListener.processPostedNotification("second", APP, "", "", 2L, 0, cap("wrong", APP)));
        assertEquals("first", VisionNotificationListener.getLatestNotification().key);
    }
    @Test public void wrongPackageCapabilityIsRejected() {
        assertFalse(VisionNotificationListener.processPostedNotification("key", APP, "", "", 1L, 0, cap("key", "org.telegram.messenger")));
        assertNull(VisionNotificationListener.getLatestNotification());
    }
    @Test public void sameKeyUpdateInvalidatesOldConfirmation() {
        VisionNotificationListener.NotificationReplyCapability old = cap("key", APP);
        post("key", 1L, old);
        post("key", 1L, cap("key", APP));
        assertFalse(VisionNotificationListener.isCapabilityActive(old));
        assertEquals(VisionNotificationListener.ReplyResult.STALE_OR_REMOVED,
                VisionNotificationListener.sendBoundReply(null, old, "hello"));
    }
    @Test public void removalInvalidatesBoundReply() {
        VisionNotificationListener.NotificationReplyCapability old = cap("key", APP);
        post("key", 1L, old);
        assertTrue(VisionNotificationListener.processRemovedNotification("key"));
        assertEquals(VisionNotificationListener.ReplyResult.STALE_OR_REMOVED,
                VisionNotificationListener.sendBoundReply(null, old, "hello"));
    }
    @Test public void clearingDropsContentAndCapability() {
        post("key", 1L, cap("key", APP));
        VisionNotificationListener.clearLatestNotification();
        assertNull(VisionNotificationListener.getLatestNotification());
        assertNull(VisionNotificationListener.getLatestReplyCapability());
        assertFalse(VisionNotificationListener.getListenerState().hasReplyCapability);
    }
    @Test public void cloneCannotReuseActiveCapability() {
        post("key", 1L, cap("key", APP));
        assertEquals(VisionNotificationListener.ReplyResult.STALE_OR_REMOVED,
                VisionNotificationListener.sendBoundReply(null, cap("key", APP), "hello"));
    }
    @Test public void missingIntentDoesNotReportSuccess() {
        VisionNotificationListener.NotificationReplyCapability c = cap("key", APP);
        post("key", 1L, c);
        assertEquals(VisionNotificationListener.ReplyResult.FAILED_INTENT,
                VisionNotificationListener.sendBoundReply(null, c, "hello"));
    }
}
