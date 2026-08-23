package com.vision.app;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Reads notification metadata only while an approved user action is active. */
public class VisionNotificationListener extends NotificationListenerService {
    private static final String[] SUPPORTED_PACKAGES = {
            "com.google.android.gm",
            "com.whatsapp",
            "org.telegram.messenger",
            "com.google.android.apps.messaging",
            "com.android.messaging",
            "com.google.android.calendar"
    };
    private static volatile NotificationSnapshot latestNotification;

    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        if (isSupported(notification.getPackageName())) {
            latestNotification = new NotificationSnapshot(
                    notification.getPackageName(),
                    value(notification.getNotification().extras.getCharSequence("android.title")),
                    value(notification.getNotification().extras.getCharSequence("android.text")));
        }
    }

    public static NotificationSnapshot getLatestNotification() {
        return latestNotification;
    }

    private static boolean isSupported(String packageName) {
        for (String supported : SUPPORTED_PACKAGES) {
            if (supported.equals(packageName)) return true;
        }
        return false;
    }

    private static String value(CharSequence text) {
        return text == null ? "" : text.toString().trim();
    }

    public static final class NotificationSnapshot {
        public final String packageName;
        public final String title;
        public final String text;

        private NotificationSnapshot(String packageName, String title, String text) {
            this.packageName = packageName;
            this.title = title;
            this.text = text;
        }
    }
}
