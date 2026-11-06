package com.vision.app;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
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

    private void installDummyActiveModel(MainActivity a) throws Exception {
        ModelManager mm = (ModelManager) field(a, "modelManager");
        File dummyFile = new File(mm.getModelsDir(), "active-model.gguf");
        try (FileOutputStream fos = new FileOutputStream(dummyFile)) {
            fos.write(new byte[]{0x47, 0x47, 0x55, 0x46});
        }
        ModelInfo info = new ModelInfo(
                "active-1", "Active Test Model", "active-model.gguf",
                dummyFile.length(), null, "import", "custom",
                System.currentTimeMillis(), true, true, "llama3"
        );
        List<ModelInfo> list = new ArrayList<>();
        list.add(info);
        mm.saveInstalledModels(list);
        mm.setActiveModelId("active-1");
    }

    private void cleanupDummyModel(MainActivity a) throws Exception {
        ModelManager mm = (ModelManager) field(a, "modelManager");
        if (mm != null) {
            File dummy = new File(mm.getModelsDir(), "active-model.gguf");
            if (dummy.exists()) dummy.delete();
            File meta = new File(mm.getModelsDir(), "models.json");
            if (meta.exists()) meta.delete();
            mm.setActiveModelId(null);
        }
    }

    private static class QueueBridge implements LlamaBridgeInterface {
        final List<String> queue = new ArrayList<>();
        int generateCallCount = 0;
        QueueBridge(String first, String second) { queue.add(first); queue.add(second); }
        @Override public long load(String path, int ctxTokens, int threads) { return 1L; }
        @Override public String generate(long handle, String prompt, String gbnfGrammar, int maxTokens, long timeoutMs) {
            generateCallCount++;
            return queue.isEmpty() ? null : queue.remove(0);
        }
        @Override public void unload(long handle) { }
        @Override public String formatChat(long handle, String hint, String sys, String user) { return "<f>" + user + "</f>"; }
    }

    private static class InlineExecutor extends java.util.concurrent.AbstractExecutorService {
        private volatile boolean off = false;
        @Override public void shutdown() { off = true; }
        @Override public java.util.List<Runnable> shutdownNow() { off = true; return java.util.Collections.emptyList(); }
        @Override public boolean isShutdown() { return off; }
        @Override public boolean isTerminated() { return off; }
        @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
        @Override public void execute(Runnable r) { r.run(); }
    }

    private LocalModelProvider realLocalProvider(MainActivity a, LlamaBridgeInterface bridge) throws Exception {
        ModelManager mm = (ModelManager) field(a, "modelManager");
        return new LocalModelProvider(a, mm, bridge, new Handler(Looper.getMainLooper()),
                new InlineExecutor(),
                new LocalModelProvider.ClockSource() { @Override public long elapsedRealtimeMs() { return 0L; } });
    }

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
        installDummyActiveModel(a);
        try {
            MockReasoningProvider fake = new MockReasoningProvider(TIME);
            selector(a).register(ProviderMode.ON_DEVICE, fake);
            command(a, "provider use on-device");
            assertEquals(ProviderMode.ON_DEVICE, selector(a).mode());
            command(a, "gibberish zzz qqq");
            settle(1500);
            assertEquals(1, fake.callCount);
            assertFalse(text(a).startsWith("THINKING"));
            assertFalse("proposal shown, not a failure", text(a).startsWith("MODEL"));
        } finally {
            cleanupDummyModel(a);
            c.pause().stop().destroy();
        }
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
        installDummyActiveModel(a);
        try {
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
        } finally {
            cleanupDummyModel(a);
            c.pause().stop().destroy();
        }
    }

    @Test public void backgroundingCancelsInFlightRequest() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        installDummyActiveModel(a);
        try {
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
        } finally {
            cleanupDummyModel(a);
            c.destroy();
        }
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
    @Test public void retryCorrectedConfirmedProposalWaitsApprovalAndDismissHasNoEffect() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        installDummyActiveModel(a);
        LocalModelProvider p = null;
        try {
            QueueBridge bridge = new QueueBridge(
                    "{\"type\":\"REMIND\",\"target\":\"18:15\",\"text\":\"call\",\"channel\":\"\"}",
                    "{\"type\":\"SET_REMINDER\",\"target\":\"18:15\",\"text\":\"call\",\"channel\":\"\"}");
            p = realLocalProvider(a, bridge);
            selector(a).register(ProviderMode.ON_DEVICE, p);
            command(a, "provider use on-device");
            command(a, "gibberish zzz qqq");
            settle(2500);
            assertEquals("malformed first output must trigger exactly one retry", 2, bridge.generateCallCount);
            AlertDialog d = (AlertDialog) field(a, "activeDialog");
            assertNotNull("retry-corrected CONFIRMED proposal must surface the confirmation dialog", d);
            assertTrue(d.isShowing());
            assertNull("nothing may execute while approval is pending", shadowOf(a).getNextStartedActivity());
            d.cancel();
            shadowOf(Looper.getMainLooper()).idle();
            assertNull("dismissal must not execute anything", shadowOf(a).getNextStartedActivity());
            assertFalse(text(a).startsWith("CLOCK OPENED"));
        } finally {
            if (p != null) p.shutdown();
            cleanupDummyModel(a);
            c.pause().stop().destroy();
        }
    }

    @Test public void retryIntoSafeTierSurfacesRefusalAtActivity() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        installDummyActiveModel(a);
        LocalModelProvider p = null;
        try {
            QueueBridge bridge = new QueueBridge(
                    "{\"type\":\"PLAY_MUSIC\",\"target\":\"x\",\"text\":\"\",\"channel\":\"\"}",
                    "{\"type\":\"TOGGLE_TORCH\",\"target\":\"on\",\"text\":\"\",\"channel\":\"\"}");
            p = realLocalProvider(a, bridge);
            selector(a).register(ProviderMode.ON_DEVICE, p);
            command(a, "provider use on-device");
            command(a, "gibberish zzz qqq");
            settle(2500);
            assertEquals(2, bridge.generateCallCount);
            assertTrue("SAFE-tier retry output must end in refusal, not auto-execution",
                    text(a).startsWith("REQUEST NOT RECOGNIZED"));
            AlertDialog d = (AlertDialog) field(a, "activeDialog");
            assertTrue("no confirmation may be shown for a discarded SAFE-tier retry",
                    d == null || !d.isShowing());
        } finally {
            if (p != null) p.shutdown();
            cleanupDummyModel(a);
            c.pause().stop().destroy();
        }
    }

}
