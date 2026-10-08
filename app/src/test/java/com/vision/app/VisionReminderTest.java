package com.vision.app;
import android.content.Intent;
import android.provider.AlarmClock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class VisionReminderTest {
    @Test public void parsesExactLabeledClockRequest() {
        VisionAction a=VisionActionParser.parse("remind me Drink Water at 7 pm");
        assertEquals(VisionAction.Type.SET_REMINDER,a.type);assertEquals("19:00",a.target);assertEquals("Drink Water",a.replyText);assertTrue(a.requiresConfirmation());
    }
    @Test public void rejectsDatesRecurrenceAndBadTime() {
        for(String s:new String[]{"remind me drink water tomorrow at 7 pm","remind me daily drink water at 7 pm","remind me drink water at 27:00","remind me drink water in 5 minutes","remind me check battery tomorrow"})
            assertEquals(s,VisionAction.Type.UNKNOWN,VisionActionParser.parse(s).type);
    }
    @Test public void clockIntentShowsUiAndDoesNotClaimSaving() {
        Intent i=ReminderIntentFactory.create("19:00","Drink Water");assertEquals(AlarmClock.ACTION_SET_ALARM,i.getAction());
        assertEquals(19,i.getIntExtra(AlarmClock.EXTRA_HOUR,-1));assertEquals(0,i.getIntExtra(AlarmClock.EXTRA_MINUTES,-1));assertEquals("Drink Water",i.getStringExtra(AlarmClock.EXTRA_MESSAGE));
        assertFalse(i.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI,true));assertFalse(i.hasExtra(AlarmClock.EXTRA_DAYS));
    }
    @Test public void modelValidationRejectsPayloadAndInvalidLabel() {
        Map<String,String> f=new LinkedHashMap<>();f.put("type","SET_REMINDER");f.put("target","19:00");f.put("text","Drink Water");f.put("channel","");assertNotNull(ReasoningProposalValidator.validate(f).action);
        f.put("target","24:00");assertNull(ReasoningProposalValidator.validate(f).action);f.put("target","19:00");f.put("text","tomorrow");assertNull(ReasoningProposalValidator.validate(f).action);
        f.put("text","Drink Water");f.put("channel","sms");assertNull(ReasoningProposalValidator.validate(f).action);
    }
}
