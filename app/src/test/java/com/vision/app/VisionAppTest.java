package com.vision.app;

import android.app.Notification;
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
