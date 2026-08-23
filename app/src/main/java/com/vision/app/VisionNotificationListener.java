package com.vision.app;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Reads notification metadata only while an approved user action is active. */
public class VisionNotificationListener extends NotificationListenerService {
    private static volatile StatusBarNotification latestNotification;

    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        latestNotification = notification;
    }

    public static StatusBarNotification getLatestNotification() {
        return latestNotification;
    }
}
