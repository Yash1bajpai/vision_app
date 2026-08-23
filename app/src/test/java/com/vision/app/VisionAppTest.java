package com.vision.app;

public class VisionAppTest {
    public static void main(String[] args) {
        int passed = 0;
        int failed = 0;

        System.out.println("Running Vision Phase A deterministic test suite...");

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

        // Test 4: Unrecognized requests
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
            System.err.println("Test 4 FAILED: " + t.getMessage());
            failed++;
        }

        // Test 5: VisionAction state machine and labels
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

            VisionAction a3 = new VisionAction(null, null, null);
            assertCondition(a3.type == VisionAction.Type.UNKNOWN, "null type defaults to UNKNOWN");
            assertCondition(a3.request.isEmpty(), "null request defaults to empty");
            assertCondition(a3.target.isEmpty(), "null target defaults to empty");
            passed++;
        } catch (Throwable t) {
            System.err.println("Test 5 FAILED: " + t.getMessage());
            failed++;
        }

        // Test 6: Notification package support allowlist
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
            System.err.println("Test 6 FAILED: " + t.getMessage());
            failed++;
        }

        // Test 7: Notification snapshot creation and key matching
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
            System.err.println("Test 7 FAILED: " + t.getMessage());
            failed++;
        }

        System.out.println("Tests passed: " + passed + ", failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
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
