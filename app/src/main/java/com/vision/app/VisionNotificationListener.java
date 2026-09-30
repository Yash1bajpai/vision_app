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
    public enum NotificationStatus {
        NO_NOTIFICATION_YET,
        ACTIVE_NOTIFICATION,
        NOTIFICATION_REMOVED
    }

    public static final class ListenerState {
        public final NotificationStatus status;
        public final String key;
        public final String packageName;
        public final long postTime;
        public final boolean hasReplyCapability;
        public final long sequenceNumber;

        public ListenerState(NotificationStatus status, String key, String packageName,
                             long postTime, boolean hasReplyCapability, long sequenceNumber) {
            this.status = status != null ? status : NotificationStatus.NO_NOTIFICATION_YET;
            this.key = key != null ? key : "";
            this.packageName = packageName != null ? packageName : "";
            this.postTime = postTime;
            this.hasReplyCapability = hasReplyCapability;
            this.sequenceNumber = sequenceNumber;
        }

        public boolean isNotificationActive() {
            return status == NotificationStatus.ACTIVE_NOTIFICATION;
        }

        public boolean isNotificationRemoved() {
            return status == NotificationStatus.NOTIFICATION_REMOVED;
        }

        public boolean hasNoNotification() {
            return status == NotificationStatus.NO_NOTIFICATION_YET;
        }
    }

    private static volatile NotificationSnapshot latestNotification;
    private static volatile NotificationReplyCapability latestReplyCapability;
    private static volatile ListenerState currentListenerState =
            new ListenerState(NotificationStatus.NO_NOTIFICATION_YET, "", "", 0L, false, 0L);
    private static long sequenceCounter = 0L;

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

    /**
     * Deterministic ordering policy for incoming notifications:
     * - Newer postTime (> currentPostTime) replaces current.
     * - Older postTime (< currentPostTime) is rejected as out-of-order.
     * - Equal postTime (== currentPostTime):
     *     - Same key: accepted as in-place notification update.
     *     - Different key: deterministic lexicographical tie-break (newKey.compareTo(currentKey) >= 0).
     */
    public static boolean shouldAcceptNotificationOrder(long newPostTime, String newKey, long currentPostTime, String currentKey) {
        if (currentKey == null || currentKey.isEmpty()) {
            return true;
        }
        if (newPostTime > currentPostTime) {
            return true;
        }
        if (newPostTime < currentPostTime) {
            return false;
        }
        if (newKey != null && newKey.equals(currentKey)) {
            return true;
        }
        if (newKey != null) {
            return newKey.compareTo(currentKey) >= 0;
        }
        return true;
    }

    /**
     * Evaluates RemoteInput eligibility.
     * Excludes data-only RemoteInputs where allowFreeForm is false and choices are empty.
     */
    public static boolean isRemoteInputEligible(boolean allowFreeForm, boolean hasChoices, String resultKey) {
        if (resultKey == null || resultKey.trim().isEmpty()) {
            return false;
        }
        return allowFreeForm || hasChoices;
    }

    /**
     * Scores an action candidate for reply suitability:
     * - Ineligible / no intent: -1
     * - Standard text-capable RemoteInput action: 1
     * - SEMANTIC_ACTION_REPLY with text-capable RemoteInput: 2 (preferred)
     */
    public static int scoreActionCandidate(boolean hasActionIntent, boolean isSemanticReply, boolean hasEligibleRemoteInput) {
        if (!hasActionIntent || !hasEligibleRemoteInput) {
            return -1;
        }
        return isSemanticReply ? 2 : 1;
    }

    public static final class ReplyActionCandidate {
        public final String actionTitle;
        public final boolean hasActionIntent;
        public final boolean isSemanticReply;
        public final boolean allowFreeFormInput;
        public final boolean hasChoices;
        public final String resultKey;

        public ReplyActionCandidate(String actionTitle, boolean hasActionIntent, boolean isSemanticReply,
                                    boolean allowFreeFormInput, boolean hasChoices, String resultKey) {
            this.actionTitle = actionTitle != null ? actionTitle : "";
            this.hasActionIntent = hasActionIntent;
            this.isSemanticReply = isSemanticReply;
            this.allowFreeFormInput = allowFreeFormInput;
            this.hasChoices = hasChoices;
            this.resultKey = resultKey != null ? resultKey : "";
        }

        public boolean isEligible() {
            return hasActionIntent && isRemoteInputEligible(allowFreeFormInput, hasChoices, resultKey);
        }

        public int getScore() {
            return scoreActionCandidate(hasActionIntent, isSemanticReply, isRemoteInputEligible(allowFreeFormInput, hasChoices, resultKey));
        }
    }

    public static ReplyActionCandidate selectBestReplyActionCandidate(List<ReplyActionCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        ReplyActionCandidate best = null;
        int bestScore = -1;
        for (ReplyActionCandidate candidate : candidates) {
            if (candidate == null) continue;
            int score = candidate.getScore();
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
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

            // 1. API 30+ MessagingStyle helper; older devices use the text fallbacks below.
            try {
                Parcelable[] msgBundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
                if (android.os.Build.VERSION.SDK_INT >= 30
                        && msgBundles != null && msgBundles.length > 0) {
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

        // Extract best RemoteInput reply action if present
        NotificationReplyCapability replyCap = null;
        if (notification.actions != null && notification.actions.length > 0) {
            Notification.Action selectedAction = null;
            RemoteInput selectedRemoteInput = null;
            int highestScore = -1;

            for (Notification.Action act : notification.actions) {
                if (act == null || act.actionIntent == null) continue;
                RemoteInput[] remoteInputs = act.getRemoteInputs();
                if (remoteInputs == null || remoteInputs.length == 0) continue;

                boolean isSemanticReply = false;
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    try {
                        isSemanticReply = (act.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY);
                    } catch (Throwable ignored) { }
                }

                for (RemoteInput ri : remoteInputs) {
                    if (ri == null) continue;
                    String resultKey = ri.getResultKey();
                    if (resultKey == null || resultKey.trim().isEmpty()) continue;

                    CharSequence[] choices = ri.getChoices();
                    boolean hasChoices = (choices != null && choices.length > 0);
                    boolean allowFreeForm = ri.getAllowFreeFormInput();

                    if (isRemoteInputEligible(allowFreeForm, hasChoices, resultKey)) {
                        int score = scoreActionCandidate(true, isSemanticReply, true);
                        if (score > highestScore) {
                            highestScore = score;
                            selectedAction = act;
                            selectedRemoteInput = ri;
                        }
                        break;
                    }
                }
                if (highestScore == 2) {
                    break;
                }
            }

            if (selectedAction != null && selectedRemoteInput != null) {
                String senderOrTitle = !title.isEmpty() ? title : (!senderPerson.isEmpty() ? senderPerson : conversationTitle);
                replyCap = new NotificationReplyCapability(
                        key,
                        sbn.getPackageName(),
                        senderOrTitle,
                        selectedAction.actionIntent,
                        selectedRemoteInput,
                        conversationTitle,
                        senderPerson
                );
            }
        }

        processPostedNotification(key, sbn.getPackageName(), title, text, sbn.getPostTime(), notification.flags, replyCap);
    }

    /** A disconnected listener cannot prove a cached destination is still active. */
    @Override
    public void onListenerDisconnected() {
        clearLatestNotification();
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        clearLatestNotification();
        super.onDestroy();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        String removeKey = sbn.getKey() != null ? sbn.getKey() : (sbn.getPackageName() + ":" + sbn.getId());
        processRemovedNotification(removeKey);
    }

    /**
     * Deterministic production processing boundary for posted notifications.
     * Takes immutable extracted notification metadata and performs synchronized filtering,
     * ordering validation, and in-memory state mutation.
     *
     * Unsupported packages return false before processing without mutating state.
     * Filtered notifications (e.g. group summaries/ongoing without reply) return false without erasing active state.
     * Older postTime notifications are rejected.
     * Equal timestamps are accepted for the same key, or resolved lexicographically.
     *
     * @return true if the notification was accepted and updated active state; false otherwise.
     */
    public static boolean processPostedNotification(String key, String packageName, String title, String text,
                                                    long postTime, int flags, NotificationReplyCapability replyCap) {
        if (packageName == null || !isSupported(packageName)) {
            return false;
        }
        String safeKey = (key != null && !key.isEmpty()) ? key : (packageName + ":0");
        // Fail closed if a capability is attached to a different notification or app.
        if (replyCap != null && (!safeKey.equals(replyCap.key)
                || !packageName.equals(replyCap.packageName))) {
            return false;
        }
        synchronized (VisionNotificationListener.class) {
            if (shouldIgnoreNotification(flags, replyCap != null)) {
                return false;
            }
            if (latestNotification != null) {
                if (!shouldAcceptNotificationOrder(postTime, safeKey, latestNotification.postTime, latestNotification.key)) {
                    return false;
                }
            }
            sequenceCounter++;
            latestNotification = new NotificationSnapshot(safeKey, packageName, title, text, postTime);
            latestReplyCapability = replyCap;
            currentListenerState = new ListenerState(
                    NotificationStatus.ACTIVE_NOTIFICATION,
                    safeKey,
                    packageName,
                    postTime,
                    replyCap != null,
                    sequenceCounter
            );
            return true;
        }
    }

    /**
     * Deterministic production processing boundary for removed notifications.
     * Clears active state only when removeKey matches the currently active notification key.
     * Non-matching keys return false and preserve the existing active notification.
     *
     * @return true if the active notification or capability was cleared; false if non-matching.
     */
    public static boolean processRemovedNotification(String removeKey) {
        if (removeKey == null || removeKey.isEmpty()) {
            return false;
        }
        synchronized (VisionNotificationListener.class) {
            if (latestNotification != null && latestNotification.key.equals(removeKey)) {
                sequenceCounter++;
                currentListenerState = new ListenerState(
                        NotificationStatus.NOTIFICATION_REMOVED,
                        latestNotification.key,
                        latestNotification.packageName,
                        latestNotification.postTime,
                        false,
                        sequenceCounter
                );
                latestNotification = null;
                latestReplyCapability = null;
                return true;
            } else if (latestReplyCapability != null && latestReplyCapability.key.equals(removeKey)) {
                sequenceCounter++;
                currentListenerState = new ListenerState(
                        latestNotification != null ? NotificationStatus.ACTIVE_NOTIFICATION : currentListenerState.status,
                        latestNotification != null ? latestNotification.key : currentListenerState.key,
                        latestNotification != null ? latestNotification.packageName : currentListenerState.packageName,
                        latestNotification != null ? latestNotification.postTime : currentListenerState.postTime,
                        false,
                        sequenceCounter
                );
                latestReplyCapability = null;
                return true;
            }
            return false;
        }
    }

    public static synchronized NotificationSnapshot getLatestNotification() {
        return latestNotification;
    }

    public static synchronized NotificationReplyCapability getLatestReplyCapability() {
        return latestReplyCapability;
    }

    public static synchronized ListenerState getListenerState() {
        return currentListenerState;
    }

    public static synchronized void setListenerStateForTesting(ListenerState state) {
        currentListenerState = state != null ? state : new ListenerState(NotificationStatus.NO_NOTIFICATION_YET, "", "", 0L, false, 0L);
    }

    public static synchronized void clearLatestNotification() {
        latestNotification = null;
        latestReplyCapability = null;
        sequenceCounter++;
        currentListenerState = new ListenerState(NotificationStatus.NO_NOTIFICATION_YET, "", "", 0L, false, sequenceCounter);
    }

    public static synchronized void setLatestNotificationForTesting(NotificationSnapshot snapshot) {
        latestNotification = snapshot;
        sequenceCounter++;
        if (snapshot == null) {
            currentListenerState = new ListenerState(NotificationStatus.NO_NOTIFICATION_YET, "", "", 0L, false, sequenceCounter);
        } else {
            currentListenerState = new ListenerState(
                    NotificationStatus.ACTIVE_NOTIFICATION,
                    snapshot.key,
                    snapshot.packageName,
                    snapshot.postTime,
                    latestReplyCapability != null,
                    sequenceCounter
            );
        }
    }

    public static synchronized void setLatestReplyCapabilityForTesting(NotificationReplyCapability capability) {
        latestReplyCapability = capability;
        sequenceCounter++;
        if (latestNotification != null) {
            currentListenerState = new ListenerState(
                    NotificationStatus.ACTIVE_NOTIFICATION,
                    latestNotification.key,
                    latestNotification.packageName,
                    latestNotification.postTime,
                    capability != null,
                    sequenceCounter
            );
        } else {
            currentListenerState = new ListenerState(
                    currentListenerState.status,
                    currentListenerState.key,
                    currentListenerState.packageName,
                    currentListenerState.postTime,
                    capability != null,
                    sequenceCounter
            );
        }
    }

    public static synchronized boolean isCapabilityActive(NotificationReplyCapability capability) {
        if (capability == null || capability.key.isEmpty()) {
            return false;
        }
        NotificationReplyCapability current = latestReplyCapability;
        return current == capability && current.key.equals(capability.key);
    }

    public static ReplyResult sendBoundReply(Context context, NotificationReplyCapability boundCapability, String replyText) {
        if (boundCapability == null || boundCapability.key.isEmpty()) {
            return ReplyResult.INVALID_ARGUMENTS;
        }
        synchronized (VisionNotificationListener.class) {
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
                // Note: External IPC dispatch is intentionally performed within the synchronized lock.
                // Atomic validation-to-dispatch is required for this in-memory capability model to prevent
                // a stale-dispatch window if onNotificationRemoved concurrently clears or replaces the capability.
                boundCapability.pendingIntent.send(context, 0, fillInIntent);
                // A confirmation authorizes one dispatch, not repeated use of this capability.
                // Android accepted the intent; this does not prove message delivery.
                latestReplyCapability = null;
                sequenceCounter++;
                currentListenerState = new ListenerState(currentListenerState.status,
                        currentListenerState.key, currentListenerState.packageName,
                        currentListenerState.postTime, false, sequenceCounter);
                return ReplyResult.SUCCESS;
            } catch (PendingIntent.CanceledException e) {
                return ReplyResult.FAILED_INTENT;
            } catch (Throwable e) {
                return ReplyResult.FAILED_INTENT;
            }
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
