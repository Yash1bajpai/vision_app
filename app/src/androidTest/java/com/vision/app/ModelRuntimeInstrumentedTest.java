package com.vision.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ModelRuntimeInstrumentedTest {
    private static final String TAG = "ModelRuntimeInstrumentedTest";

    private static Object field(MainActivity activity, String name) {
        try {
            Field f = MainActivity.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(activity);
        } catch (Exception e) {
            throw new AssertionError("Missing MainActivity field: " + name, e);
        }
    }

    private static void takeScreenshot(String filename) {
        try {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            File dest = new File(dir, filename);

            Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if (bitmap != null) {
                try (FileOutputStream out = new FileOutputStream(dest)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            }
            Log.i(TAG, "Screenshot " + filename + " saved: " + dest.getAbsolutePath());
            System.out.println("Screenshot " + filename + " saved: " + dest.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "Failed taking screenshot " + filename, t);
        }
    }

    @Test
    public void settingsModelListEmptyStateAndSyntheticImportFailsGracefully() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            // 1. Open model manager dialog from empty state
            scenario.onActivity(activity -> {
                try {
                    Method m = MainActivity.class.getDeclaredMethod("showModelManagerDialog");
                    m.setAccessible(true);
                    m.invoke(activity);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            VisionUi.settle();

            scenario.onActivity(activity -> {
                AlertDialog dialog = VisionUi.activeDialog(activity);
                assertNotNull("Model manager dialog should be displayed", dialog);
                assertTrue(dialog.isShowing());
            });

            takeScreenshot("model-settings-empty.png");

            scenario.onActivity(activity -> {
                AlertDialog dialog = VisionUi.activeDialog(activity);
                if (dialog != null && dialog.isShowing()) {
                    dialog.dismiss();
                }
            });
            VisionUi.settle();

            // 2. Synthetic GGUF header stream (magic GGUF + version 3 + 0 tensors + 0 kv)
            ByteBuffer bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            bb.put(new byte[]{0x47, 0x47, 0x55, 0x46}); // "GGUF"
            bb.putInt(3);                               // version 3
            bb.putLong(0L);                             // tensor count 0
            bb.putLong(0L);                             // metadata kv count 0
            byte[] syntheticGguf = bb.array();

            scenario.onActivity(activity -> {
                try {
                    ModelManager mm = (ModelManager) field(activity, "modelManager");
                    assertNotNull(mm);

                    // Import synthetic header
                    ByteArrayInputStream bais = new ByteArrayInputStream(syntheticGguf);
                    ModelInfo imported = mm.importModel(bais, "synthetic_test.gguf", syntheticGguf.length);
                    assertNotNull("Synthetic GGUF header import must succeed", imported);

                    File importedFile = new File(mm.getModelsDir(), imported.fileName);
                    assertTrue("Imported file must exist", importedFile.exists());

                    // Loading synthetic model (no real weights) must return 0 and fail gracefully without crashing
                    LlamaBridge bridge = new LlamaBridge();
                    long handle = bridge.load(importedFile.getAbsolutePath(), 512, 2);
                    assertEquals("Loading header-only synthetic GGUF must return 0", 0L, handle);

                    // Clean up test model
                    mm.deleteModel(imported);
                    assertFalse(importedFile.exists());
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }
    }
}
