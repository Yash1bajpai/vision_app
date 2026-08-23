package com.vision.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.List;

/** Keeps only the latest supported notification and reply capability in process memory. */
public class VisionNotificationListener extends NotificationListenerService {
    private static final String[] SUPPORTED_PACKAGES = {
            "com.google.android.gm",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.google.android.apps.messaging",
            "com.android.messaging",
            "com.google.android.calendar"
    };
    private static volatile NotificationSnapshot latestNotification;
    private static volatile NotificationReplyCapability latestReplyCapability;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getPackageName() == null) return;
        if (!isSupported(sbn.getPackageName())) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        String title = "";
        String text = "";

        if (extras != null) {
            CharSequence titleCs = extras.getCharSequence(Notification.EXTRA_TITLE);
            if (titleCs == null || titleCs.length() == 0) {
                titleCs = extras.getCharSequence(Notification.EXTRA_TITLE_BIG);
            }
            if (titleCs == null || titleCs.length() == 0) {
                titleCs = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
            }
            title = value(titleCs);

            // 1. Check MessagingStyle messages
            try {
                Parcelable[] msgBundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
                if (msgBundles != null && msgBundles.length > 0) {
                    List<Notification.MessagingStyle.Message> messages =
                            Notification.MessagingStyle.Message.getMessagesFromBundleArray(msgBundles);
                    if (messages != null && !messages.isEmpty()) {
                        Notification.MessagingStyle.Message lastMsg = messages.get(messages.size() - 1);
                        if (lastMsg != null && lastMsg.getText() != null) {
                            CharSequence sender = null;
                            if (android.os.Build.VERSION.SDK_INT >= 28 && lastMsg.getSenderPerson() != null) {
                                sender = lastMsg.getSenderPerson().getName();
                            }
                            if (sender == null || sender.length() == 0) {
                                sender = lastMsg.getSender();
                            }
                            if (sender != null && sender.length() > 0 && !sender.toString().equals(title)) {
                                text = sender + ": " + lastMsg.getText();
                            } else {
                                text = lastMsg.getText().toString().trim();
                            }
                        }
                    }
                }
            } catch (Throwable ignored) { }

            // 2. Check bigText
            if (text.isEmpty()) {
                text = value(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            }

            // 3. Check textLines (InboxStyle)
            if (text.isEmpty()) {
                CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null && lines.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (CharSequence line : lines) {
                        if (line != null && line.length() > 0) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(line.toString().trim());
                        }
                    }
                    text = sb.toString().trim();
                }
            }

            // 4. Check standard text
            if (text.isEmpty()) {
                text = value(extras.getCharSequence(Notification.EXTRA_TEXT));
            }

            // 5. Fallback to summary or info text
            if (text.isEmpty()) {
                text = value(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
            }
            if (text.isEmpty()) {
                text = value(extras.getCharSequence(Notification.EXTRA_INFO_TEXT));
            }
            if (text.isEmpty()) {
                text = value(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
            }
        }

        String key = sbn.getKey() != null ? sbn.getKey() : (sbn.getPackageName() + ":" + sbn.getId());
        latestNotification = new NotificationSnapshot(key, sbn.getPackageName(), title, text, sbn.getPostTime());

        // Extract RemoteInput reply action if present
        NotificationReplyCapability replyCap = null;
        if (notification.actions != null) {
            for (Notification.Action act : notification.actions) {
                if (act != null && act.actionIntent != null) {
                    RemoteInput[] remoteInputs = act.getRemoteInputs();
                    if (remoteInputs != null && remoteInputs.length > 0) {
                        for (RemoteInput ri : remoteInputs) {
                            if (ri != null && ri.getResultKey() != null) {
                                replyCap = new NotificationReplyCapability(
                                        key,
                                        sbn.getPackageName(),
                                        title,
                                        act.actionIntent,
                                        ri
                                );
                                break;
                            }
                        }
                    }
                }
                if (replyCap != null) break;
            }
        }
        latestReplyCapability = replyCap;
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        String removeKey = sbn.getKey() != null ? sbn.getKey() : (sbn.getPackageName() + ":" + sbn.getId());
        NotificationSnapshot current = latestNotification;
        if (current != null && current.key.equals(removeKey)) {
            latestNotification = null;
        }
        NotificationReplyCapability currentReply = latestReplyCapability;
        if (currentReply != null && currentReply.key.equals(removeKey)) {
            latestReplyCapability = null;
        }
    }

    public static NotificationSnapshot getLatestNotification() {
        return latestNotification;
    }

    public static NotificationReplyCapability getLatestReplyCapability() {
        return latestReplyCapability;
    }

    public static void clearLatestNotification() {
        latestNotification = null;
        latestReplyCapability = null;
    }

    public static boolean sendReply(Context context, NotificationReplyCapability capability, String replyText) {
        if (context == null || capability == null || capability.pendingIntent == null || capability.remoteInput == null) {
            return false;
        }
        if (replyText == null) {
            replyText = "";
        }
        try {
            Intent fillInIntent = new Intent();
            Bundle bundle = new Bundle();
            bundle.putCharSequence(capability.remoteInput.getResultKey(), replyText);
            RemoteInput.addResultsToIntent(new RemoteInput[] { capability.remoteInput }, fillInIntent, bundle);
            capability.pendingIntent.send(context, 0, fillInIntent);
            return true;
        } catch (PendingIntent.CanceledException e) {
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean isSupported(String packageName) {
        if (packageName == null) return false;
        for (String supported : SUPPORTED_PACKAGES) {
            if (supported.equals(packageName)) return true;
        }
        return false;
    }

    private static String value(CharSequence text) {
        return text == null ? "" : text.toString().trim();
    }

    public static final class NotificationSnapshot {
        public final String key;
        public final String packageName;
        public final String title;
        public final String text;
        public final long postTime;

        public NotificationSnapshot(String key, String packageName, String title, String text, long postTime) {
            this.key = key != null ? key : "";
            this.packageName = packageName != null ? packageName : "";
            this.title = title != null ? title : "";
            this.text = text != null ? text : "";
            this.postTime = postTime;
        }
    }

    public static final class NotificationReplyCapability {
        public final String key;
        public final String packageName;
        public final String senderOrTitle;
        public final PendingIntent pendingIntent;
        public final RemoteInput remoteInput;

        public NotificationReplyCapability(String key, String packageName, String senderOrTitle,
                                           PendingIntent pendingIntent, RemoteInput remoteInput) {
            this.key = key != null ? key : "";
            this.packageName = packageName != null ? packageName : "";
            this.senderOrTitle = senderOrTitle != null ? senderOrTitle : "";
            this.pendingIntent = pendingIntent;
            this.remoteInput = remoteInput;
        }
    }
}
