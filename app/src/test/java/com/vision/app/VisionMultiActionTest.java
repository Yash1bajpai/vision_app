package com.vision.app;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Chained free-text requests must be refused as UNSUPPORTED_MULTI_ACTION instead of
 * silently running only their first action. Harmless conjunctions in payloads, names
 * and single-action phrasings must keep their existing behavior.
 */
public class VisionMultiActionTest {

    private static void assertMultiAction(String request) {
        VisionAction action = VisionActionParser.parse(request);
        assertEquals("Expected UNSUPPORTED_MULTI_ACTION for: " + request,
                VisionAction.Type.UNSUPPORTED_MULTI_ACTION, action.type);
    }

    @Test
    public void chainedRequestsFromLiveEvalAreRefused() {
        // Both phrases silently ran only their first action in the 2026-10-23 live test.
        assertMultiAction("open Telegram and then open Gmail");
        assertMultiAction("turn the torch off and then on again");
    }

    @Test
    public void otherChainedRequestsAreRefused() {
        assertMultiAction("set a timer for 10 minutes and then check the battery");
        assertMultiAction("set volume to 50 and then mute");
        assertMultiAction("check my notifications and then open whatsapp");
        assertMultiAction("turn the flashlight on and off");
        assertMultiAction("play music and also stop the music");
    }

    @Test
    public void twoStatusReadsInOneRequestAreRefused() {
        assertMultiAction("check battery and network");
        assertMultiAction("what is the battery and the time");
        assertMultiAction("show battery, network");
    }

    @Test
    public void twoAppsInOneRequestAreRefused() {
        assertMultiAction("open telegram and gmail");
        assertMultiAction("open telegram then gmail");
    }

    @Test
    public void singleActionsStaySingle() {
        VisionAction open = VisionActionParser.parse("open telegram");
        assertEquals(VisionAction.Type.OPEN_APP, open.type);
        assertEquals("Telegram", open.target);

        VisionAction rockAndRoll = VisionActionParser.parse("play rock and roll music");
        assertEquals(VisionAction.Type.MEDIA_CONTROL, rockAndRoll.type);
        assertEquals("play", rockAndRoll.target);

        VisionAction duration = VisionActionParser.parse("set a timer for 1 hour and 30 minutes");
        assertEquals(VisionAction.Type.SET_TIMER, duration.type);
        assertEquals("1h 30m", duration.target);

        assertEquals(VisionAction.Type.READ_BATTERY, VisionActionParser.parse("check battery").type);
        assertEquals(VisionAction.Type.READ_NETWORK, VisionActionParser.parse("check network").type);
    }

    @Test
    public void payloadsAndNamesWithAndAreNotInspected() {
        VisionAction direct = VisionActionParser.parse("send whatsapp to divyansh: hello and then call you");
        assertEquals(VisionAction.Type.SEND_MESSAGE_DIRECT, direct.type);
        assertEquals("hello and then call you", direct.replyText);

        VisionAction reply = VisionActionParser.parse("reply to alice: sure and then see you");
        assertEquals(VisionAction.Type.REPLY_NOTIFICATION, reply.type);
        assertEquals("sure and then see you", reply.replyText);

        VisionAction reminder = VisionActionParser.parse("remind me to call mom and dad at 5 pm");
        assertEquals(VisionAction.Type.SET_REMINDER, reminder.type);

        VisionAction event = VisionActionParser.parse("create event mom and dad dinner tomorrow at 7");
        assertEquals(VisionAction.Type.CREATE_CALENDAR_EVENT, event.type);
    }

    @Test
    public void refusedRequestIsNotUnknownSoItNeverRoutesToAProvider() {
        // MainActivity sends only UNKNOWN commands to a configured cloud provider; a
        // refused chain must get the local refusal message in every provider mode.
        assertNotEquals(VisionAction.Type.UNKNOWN,
                VisionActionParser.parse("open Telegram and then open Gmail").type);
    }
}
