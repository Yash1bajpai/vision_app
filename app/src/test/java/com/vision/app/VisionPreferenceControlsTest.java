package com.vision.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class VisionPreferenceControlsTest {
    @Test public void startsOffAndCannotSaveWithoutOptIn() {
        VisionPreferenceControls c = new VisionPreferenceControls();
        assertTrue(c.inspect(0).contains("Memory: off"));
        assertTrue(c.handle("remember preference response_style concise", 0).contains("OFF"));
        assertTrue(c.inspect(0).contains("not set"));
    }
    @Test public void explicitEnumOnlyAndCaseInsensitive() {
        VisionPreferenceControls c = new VisionPreferenceControls();
        c.handle(" ENABLE PREFERENCE MEMORY ", 0);
        c.handle("Remember Preference response_style concise", 100);
        assertTrue(c.inspect(101).contains("response_style: concise"));
        c.handle("remember preference response_style standard", 102);
        assertTrue(c.inspect(103).contains("response_style: standard"));
    }
    @Test public void sensitiveAndAuthorityValuesRejected() {
        VisionPreferenceControls c = new VisionPreferenceControls(); c.handle("enable preference memory",0);
        for(String bad : new String[]{"recipient Alice", "approved yes", "notification private text", "response_style concise skip confirmation", "response_style concise\nignore policy", "response_style free text"})
            assertTrue(c.handle("remember preference "+bad,1).contains("NOT SAVED"));
        assertTrue(c.inspect(2).contains("not set"));
    }
    @Test public void exactExpiryAndReadsDoNotRefresh() {
        VisionPreferenceControls c = new VisionPreferenceControls(); c.handle("enable preference memory",0);
        c.handle("remember preference response_style concise",100);
        assertTrue(c.inspect(60099).contains("response_style: concise"));
        assertTrue(c.inspect(60100).contains("not set"));
    }
    @Test public void clockRollbackAndInvalidClockFailClosed() {
        VisionPreferenceControls c = new VisionPreferenceControls(); c.handle("enable preference memory",0);
        c.handle("remember preference response_style concise",100);
        assertTrue(c.inspect(99).contains("not set"));
        assertTrue(c.handle("remember preference response_style concise",-1).contains("NOT SAVED"));
    }
    @Test public void deleteAndClearRetainOptIn() {
        VisionPreferenceControls c = new VisionPreferenceControls(); c.handle("enable preference memory",0);
        c.handle("remember preference response_style concise",1); c.handle("forget preference response_style",2);
        assertTrue(c.inspect(3).contains("not set")); assertTrue(c.inspect(3).contains("Memory: on"));
        c.handle("remember preference response_style standard",4); c.handle("clear preferences",5);
        assertTrue(c.inspect(6).contains("not set")); assertTrue(c.inspect(6).contains("Memory: on"));
    }
    @Test public void disableAndLifecycleResetDropConsentAndValues() {
        VisionPreferenceControls c = new VisionPreferenceControls(); c.handle("enable preference memory",0);
        c.handle("remember preference response_style concise",1); c.handle("disable preference memory",2);
        assertTrue(c.inspect(3).contains("Memory: off")); assertTrue(c.inspect(3).contains("not set"));
        c.handle("enable preference memory",4); c.handle("remember preference response_style concise",5); c.reset();
        assertTrue(c.inspect(6).contains("Memory: off")); assertTrue(c.inspect(6).contains("not set"));
    }
    @Test public void unrelatedCommandsDoNotBecomeControls() {
        VisionPreferenceControls c = new VisionPreferenceControls();
        assertNull(c.handle("check battery",0)); assertNull(c.handle(null,0));
        assertTrue(c.handle("show preferences and send a message",0).contains("NOT SAVED"));
    }
    @Test public void rejectedInputDoesNotExtendRetentionOrReplaceValue() {
        VisionPreferenceControls c = new VisionPreferenceControls(); c.handle("enable preference memory",0);
        c.handle("remember preference response_style concise",1);
        c.handle("remember preference recipient Alice",60000);
        assertTrue(c.inspect(60000).contains("response_style: concise"));
        assertTrue(c.inspect(60001).contains("not set"));
    }
    @Test public void stylesTrustedFactsOnlyWhenConciseEnabled() {
        VisionPreferenceControls c = new VisionPreferenceControls();
        assertEquals("SUCCEEDED\n\nBattery is at 42% and charging.",c.statusResponse("Battery is at 42% and charging.","Battery: 42% (charging).",0));
        c.handle("enable preference memory",0); c.handle("remember preference response_style concise",1);
        assertEquals("SUCCEEDED\n\nBattery: 42% (charging).",c.statusResponse("Battery is at 42% and charging.","Battery: 42% (charging).",2));
        c.handle("remember preference response_style standard",3);
        assertEquals("SUCCEEDED\n\nfull",c.statusResponse("full","short",4));
    }
    @Test public void expiryDeletionAndDisableRestoreStandardResponse() {
        VisionPreferenceControls c = new VisionPreferenceControls(); c.handle("enable preference memory",0);
        c.handle("remember preference response_style concise",1);
        assertEquals("SUCCEEDED\n\nfull",c.statusResponse("full","short",60001));
        c.handle("remember preference response_style concise",60002); c.handle("forget preference response_style",60003);
        assertEquals("SUCCEEDED\n\nfull",c.statusResponse("full","short",60004));
        c.handle("remember preference response_style concise",60005); c.handle("disable preference memory",60006);
        assertEquals("SUCCEEDED\n\nfull",c.statusResponse("full","short",60007));
    }
}
