package com.vision.app;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public class ProviderSelectorTest {
    @Test public void defaultsToDeterministicOnlyWithNoOpProvider() {
        ProviderSelector s = new ProviderSelector();
        assertEquals(ProviderMode.DETERMINISTIC_ONLY, s.mode());
        assertTrue(s.active() instanceof NoOpReasoningProvider);
        assertNull(s.active().propose("anything"));
    }

    @Test public void modelModesAreUnavailableUntilRegisteredAndSelectionIsRefused() {
        ProviderSelector s = new ProviderSelector();
        assertFalse(s.isAvailable(ProviderMode.ON_DEVICE));
        assertFalse(s.isAvailable(ProviderMode.CLOUD));
        assertFalse(s.select(ProviderMode.CLOUD));
        assertFalse(s.select(null));
        assertEquals(ProviderMode.DETERMINISTIC_ONLY, s.mode());
    }

    @Test public void failedModeNeverFallsBackToAnotherModel() {
        ProviderSelector s = new ProviderSelector();
        MockReasoningProvider local = new MockReasoningProvider("{}");
        s.register(ProviderMode.ON_DEVICE, local);
        assertFalse("cloud not registered: refused, not rerouted to on-device", s.select(ProviderMode.CLOUD));
        assertSame(s.active(), s.active());
        assertTrue(s.active() instanceof NoOpReasoningProvider);
        assertEquals(0, local.callCount);
    }

    @Test public void registeredRouteCanBeSelectedAndOnlyThatProviderIsActive() {
        ProviderSelector s = new ProviderSelector();
        MockReasoningProvider local = new MockReasoningProvider("x");
        MockReasoningProvider cloud = new MockReasoningProvider("y");
        s.register(ProviderMode.ON_DEVICE, local);
        s.register(ProviderMode.CLOUD, cloud);
        assertTrue(s.select(ProviderMode.ON_DEVICE));
        assertSame(local, s.active());
        assertTrue(s.select(ProviderMode.DETERMINISTIC_ONLY));
        assertTrue(s.active() instanceof NoOpReasoningProvider);
        assertEquals(0, cloud.callCount);
    }

    @Test public void deterministicRouteCannotBeReplacedOrNullRegistered() {
        ProviderSelector s = new ProviderSelector();
        MockReasoningProvider p = new MockReasoningProvider("x");
        try { s.register(ProviderMode.DETERMINISTIC_ONLY, p); fail(); } catch (IllegalArgumentException expected) { }
        try { s.register(ProviderMode.CLOUD, null); fail(); } catch (IllegalArgumentException expected) { }
        try { s.register(null, p); fail(); } catch (IllegalArgumentException expected) { }
        assertTrue(s.active() instanceof NoOpReasoningProvider);
    }

    @Test public void unknownModeNamesMapToDeterministicOnly() {
        assertEquals(ProviderMode.CLOUD, ProviderMode.fromName("CLOUD"));
        assertEquals(ProviderMode.ON_DEVICE, ProviderMode.fromName(" ON_DEVICE "));
        assertEquals(ProviderMode.DETERMINISTIC_ONLY, ProviderMode.fromName("cloud"));
        assertEquals(ProviderMode.DETERMINISTIC_ONLY, ProviderMode.fromName("OPENAI"));
        assertEquals(ProviderMode.DETERMINISTIC_ONLY, ProviderMode.fromName(null));
    }

    @Test public void appManifestStillHasNoInternetPermission() throws Exception {
        String manifest = new String(Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")), StandardCharsets.UTF_8);
        assertFalse(manifest.contains("android.permission.INTERNET"));
    }
}
