package com.vision.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * Exercises the real vision_llama native library on device WITHOUT model weights.
 * Invalid-path rejection is asserted through DIRECT nativeLoad calls: the public
 * wrapper LlamaBridge.load swallows Throwable into 0, so wrapper-level assertions
 * could pass without the native entry ever running. Direct calls surface an
 * UnsatisfiedLinkError if the library failed to load. No test passes a nonzero
 * handle that did not come from a successful load - native code would dereference
 * it - and nativeLoad(null, ...) is not called (jstring null is undefined there).
 */
@RunWith(AndroidJUnit4.class)
public class NativeBridgeInstrumentedTest {

    @Test
    public void nativeLibraryLoadsOnDeviceAbi() {
        assertTrue("vision_llama must load on this device ABI: " + LlamaBridge.getLoadError(),
                LlamaBridge.isLibraryLoaded());
    }

    @Test
    public void nativeLoadRejectsNonexistentPathDirectly() {
        // Direct native entry: 0 can only come from the real loader rejecting the path.
        assertEquals("missing file must be rejected by the real native loader", 0L,
                LlamaBridge.nativeLoad("/nonexistent/definitely_not_here.gguf", 128, 1));
    }

    @Test
    public void nativeLoadRejectsDirectoryPathDirectly() {
        File dir = InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir();
        assertEquals("a directory must be rejected by the real native loader", 0L,
                LlamaBridge.nativeLoad(dir.getAbsolutePath(), 128, 1));
    }

    @Test
    public void nativeLoadRejectsTruncatedGgufDirectly() throws Exception {
        File dir = InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir();
        File truncated = new File(dir, "truncated_test.gguf");
        try {
            ByteBuffer bb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            bb.put(new byte[]{0x47, 0x47, 0x55, 0x46}); // "GGUF"
            bb.putInt(3);                               // version
            bb.putInt(0xDEADBEEF);                      // garbage where tensor count starts
            try (FileOutputStream fos = new FileOutputStream(truncated)) {
                fos.write(bb.array());
            }
            assertEquals("truncated GGUF must be rejected by the real native loader", 0L,
                    LlamaBridge.nativeLoad(truncated.getAbsolutePath(), 128, 1));
        } finally {
            truncated.delete();
        }
    }

    @Test
    public void wrapperGuardsZeroAndNullInputs() {
        // Wrapper-level API behavior: guards return before any native entry.
        LlamaBridge bridge = new LlamaBridge();
        assertEquals(0L, bridge.load(null, 128, 1));
        assertNull("generate with handle 0 is rejected by the Java guard",
                bridge.generate(0, "hello", null, 8, 1000));
        assertNull("formatChat with handle 0 is rejected by the Java guard",
                bridge.formatChat(0, "chatml", "system", "user"));
        bridge.unload(0); // must be a no-op, no crash
    }
}
