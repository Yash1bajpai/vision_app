package com.vision.app;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class VisionCompletionGateTest {

    @Test public void firstFireRunsCompletion() {
        VisionCompletionGate gate = new VisionCompletionGate();
        AtomicInteger calls = new AtomicInteger();
        assertTrue(gate.fireOnce(calls::incrementAndGet));
        assertEquals(1, calls.get());
        assertTrue(gate.hasFired());
    }

    @Test public void laterFiresAreIgnored() {
        VisionCompletionGate gate = new VisionCompletionGate();
        AtomicInteger calls = new AtomicInteger();
        assertTrue(gate.fireOnce(calls::incrementAndGet));
        assertFalse(gate.fireOnce(calls::incrementAndGet));
        assertFalse(gate.fireOnce(calls::incrementAndGet));
        assertEquals(1, calls.get());
    }

    @Test public void nullCompletionStillLatches() {
        VisionCompletionGate gate = new VisionCompletionGate();
        assertTrue(gate.fireOnce(null));
        assertTrue(gate.hasFired());
        assertFalse(gate.fireOnce(() -> fail("completion must not run twice")));
    }

    @Test public void failedCompletionDoesNotUnlockGate() {
        VisionCompletionGate gate = new VisionCompletionGate();
        try {
            gate.fireOnce(() -> { throw new RuntimeException("boom"); });
            fail("exception should propagate");
        } catch (RuntimeException expected) { }
        assertTrue(gate.hasFired());
        assertFalse(gate.fireOnce(() -> fail("completion must not run twice")));
    }
}
