package com.vision.app;

import android.content.Context;
import org.robolectric.RuntimeEnvironment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ModelManagerTest {
    private Context context;
    private ModelManager modelManager;
    private File modelsDir;

    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
        modelManager = new ModelManager(context);
        modelsDir = modelManager.getModelsDir();
    }

    @After
    public void cleanup() {
        if (modelsDir != null && modelsDir.exists()) {
            File[] files = modelsDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
        }
    }

    // 1. models.json round-trip
    @Test
    public void modelsJsonRoundTripPreservesAllFields() throws Exception {
        List<ModelInfo> models = new ArrayList<>();
        ModelInfo m1 = new ModelInfo(
                "model-1",
                "Llama 3.2 1B",
                "llama3.gguf",
                800000000L,
                "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83",
                "catalog",
                "llama3.2",
                1700000000000L,
                true,
                true,
                "llama3"
        );
        ModelInfo m2 = new ModelInfo(
                "model-2",
                "Imported Model",
                "custom.gguf",
                500000000L,
                null,
                "import",
                "custom",
                1700000001000L,
                false,
                true,
                "chatml"
        );
        models.add(m1);
        models.add(m2);

        // Create the dummy files so listInstalledModels finds them
        new File(modelsDir, m1.fileName).createNewFile();
        new File(modelsDir, m2.fileName).createNewFile();

        modelManager.saveInstalledModels(models);
        modelManager.setActiveModelId("model-1");

        List<ModelInfo> loaded = modelManager.listInstalledModels();
        assertEquals(2, loaded.size());

        ModelInfo loaded1 = loaded.get(0);
        assertEquals("model-1", loaded1.id);
        assertEquals("Llama 3.2 1B", loaded1.displayName);
        assertEquals("llama3.gguf", loaded1.fileName);
        assertEquals(800000000L, loaded1.sizeBytes);
        assertEquals("6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83", loaded1.sha256);
        assertEquals("catalog", loaded1.source);
        assertEquals("llama3.2", loaded1.licenseSpdx);
        assertEquals(1700000000000L, loaded1.addedAt);
        assertTrue(loaded1.active);
        assertEquals("llama3", loaded1.templateHint);

        ModelInfo loaded2 = loaded.get(1);
        assertEquals("model-2", loaded2.id);
        assertNull(loaded2.sha256);
        assertEquals("import", loaded2.source);
        assertFalse(loaded2.active);
        assertEquals("chatml", loaded2.templateHint);
    }

    // 2. Import magic-byte validation including truncated/corrupt
    @Test
    public void importMagicByteValidation() throws Exception {
        // Exact ASCII 'G','G','U','F' (0x47, 0x47, 0x55, 0x46)
        byte[] validHeader = new byte[]{0x47, 0x47, 0x55, 0x46, 0x03, 0x00, 0x00, 0x00};
        assertTrue(ModelManager.validateGgufMagic(validHeader));

        // Wrong magic
        byte[] wrongMagic = new byte[]{0x46, 0x55, 0x47, 0x47};
        assertFalse(ModelManager.validateGgufMagic(wrongMagic));

        // Truncated headers (less than 4 bytes)
        assertFalse(ModelManager.validateGgufMagic(new byte[]{0x47, 0x47, 0x55}));
        assertFalse(ModelManager.validateGgufMagic(new byte[]{0x47, 0x47}));
        assertFalse(ModelManager.validateGgufMagic(new byte[]{0x47}));
        assertFalse(ModelManager.validateGgufMagic(new byte[]{}));
        assertFalse(ModelManager.validateGgufMagic(null));

        // Full import of valid GGUF stream succeeds
        ByteArrayInputStream validStream = new ByteArrayInputStream(validHeader);
        ModelInfo imported = modelManager.importModel(validStream, "ValidModel", validHeader.length);
        assertNotNull(imported);
        assertTrue(new File(modelsDir, imported.fileName).exists());

        // Import of invalid magic throws IllegalArgumentException
        ByteArrayInputStream invalidStream = new ByteArrayInputStream(new byte[]{0x00, 0x01, 0x02, 0x03});
        try {
            modelManager.importModel(invalidStream, "BadModel", 4);
            fail("Should have rejected bad GGUF magic header");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("magic header missing"));
        }

        // Import of truncated header throws IllegalArgumentException
        ByteArrayInputStream truncatedStream = new ByteArrayInputStream(new byte[]{0x47, 0x47});
        try {
            modelManager.importModel(truncatedStream, "TruncatedModel", 2);
            fail("Should have rejected truncated header");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("magic header missing"));
        }
    }
    // N1: Import magic-byte loop handles fragmented stream reads (< 4 bytes per read)
    @Test
    public void importMagicByteLoopHandlesFragmentedReads() throws Exception {
        byte[] validHeader = new byte[]{0x47, 0x47, 0x55, 0x46, 0x01, 0x02};
        java.io.InputStream singleByteStream = new java.io.InputStream() {
            int pos = 0;
            @Override
            public int read() {
                if (pos >= validHeader.length) return -1;
                return validHeader[pos++] & 0xFF;
            }
            @Override
            public int read(byte[] b, int off, int len) {
                if (pos >= validHeader.length) return -1;
                b[off] = validHeader[pos++];
                return 1;
            }
        };

        ModelInfo imported = modelManager.importModel(singleByteStream, "FragmentedModel", validHeader.length);
        assertNotNull("Fragmented stream must successfully import after buffering 4 magic bytes", imported);
        assertTrue(new File(modelsDir, imported.fileName).exists());
    }

    // R2: Download guards and deduplication
    @Test
    public void downloadGuardsAndDeduplication() throws Exception {
        CatalogEntry entry = modelManager.loadCatalog().get(0);

        // 1. Pending download state
        modelManager.setPendingDownload(12345L, entry.id);
        assertEquals(12345L, modelManager.getPendingDownloadId());
        assertEquals(entry.id, modelManager.getPendingDownloadCatalogId());

        // Attempting startDownload while pending throws IllegalStateException
        try {
            modelManager.startDownload(entry);
            fail("Should refuse startDownload when download already pending");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("already in progress"));
        }

        // 2. Clear pending and complete download
        modelManager.clearPendingDownload();
        File dummyPart = new File(modelsDir, "test.part");
        try (FileOutputStream fos = new FileOutputStream(dummyPart)) {
            fos.write("dummy content".getBytes(StandardCharsets.UTF_8));
        }
        String hash = ModelManager.computeSha256(dummyPart);
        CatalogEntry testEntry = new CatalogEntry(
                entry.id, entry.displayName, entry.fileName, dummyPart.length(),
                hash, entry.downloadUrl, entry.upstreamRepo, entry.upstreamCommit,
                entry.licenseSpdx, entry.licenseUrl, entry.templateHint, entry.defaultCtx
        );

        assertTrue(modelManager.completeDownload(testEntry, dummyPart));
        assertTrue(modelManager.isModelInstalled(entry.id));
        assertEquals(-1L, modelManager.getPendingDownloadId());

        // 3. Attempting startDownload when model already installed throws IllegalStateException
        try {
            modelManager.startDownload(entry);
            fail("Should refuse startDownload when model already installed");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("already installed"));
        }

        // 4. Re-download replaces instead of duplicating
        File dummyPart2 = new File(modelsDir, "test2.part");
        try (FileOutputStream fos = new FileOutputStream(dummyPart2)) {
            fos.write("updated content".getBytes(StandardCharsets.UTF_8));
        }
        String hash2 = ModelManager.computeSha256(dummyPart2);
        CatalogEntry updatedEntry = new CatalogEntry(
                entry.id, "Updated Display", entry.fileName, dummyPart2.length(),
                hash2, entry.downloadUrl, entry.upstreamRepo, entry.upstreamCommit,
                entry.licenseSpdx, entry.licenseUrl, entry.templateHint, entry.defaultCtx
        );
        assertTrue(modelManager.completeDownload(updatedEntry, dummyPart2));

        List<ModelInfo> installed = modelManager.listInstalledModels();
        int count = 0;
        for (ModelInfo m : installed) {
            if (m.id.equals(entry.id)) count++;
        }
        assertEquals("Model must be replaced, not duplicated", 1, count);
    }

    // 3. Delete path-traversal rejection
    @Test
    public void deletePathTraversalRejection() throws Exception {
        ModelInfo evilModel = new ModelInfo(
                "evil-1",
                "Evil Path",
                "../evil.txt",
                100L,
                null,
                "import",
                "custom",
                System.currentTimeMillis(),
                false,
                true,
                ""
        );

        try {
            modelManager.deleteModel(evilModel);
            fail("Should have rejected path traversal in fileName");
        } catch (SecurityException expected) {
            assertTrue(expected.getMessage().contains("Path traversal attempt detected"));
        }
    }

    // 4. In-use delete block
    @Test
    public void deleteBlockedWhileModelInUse() throws Exception {
        ModelInfo m = new ModelInfo(
                "active-1",
                "Active Model",
                "active.gguf",
                100L,
                null,
                "import",
                "custom",
                System.currentTimeMillis(),
                true,
                true,
                ""
        );
        new File(modelsDir, m.fileName).createNewFile();

        modelManager.setModelInUse(true);
        try {
            modelManager.deleteModel(m);
            fail("Should have blocked delete while model is in use");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Cannot delete model while generation is in progress"));
        } finally {
            modelManager.setModelInUse(false);
        }

        // Now deletion should succeed
        modelManager.deleteModel(m);
        assertFalse(new File(modelsDir, m.fileName).exists());
    }

    // AB-BA lock-ordering deadlock between LocalModelProvider and ModelManager is prevented
    // by construction: ModelManager.deleteModel takes no callback under its monitor, and callers
    // follow the strict lock order of LocalModelProvider before ModelManager.
    @Test
    public void deleteActiveModelClearsActiveIdAndFile() throws Exception {
        ModelInfo active = new ModelInfo(
                "active-del", "Active To Delete", "active_del.gguf", 200L,
                null, "import", "custom", System.currentTimeMillis(), true, true, "llama3"
        );
        File f = new File(modelsDir, active.fileName);
        f.createNewFile();

        List<ModelInfo> list = new ArrayList<>();
        list.add(active);
        modelManager.saveInstalledModels(list);
        modelManager.setActiveModelId(active.id);

        assertEquals("active-del", modelManager.getActiveModelId());
        assertTrue(f.exists());

        assertTrue(modelManager.deleteModel(active));

        assertNull("Active model ID must be cleared when active model is deleted", modelManager.getActiveModelId());
        assertFalse("Model file must be deleted", f.exists());
        assertTrue("Installed models list must be empty", modelManager.listInstalledModels().isEmpty());
    }

    @Test
    public void deleteNonActiveModelKeepsActiveSelection() throws Exception {
        ModelInfo active = new ModelInfo(
                "active-stay", "Active Stays", "active_stay.gguf", 200L,
                null, "import", "custom", System.currentTimeMillis(), true, true, "llama3"
        );
        ModelInfo inactive = new ModelInfo(
                "inactive-del", "Inactive Delete", "inactive_del.gguf", 100L,
                null, "import", "custom", System.currentTimeMillis(), false, true, "chatml"
        );
        File fActive = new File(modelsDir, active.fileName);
        File fInactive = new File(modelsDir, inactive.fileName);
        fActive.createNewFile();
        fInactive.createNewFile();

        List<ModelInfo> list = new ArrayList<>();
        list.add(active);
        list.add(inactive);
        modelManager.saveInstalledModels(list);
        modelManager.setActiveModelId(active.id);

        assertEquals("active-stay", modelManager.getActiveModelId());

        assertTrue(modelManager.deleteModel(inactive));

        assertEquals("Active model ID must remain unchanged when deleting an inactive model",
                "active-stay", modelManager.getActiveModelId());
        assertTrue("Active model file must remain untouched", fActive.exists());
        assertFalse("Inactive model file must be deleted", fInactive.exists());
        assertEquals(1, modelManager.listInstalledModels().size());
        assertEquals("active-stay", modelManager.listInstalledModels().get(0).id);
    }

    // 5. Bundled catalog parses + required fields
    @Test
    public void bundledCatalogParsesWithRequiredFields() {
        List<CatalogEntry> catalog = modelManager.loadCatalog();
        assertNotNull(catalog);
        assertEquals("Catalog must contain exactly 2 bundled models", 2, catalog.size());

        CatalogEntry llama = catalog.get(0);
        assertEquals("llama-3.2-1b-instruct-q4_k_m", llama.id);
        assertEquals("Llama 3.2 1B Instruct (Q4_K_M)", llama.displayName);
        assertEquals("Llama-3.2-1B-Instruct-Q4_K_M.gguf", llama.fileName);
        assertEquals(807694464L, llama.sizeBytes);
        assertEquals("6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83", llama.sha256);
        assertTrue(llama.downloadUrl.startsWith("https://huggingface.co/"));
        assertEquals("llama3.2", llama.licenseSpdx);
        assertEquals("llama3", llama.templateHint);
        assertEquals(1024, llama.defaultCtx);

        CatalogEntry qwen = catalog.get(1);
        assertEquals("qwen2.5-1.5b-instruct-q4_k_m", qwen.id);
        assertEquals("Qwen 2.5 1.5B Instruct (Q4_K_M)", qwen.displayName);
        assertEquals("qwen2.5-1.5b-instruct-q4_k_m.gguf", qwen.fileName);
        assertEquals(1117320736L, qwen.sizeBytes);
        assertEquals("6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e", qwen.sha256);
        assertTrue(qwen.downloadUrl.startsWith("https://huggingface.co/"));
        assertEquals("Apache-2.0", qwen.licenseSpdx);
        assertEquals("chatml", qwen.templateHint);
        assertEquals(1024, qwen.defaultCtx);
    }

    // 6. SHA-256 verifier against known test vectors
    @Test
    public void sha256VerifierAgainstKnownVectors() throws Exception {
        // Test vector 1: Empty file -> e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        File emptyFile = new File(modelsDir, "empty.txt");
        emptyFile.createNewFile();
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ModelManager.computeSha256(emptyFile));
        assertTrue(ModelManager.verifySha256(emptyFile, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
        assertFalse(ModelManager.verifySha256(emptyFile, "wronghash"));

        // Test vector 2: "hello world" -> b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
        File helloFile = new File(modelsDir, "hello.txt");
        try (FileOutputStream fos = new FileOutputStream(helloFile)) {
            fos.write("hello world".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
                ModelManager.computeSha256(helloFile));
        assertTrue(ModelManager.verifySha256(helloFile, "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"));
        assertFalse(ModelManager.verifySha256(helloFile, null));
    }

    // 7. Fit estimator branches
    @Test
    public void fitEstimatorBranches() {
        // Very small size should estimate to fit under Robolectric's default memory
        ModelManager.FitStatus fitSmall = modelManager.estimateFit(1024L);
        assertNotNull(fitSmall);

        // Huge size exceeding device memory yields warning
        ModelManager.FitStatus fitHuge = modelManager.estimateFit(100L * 1024L * 1024L * 1024L);
        assertEquals(ModelManager.FitStatus.WARN_LOW_MEMORY, fitHuge);
    }
}
