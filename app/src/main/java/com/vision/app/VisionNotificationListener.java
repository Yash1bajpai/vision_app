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
import java.util.Locale;

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

    public enum ReplyResult {
        SUCCESS,
        STALE_OR_REMOVED,
        FAILED_INTENT,
        INVALID_ARGUMENTS
    }

    /**
     * Determines whether an incoming notification should be ignored based on flags and reply capability.
     * N9: Skip group summaries ONLY when no direct reply action exists. Skip ongoing/foreground noise ONLY when no direct reply action exists.
     */
    public static boolean shouldIgnoreNotification(int flags, boolean hasReplyAction) {
        if ((flags & (Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0 && !hasReplyAction) {
            return true;
        }
        return false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getPackageName() == null) return;
        if (!isSupported(sbn.getPackageName())) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        String title = "";
        String text = "";
        String conversationTitle = "";
        String senderPerson = "";

        if (extras != null) {
            CharSequence convTitleCs = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
            if (convTitleCs != null && convTitleCs.length() > 0) {
                conversationTitle = convTitleCs.toString().trim();
            }

            CharSequence titleCs = extras.getCharSequence(Notification.EXTRA_TITLE);
            if (titleCs == null || titleCs.length() == 0) {
                titleCs = extras.getCharSequence(Notification.EXTRA_TITLE_BIG);
            }
            if (titleCs == null || titleCs.length() == 0) {
                titleCs = convTitleCs;
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
                            if (sender != null && sender.length() > 0) {
                                senderPerson = sender.toString().trim();
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

        // Extract RemoteInput reply action if present
        NotificationReplyCapability replyCap = null;
        if (notification.actions != null) {
            for (Notification.Action act : notification.actions) {
                if (act != null && act.actionIntent != null) {
                    RemoteInput[] remoteInputs = act.getRemoteInputs();
                    if (remoteInputs != null && remoteInputs.length > 0) {
                        for (RemoteInput ri : remoteInputs) {
                            if (ri != null && ri.getResultKey() != null) {
                                String senderOrTitle = !title.isEmpty() ? title : (!senderPerson.isEmpty() ? senderPerson : conversationTitle);
                                replyCap = new NotificationReplyCapability(
                                        key,
                                        sbn.getPackageName(),
                                        senderOrTitle,
                                        act.actionIntent,
                                        ri,
                                        conversationTitle,
                                        senderPerson
                                );
                                break;
                            }
                        }
                    }
                }
                if (replyCap != null) break;
            }
        }

        // F4/N2: Synchronize binder-thread check and write to prevent race conditions with removals and dispatch checks
        synchronized (VisionNotificationListener.class) {
            if (shouldIgnoreNotification(notification.flags, replyCap != null)) {
                return;
            }
            latestNotification = new NotificationSnapshot(key, sbn.getPackageName(), title, text, sbn.getPostTime());
            latestReplyCapability = replyCap;
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        String removeKey = sbn.getKey() != null ? sbn.getKey() : (sbn.getPackageName() + ":" + sbn.getId());
        synchronized (VisionNotificationListener.class) {
            NotificationSnapshot current = latestNotification;
            if (current != null && current.key.equals(removeKey)) {
                latestNotification = null;
            }
            NotificationReplyCapability currentReply = latestReplyCapability;
            if (currentReply != null && currentReply.key.equals(removeKey)) {
                latestReplyCapability = null;
            }
        }
    }

    public static synchronized NotificationSnapshot getLatestNotification() {
        return latestNotification;
    }

    public static synchronized NotificationReplyCapability getLatestReplyCapability() {
        return latestReplyCapability;
    }

    public static synchronized void clearLatestNotification() {
        latestNotification = null;
        latestReplyCapability = null;
    }

    public static synchronized void setLatestNotificationForTesting(NotificationSnapshot snapshot) {
        latestNotification = snapshot;
    }

    public static synchronized void setLatestReplyCapabilityForTesting(NotificationReplyCapability capability) {
        latestReplyCapability = capability;
    }

    public static synchronized boolean isCapabilityActive(NotificationReplyCapability capability) {
        if (capability == null || capability.key.isEmpty()) {
            return false;
        }
        NotificationReplyCapability current = latestReplyCapability;
        return current == capability && current.key.equals(capability.key);
    }

    public static synchronized ReplyResult sendBoundReply(Context context, NotificationReplyCapability boundCapability, String replyText) {
        if (boundCapability == null || boundCapability.key.isEmpty()) {
            return ReplyResult.INVALID_ARGUMENTS;
        }
        NotificationReplyCapability current = latestReplyCapability;
        if (current == null || current != boundCapability || !current.key.equals(boundCapability.key)) {
            return ReplyResult.STALE_OR_REMOVED;
        }
        if (boundCapability.pendingIntent == null || boundCapability.remoteInput == null) {
            return ReplyResult.FAILED_INTENT;
        }
        if (context == null) {
            return ReplyResult.INVALID_ARGUMENTS;
        }
        if (replyText == null) {
            replyText = "";
        }
        try {
            Intent fillInIntent = new Intent();
            Bundle bundle = new Bundle();
            bundle.putCharSequence(boundCapability.remoteInput.getResultKey(), replyText);
            RemoteInput.addResultsToIntent(new RemoteInput[] { boundCapability.remoteInput }, fillInIntent, bundle);
            boundCapability.pendingIntent.send(context, 0, fillInIntent);
            return ReplyResult.SUCCESS;
        } catch (PendingIntent.CanceledException e) {
            return ReplyResult.FAILED_INTENT;
        } catch (Throwable e) {
            return ReplyResult.FAILED_INTENT;
        }
    }

    public static boolean sendReply(Context context, NotificationReplyCapability capability, String replyText) {
        return sendBoundReply(context, capability, replyText) == ReplyResult.SUCCESS;
    }

    public static String resolveSourceName(String packageName) {
        if ("com.whatsapp".equals(packageName) || "com.whatsapp.w4b".equals(packageName)) return "WhatsApp";
        if ("org.telegram.messenger".equals(packageName)) return "Telegram";
        if ("com.google.android.gm".equals(packageName)) return "Gmail";
        if ("com.google.android.calendar".equals(packageName)) return "Calendar";
        if ("com.google.android.apps.messaging".equals(packageName) || "com.android.messaging".equals(packageName)) return "Messages";
        return "Notification";
    }

    public static String formatDestinationDisplay(String source, NotificationReplyCapability cap) {
        if (cap == null) return source != null && !source.isEmpty() ? source : "the latest notification";
        return formatDestinationDisplay(source, cap.senderOrTitle, cap.conversationTitle, cap.senderPerson);
    }

    public static String formatDestinationDisplay(String source, String senderOrTitle, String conversationTitle, String senderPerson) {
        String src = (source != null && !source.isEmpty()) ? source : "Notification";
        String sOrT = senderOrTitle != null ? senderOrTitle.trim() : "";
        String convTitle = conversationTitle != null ? conversationTitle.trim() : "";
        String senderP = senderPerson != null ? senderPerson.trim() : "";

        if (!convTitle.isEmpty() && !senderP.isEmpty() && !convTitle.equalsIgnoreCase(senderP)) {
            return src + " (" + convTitle + " - " + senderP + ")";
        }
        String recipient = !sOrT.isEmpty() ? sOrT : (!senderP.isEmpty() ? senderP : convTitle);
        if (recipient.isEmpty() || recipient.equalsIgnoreCase(src)) {
            return src;
        }
        return src + " (" + recipient + ")";
    }

    public static boolean validateTarget(String requestedTarget, String packageName, String sourceName, String senderOrTitle) {
        return validateTarget(requestedTarget, packageName, sourceName, senderOrTitle, "", "");
    }

    public static boolean validateTarget(String requestedTarget, String packageName, String sourceName,
                                          String senderOrTitle, String conversationTitle, String senderPerson) {
        if (requestedTarget == null) return true;
        String target = requestedTarget.trim();
        if (target.isEmpty()) return true;

        String normTarget = target.toLowerCase(Locale.US);
        if (normTarget.equals("latest notification") || normTarget.equals("latest") ||
                normTarget.equals("latest message") || normTarget.equals("the latest notification") ||
                normTarget.equals("notification")) {
            return true;
        }

        String pkg = packageName != null ? packageName.trim() : "";
        String normSrc = sourceName != null ? sourceName.trim().toLowerCase(Locale.US) : "";

        // 1. Strict app matching against canonical source names and aliases (no generic substring on package name)
        if (!normSrc.isEmpty() && normTarget.equals(normSrc)) {
            return true;
        }

        // WhatsApp aliases
        if (normTarget.equals("whatsapp") || normTarget.equals("wa")) {
            if ("com.whatsapp".equals(pkg) || "com.whatsapp.w4b".equals(pkg)) return true;
        }
        if (normTarget.equals("whatsapp business") || normTarget.equals("w4b")) {
            if ("com.whatsapp.w4b".equals(pkg) || "com.whatsapp".equals(pkg)) return true;
        }

        // Telegram aliases
        if (normTarget.equals("telegram") || normTarget.equals("tg")) {
            if ("org.telegram.messenger".equals(pkg)) return true;
        }

        // Gmail aliases
        if (normTarget.equals("gmail") || normTarget.equals("google mail") || normTarget.equals("mail") || normTarget.equals("email")) {
            if ("com.google.android.gm".equals(pkg)) return true;
        }

        // Messages aliases
        if (normTarget.equals("messages") || normTarget.equals("message") || normTarget.equals("sms") ||
                normTarget.equals("google messages") || normTarget.equals("text")) {
            if ("com.google.android.apps.messaging".equals(pkg) || "com.android.messaging".equals(pkg)) return true;
        }

        // Calendar aliases
        if (normTarget.equals("calendar") || normTarget.equals("google calendar")) {
            if ("com.google.android.calendar".equals(pkg)) return true;
        }

        // 2. Sender matching: exact full match or whole-token match against senderOrTitle, conversationTitle, senderPerson
        if (matchSender(normTarget, senderOrTitle)) return true;
        if (matchSender(normTarget, conversationTitle)) return true;
        if (matchSender(normTarget, senderPerson)) return true;

        return false;
    }

    private static boolean matchSender(String normTarget, String candidate) {
        if (candidate == null) return false;
        String normCandidate = candidate.trim().toLowerCase(Locale.US);
        if (normCandidate.isEmpty()) return false;
        if (normCandidate.equals(normTarget)) return true;
        String[] tokens = normCandidate.split("\\s+");
        for (String token : tokens) {
            if (token.equals(normTarget)) return true;
        }
        return false;
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
        public final String conversationTitle;
        public final String senderPerson;

        public NotificationReplyCapability(String key, String packageName, String senderOrTitle,
                                           PendingIntent pendingIntent, RemoteInput remoteInput) {
            this(key, packageName, senderOrTitle, pendingIntent, remoteInput, "", "");
        }

        public NotificationReplyCapability(String key, String packageName, String senderOrTitle,
                                           PendingIntent pendingIntent, RemoteInput remoteInput,
                                           String conversationTitle, String senderPerson) {
            this.key = key != null ? key : "";
            this.packageName = packageName != null ? packageName : "";
            this.senderOrTitle = senderOrTitle != null ? senderOrTitle : "";
            this.pendingIntent = pendingIntent;
            this.remoteInput = remoteInput;
            this.conversationTitle = conversationTitle != null ? conversationTitle : "";
            this.senderPerson = senderPerson != null ? senderPerson : "";
        }
    }
}
