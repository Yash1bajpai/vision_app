package com.vision.app;

import android.content.Context;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Activity lifecycle with synthetic recognition; never opens a microphone. */
@RunWith(AndroidJUnit4.class)
public class VoiceLifecycleInstrumentedTest {
    private static class FakeVoice extends OfflineVoiceInput {
        boolean cancelled;
        @Override public boolean available(Context context) { return false; }
        @Override public void cancel() { cancelled = true; }
    }
    private static Object field(MainActivity activity, String name) throws Exception {
        Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true);
        return f.get(activity);
    }
    @Test public void losingForegroundCancelsSessionAndRecognizer() throws Exception {
        FakeVoice voice = new FakeVoice();
        final VisionVoiceSession[] session = new VisionVoiceSession[1];
        final long[] token = new long[1];
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    Field f = MainActivity.class.getDeclaredField("voiceInput");
                    f.setAccessible(true); f.set(activity, voice);
                    session[0] = (VisionVoiceSession) field(activity, "voiceSession");
                    token[0] = session[0].start("");
                } catch (Exception e) { throw new AssertionError(e); }
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            assertTrue(voice.cancelled);
            assertFalse(session[0].accepts(token[0], "", "help"));
        }
    }
    @Test public void recreationDoesNotRestoreVoiceDraftOrSession() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try { ((VisionVoiceSession) field(activity, "voiceSession")).start(""); }
                catch (Exception e) { throw new AssertionError(e); }
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                try {
                    assertFalse(((VisionVoiceSession) field(activity, "voiceSession")).isActive());
                    assertEquals("", VisionUi.inputField(activity).getText().toString());
                } catch (Exception e) { throw new AssertionError(e); }
            });
        }
    }
}
