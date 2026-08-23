package com.vision.app;

public class VisionAppTest {
    public static void main(String[] args) {
        int passed = 0;
        int failed = 0;

        System.out.println("Running Vision deterministic test suite (Phases 1-5)...");

        // Test 1: Null and empty parser inputs
        try {
            assertCondition(VisionActionParser.parse(null).type == VisionAction.Type.UNKNOWN, "parse(null) == UNKNOWN");
            assertCondition(VisionActionParser.parse("").type == VisionAction.Type.UNKNOWN, "parse(\"\") == UNKNOWN");
            assertCondition(VisionActionParser.parse("   \n\t  ").type == VisionAction.Type.UNKNOWN, "parse(whitespace) == UNKNOWN");
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 1 FAILED: " + t.getMessage());
            failed++;
        }

        // Test 2: Notification reading requests
        try {
            String[] notifRequests = {
                    "read notification",
                    "read notifications",
                    "read my notification",
                    "read latest notification",
                    "read the newest notification",
                    "show notification",
                    "show my latest message",
                    "check notifications",
                    "check messages",
                    "get latest notification",
                    "see latest notification",
                    "please read my latest notification",
                    "read notification now"
            };
            for (String req : notifRequests) {
                VisionAction action = VisionActionParser.parse(req);
                assertCondition(action.type == VisionAction.Type.READ_NOTIFICATION, "Expected READ_NOTIFICATION for: " + req);
            }
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 2 FAILED: " + t.getMessage());
            failed++;
        }

        // Test 3: App launching requests
        try {
            assertAppLaunch("open whatsapp", "WhatsApp");
            assertAppLaunch("open whatsapp business", "WhatsApp");
            assertAppLaunch("launch telegram", "Telegram");
            assertAppLaunch("start gmail", "Gmail");
            assertAppLaunch("open messages", "Messages");
            assertAppLaunch("open calendar", "Calendar");
            assertAppLaunch("launch calendar", "Calendar");
            assertAppLaunch("open sms", "Messages");
            assertAppLaunch("open whatsapp now", "WhatsApp");
            assertAppLaunch("please open telegram", "Telegram");
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 3 FAILED: " + t.getMessage());
            failed++;
        }

        // Test 4: Notification reply requests (Phase 5 MVP)
        try {
            assertReply("reply I will be there soon", "latest notification", "I will be there soon");
            assertReply("reply: Sounds great!", "latest notification", "Sounds great!");
            assertReply("reply to WhatsApp: On my way!", "WhatsApp", "On my way!");
            assertReply("reply to Alice: Yes, confirmed", "Alice", "Yes, confirmed");
            assertReply("send reply OK, see you then", "latest notification", "OK, see you then");
            assertReply("answer Perfect, thank you", "latest notification", "Perfect, thank you");
            assertReply("please reply: Will do.", "latest notification", "Will do.");

            // Empty reply text returns UNKNOWN
            assertCondition(VisionActionParser.parse("reply").type == VisionAction.Type.UNKNOWN, "reply without text -> UNKNOWN");
            assertCondition(VisionActionParser.parse("reply:").type == VisionAction.Type.UNKNOWN, "reply: without text -> UNKNOWN");
            assertCondition(VisionActionParser.parse("send reply").type == VisionAction.Type.UNKNOWN, "send reply without text -> UNKNOWN");
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 4 FAILED: " + t.getMessage());
            failed++;
        }

        // Test 5: Unrecognized requests
        try {
            String[] unknownRequests = {
                    "hello",
                    "play music",
                    "open random app",
                    "search google",
                    "take a photo"
            };
            for (String req : unknownRequests) {
                VisionAction action = VisionActionParser.parse(req);
                assertCondition(action.type == VisionAction.Type.UNKNOWN, "Expected UNKNOWN for: " + req);
            }
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 5 FAILED: " + t.getMessage());
            failed++;
        }

        // Test 6: VisionAction state machine and labels
        try {
            VisionAction a1 = new VisionAction(VisionAction.Type.READ_NOTIFICATION, "read notification", "latest notification");
            assertCondition(a1.state == VisionAction.State.PROPOSED, "initial state is PROPOSED");
            assertCondition(a1.label().equals("Read the latest supported notification"), "label matches");
            a1.state = VisionAction.State.APPROVED;
            assertCondition(a1.state == VisionAction.State.APPROVED, "approved state");
            a1.state = VisionAction.State.RUNNING;
            assertCondition(a1.state == VisionAction.State.RUNNING, "running state");
            a1.state = VisionAction.State.SUCCEEDED;
            assertCondition(a1.state == VisionAction.State.SUCCEEDED, "succeeded state");

            VisionAction a2 = new VisionAction(VisionAction.Type.OPEN_APP, "open telegram", "Telegram");
            assertCondition(a2.label().equals("Open Telegram"), "open app label matches");

            VisionAction a3 = new VisionAction(VisionAction.Type.REPLY_NOTIFICATION, "reply OK", "WhatsApp", "OK");
            assertCondition(a3.label().equals("Reply to WhatsApp"), "reply label matches");
            assertCondition(a3.replyText.equals("OK"), "replyText matches");

            VisionAction a4 = new VisionAction(VisionAction.Type.REPLY_NOTIFICATION, "reply OK", "", "OK");
            assertCondition(a4.label().equals("Reply to the latest notification"), "reply label defaults to latest notification");

            VisionAction aNull = new VisionAction(null, null, null, null);
            assertCondition(aNull.type == VisionAction.Type.UNKNOWN, "null type defaults to UNKNOWN");
            assertCondition(aNull.request.isEmpty(), "null request defaults to empty");
            assertCondition(aNull.target.isEmpty(), "null target defaults to empty");
            assertCondition(aNull.replyText.isEmpty(), "null replyText defaults to empty");
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 6 FAILED: " + t.getMessage());
            failed++;
        }

        // Test 7: Notification package support allowlist
        try {
            assertCondition(VisionNotificationListener.isSupported("com.google.android.gm"), "Gmail supported");
            assertCondition(VisionNotificationListener.isSupported("com.whatsapp"), "WhatsApp supported");
            assertCondition(VisionNotificationListener.isSupported("com.whatsapp.w4b"), "WhatsApp Business supported");
            assertCondition(VisionNotificationListener.isSupported("org.telegram.messenger"), "Telegram supported");
            assertCondition(VisionNotificationListener.isSupported("com.google.android.apps.messaging"), "Google Messages supported");
            assertCondition(VisionNotificationListener.isSupported("com.android.messaging"), "AOSP Messages supported");
            assertCondition(VisionNotificationListener.isSupported("com.google.android.calendar"), "Calendar supported");

            assertCondition(!VisionNotificationListener.isSupported("com.facebook.katana"), "Facebook rejected");
            assertCondition(!VisionNotificationListener.isSupported("com.instagram.android"), "Instagram rejected");
            assertCondition(!VisionNotificationListener.isSupported(null), "null rejected");
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 7 FAILED: " + t.getMessage());
            failed++;
        }

        // Test 8: Notification snapshot creation and key matching
        try {
            VisionNotificationListener.NotificationSnapshot s1 =
                    new VisionNotificationListener.NotificationSnapshot("key1", "com.whatsapp", "Alice", "Hello there", 1000L);
            assertCondition(s1.key.equals("key1"), "key matches");
            assertCondition(s1.packageName.equals("com.whatsapp"), "package matches");
            assertCondition(s1.title.equals("Alice"), "title matches");
            assertCondition(s1.text.equals("Hello there"), "text matches");
            assertCondition(s1.postTime == 1000L, "postTime matches");

            VisionNotificationListener.NotificationSnapshot sNull =
                    new VisionNotificationListener.NotificationSnapshot(null, null, null, null, 0L);
            assertCondition(sNull.key.isEmpty(), "null key defaults to empty");
            assertCondition(sNull.packageName.isEmpty(), "null packageName defaults to empty");
            assertCondition(sNull.title.isEmpty(), "null title defaults to empty");
            assertCondition(sNull.text.isEmpty(), "null text defaults to empty");
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 8 FAILED: " + t.getMessage());
            failed++;
        }

        // Test 9: NotificationReplyCapability structure and safety
        try {
            VisionNotificationListener.NotificationReplyCapability cap =
                    new VisionNotificationListener.NotificationReplyCapability("key2", "org.telegram.messenger", "Bob", null, null);
            assertCondition(cap.key.equals("key2"), "cap key matches");
            assertCondition(cap.packageName.equals("org.telegram.messenger"), "cap package matches");
            assertCondition(cap.senderOrTitle.equals("Bob"), "cap sender matches");
            assertCondition(cap.pendingIntent == null, "cap pendingIntent is null");
            assertCondition(cap.remoteInput == null, "cap remoteInput is null");

            // sendReply returns false when capability or intent is null
            assertCondition(!VisionNotificationListener.sendReply(null, null, "test"), "sendReply(null, null) returns false");
            assertCondition(!VisionNotificationListener.sendReply(null, cap, "test"), "sendReply(null, cap) returns false");
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 9 FAILED: " + t.getMessage());
            failed++;
        }

        // Test 10: Target validation and conservative mismatch rejection
        try {
            // Explicit contact target matching
            assertCondition(VisionNotificationListener.validateTarget("Alice", "com.whatsapp", "WhatsApp", "Alice"), "Exact contact match");
            assertCondition(VisionNotificationListener.validateTarget("Alice", "com.whatsapp", "WhatsApp", "Alice Smith"), "Substring contact match");
            assertCondition(VisionNotificationListener.validateTarget("Alice Smith", "com.whatsapp", "WhatsApp", "Alice"), "Superset contact match");
            assertCondition(VisionNotificationListener.validateTarget("alice", "com.whatsapp", "WhatsApp", "Alice"), "Case-insensitive contact match");

            // Explicit contact target mismatch
            assertCondition(!VisionNotificationListener.validateTarget("Alice", "com.whatsapp", "WhatsApp", "Bob"), "Mismatch sender rejected");
            assertCondition(!VisionNotificationListener.validateTarget("Alice", "com.whatsapp", "WhatsApp", ""), "Empty sender with explicit target rejected");
            assertCondition(!VisionNotificationListener.validateTarget("Alice", "com.whatsapp", "WhatsApp", null), "Null sender with explicit target rejected");

            // Explicit app target matching
            assertCondition(VisionNotificationListener.validateTarget("WhatsApp", "com.whatsapp", "WhatsApp", "Bob"), "WhatsApp app match");
            assertCondition(VisionNotificationListener.validateTarget("WhatsApp Business", "com.whatsapp.w4b", "WhatsApp", "Bob"), "WhatsApp Business app match");
            assertCondition(VisionNotificationListener.validateTarget("Telegram", "org.telegram.messenger", "Telegram", "Bob"), "Telegram app match");
            assertCondition(VisionNotificationListener.validateTarget("Gmail", "com.google.android.gm", "Gmail", "Bob"), "Gmail app match");
            assertCondition(VisionNotificationListener.validateTarget("Messages", "com.google.android.apps.messaging", "Messages", "Bob"), "Messages app match");
            assertCondition(VisionNotificationListener.validateTarget("Calendar", "com.google.android.calendar", "Calendar", "Bob"), "Calendar app match");

            // Explicit app target mismatch
            assertCondition(!VisionNotificationListener.validateTarget("Telegram", "com.whatsapp", "WhatsApp", "Bob"), "App mismatch (Telegram vs WhatsApp) rejected");
            assertCondition(!VisionNotificationListener.validateTarget("WhatsApp", "org.telegram.messenger", "Telegram", "Bob"), "App mismatch (WhatsApp vs Telegram) rejected");
            assertCondition(!VisionNotificationListener.validateTarget("Gmail", "com.google.android.apps.messaging", "Messages", "Bob"), "App mismatch (Gmail vs Messages) rejected");

            // Unspecified / default targets
            assertCondition(VisionNotificationListener.validateTarget("latest notification", "com.whatsapp", "WhatsApp", "Bob"), "Default target accepted");
            assertCondition(VisionNotificationListener.validateTarget("latest", "org.telegram.messenger", "Telegram", "Alice"), "'latest' accepted");
            assertCondition(VisionNotificationListener.validateTarget("", "com.whatsapp", "WhatsApp", "Bob"), "Empty target accepted");
            assertCondition(VisionNotificationListener.validateTarget(null, "com.whatsapp", "WhatsApp", "Bob"), "Null target accepted");
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 10 FAILED: " + t.getMessage());
            failed++;
        }

        // Test 11: Notification replacement between proposal and approval (TOCTOU race prevention)
        try {
            VisionNotificationListener.NotificationReplyCapability capAlice =
                    new VisionNotificationListener.NotificationReplyCapability("key_alice", "com.whatsapp", "Alice", null, null);
            VisionNotificationListener.NotificationReplyCapability capBob =
                    new VisionNotificationListener.NotificationReplyCapability("key_bob", "com.whatsapp", "Bob", null, null);

            // Proposal binds capAlice
            VisionNotificationListener.setLatestReplyCapabilityForTesting(capAlice);
            VisionNotificationListener.NotificationReplyCapability boundCap = VisionNotificationListener.getLatestReplyCapability();
            assertCondition(boundCap == capAlice, "Bound capability is capAlice at proposal");

            // Incoming notification from Bob replaces active capability
            VisionNotificationListener.setLatestReplyCapabilityForTesting(capBob);

            // User taps Allow on dialog that was bound to capAlice
            VisionNotificationListener.ReplyResult result = VisionNotificationListener.sendBoundReply(null, boundCap, "Approved reply text");
            assertCondition(result == VisionNotificationListener.ReplyResult.STALE_OR_REMOVED, "TOCTOU replaced capability rejected as STALE_OR_REMOVED");

            // Same chat updated with new capability instance
            VisionNotificationListener.NotificationReplyCapability capAliceNew =
                    new VisionNotificationListener.NotificationReplyCapability("key_alice", "com.whatsapp", "Alice", null, null);
            VisionNotificationListener.setLatestReplyCapabilityForTesting(capAliceNew);
            VisionNotificationListener.ReplyResult resultNew = VisionNotificationListener.sendBoundReply(null, boundCap, "Approved reply text");
            assertCondition(resultNew == VisionNotificationListener.ReplyResult.STALE_OR_REMOVED, "Replaced instance rejected as STALE_OR_REMOVED");
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 11 FAILED: " + t.getMessage());
            failed++;
        }

        // Test 12: Notification removal / stale key dispatch prevention
        try {
            VisionNotificationListener.NotificationReplyCapability cap1 =
                    new VisionNotificationListener.NotificationReplyCapability("key_dismiss", "org.telegram.messenger", "Charlie", null, null);

            // Proposal binds cap1
            VisionNotificationListener.setLatestReplyCapabilityForTesting(cap1);
            VisionNotificationListener.NotificationReplyCapability boundCap = VisionNotificationListener.getLatestReplyCapability();

            // Notification dismissed/cleared
            VisionNotificationListener.clearLatestNotification();

            // Approval attempts dispatch on cleared capability
            VisionNotificationListener.ReplyResult result = VisionNotificationListener.sendBoundReply(null, boundCap, "Reply text");
            assertCondition(result == VisionNotificationListener.ReplyResult.STALE_OR_REMOVED, "Dismissed notification rejected as STALE_OR_REMOVED");

            // Invalid arguments test
            assertCondition(VisionNotificationListener.sendBoundReply(null, null, "text") == VisionNotificationListener.ReplyResult.INVALID_ARGUMENTS, "null cap returns INVALID_ARGUMENTS");
            VisionNotificationListener.NotificationReplyCapability emptyKeyCap =
                    new VisionNotificationListener.NotificationReplyCapability("", "com.whatsapp", "User", null, null);
            assertCondition(VisionNotificationListener.sendBoundReply(null, emptyKeyCap, "text") == VisionNotificationListener.ReplyResult.INVALID_ARGUMENTS, "empty key cap returns INVALID_ARGUMENTS");
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 12 FAILED: " + t.getMessage());
            failed++;
        }

        // Test 13: Capability active match verification and bound lifecycle flow
        try {
            VisionNotificationListener.NotificationReplyCapability cap1 =
                    new VisionNotificationListener.NotificationReplyCapability("key_active", "com.whatsapp", "David", null, null);
            VisionNotificationListener.NotificationReplyCapability cap2 =
                    new VisionNotificationListener.NotificationReplyCapability("key_other", "com.whatsapp", "Eve", null, null);

            VisionNotificationListener.clearLatestNotification();
            assertCondition(!VisionNotificationListener.isCapabilityActive(cap1), "cap1 is inactive when empty");

            VisionNotificationListener.setLatestReplyCapabilityForTesting(cap1);
            assertCondition(VisionNotificationListener.isCapabilityActive(cap1), "cap1 is active when set");
            assertCondition(!VisionNotificationListener.isCapabilityActive(cap2), "cap2 is inactive");
            assertCondition(!VisionNotificationListener.isCapabilityActive(null), "null is inactive");

            // Source name resolution
            assertCondition(VisionNotificationListener.resolveSourceName("com.whatsapp").equals("WhatsApp"), "com.whatsapp -> WhatsApp");
            assertCondition(VisionNotificationListener.resolveSourceName("com.whatsapp.w4b").equals("WhatsApp"), "com.whatsapp.w4b -> WhatsApp");
            assertCondition(VisionNotificationListener.resolveSourceName("org.telegram.messenger").equals("Telegram"), "org.telegram.messenger -> Telegram");
            assertCondition(VisionNotificationListener.resolveSourceName("com.google.android.gm").equals("Gmail"), "com.google.android.gm -> Gmail");
            assertCondition(VisionNotificationListener.resolveSourceName("com.google.android.calendar").equals("Calendar"), "com.google.android.calendar -> Calendar");
            assertCondition(VisionNotificationListener.resolveSourceName("com.google.android.apps.messaging").equals("Messages"), "Google Messages -> Messages");
            assertCondition(VisionNotificationListener.resolveSourceName("com.android.messaging").equals("Messages"), "AOSP Messages -> Messages");
            assertCondition(VisionNotificationListener.resolveSourceName("unknown.pkg").equals("Notification"), "unknown.pkg -> Notification");
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 13 FAILED: " + t.getMessage());
            failed++;
        }

        System.out.println("Tests passed: " + passed + ", failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void assertReply(String command, String expectedTarget, String expectedText) {
        VisionAction action = VisionActionParser.parse(command);
        assertCondition(action.type == VisionAction.Type.REPLY_NOTIFICATION, "Expected REPLY_NOTIFICATION for: " + command);
        assertCondition(action.target.equals(expectedTarget), "Expected target " + expectedTarget + " but got " + action.target + " for: " + command);
        assertCondition(action.replyText.equals(expectedText), "Expected replyText \"" + expectedText + "\" but got \"" + action.replyText + "\" for: " + command);
    }

    private static void assertAppLaunch(String command, String expectedTarget) {
        VisionAction action = VisionActionParser.parse(command);
        assertCondition(action.type == VisionAction.Type.OPEN_APP, "Expected OPEN_APP for: " + command);
        assertCondition(action.target.equals(expectedTarget), "Expected target " + expectedTarget + " but got " + action.target + " for: " + command);
    }

    private static void assertCondition(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Assertion failed: " + message);
        }
    }
}
