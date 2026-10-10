package com.vision.app;

import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class ProviderWiringTest {
    private static final String TIME = "{\"type\":\"READ_TIME\",\"target\":\"\",\"text\":\"\",\"channel\":\"\"}";
    private Object field(MainActivity a, String n) throws Exception {
        Field f = MainActivity.class.getDeclaredField(n); f.setAccessible(true); return f.get(a);
    }
    private Button send(View v) {
        if (v instanceof Button && "↑".contentEquals(((Button) v).getText())) return (Button) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { Button b = send(g.getChildAt(i)); if (b != null) return b; }
        }
        return null;
    }
    private void command(MainActivity a, String t) throws Exception {
        ((EditText) field(a, "inputField")).setText(t); send(a.getWindow().getDecorView()).performClick();
    }
    private String text(MainActivity a) throws Exception { return ((TextView) field(a, "activityText")).getText().toString(); }
    private void settle(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10); }
    }
    private ProviderSelector selector(MainActivity a) throws Exception { return (ProviderSelector) field(a, "providerSelector"); }

    @Test public void defaultModeIsDeterministicAndModelModesAreRefused() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        command(a, "provider status");
        assertTrue(text(a).contains("Mode: deterministic only"));
        command(a, "provider use cloud");
        assertTrue(text(a).startsWith("PROVIDER NOT CHANGED"));
        assertEquals(ProviderMode.DETERMINISTIC_ONLY, selector(a).mode());
        command(a, "gibberish zzz qqq");
        assertTrue(text(a).startsWith("REQUEST NOT RECOGNIZED"));
        c.pause().stop().destroy();
    }

    @Test public void modelProposalRunsOffThreadThenIsValidatedAndShown() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        MockReasoningProvider fake = new MockReasoningProvider(TIME);
        selector(a).register(ProviderMode.ON_DEVICE, fake);
        command(a, "provider use on-device");
        assertEquals(ProviderMode.ON_DEVICE, selector(a).mode());
        command(a, "gibberish zzz qqq");
        settle(1500);
        assertEquals(1, fake.callCount);
        assertFalse(text(a).startsWith("THINKING"));
        assertFalse("proposal shown, not a failure", text(a).startsWith("MODEL"));
        c.pause().stop().destroy();
    }

    @Test public void parserRecognisedCommandNeverReachesModel() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        MockReasoningProvider fake = new MockReasoningProvider(TIME);
        selector(a).register(ProviderMode.ON_DEVICE, fake);
        command(a, "provider use on-device");
        command(a, "check battery");
        settle(300);
        assertEquals(0, fake.callCount);
        c.pause().stop().destroy();
    }

    @Test public void injectedModelOutputIsRejectedFailClosed() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        selector(a).register(ProviderMode.ON_DEVICE, new MockReasoningProvider(
                "{\"type\":\"READ_TIME\",\"target\":\"\",\"text\":\"\",\"channel\":\"\",\"approved\":\"true\"}"));
        command(a, "provider use on-device");
        command(a, "gibberish zzz qqq");
        settle(1500);
        assertTrue(text(a).startsWith("REQUEST NOT RECOGNIZED"));
        c.pause().stop().destroy();
    }

    @Test public void cancelRequestDiscardsLateProposal() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        selector(a).register(ProviderMode.ON_DEVICE, new ReasoningProvider() {
            @Override public String propose(String r) {
                started.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignore) { }
                return TIME;
            }
        });
        command(a, "provider use on-device");
        command(a, "gibberish zzz qqq");
        assertTrue(text(a).startsWith("THINKING"));
        assertTrue(started.await(3, TimeUnit.SECONDS));
        command(a, "cancel request");
        assertEquals("REQUEST CANCELLED", text(a));
        release.countDown();
        settle(400);
        assertEquals("late proposal must not replace the cancel message", "REQUEST CANCELLED", text(a));
        command(a, "cancel request");
        assertEquals("NO ACTIVE REQUEST", text(a));
        c.pause().stop().destroy();
    }

    @Test public void backgroundingCancelsInFlightRequest() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        selector(a).register(ProviderMode.ON_DEVICE, new ReasoningProvider() {
            @Override public String propose(String r) {
                started.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignore) { }
                return TIME;
            }
        });
        command(a, "provider use on-device");
        command(a, "gibberish zzz qqq");
        assertTrue(started.await(3, TimeUnit.SECONDS));
        c.pause().stop();
        assertEquals(0L, field(a, "providerRequestId"));
        release.countDown();
        settle(300);
        assertFalse(text(a).contains("TIME"));
        c.destroy();
    }

    @Test public void providerCommandsParse() {
        ProviderSelector s = new ProviderSelector();
        assertNull(ProviderCommands.handle("check battery", s));
        assertNull(ProviderCommands.handle(null, s));
        assertTrue(ProviderCommands.handle("provider use bogus", s).startsWith("PROVIDER NOT CHANGED"));
        s.register(ProviderMode.CLOUD, new MockReasoningProvider(null));
        assertTrue(ProviderCommands.handle("Provider  Use CLOUD", s).startsWith("PROVIDER SET"));
        assertEquals(ProviderMode.CLOUD, s.mode());
        assertTrue(ProviderCommands.handle("provider use off", s).startsWith("PROVIDER SET"));
        assertEquals(ProviderMode.DETERMINISTIC_ONLY, s.mode());
    }
}
