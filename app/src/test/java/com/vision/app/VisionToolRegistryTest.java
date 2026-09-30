package com.vision.app;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import android.view.KeyEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class VisionToolRegistryTest {
    private Map<String, String> fields(String type, String target, String text, String channel) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("target", target);
        result.put("text", text);
        result.put("channel", channel);
        return result;
    }

    @Test public void everySupportedTypeHasOneRegistryEntry() {
        Set<VisionAction.Type> seen = new HashSet<>();
        for (VisionToolRegistry.Tool tool : VisionToolRegistry.all()) {
            assertTrue("duplicate type", seen.add(tool.type));
            assertSame(tool, VisionToolRegistry.find(tool.type.name()));
            assertEquals(VisionRiskPolicy.requiresConfirmation(tool.type), tool.requiresConfirmation());
        }
        for (VisionAction.Type type : VisionAction.Type.values()) {
            assertEquals(type != VisionAction.Type.UNKNOWN, seen.contains(type));
        }
    }

    @Test public void registryIsImmutableAndLookupIsExact() {
        try {
            VisionToolRegistry.all().clear();
            fail("Registry must be immutable");
        } catch (UnsupportedOperationException expected) { }
        assertNull(VisionToolRegistry.find(null));
        assertNull(VisionToolRegistry.find("UNKNOWN"));
        assertNull(VisionToolRegistry.find("PAYMENT"));
        assertNull(VisionToolRegistry.find("read_time"));
        assertNull(VisionToolRegistry.find(" READ_TIME "));
    }

    @Test public void everyHelpExampleParsesAndPassesValidator() {
        for (VisionToolRegistry.Tool tool : VisionToolRegistry.all()) {
            VisionAction action = VisionActionParser.parse(tool.example);
            assertEquals(tool.example, tool.type, action.type);
            assertNotNull(tool.example, ReasoningProposalValidator.validate(
                    fields(action.type.name(), action.target, action.replyText, action.channel)).action);
        }
    }

    @Test public void helpAliasesAreSafeAndCaseInsensitive() {
        for (String request : new String[]{"help", "HELP!", " commands ", "Show Commands", "show capabilities.", "What can you do?", "what\ncan\tyou do"}) {
            VisionAction action = VisionActionParser.parse(request);
            assertEquals(VisionAction.Type.SHOW_HELP, action.type);
            assertEquals("capabilities", action.target);
            assertFalse(action.requiresConfirmation());
        }
        assertEquals(VisionAction.Type.UNKNOWN, VisionActionParser.parse("help send money").type);
        assertEquals(VisionAction.Type.REPLY_NOTIFICATION, VisionActionParser.parse("reply: help").type);
        assertEquals(VisionAction.Type.SEND_MESSAGE_DIRECT, VisionActionParser.parse("send sms to Alice: what can you do?").type);
    }

    @Test public void helpNeverNeedsTheProvider() {
        VisionAction action = ReasoningCoordinator.coordinate("help", request -> {
            throw new AssertionError("Provider must not run for deterministic help");
        });
        assertEquals(VisionAction.Type.SHOW_HELP, action.type);
    }

    @Test public void helpProposalsCannotSmugglePayloadOrApproval() {
        assertNotNull(ReasoningProposalValidator.validate(fields("SHOW_HELP", "", "", "")).action);
        assertNotNull(ReasoningProposalValidator.validate(fields("SHOW_HELP", "CAPABILITIES", "", "")).action);
        assertNull(ReasoningProposalValidator.validate(fields("SHOW_HELP", "Alice", "", "")).action);
        assertNull(ReasoningProposalValidator.validate(fields("SHOW_HELP", "", "private text", "")).action);
        assertNull(ReasoningProposalValidator.validate(fields("SHOW_HELP", "", "", "sms")).action);
        Map<String, String> approved = fields("SHOW_HELP", "", "", "");
        approved.put("approved", "true");
        assertEquals(ReasoningProposalValidator.RejectionReason.EXTRA_KEYS,
                ReasoningProposalValidator.validate(approved).reason);
    }

    @Test public void helpCanBeAValidatedPlanStep() {
        ReasoningCoordinator.CoordinationResult result = ReasoningCoordinator.coordinateFull("my options and clock", request ->
                "[{\"type\":\"SHOW_HELP\",\"target\":\"\",\"text\":\"\",\"channel\":\"\"},"
                + "{\"type\":\"READ_TIME\",\"target\":\"time\",\"text\":\"\",\"channel\":\"\"}]");
        assertNotNull(result.plan);
        assertEquals(2, result.plan.stepCount());
        assertEquals(VisionAction.Type.SHOW_HELP, result.plan.step(0).type);
    }

    @Test public void helpExplainsGatesAndDoesNotInventAbilities() {
        String help = VisionToolRegistry.helpText();
        assertTrue(help.contains("[Confirm] Reply"));
        assertTrue(help.contains("[Confirm] Open a message composer"));
        assertTrue(help.contains("[Confirm] Create a one-hour calendar event"));
        assertTrue(help.contains("final send stays external"));
        assertFalse(help.contains("cloud"));
        assertFalse(help.contains("payments"));
        assertEquals("Show supported commands", new VisionAction(VisionAction.Type.SHOW_HELP, "help", "").label());
    }

    @Test public void exportIsDeterministicStaticMetadata() {
        String json = VisionToolRegistry.toJson();
        assertEquals(json, VisionToolRegistry.toJson());
        assertTrue(json.startsWith("{\"schemaVersion\":1,"));
        assertTrue(json.contains("\"proposalKeys\":[\"type\",\"target\",\"text\",\"channel\"]"));
        assertTrue(json.contains("\"type\":\"REPLY_NOTIFICATION\""));
        assertFalse(json.contains("\"type\":\"UNKNOWN\""));
        assertFalse(json.contains("\"approved\""));
        assertEquals(1, VisionToolRegistry.SCHEMA_VERSION);
    }

    @Test public void mediaCommandsUseExplicitAndroidKeys() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, VisionMediaCommand.keyCode("play"));
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, VisionMediaCommand.keyCode("pause"));
        assertEquals(KeyEvent.KEYCODE_MEDIA_STOP, VisionMediaCommand.keyCode("stop"));
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, VisionMediaCommand.keyCode("next"));
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, VisionMediaCommand.keyCode("previous"));
        assertNotEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, VisionMediaCommand.keyCode("play"));
        assertNotEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, VisionMediaCommand.keyCode("pause"));
    }

    @Test public void unsupportedMediaCommandsFailClosed() {
        for (String command : new String[]{null, "", "toggle", "PLAY", "garbage"}) {
            try {
                VisionMediaCommand.keyCode(command);
                fail("Must reject " + command);
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void uppercaseEventDayAndAmPmPreserveMeaningAndTitle() {
        LocalDate before = LocalDate.now();
        VisionAction action = VisionActionParser.parse("CREATE EVENT Team Sync TOMORROW AT 3 PM");
        LocalDate after = LocalDate.now();
        assertEquals(VisionAction.Type.CREATE_CALENDAR_EVENT, action.type);
        assertEquals("Team Sync", action.target);
        LocalDateTime time = LocalDateTime.parse(action.replyText.replace(' ', 'T'));
        assertEquals(15, time.getHour());
        assertTrue(time.toLocalDate().equals(before.plusDays(1)) || time.toLocalDate().equals(after.plusDays(1)));
        assertTrue(VisionActionParser.parse("create event Midnight tomorrow at 12 AM").replyText.endsWith("00:00"));
        assertTrue(VisionActionParser.parse("create event Lunch tomorrow at 12 PM").replyText.endsWith("12:00"));
    }

    @Test public void impossibleTwelveHourEventTimesAreRejected() {
        for (String time : new String[]{"0 am", "13 pm", "24:00", "3:60 pm"}) {
            assertEquals(time, VisionAction.Type.UNKNOWN,
                    VisionActionParser.parse("create event Team Sync tomorrow at " + time).type);
        }
    }
}
