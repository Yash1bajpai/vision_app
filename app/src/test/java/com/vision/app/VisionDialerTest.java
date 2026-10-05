package com.vision.app;

import android.content.Intent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VisionDialerTest {
    @Test public void parsesOnlyExplicitInternationalNumbers() {
        for (String s : new String[]{"call +15555550123", "DIAL +15555550123"}) {
            VisionAction a = VisionActionParser.parse(s);
            assertEquals(VisionAction.Type.OPEN_DIALER, a.type);
            assertTrue(a.requiresConfirmation());
        }
        for (String s : new String[]{"call Alice", "call 5551234", "call +15555550123 now", "call +15555550123;123", "call +15555550123 check battery"})
            assertEquals(s, VisionAction.Type.UNKNOWN, VisionActionParser.parse(s).type);
    }
    @Test public void intentCannotPlaceCallAndRejectsUnsafeNumbers() {
        Intent i = DialerIntentFactory.create("+15555550123");
        assertEquals(Intent.ACTION_DIAL, i.getAction());
        assertEquals("tel", i.getData().getScheme());
        assertEquals("+15555550123", i.getData().getSchemeSpecificPart());
        for (String n : new String[]{"Alice", "911", "+15555550123#", "+15555550123,123"}) {
            try { DialerIntentFactory.create(n); fail(n); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void modelPayloadCannotSmuggleDialExtras() {
        Map<String,String> f = new LinkedHashMap<>();
        f.put("type","OPEN_DIALER"); f.put("target","+15555550123"); f.put("text",""); f.put("channel","");
        assertNotNull(ReasoningProposalValidator.validate(f).action);
        f.put("text","123"); assertNull(ReasoningProposalValidator.validate(f).action);
        f.put("text",""); f.put("channel","sms"); assertNull(ReasoningProposalValidator.validate(f).action);
        f.put("channel",""); f.put("target","+15555550123#"); assertNull(ReasoningProposalValidator.validate(f).action);
    }
}
