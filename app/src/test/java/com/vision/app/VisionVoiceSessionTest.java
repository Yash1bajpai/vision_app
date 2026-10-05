package com.vision.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class VisionVoiceSessionTest {
    @Test public void resultsOnlyForCurrentUnchangedDraft() {
        VisionVoiceSession s=new VisionVoiceSession();long t=s.start("draft");
        assertTrue(s.accepts(t,"draft","help"));assertFalse(s.accepts(t,"edited","help"));
        s.cancel();assertFalse(s.accepts(t,"draft","help"));
    }
    @Test public void replacementRejectsOldResultAndLimitsText() {
        VisionVoiceSession s=new VisionVoiceSession();long old=s.start("");long t=s.start("");
        assertFalse(s.accepts(old,"","help"));assertFalse(s.accepts(t,"",null));assertFalse(s.accepts(t,""," "));
        assertFalse(s.accepts(t,"",new String(new char[513]).replace('\0','a')));
    }
}
