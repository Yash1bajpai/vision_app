package com.vision.app;

import android.app.Notification;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class VisionAppTest {

    // Test 1: Null and empty parser inputs
    @Test
    public void test01_nullAndEmptyParserInputs() {
        assertEquals("parse(null) == UNKNOWN", VisionAction.Type.UNKNOWN, VisionActionParser.parse(null).type);
        assertEquals("parse(\"\") == UNKNOWN", VisionAction.Type.UNKNOWN, VisionActionParser.parse("").type);
        assertEquals("parse(whitespace) == UNKNOWN", VisionAction.Type.UNKNOWN, VisionActionParser.parse("   \n\t  ").type);
    }

    // Test 2: Notification reading requests (including F6 reordering & gerunds)
    @Test
    public void test02_notificationReadingRequests() {
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
                "read notification now",
                "start reading my messages",
                "start reading notifications"
        };
        for (String req : notifRequests) {
            VisionAction action = VisionActionParser.parse(req);
            assertEquals("Expected READ_NOTIFICATION for: " + req, VisionAction.Type.READ_NOTIFICATION, action.type);
        }
    }

    // Test 3: App launching requests (including F5 WhatsApp Business distinct target)
    @Test
    public void test03_appLaunchingRequests() {
        assertAppLaunch("open whatsapp", "WhatsApp");
        assertAppLaunch("open whatsapp business", "WhatsApp Business");
        assertAppLaunch("launch whatsapp business", "WhatsApp Business");
        assertAppLaunch("start w4b", "WhatsApp Business");
        assertAppLaunch("launch telegram", "Telegram");
        assertAppLaunch("start gmail", "Gmail");
        assertAppLaunch("open messages", "Messages");
        assertAppLaunch("open calendar", "Calendar");
        assertAppLaunch("launch calendar", "Calendar");
        assertAppLaunch("open sms", "Messages");
        assertAppLaunch("open whatsapp now", "WhatsApp");
        assertAppLaunch("please open telegram", "Telegram");
    }

    // Test 4: Notification reply requests (F3 colon requirement, F9 reply-to-colon, N13 word boundaries)
    @Test
    public void test04_notificationReplyRequests() {
        assertReply("reply I will be there soon", "latest notification", "I will be there soon");
        assertReply("reply: Sounds great!", "latest notification", "Sounds great!");
        assertReply("reply to: Sounds great!", "latest notification", "Sounds great!");
        assertReply("please reply to: Will do.", "latest notification", "Will do.");
        assertReply("reply to WhatsApp: On my way!", "WhatsApp", "On my way!");
        assertReply("reply to Alice: Yes, confirmed", "Alice", "Yes, confirmed");
        assertReply("reply to Bob Smith: thanks", "Bob Smith", "thanks");
        assertReply("send reply OK, see you then", "latest notification", "OK, see you then");
        assertReply("answer Perfect, thank you", "latest notification", "Perfect, thank you");
        assertReply("please reply: Will do.", "latest notification", "Will do.");

        // N13: Target token beginning with "to" (tokyo, Tom, tony) and colons in message body
        assertReply("reply to Alice: hello: world", "Alice", "hello: world");
        assertReply("reply to tokyo: hi", "tokyo", "hi");
        assertReply("reply to Tom: hi", "Tom", "hi");
        assertReply("reply to tony: hi", "tony", "hi");
        assertReply("reply to: tokyo", "latest notification", "tokyo");
        assertReply("reply to: hi", "latest notification", "hi");
        assertReply("reply tokyo: hi", "latest notification", "tokyo: hi");

        // F3: Space-separated "reply to <target> <text>" without colon is rejected as UNKNOWN
        assertEquals("reply to Bob Smith thanks -> UNKNOWN", VisionAction.Type.UNKNOWN, VisionActionParser.parse("reply to Bob Smith thanks").type);
        assertEquals("reply to Alice thanks -> UNKNOWN", VisionAction.Type.UNKNOWN, VisionActionParser.parse("reply to Alice thanks").type);

        // Empty reply text returns UNKNOWN
        assertEquals("reply without text -> UNKNOWN", VisionAction.Type.UNKNOWN, VisionActionParser.parse("reply").type);
        assertEquals("reply: without text -> UNKNOWN", VisionAction.Type.UNKNOWN, VisionActionParser.parse("reply:").type);
        assertEquals("reply to: without text -> UNKNOWN", VisionAction.Type.UNKNOWN, VisionActionParser.parse("reply to:").type);
        assertEquals("send reply without text -> UNKNOWN", VisionAction.Type.UNKNOWN, VisionActionParser.parse("send reply").type);
        assertEquals("reply to Alice without text -> UNKNOWN", VisionAction.Type.UNKNOWN, VisionActionParser.parse("reply to Alice").type);
    }

    // Test 5: Unrecognized requests
    @Test
    public void test05_unrecognizedRequests() {
        String[] unknownRequests = {
                "hello",
                "play music",
                "open random app",
                "search google",
                "take a photo",
                "reply to Bob Smith thanks",
                "reply to Alice"
        };
        for (String req : unknownRequests) {
            VisionAction action = VisionActionParser.parse(req);
            assertEquals("Expected UNKNOWN for: " + req, VisionAction.Type.UNKNOWN, action.type);
        }
    }

    // Test 6: VisionAction state machine, labels, and Risk Policy (N33 fail-closed)
    @Test
    public void test06_actionStateMachineAndRiskPolicy() {
        VisionAction a1 = new VisionAction(VisionAction.Type.READ_NOTIFICATION, "read notification", "latest notification");
        assertEquals("initial state is PROPOSED", VisionAction.State.PROPOSED, a1.state);
        assertEquals("label matches", "Read the latest supported notification", a1.label());
        assertFalse("READ_NOTIFICATION requiresConfirmation() must be false (Tier SAFE)", a1.requiresConfirmation());
        assertEquals("READ_NOTIFICATION is SAFE tier", VisionRiskPolicy.RiskTier.SAFE, VisionRiskPolicy.getRiskTier(a1.type));

        a1.state = VisionAction.State.APPROVED;
        assertEquals("approved state", VisionAction.State.APPROVED, a1.state);
        a1.state = VisionAction.State.RUNNING;
        assertEquals("running state", VisionAction.State.RUNNING, a1.state);
        a1.state = VisionAction.State.SUCCEEDED;
        assertEquals("succeeded state", VisionAction.State.SUCCEEDED, a1.state);

        VisionAction a2 = new VisionAction(VisionAction.Type.OPEN_APP, "open telegram", "Telegram");
        assertEquals("open app label matches", "Open Telegram", a2.label());
        assertFalse("OPEN_APP requiresConfirmation() must be false (Tier SAFE)", a2.requiresConfirmation());
        assertEquals("OPEN_APP is SAFE tier", VisionRiskPolicy.RiskTier.SAFE, VisionRiskPolicy.getRiskTier(a2.type));

        VisionAction a3 = new VisionAction(VisionAction.Type.REPLY_NOTIFICATION, "reply OK", "WhatsApp", "OK");
        assertEquals("reply label matches", "Reply to WhatsApp", a3.label());
        assertEquals("replyText matches", "OK", a3.replyText);
        assertTrue("REPLY_NOTIFICATION requiresConfirmation() must be true (Tier CONFIRMED)", a3.requiresConfirmation());
        assertEquals("REPLY_NOTIFICATION is CONFIRMED tier", VisionRiskPolicy.RiskTier.CONFIRMED, VisionRiskPolicy.getRiskTier(a3.type));

        VisionAction a4 = new VisionAction(VisionAction.Type.REPLY_NOTIFICATION, "reply OK", "", "OK");
        assertEquals("reply label defaults to latest notification", "Reply to the latest notification", a4.label());
        assertTrue("REPLY_NOTIFICATION requiresConfirmation() must be true", a4.requiresConfirmation());

        VisionAction aNull = new VisionAction(null, null, null, null);
        assertEquals("null type defaults to UNKNOWN", VisionAction.Type.UNKNOWN, aNull.type);
        assertTrue("null request defaults to empty", aNull.request.isEmpty());
        assertTrue("null target defaults to empty", aNull.target.isEmpty());
        assertTrue("null replyText defaults to empty", aNull.replyText.isEmpty());
        assertTrue("UNKNOWN requiresConfirmation() must be true under fail-closed policy (N33)", aNull.requiresConfirmation());
    }

    // Test 7: Notification package support allowlist
    @Test
    public void test07_notificationPackageAllowlist() {
        assertTrue("Gmail supported", VisionNotificationListener.isSupported("com.google.android.gm"));
        assertTrue("WhatsApp supported", VisionNotificationListener.isSupported("com.whatsapp"));
        assertTrue("WhatsApp Business supported", VisionNotificationListener.isSupported("com.whatsapp.w4b"));
        assertTrue("Telegram supported", VisionNotificationListener.isSupported("org.telegram.messenger"));
        assertTrue("Google Messages supported", VisionNotificationListener.isSupported("com.google.android.apps.messaging"));
        assertTrue("AOSP Messages supported", VisionNotificationListener.isSupported("com.android.messaging"));
        assertTrue("Calendar supported", VisionNotificationListener.isSupported("com.google.android.calendar"));

        assertFalse("Facebook rejected", VisionNotificationListener.isSupported("com.facebook.katana"));
        assertFalse("Instagram rejected", VisionNotificationListener.isSupported("com.instagram.android"));
        assertFalse("null rejected", VisionNotificationListener.isSupported(null));
    }

    // Test 8: Notification snapshot creation and key matching
    @Test
    public void test08_notificationSnapshotCreationAndKeyMatching() {
        VisionNotificationListener.NotificationSnapshot s1 =
                new VisionNotificationListener.NotificationSnapshot("key1", "com.whatsapp", "Alice", "Hello there", 1000L);
        assertEquals("key matches", "key1", s1.key);
        assertEquals("package matches", "com.whatsapp", s1.packageName);
        assertEquals("title matches", "Alice", s1.title);
        assertEquals("text matches", "Hello there", s1.text);
        assertEquals("postTime matches", 1000L, s1.postTime);

        VisionNotificationListener.NotificationSnapshot sNull =
                new VisionNotificationListener.NotificationSnapshot(null, null, null, null, 0L);
        assertTrue("null key defaults to empty", sNull.key.isEmpty());
        assertTrue("null packageName defaults to empty", sNull.packageName.isEmpty());
        assertTrue("null title defaults to empty", sNull.title.isEmpty());
        assertTrue("null text defaults to empty", sNull.text.isEmpty());
    }

    // Test 9: NotificationReplyCapability structure and safety (F7 fields included)
    @Test
    public void test09_replyCapabilityStructureAndSafety() {
        VisionNotificationListener.NotificationReplyCapability cap =
                new VisionNotificationListener.NotificationReplyCapability("key2", "org.telegram.messenger", "Bob", null, null, "Group", "Bob");
        assertEquals("cap key matches", "key2", cap.key);
        assertEquals("cap package matches", "org.telegram.messenger", cap.packageName);
        assertEquals("cap sender matches", "Bob", cap.senderOrTitle);
        assertEquals("cap conversationTitle matches", "Group", cap.conversationTitle);
        assertEquals("cap senderPerson matches", "Bob", cap.senderPerson);
        assertNull("cap pendingIntent is null", cap.pendingIntent);
        assertNull("cap remoteInput is null", cap.remoteInput);

        // sendReply returns false when capability or intent is null
        assertFalse("sendReply(null, null) returns false", VisionNotificationListener.sendReply(null, null, "test"));
        assertFalse("sendReply(null, cap) returns false", VisionNotificationListener.sendReply(null, cap, "test"));
    }

    // Test 10: Target validation hardening (F2, F5, F7)
    @Test
    public void test10_targetValidationHardening() {
        // Explicit contact target matching (exact, full, token)
        assertTrue("Exact contact match", VisionNotificationListener.validateTarget("Alice", "com.whatsapp", "WhatsApp", "Alice"));
        assertTrue("Full sender match", VisionNotificationListener.validateTarget("Alice Smith", "com.whatsapp", "WhatsApp", "Alice Smith"));
        assertTrue("Token contact match (Alice in Alice Smith)", VisionNotificationListener.validateTarget("Alice", "com.whatsapp", "WhatsApp", "Alice Smith"));
        assertTrue("Token contact match (Smith in Alice Smith)", VisionNotificationListener.validateTarget("Smith", "com.whatsapp", "WhatsApp", "Alice Smith"));
        assertTrue("Case-insensitive contact match", VisionNotificationListener.validateTarget("alice", "com.whatsapp", "WhatsApp", "Alice"));

        // F2 Hardened contact matching: loose substring matches MUST fail
        assertFalse("'li' vs Alice substring must FAIL", VisionNotificationListener.validateTarget("li", "com.whatsapp", "WhatsApp", "Alice"));
        assertFalse("'lic' vs Alice substring must FAIL", VisionNotificationListener.validateTarget("lic", "com.whatsapp", "WhatsApp", "Alice"));
        assertFalse("Mismatch sender rejected", VisionNotificationListener.validateTarget("Alice", "com.whatsapp", "WhatsApp", "Bob"));
        assertFalse("Empty sender with explicit target rejected", VisionNotificationListener.validateTarget("Alice", "com.whatsapp", "WhatsApp", ""));
        assertFalse("Null sender with explicit target rejected", VisionNotificationListener.validateTarget("Alice", "com.whatsapp", "WhatsApp", null));

        // Explicit app target matching (canonical aliases)
        assertTrue("WhatsApp app match", VisionNotificationListener.validateTarget("WhatsApp", "com.whatsapp", "WhatsApp", "Bob"));
        assertTrue("WhatsApp matches w4b (F5)", VisionNotificationListener.validateTarget("WhatsApp", "com.whatsapp.w4b", "WhatsApp", "Bob"));
        assertTrue("WhatsApp Business app match", VisionNotificationListener.validateTarget("WhatsApp Business", "com.whatsapp.w4b", "WhatsApp", "Bob"));
        assertTrue("Telegram app match", VisionNotificationListener.validateTarget("Telegram", "org.telegram.messenger", "Telegram", "Bob"));
        assertTrue("Gmail app match", VisionNotificationListener.validateTarget("Gmail", "com.google.android.gm", "Gmail", "Bob"));
        assertTrue("Messages app match", VisionNotificationListener.validateTarget("Messages", "com.google.android.apps.messaging", "Messages", "Bob"));
        assertTrue("Calendar app match", VisionNotificationListener.validateTarget("Calendar", "com.google.android.calendar", "Calendar", "Bob"));

        // F2 Hardened app matching: loose package substrings MUST fail
        assertFalse("'Messenger' vs Telegram must FAIL", VisionNotificationListener.validateTarget("Messenger", "org.telegram.messenger", "Telegram", "Bob"));
        assertFalse("'Me' vs Google Messages must FAIL", VisionNotificationListener.validateTarget("Me", "com.google.android.apps.messaging", "Messages", "Bob"));
        assertFalse("Cross-app composite target must FAIL closed", VisionNotificationListener.validateTarget("Telegram Bob", "com.whatsapp", "WhatsApp", "Bob"));
        assertFalse("App mismatch (Telegram vs WhatsApp) rejected", VisionNotificationListener.validateTarget("Telegram", "com.whatsapp", "WhatsApp", "Bob"));
        assertFalse("App mismatch (WhatsApp vs Telegram) rejected", VisionNotificationListener.validateTarget("WhatsApp", "org.telegram.messenger", "Telegram", "Bob"));
        assertFalse("App mismatch (Gmail vs Messages) rejected", VisionNotificationListener.validateTarget("Gmail", "com.google.android.apps.messaging", "Messages", "Bob"));

        // F7: Group chat validation with conversationTitle and senderPerson
        assertTrue("Group conversationTitle match", VisionNotificationListener.validateTarget("Dev Team", "com.whatsapp", "WhatsApp", "Dev Team", "Dev Team", "Alice"));
        assertTrue("Group conversationTitle token match", VisionNotificationListener.validateTarget("Team", "com.whatsapp", "WhatsApp", "Dev Team", "Dev Team", "Alice"));
        assertTrue("Group senderPerson match", VisionNotificationListener.validateTarget("Alice", "com.whatsapp", "WhatsApp", "Dev Team", "Dev Team", "Alice"));
        assertFalse("Group non-member rejected", VisionNotificationListener.validateTarget("Bob", "com.whatsapp", "WhatsApp", "Dev Team", "Dev Team", "Alice"));

        // Unspecified / default targets
        assertTrue("Default target accepted", VisionNotificationListener.validateTarget("latest notification", "com.whatsapp", "WhatsApp", "Bob"));
        assertTrue("'latest' accepted", VisionNotificationListener.validateTarget("latest", "org.telegram.messenger", "Telegram", "Alice"));
        assertTrue("Empty target accepted", VisionNotificationListener.validateTarget("", "com.whatsapp", "WhatsApp", "Bob"));
        assertTrue("Null target accepted", VisionNotificationListener.validateTarget(null, "com.whatsapp", "WhatsApp", "Bob"));
    }

    // Test 11: Notification replacement between proposal and approval (TOCTOU race prevention)
    @Test
    public void test11_notificationReplacementToctouRace() {
        VisionNotificationListener.NotificationReplyCapability capAlice =
                new VisionNotificationListener.NotificationReplyCapability("key_alice", "com.whatsapp", "Alice", null, null);
        VisionNotificationListener.NotificationReplyCapability capBob =
                new VisionNotificationListener.NotificationReplyCapability("key_bob", "com.whatsapp", "Bob", null, null);

        // Proposal binds capAlice
        VisionNotificationListener.setLatestReplyCapabilityForTesting(capAlice);
        VisionNotificationListener.NotificationReplyCapability boundCap = VisionNotificationListener.getLatestReplyCapability();
        assertSame("Bound capability is capAlice at proposal", capAlice, boundCap);

        // Incoming notification from Bob replaces active capability
        VisionNotificationListener.setLatestReplyCapabilityForTesting(capBob);

        // User taps Allow on dialog that was bound to capAlice
        VisionNotificationListener.ReplyResult result = VisionNotificationListener.sendBoundReply(null, boundCap, "Approved reply text");
        assertEquals("TOCTOU replaced capability rejected as STALE_OR_REMOVED", VisionNotificationListener.ReplyResult.STALE_OR_REMOVED, result);

        // Same chat updated with new capability instance
        VisionNotificationListener.NotificationReplyCapability capAliceNew =
                new VisionNotificationListener.NotificationReplyCapability("key_alice", "com.whatsapp", "Alice", null, null);
        VisionNotificationListener.setLatestReplyCapabilityForTesting(capAliceNew);
        VisionNotificationListener.ReplyResult resultNew = VisionNotificationListener.sendBoundReply(null, boundCap, "Approved reply text");
        assertEquals("Replaced instance rejected as STALE_OR_REMOVED", VisionNotificationListener.ReplyResult.STALE_OR_REMOVED, resultNew);
    }

    // Test 12: Notification removal / stale key dispatch prevention
    @Test
    public void test12_notificationRemovalStaleKeyDispatch() {
        VisionNotificationListener.NotificationReplyCapability cap1 =
                new VisionNotificationListener.NotificationReplyCapability("key_dismiss", "org.telegram.messenger", "Charlie", null, null);

        // Proposal binds cap1
        VisionNotificationListener.setLatestReplyCapabilityForTesting(cap1);
        VisionNotificationListener.NotificationReplyCapability boundCap = VisionNotificationListener.getLatestReplyCapability();

        // Notification dismissed/cleared
        VisionNotificationListener.clearLatestNotification();

        // Approval attempts dispatch on cleared capability
        VisionNotificationListener.ReplyResult result = VisionNotificationListener.sendBoundReply(null, boundCap, "Reply text");
        assertEquals("Dismissed notification rejected as STALE_OR_REMOVED", VisionNotificationListener.ReplyResult.STALE_OR_REMOVED, result);

        // Invalid arguments test
        assertEquals("null cap returns INVALID_ARGUMENTS", VisionNotificationListener.ReplyResult.INVALID_ARGUMENTS, VisionNotificationListener.sendBoundReply(null, null, "text"));
        VisionNotificationListener.NotificationReplyCapability emptyKeyCap =
                new VisionNotificationListener.NotificationReplyCapability("", "com.whatsapp", "User", null, null);
        assertEquals("empty key cap returns INVALID_ARGUMENTS", VisionNotificationListener.ReplyResult.INVALID_ARGUMENTS, VisionNotificationListener.sendBoundReply(null, emptyKeyCap, "text"));
    }

    // Test 13: Capability active match verification and bound lifecycle flow
    @Test
    public void test13_capabilityActiveMatchAndLifecycle() {
        VisionNotificationListener.NotificationReplyCapability cap1 =
                new VisionNotificationListener.NotificationReplyCapability("key_active", "com.whatsapp", "David", null, null);
        VisionNotificationListener.NotificationReplyCapability cap2 =
                new VisionNotificationListener.NotificationReplyCapability("key_other", "com.whatsapp", "Eve", null, null);

        VisionNotificationListener.clearLatestNotification();
        assertFalse("cap1 is inactive when empty", VisionNotificationListener.isCapabilityActive(cap1));

        VisionNotificationListener.setLatestReplyCapabilityForTesting(cap1);
        assertTrue("cap1 is active when set", VisionNotificationListener.isCapabilityActive(cap1));
        assertFalse("cap2 is inactive", VisionNotificationListener.isCapabilityActive(cap2));
        assertFalse("null is inactive", VisionNotificationListener.isCapabilityActive(null));

        // Source name resolution
        assertEquals("com.whatsapp -> WhatsApp", "WhatsApp", VisionNotificationListener.resolveSourceName("com.whatsapp"));
        assertEquals("com.whatsapp.w4b -> WhatsApp", "WhatsApp", VisionNotificationListener.resolveSourceName("com.whatsapp.w4b"));
        assertEquals("org.telegram.messenger -> Telegram", "Telegram", VisionNotificationListener.resolveSourceName("org.telegram.messenger"));
        assertEquals("com.google.android.gm -> Gmail", "Gmail", VisionNotificationListener.resolveSourceName("com.google.android.gm"));
        assertEquals("com.google.android.calendar -> Calendar", "Calendar", VisionNotificationListener.resolveSourceName("com.google.android.calendar"));
        assertEquals("Google Messages -> Messages", "Messages", VisionNotificationListener.resolveSourceName("com.google.android.apps.messaging"));
        assertEquals("AOSP Messages -> Messages", "Messages", VisionNotificationListener.resolveSourceName("com.android.messaging"));
        assertEquals("unknown.pkg -> Notification", "Notification", VisionNotificationListener.resolveSourceName("unknown.pkg"));
    }

    // Test 14: Flag filtering logic (N9 group-summary with reply action accepted)
    @Test
    public void test14_flagFilteringLogic() {
        // N9: Group summary without reply -> ignored; group summary with reply -> accepted
        assertTrue("FLAG_GROUP_SUMMARY without reply -> ignored", VisionNotificationListener.shouldIgnoreNotification(Notification.FLAG_GROUP_SUMMARY, false));
        assertFalse("FLAG_GROUP_SUMMARY with reply -> accepted (N9)", VisionNotificationListener.shouldIgnoreNotification(Notification.FLAG_GROUP_SUMMARY, true));

        // Ongoing event / Foreground service without reply action -> ignored
        assertTrue("FLAG_ONGOING_EVENT without reply -> ignored", VisionNotificationListener.shouldIgnoreNotification(Notification.FLAG_ONGOING_EVENT, false));
        assertTrue("FLAG_FOREGROUND_SERVICE without reply -> ignored", VisionNotificationListener.shouldIgnoreNotification(Notification.FLAG_FOREGROUND_SERVICE, false));
        assertTrue("Ongoing+FG without reply -> ignored", VisionNotificationListener.shouldIgnoreNotification(Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE, false));

        // Ongoing event / Foreground service WITH reply action -> NOT ignored
        assertFalse("FLAG_ONGOING_EVENT with reply -> accepted", VisionNotificationListener.shouldIgnoreNotification(Notification.FLAG_ONGOING_EVENT, true));
        assertFalse("FLAG_FOREGROUND_SERVICE with reply -> accepted", VisionNotificationListener.shouldIgnoreNotification(Notification.FLAG_FOREGROUND_SERVICE, true));

        // Normal notification (flags = 0) -> NOT ignored
        assertFalse("Standard notification -> accepted", VisionNotificationListener.shouldIgnoreNotification(0, false));
        assertFalse("Standard notification with reply -> accepted", VisionNotificationListener.shouldIgnoreNotification(0, true));
    }

    // Test 15: Risk policy tier verification (N33 fail-closed policy)
    @Test
    public void test15_riskPolicyTierVerification() {
        assertFalse("OPEN_APP requiresConfirmation == false", VisionRiskPolicy.requiresConfirmation(VisionAction.Type.OPEN_APP));
        assertFalse("READ_NOTIFICATION requiresConfirmation == false", VisionRiskPolicy.requiresConfirmation(VisionAction.Type.READ_NOTIFICATION));
        assertTrue("UNKNOWN requiresConfirmation == true under fail-closed (N33)", VisionRiskPolicy.requiresConfirmation(VisionAction.Type.UNKNOWN));
        assertTrue("null requiresConfirmation == true under fail-closed (N33)", VisionRiskPolicy.requiresConfirmation(null));

        assertTrue("REPLY_NOTIFICATION requiresConfirmation == true", VisionRiskPolicy.requiresConfirmation(VisionAction.Type.REPLY_NOTIFICATION));

        assertEquals("OPEN_APP is SAFE tier", VisionRiskPolicy.RiskTier.SAFE, VisionRiskPolicy.getRiskTier(VisionAction.Type.OPEN_APP));
        assertEquals("READ_NOTIFICATION is SAFE tier", VisionRiskPolicy.RiskTier.SAFE, VisionRiskPolicy.getRiskTier(VisionAction.Type.READ_NOTIFICATION));
        assertEquals("REPLY_NOTIFICATION is CONFIRMED tier", VisionRiskPolicy.RiskTier.CONFIRMED, VisionRiskPolicy.getRiskTier(VisionAction.Type.REPLY_NOTIFICATION));
        assertEquals("UNKNOWN is CONFIRMED tier under fail-closed (N33)", VisionRiskPolicy.RiskTier.CONFIRMED, VisionRiskPolicy.getRiskTier(VisionAction.Type.UNKNOWN));
        assertEquals("null is CONFIRMED tier under fail-closed (N33)", VisionRiskPolicy.RiskTier.CONFIRMED, VisionRiskPolicy.getRiskTier(null));
    }

    // Test 16: Multiline composer reply and command input preservation
    @Test
    public void test16_multilineReplyAndCommandParsing() {
        // Preserves internal newlines in reply body with target
        assertReply("reply to Alice:\nHello Alice,\nI will be there in 5 minutes.\nSee you soon!",
                "Alice",
                "Hello Alice,\nI will be there in 5 minutes.\nSee you soon!");

        // Preserves internal newlines in reply body without target
        assertReply("reply:\nLine 1\nLine 2\nLine 3",
                "latest notification",
                "Line 1\nLine 2\nLine 3");

        // Preserves internal blank lines / paragraphs
        assertReply("reply to:\nFirst paragraph.\n\nSecond paragraph.",
                "latest notification",
                "First paragraph.\n\nSecond paragraph.");

        // Preserves multiline reply to contacts with multi-word names
        assertReply("reply to Bob Smith:\nMeeting at 3 PM\nRoom 402",
                "Bob Smith",
                "Meeting at 3 PM\nRoom 402");

        // Multiline open app command normalization
        assertAppLaunch("open\nwhatsapp", "WhatsApp");
        assertAppLaunch("launch\nwhatsapp business", "WhatsApp Business");

        // Multiline read notification command normalization
        VisionAction readAction = VisionActionParser.parse("read\nmy latest\nnotification");
        assertEquals("Multiline read command parsed as READ_NOTIFICATION", VisionAction.Type.READ_NOTIFICATION, readAction.type);

        // Multiline without colon (REPLY_NO_TO_PATTERN)
        assertReply("send reply\nMultiline message\nwithout colon",
                "latest notification",
                "Multiline message\nwithout colon");
    }

    // Test 17: Jarvis-style conversational reply confirmation formatting and destination display
    @Test
    public void test17_jarvisStyleReplyConfirmationFormatting() {
        // Dialog Title
        assertEquals("Dialog title is respectful Jarvis request",
                "Tony, may I send this message?",
                VisionRiskPolicy.formatReplyConfirmationTitle());

        // Dialog Message with recipient and single-line text
        String msg1 = VisionRiskPolicy.formatReplyConfirmationMessage("WhatsApp (Alice)", "I will be there soon");
        String expected1 = "I am ready to send this message to WhatsApp (Alice):\n\n\"I will be there soon\"\n\nMay I proceed?";
        assertEquals("Confirmation message matches Jarvis specification", expected1, msg1);

        // Dialog Message with multiline text
        String msg2 = VisionRiskPolicy.formatReplyConfirmationMessage("Telegram (Dev Team - Bob)", "Line 1\nLine 2");
        String expected2 = "I am ready to send this message to Telegram (Dev Team - Bob):\n\n\"Line 1\nLine 2\"\n\nMay I proceed?";
        assertEquals("Confirmation message handles multiline text", expected2, msg2);

        // Dialog Message null fallback safety
        String msgNull = VisionRiskPolicy.formatReplyConfirmationMessage(null, null);
        String expectedNull = "I am ready to send this message to the recipient:\n\n\"\"\n\nMay I proceed?";
        assertEquals("Confirmation message handles null inputs safely", expectedNull, msgNull);

        // Destination display formatting helper
        assertEquals("WhatsApp (Alice)", VisionNotificationListener.formatDestinationDisplay("WhatsApp", "Alice", "", ""));
        assertEquals("WhatsApp", VisionNotificationListener.formatDestinationDisplay("WhatsApp", "WhatsApp", "", ""));
        assertEquals("WhatsApp (Dev Team - Alice)", VisionNotificationListener.formatDestinationDisplay("WhatsApp", "Dev Team", "Dev Team", "Alice"));
        assertEquals("Telegram (Group - Charlie)", VisionNotificationListener.formatDestinationDisplay("Telegram", "", "Group", "Charlie"));
        assertEquals("Telegram", VisionNotificationListener.formatDestinationDisplay("Telegram", "", "", ""));
        assertEquals("the latest notification", VisionNotificationListener.formatDestinationDisplay(null, (VisionNotificationListener.NotificationReplyCapability) null));
        assertEquals("Notification", VisionNotificationListener.formatDestinationDisplay(null, null, null, null));
    }

    // Test 18: Notification ordering, out-of-order rejection, and deterministic tie-breaking policy
    @Test
    public void test18_outOfOrderNotificationAcceptanceAndTieBreaking() {
        // 1. Initial notification when no prior notification exists is always accepted
        assertTrue("Initial notification accepted",
                VisionNotificationListener.shouldAcceptNotificationOrder(1000L, "key_initial", 0L, null));
        assertTrue("Initial notification with empty current key accepted",
                VisionNotificationListener.shouldAcceptNotificationOrder(1000L, "key_initial", 0L, ""));

        // 2. Newer postTime replaces older snapshot
        assertTrue("Newer postTime (2000L > 1000L) accepted",
                VisionNotificationListener.shouldAcceptNotificationOrder(2000L, "key_newer", 1000L, "key_older"));

        // 3. Older postTime arriving out-of-order is rejected
        assertFalse("Older postTime (500L < 1000L) rejected",
                VisionNotificationListener.shouldAcceptNotificationOrder(500L, "key_older", 1000L, "key_current"));
        assertFalse("Older postTime (999L < 1000L) rejected",
                VisionNotificationListener.shouldAcceptNotificationOrder(999L, "key_older", 1000L, "key_current"));

        // 4. Equal postTime with identical key is accepted as in-place update
        assertTrue("Equal postTime with identical key accepted as update",
                VisionNotificationListener.shouldAcceptNotificationOrder(1000L, "key_same", 1000L, "key_same"));

        // 5. Equal postTime with different keys uses deterministic lexicographical tie-break
        assertTrue("Equal postTime with lexicographically greater key accepted",
                VisionNotificationListener.shouldAcceptNotificationOrder(1000L, "key_z", 1000L, "key_a"));
        assertFalse("Equal postTime with lexicographically smaller key rejected",
                VisionNotificationListener.shouldAcceptNotificationOrder(1000L, "key_a", 1000L, "key_z"));

        // 6. Verify via production processing boundary (processPostedNotification)
        VisionNotificationListener.clearLatestNotification();
        assertTrue("Initial post accepted",
                VisionNotificationListener.processPostedNotification("key_a", "com.whatsapp", "Alice", "Msg 1", 1000L, 0, null));
        assertEquals("key_a", VisionNotificationListener.getLatestNotification().key);

        assertFalse("Out-of-order older postTime rejected",
                VisionNotificationListener.processPostedNotification("key_older", "com.whatsapp", "Alice", "Stale", 500L, 0, null));
        assertEquals("key_a", VisionNotificationListener.getLatestNotification().key);
        assertEquals("Msg 1", VisionNotificationListener.getLatestNotification().text);

        assertTrue("Equal timestamp same-key update accepted",
                VisionNotificationListener.processPostedNotification("key_a", "com.whatsapp", "Alice", "Msg 1 Updated", 1000L, 0, null));
        assertEquals("Msg 1 Updated", VisionNotificationListener.getLatestNotification().text);

        assertTrue("Equal timestamp lexicographically greater key accepted",
                VisionNotificationListener.processPostedNotification("key_z", "com.whatsapp", "Zoe", "Msg Z", 1000L, 0, null));
        assertEquals("key_z", VisionNotificationListener.getLatestNotification().key);

        assertFalse("Equal timestamp lexicographically smaller key rejected",
                VisionNotificationListener.processPostedNotification("key_b", "com.whatsapp", "Bob", "Msg B", 1000L, 0, null));
        assertEquals("key_z", VisionNotificationListener.getLatestNotification().key);

        assertTrue("Newer postTime accepted",
                VisionNotificationListener.processPostedNotification("key_newer", "com.whatsapp", "Charlie", "Msg New", 2000L, 0, null));
        assertEquals("key_newer", VisionNotificationListener.getLatestNotification().key);
        assertEquals(2000L, VisionNotificationListener.getLatestNotification().postTime);
    }

    // Test 19: Matching vs non-matching notification removal
    @Test
    public void test19_matchingVsNonMatchingRemoval() {
        VisionNotificationListener.clearLatestNotification();
        assertEquals("Initial status NO_NOTIFICATION_YET",
                VisionNotificationListener.NotificationStatus.NO_NOTIFICATION_YET,
                VisionNotificationListener.getListenerState().status);

        VisionNotificationListener.NotificationReplyCapability cap1 =
                new VisionNotificationListener.NotificationReplyCapability("key_active", "com.whatsapp", "Alice", null, null);

        // Post active notification via production processing boundary
        assertTrue("Active notification posted",
                VisionNotificationListener.processPostedNotification("key_active", "com.whatsapp", "Alice", "Hello", 1000L, 0, cap1));

        assertTrue("Active state is notification active", VisionNotificationListener.getListenerState().isNotificationActive());
        assertEquals("Active key matches", "key_active", VisionNotificationListener.getListenerState().key);
        assertTrue("Active state has reply capability", VisionNotificationListener.getListenerState().hasReplyCapability);
        assertNotNull("Active snapshot present", VisionNotificationListener.getLatestNotification());
        assertNotNull("Active capability present", VisionNotificationListener.getLatestReplyCapability());

        // Production non-matching removal (e.g. background notification from another chat or service removed)
        boolean removedNonMatching = VisionNotificationListener.processRemovedNotification("key_unrelated");
        assertFalse("Non-matching removal returns false", removedNonMatching);
        assertNotNull("Active snapshot remains present after non-matching removal", VisionNotificationListener.getLatestNotification());
        assertEquals("key_active", VisionNotificationListener.getLatestNotification().key);
        assertTrue("State remains active after non-matching removal", VisionNotificationListener.getListenerState().isNotificationActive());

        // Production matching removal
        long seqBefore = VisionNotificationListener.getListenerState().sequenceNumber;
        boolean removedMatching = VisionNotificationListener.processRemovedNotification("key_active");
        assertTrue("Matching removal returns true", removedMatching);

        assertNull("Snapshot is null after matching removal", VisionNotificationListener.getLatestNotification());
        assertNull("Capability is null after matching removal", VisionNotificationListener.getLatestReplyCapability());
        assertTrue("State is notification removed", VisionNotificationListener.getListenerState().isNotificationRemoved());
        assertEquals("Removed state preserves metadata key", "key_active", VisionNotificationListener.getListenerState().key);
        assertEquals("Removed state preserves metadata package", "com.whatsapp", VisionNotificationListener.getListenerState().packageName);
        assertEquals("Removed state preserves metadata timestamp", 1000L, VisionNotificationListener.getListenerState().postTime);
        assertFalse("Removed state hasReplyCapability is false", VisionNotificationListener.getListenerState().hasReplyCapability);
        assertTrue("Sequence number incremented on removal", VisionNotificationListener.getListenerState().sequenceNumber > seqBefore);

        // Second removal on already removed key returns false
        assertFalse("Second removal on same key returns false", VisionNotificationListener.processRemovedNotification("key_active"));
    }

    // Test 20: Filtered and unsupported notifications preserve valid active state
    @Test
    public void test20_filteredAndUnsupportedPreserveActiveState() {
        VisionNotificationListener.clearLatestNotification();

        // 1. Package allowlist filter verification
        assertFalse("com.untrusted.app is unsupported", VisionNotificationListener.isSupported("com.untrusted.app"));
        assertFalse("com.facebook.orca is unsupported", VisionNotificationListener.isSupported("com.facebook.orca"));
        assertTrue("com.whatsapp is supported", VisionNotificationListener.isSupported("com.whatsapp"));

        // 2. Active notification set via production processing boundary
        assertTrue("Active notification accepted",
                VisionNotificationListener.processPostedNotification("key_main", "com.whatsapp", "Bob", "Test", 5000L, 0, null));
        VisionNotificationListener.NotificationSnapshot active = VisionNotificationListener.getLatestNotification();
        assertNotNull("Active snapshot exists", active);

        // 3. Flags filter logic verification
        assertTrue("Group summary without reply is filtered",
                VisionNotificationListener.shouldIgnoreNotification(Notification.FLAG_GROUP_SUMMARY, false));
        assertTrue("Ongoing event without reply is filtered",
                VisionNotificationListener.shouldIgnoreNotification(Notification.FLAG_ONGOING_EVENT, false));
        assertTrue("Foreground service without reply is filtered",
                VisionNotificationListener.shouldIgnoreNotification(Notification.FLAG_FOREGROUND_SERVICE, false));

        // 4. Production processing with unsupported package is rejected and preserves active state
        assertFalse("Unsupported package rejected by production processor",
                VisionNotificationListener.processPostedNotification("key_unsupported", "com.facebook.katana", "FB", "Ad", 6000L, 0, null));
        assertEquals("Active key unchanged after unsupported package", "key_main", VisionNotificationListener.getLatestNotification().key);
        assertEquals("Test", VisionNotificationListener.getLatestNotification().text);
        assertTrue("State is still active", VisionNotificationListener.getListenerState().isNotificationActive());

        // 5. Production processing with filtered flags (no reply action) is rejected and preserves active state
        assertFalse("Group summary without reply rejected by production processor",
                VisionNotificationListener.processPostedNotification("key_summary", "com.whatsapp", "Group", "3 messages", 7000L, Notification.FLAG_GROUP_SUMMARY, null));
        assertEquals("Active key unchanged after group summary", "key_main", VisionNotificationListener.getLatestNotification().key);

        assertFalse("Ongoing event without reply rejected by production processor",
                VisionNotificationListener.processPostedNotification("key_ongoing", "com.whatsapp", "Call", "Call ongoing", 8000L, Notification.FLAG_ONGOING_EVENT, null));
        assertEquals("Active key unchanged after ongoing event", "key_main", VisionNotificationListener.getLatestNotification().key);

        assertFalse("Foreground service without reply rejected by production processor",
                VisionNotificationListener.processPostedNotification("key_fg", "com.whatsapp", "Sync", "Syncing", 9000L, Notification.FLAG_FOREGROUND_SERVICE, null));
        assertEquals("Active key unchanged after foreground service", "key_main", VisionNotificationListener.getLatestNotification().key);
        assertTrue("Active state is still active", VisionNotificationListener.getListenerState().isNotificationActive());
        assertEquals("key_main", VisionNotificationListener.getListenerState().key);
    }

    // Test 21: Deterministic reply action candidate scoring, selection, and RemoteInput eligibility
    @Test
    public void test21_replyActionSelectionAndRemoteInputEligibility() {
        // RemoteInput eligibility
        assertTrue("Free-form text is eligible",
                VisionNotificationListener.isRemoteInputEligible(true, false, "key_reply"));
        assertTrue("Choices-only text is eligible",
                VisionNotificationListener.isRemoteInputEligible(false, true, "key_choice"));
        assertTrue("Both free-form and choices is eligible",
                VisionNotificationListener.isRemoteInputEligible(true, true, "key_both"));
        assertFalse("Data-only (no free-form and no choices) is INELIGIBLE",
                VisionNotificationListener.isRemoteInputEligible(false, false, "key_data_only"));
        assertFalse("Null resultKey is INELIGIBLE",
                VisionNotificationListener.isRemoteInputEligible(true, true, null));
        assertFalse("Empty resultKey is INELIGIBLE",
                VisionNotificationListener.isRemoteInputEligible(true, true, "   "));

        // Action scoring
        assertEquals("No action intent yields -1", -1,
                VisionNotificationListener.scoreActionCandidate(false, false, true));
        assertEquals("Ineligible RemoteInput yields -1", -1,
                VisionNotificationListener.scoreActionCandidate(true, false, false));
        assertEquals("Standard text reply action scores 1", 1,
                VisionNotificationListener.scoreActionCandidate(true, false, true));
        assertEquals("SEMANTIC_ACTION_REPLY scores 2 (preferred)", 2,
                VisionNotificationListener.scoreActionCandidate(true, true, true));

        // Multi-candidate list evaluation
        VisionNotificationListener.ReplyActionCandidate cArchive =
                new VisionNotificationListener.ReplyActionCandidate("Archive", true, false, false, false, "key_archive");
        VisionNotificationListener.ReplyActionCandidate cQuick =
                new VisionNotificationListener.ReplyActionCandidate("Quick Reply", true, false, true, false, "key_quick");
        VisionNotificationListener.ReplyActionCandidate cSemantic =
                new VisionNotificationListener.ReplyActionCandidate("Reply", true, true, true, false, "key_semantic");
        VisionNotificationListener.ReplyActionCandidate cDataOnly =
                new VisionNotificationListener.ReplyActionCandidate("Attach", true, false, false, false, "key_attach");

        List<VisionNotificationListener.ReplyActionCandidate> listWithSemantic =
                Arrays.asList(cArchive, cQuick, cSemantic, cDataOnly);
        VisionNotificationListener.ReplyActionCandidate best1 =
                VisionNotificationListener.selectBestReplyActionCandidate(listWithSemantic);
        assertNotNull("Best candidate found", best1);
        assertEquals("Prefers SEMANTIC_ACTION_REPLY", "Reply", best1.actionTitle);
        assertEquals(2, best1.getScore());

        List<VisionNotificationListener.ReplyActionCandidate> listWithoutSemantic =
                Arrays.asList(cArchive, cQuick, cDataOnly);
        VisionNotificationListener.ReplyActionCandidate best2 =
                VisionNotificationListener.selectBestReplyActionCandidate(listWithoutSemantic);
        assertNotNull("Best candidate found", best2);
        assertEquals("Falls back to first text-capable action", "Quick Reply", best2.actionTitle);
        assertEquals(1, best2.getScore());

        List<VisionNotificationListener.ReplyActionCandidate> listOnlyIneligible =
                Arrays.asList(cArchive, cDataOnly);
        VisionNotificationListener.ReplyActionCandidate best3 =
                VisionNotificationListener.selectBestReplyActionCandidate(listOnlyIneligible);
        assertNull("Returns null when no eligible candidate exists", best3);
    }

    // Test 22: ListenerState transitions, monotonic sequencing, and metadata security
    @Test
    public void test22_listenerStateTransitionsAndMetadataSafety() {
        VisionNotificationListener.clearLatestNotification();
        VisionNotificationListener.ListenerState initial = VisionNotificationListener.getListenerState();
        assertEquals(VisionNotificationListener.NotificationStatus.NO_NOTIFICATION_YET, initial.status);
        assertTrue("hasNoNotification() == true", initial.hasNoNotification());
        assertFalse("isNotificationActive() == false", initial.isNotificationActive());
        assertFalse("isNotificationRemoved() == false", initial.isNotificationRemoved());
        assertEquals("", initial.key);
        assertEquals("", initial.packageName);
        assertEquals(0L, initial.postTime);
        assertFalse(initial.hasReplyCapability);

        // Transition to ACTIVE via production processing boundary
        VisionNotificationListener.NotificationReplyCapability cap =
                new VisionNotificationListener.NotificationReplyCapability("pkg:1", "com.google.android.gm", "Sender", null, null);
        boolean posted = VisionNotificationListener.processPostedNotification(
                "pkg:1", "com.google.android.gm", "Subject", "Body", 123456L, 0, cap);
        assertTrue("Posted successfully", posted);

        VisionNotificationListener.ListenerState activeState = VisionNotificationListener.getListenerState();
        assertEquals(VisionNotificationListener.NotificationStatus.ACTIVE_NOTIFICATION, activeState.status);
        assertTrue(activeState.isNotificationActive());
        assertEquals("pkg:1", activeState.key);
        assertEquals("com.google.android.gm", activeState.packageName);
        assertEquals(123456L, activeState.postTime);
        assertTrue("Active state has reply capability", activeState.hasReplyCapability);
        assertTrue("Sequence number incremented", activeState.sequenceNumber > initial.sequenceNumber);

        // Transition to NOTIFICATION_REMOVED via production removal boundary
        boolean removed = VisionNotificationListener.processRemovedNotification("pkg:1");
        assertTrue("Removed successfully", removed);

        VisionNotificationListener.ListenerState removedState = VisionNotificationListener.getListenerState();
        assertEquals(VisionNotificationListener.NotificationStatus.NOTIFICATION_REMOVED, removedState.status);
        assertTrue(removedState.isNotificationRemoved());
        assertFalse(removedState.isNotificationActive());
        assertEquals("pkg:1", removedState.key);
        assertEquals("com.google.android.gm", removedState.packageName);
        assertEquals(123456L, removedState.postTime);
        assertFalse("Removed state hasReplyCapability is false", removedState.hasReplyCapability);
        assertTrue("Sequence number continuously increases", removedState.sequenceNumber > activeState.sequenceNumber);

        // Clear notification -> NO_NOTIFICATION_YET
        VisionNotificationListener.clearLatestNotification();
        VisionNotificationListener.ListenerState clearedState = VisionNotificationListener.getListenerState();
        assertEquals(VisionNotificationListener.NotificationStatus.NO_NOTIFICATION_YET, clearedState.status);
        assertTrue("Sequence number continuously increases on clear", clearedState.sequenceNumber > removedState.sequenceNumber);
    }

    // Test 23: Multiline payload exact identity, whitespace trimming, and line break preservation
    @Test
    public void test23_multilinePayloadExactIdentityAndIntegrity() {
        String exactMultiline = "Hello Tony,\n\nHere is the report:\n- Item 1: Complete\n- Item 2: In progress\n\nBest regards,\nVision";
        String command = "reply to WhatsApp:\n" + exactMultiline;

        VisionAction action = VisionActionParser.parse(command);
        assertEquals(VisionAction.Type.REPLY_NOTIFICATION, action.type);
        assertEquals("WhatsApp", action.target);
        assertEquals("Parsed replyText matches exact multiline body byte-for-byte", exactMultiline, action.replyText);

        // Confirmation dialog message formatting
        String confirmationMessage = VisionRiskPolicy.formatReplyConfirmationMessage("WhatsApp", action.replyText);
        String expectedDialog = "I am ready to send this message to WhatsApp:\n\n\"" + exactMultiline + "\"\n\nMay I proceed?";
        assertEquals("Confirmation dialog displays exact multiline payload", expectedDialog, confirmationMessage);

        // CRLF preservation
        String crlfText = "Line 1\r\nLine 2\r\nLine 3";
        VisionAction crlfAction = VisionActionParser.parse("reply:\n" + crlfText);
        assertEquals("CRLF line breaks preserved exactly", crlfText, crlfAction.replyText);
    }

    // Test 24: Risk tiers and fail-closed security invariants
    @Test
    public void test24_riskTiersAndActionSafetyInvariants() {
        // Safe actions require no confirmation
        assertEquals("OPEN_APP is SAFE tier", VisionRiskPolicy.RiskTier.SAFE, VisionRiskPolicy.getRiskTier(VisionAction.Type.OPEN_APP));
        assertFalse("OPEN_APP requiresConfirmation is false", VisionRiskPolicy.requiresConfirmation(VisionAction.Type.OPEN_APP));

        assertEquals("READ_NOTIFICATION is SAFE tier", VisionRiskPolicy.RiskTier.SAFE, VisionRiskPolicy.getRiskTier(VisionAction.Type.READ_NOTIFICATION));
        assertFalse("READ_NOTIFICATION requiresConfirmation is false", VisionRiskPolicy.requiresConfirmation(VisionAction.Type.READ_NOTIFICATION));

        // Confirmed action requires modal Allow/Deny
        assertEquals("REPLY_NOTIFICATION is CONFIRMED tier", VisionRiskPolicy.RiskTier.CONFIRMED, VisionRiskPolicy.getRiskTier(VisionAction.Type.REPLY_NOTIFICATION));
        assertTrue("REPLY_NOTIFICATION requiresConfirmation is true", VisionRiskPolicy.requiresConfirmation(VisionAction.Type.REPLY_NOTIFICATION));

        // Fail-closed fallback: UNKNOWN and null require confirmation
        assertEquals("UNKNOWN is CONFIRMED tier", VisionRiskPolicy.RiskTier.CONFIRMED, VisionRiskPolicy.getRiskTier(VisionAction.Type.UNKNOWN));
        assertTrue("UNKNOWN requiresConfirmation is true", VisionRiskPolicy.requiresConfirmation(VisionAction.Type.UNKNOWN));
        assertEquals("null is CONFIRMED tier", VisionRiskPolicy.RiskTier.CONFIRMED, VisionRiskPolicy.getRiskTier(null));
        assertTrue("null requiresConfirmation is true", VisionRiskPolicy.requiresConfirmation(null));
    }

    // Test 25: Production listener processing boundary coverage and atomic dispatch invariants
    @Test
    public void test25_productionProcessingBoundaryComprehensive() {
        VisionNotificationListener.clearLatestNotification();

        // 1. Unsupported package returns false and does not mutate sequence or state
        long seq0 = VisionNotificationListener.getListenerState().sequenceNumber;
        assertFalse("Unsupported package rejected",
                VisionNotificationListener.processPostedNotification("bad_key", "com.malicious.app", "Evil", "Payload", 1000L, 0, null));
        assertEquals("Sequence unchanged after rejected unsupported package", seq0, VisionNotificationListener.getListenerState().sequenceNumber);
        assertEquals(VisionNotificationListener.NotificationStatus.NO_NOTIFICATION_YET, VisionNotificationListener.getListenerState().status);

        // 2. Filtered ongoing noise without reply returns false and does not mutate state
        assertFalse("Filtered ongoing event rejected",
                VisionNotificationListener.processPostedNotification("noise_key", "com.google.android.gm", "Syncing", "In progress", 1000L, Notification.FLAG_ONGOING_EVENT, null));
        assertEquals(VisionNotificationListener.NotificationStatus.NO_NOTIFICATION_YET, VisionNotificationListener.getListenerState().status);

        // 3. Filtered ongoing event WITH reply capability is accepted
        VisionNotificationListener.NotificationReplyCapability capReply =
                new VisionNotificationListener.NotificationReplyCapability("valid_ongoing", "com.google.android.gm", "Email Alert", null, null);
        assertTrue("Filtered flag with reply capability accepted",
                VisionNotificationListener.processPostedNotification("valid_ongoing", "com.google.android.gm", "Email Alert", "New message", 1000L, Notification.FLAG_ONGOING_EVENT, capReply));
        assertEquals(VisionNotificationListener.NotificationStatus.ACTIVE_NOTIFICATION, VisionNotificationListener.getListenerState().status);
        assertEquals("valid_ongoing", VisionNotificationListener.getListenerState().key);
        assertTrue(VisionNotificationListener.getListenerState().hasReplyCapability);

        // 4. Removal clears active state and updates sequence atomically
        long seqActive = VisionNotificationListener.getListenerState().sequenceNumber;
        assertTrue("Matching removal succeeds", VisionNotificationListener.processRemovedNotification("valid_ongoing"));
        assertEquals(VisionNotificationListener.NotificationStatus.NOTIFICATION_REMOVED, VisionNotificationListener.getListenerState().status);
        assertNull("Latest snapshot cleared", VisionNotificationListener.getLatestNotification());
        assertNull("Latest capability cleared", VisionNotificationListener.getLatestReplyCapability());
        assertTrue("Sequence incremented on removal", VisionNotificationListener.getListenerState().sequenceNumber > seqActive);

        // 5. Posting new notification after removal restores ACTIVE status
        assertTrue("Post after removal succeeds",
                VisionNotificationListener.processPostedNotification("new_key", "org.telegram.messenger", "Alice", "Hey", 2000L, 0, null));
        assertEquals(VisionNotificationListener.NotificationStatus.ACTIVE_NOTIFICATION, VisionNotificationListener.getListenerState().status);
        assertEquals("new_key", VisionNotificationListener.getListenerState().key);
        assertFalse(VisionNotificationListener.getListenerState().hasReplyCapability);
    }

    // Test 26: Production boundary regression semantics for capability lifecycle, replacement, and non-matching removal
    @Test
    public void test26_productionBoundaryRegressionSemantics() {
        VisionNotificationListener.clearLatestNotification();

        // 1. Post same key with capability then same key without capability -> getLatestReplyCapability is null
        VisionNotificationListener.NotificationReplyCapability cap1 =
                new VisionNotificationListener.NotificationReplyCapability("same_key", "com.whatsapp", "Alice", null, null);
        assertTrue("Initial post with capability succeeds",
                VisionNotificationListener.processPostedNotification("same_key", "com.whatsapp", "Alice", "Hello", 1000L, 0, cap1));
        assertNotNull("Latest notification snapshot present", VisionNotificationListener.getLatestNotification());
        assertEquals("same_key", VisionNotificationListener.getLatestNotification().key);
        assertEquals("Latest capability is cap1", cap1, VisionNotificationListener.getLatestReplyCapability());
        assertTrue("cap1 is capability active", VisionNotificationListener.isCapabilityActive(cap1));
        assertTrue("ListenerState has reply capability", VisionNotificationListener.getListenerState().hasReplyCapability);

        // Update same key without capability (e.g. read receipt / update notification without RemoteInput)
        assertTrue("Same key update without capability succeeds",
                VisionNotificationListener.processPostedNotification("same_key", "com.whatsapp", "Alice", "Hello (read)", 1000L, 0, null));
        assertNotNull("Notification snapshot retained with updated text", VisionNotificationListener.getLatestNotification());
        assertEquals("Hello (read)", VisionNotificationListener.getLatestNotification().text);
        assertNull("getLatestReplyCapability is null after same-key replacement without capability",
                VisionNotificationListener.getLatestReplyCapability());
        assertFalse("cap1 is no longer active", VisionNotificationListener.isCapabilityActive(cap1));
        assertFalse("ListenerState hasReplyCapability is false", VisionNotificationListener.getListenerState().hasReplyCapability);

        // 2. Post cap1 then post cap2 and verify isCapabilityActive(cap1) false and cap2 active
        VisionNotificationListener.clearLatestNotification();
        VisionNotificationListener.NotificationReplyCapability capA =
                new VisionNotificationListener.NotificationReplyCapability("key_a", "com.whatsapp", "Alice", null, null);
        VisionNotificationListener.NotificationReplyCapability capB =
                new VisionNotificationListener.NotificationReplyCapability("key_b", "org.telegram.messenger", "Bob", null, null);

        assertTrue("Post capA succeeds",
                VisionNotificationListener.processPostedNotification("key_a", "com.whatsapp", "Alice", "Msg A", 2000L, 0, capA));
        assertTrue("capA is active", VisionNotificationListener.isCapabilityActive(capA));
        assertFalse("capB is inactive", VisionNotificationListener.isCapabilityActive(capB));
        assertEquals("Latest capability is capA", capA, VisionNotificationListener.getLatestReplyCapability());

        assertTrue("Post capB succeeds",
                VisionNotificationListener.processPostedNotification("key_b", "org.telegram.messenger", "Bob", "Msg B", 3000L, 0, capB));
        assertFalse("capA is inactive after capB posted", VisionNotificationListener.isCapabilityActive(capA));
        assertTrue("capB is active after posted", VisionNotificationListener.isCapabilityActive(capB));
        assertEquals("Latest capability is capB", capB, VisionNotificationListener.getLatestReplyCapability());
        assertEquals("key_b", VisionNotificationListener.getLatestNotification().key);

        // 3. Nonmatching removal preserves active state
        boolean nonMatchingRemoved = VisionNotificationListener.processRemovedNotification("key_unrelated");
        assertFalse("Nonmatching removal returns false", nonMatchingRemoved);
        assertNotNull("Latest notification intact after nonmatching removal", VisionNotificationListener.getLatestNotification());
        assertEquals("key_b", VisionNotificationListener.getLatestNotification().key);
        assertEquals("Latest capability intact after nonmatching removal", capB, VisionNotificationListener.getLatestReplyCapability());
        assertTrue("capB remains active after nonmatching removal", VisionNotificationListener.isCapabilityActive(capB));
        assertFalse("capA remains inactive", VisionNotificationListener.isCapabilityActive(capA));
        assertTrue("ListenerState remains ACTIVE_NOTIFICATION", VisionNotificationListener.getListenerState().isNotificationActive());
        assertTrue("ListenerState still has reply capability", VisionNotificationListener.getListenerState().hasReplyCapability);

        // Matching removal clears state
        boolean matchingRemoved = VisionNotificationListener.processRemovedNotification("key_b");
        assertTrue("Matching removal returns true", matchingRemoved);
        assertNull("Latest notification null after matching removal", VisionNotificationListener.getLatestNotification());
        assertNull("Latest capability null after matching removal", VisionNotificationListener.getLatestReplyCapability());
        assertFalse("capB inactive after removal", VisionNotificationListener.isCapabilityActive(capB));
        assertTrue("ListenerState is NOTIFICATION_REMOVED", VisionNotificationListener.getListenerState().isNotificationRemoved());
    }

    @Test
    public void test27_directMessageParsingAndRiskPolicy() {
        VisionAction sms = VisionActionParser.parse("send message to +91 98765-43210: Line 1\nLine 2");
        assertEquals(VisionAction.Type.SEND_MESSAGE_DIRECT, sms.type);
        assertEquals("+91 98765-43210", sms.target);
        assertEquals("sms", sms.channel);
        assertEquals("Line 1\nLine 2", sms.replyText);
        assertTrue("Direct message requires confirmation", sms.requiresConfirmation());
        assertEquals(VisionRiskPolicy.RiskTier.CONFIRMED,
                VisionRiskPolicy.getRiskTier(VisionAction.Type.SEND_MESSAGE_DIRECT));
        assertEquals("Send a new message to +91 98765-43210", sms.label());

        VisionAction email = VisionActionParser.parse("send email to alice@example.com: Subject: hello");
        assertEquals(VisionAction.Type.SEND_MESSAGE_DIRECT, email.type);
        assertEquals("email", email.channel);
        assertEquals("Subject: hello", email.replyText);
        assertEquals(VisionAction.Type.UNKNOWN,
                VisionActionParser.parse("send email to alice?x@example.com: Hello").type);

        VisionAction whatsapp = VisionActionParser.parse("send WhatsApp message to +15551234567: Hello");
        assertEquals(VisionAction.Type.SEND_MESSAGE_DIRECT, whatsapp.type);
        assertEquals("whatsapp", whatsapp.channel);
        assertEquals(VisionAction.Type.UNKNOWN,
                VisionActionParser.parse("send WhatsApp message to 15551234567: Hello").type);

        VisionAction whatsappBusiness = VisionActionParser.parse("send WhatsApp Business message to +15551234567: Hello");
        assertEquals(VisionAction.Type.SEND_MESSAGE_DIRECT, whatsappBusiness.type);
        assertEquals("whatsapp_business", whatsappBusiness.channel);

        VisionAction telegram = VisionActionParser.parse("please send telegram message to @alice_1234: Hello");
        assertEquals(VisionAction.Type.SEND_MESSAGE_DIRECT, telegram.type);
        assertEquals("telegram", telegram.channel);

        assertEquals("reply remains notification reply", VisionAction.Type.REPLY_NOTIFICATION,
                VisionActionParser.parse("send reply Hello").type);
        String[] invalid = {
                "send message Hello", "send message to Alice Hello", "send message to Alice:",
                "send message to Alice: Hello", "send message", "send fax message to 12345678: Hi",
                "send email message to alice@example.com: ", "send message to 1-----: Hi",
                "send telegram message to @bob: Hi"
        };
        for (String command : invalid) {
            assertEquals("Invalid direct message rejected: " + command,
                    VisionAction.Type.UNKNOWN, VisionActionParser.parse(command).type);
        }
    }

    @Test
    public void test28_directMessageIntentFactory() {
        // Android framework Uri/Intent methods are not available in this JVM test environment.
        // Device validation covers the positive external-app handoff; invalid actions remain pure.
        assertNull("Unknown direct channel has no intent", DirectMessageIntentFactory.create(
                new VisionAction(VisionAction.Type.SEND_MESSAGE_DIRECT, "x", "target", "body", "fax")));
        assertNull("Unsigned SMS rejected at factory boundary", DirectMessageIntentFactory.create(
                new VisionAction(VisionAction.Type.SEND_MESSAGE_DIRECT, "x", "1234567890", "body", "sms")));
        assertNull("Null action has no intent", DirectMessageIntentFactory.create(null));
    }

    private static void assertReply(String command, String expectedTarget, String expectedText) {
        VisionAction action = VisionActionParser.parse(command);
        assertEquals("Expected REPLY_NOTIFICATION for: " + command, VisionAction.Type.REPLY_NOTIFICATION, action.type);
        assertEquals("Expected target \"" + expectedTarget + "\" for: " + command, expectedTarget, action.target);
        assertEquals("Expected replyText \"" + expectedText + "\" for: " + command, expectedText, action.replyText);
    }

    private static void assertAppLaunch(String command, String expectedTarget) {
        VisionAction action = VisionActionParser.parse(command);
        assertEquals("Expected OPEN_APP for: " + command, VisionAction.Type.OPEN_APP, action.type);
        assertEquals("Expected target \"" + expectedTarget + "\" for: " + command, expectedTarget, action.target);
    }
}
