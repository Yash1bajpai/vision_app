package com.vision.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import static org.junit.Assert.*;

/**
 * Real-model single-prompt JNI/schema smoke with validator-level action-fit
 * checks on the CI emulator - NOT a usability or generation-quality claim.
 * Downloads the pinned catalog model, verifies its bytes against the catalog
 * sha256, loads it through the real JNI bridge, and generates grammar-constrained
 * proposals for two commands; each proposal must parse through the app's own
 * StrictJson, be accepted by ReasoningProposalValidator, and carry the expected
 * action (TOGGLE_TORCH with target "on"; READ_TIME after an unload/reload cycle).
 * Scoped evidence: x86_64 emulator ABI, greedy+grammar generation; arm64 coverage
 * is an unverified route not selected here.
 *
 * Budget: the workflow's outer `timeout -k 30 1200 gradle` is the hard process
 * bound (native calls do not honor thread interrupts reliably); the per-test
 * JUnit timeouts below (300+200+200=700s worst case) stay well under it.
 * Cleanup: teardown deletes only the unique import-model/.part names this
 * class's import could create that were absent before the class ran, so a
 * failure between the import file move and the metadata save still leaves no
 * orphan;
 * the shared metadata files (models.json, models.json.tmp) and any
 * pre-existing file are never deleted.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class RealModelInstrumentedTest {
    private static final String CATALOG_ID = "qwen2.5-1.5b-instruct-q4_k_m";
    // Keep in sync with LocalModelProvider.DEFAULT_SYSTEM_PROMPT (private there).
    private static final String SYSTEM_PROMPT =
            "You are an on-device Android assistant. Convert the user command into a single JSON "
            + "object with exact keys \"type\", \"target\", \"text\", \"channel\".";

    private static ModelInfo installed;
    private static File installedFile;
    private static String templateHint;
    private static Set<String> preexistingFiles;
    private static String expectedSafeBase;

    @BeforeClass
    public static void snapshotModelsDir() {
        ModelManager mm = new ModelManager(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        String[] names = mm.getModelsDir().list();
        preexistingFiles = new HashSet<>(names != null ? Arrays.asList(names) : java.util.Collections.<String>emptyList());
    }

    private static org.json.JSONObject catalogEntry() throws Exception {
        InputStream is = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getAssets().open("models/catalog.json");
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, "UTF-8"));
        }
        is.close();
        org.json.JSONArray arr = new org.json.JSONArray(sb.toString());
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject e = arr.getJSONObject(i);
            if (CATALOG_ID.equals(e.getString("id"))) {
                return e;
            }
        }
        throw new AssertionError("catalog entry not found: " + CATALOG_ID);
    }

    private static String sha256Of(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = new FileInputStream(f)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = is.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test(timeout = 300_000)
    public void test1_catalogModelDownloadsWithVerifiedBytes() throws Exception {
        org.json.JSONObject entry = catalogEntry();
        String catalogSha256 = entry.getString("sha256");
        templateHint = entry.getString("templateHint");
        long expectedSize = entry.getLong("sizeBytes");

        ModelManager mm = new ModelManager(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        HttpURLConnection conn = (HttpURLConnection) new URL(entry.getString("downloadUrl")).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(60_000);
        conn.setInstanceFollowRedirects(true);
        // Class-run-unique basename: a concurrent import of the same catalog
        // display name can never share this UUID, so the teardown sweep below
        // can only ever match files this class's import created.
        String base = entry.getString("fileName").replaceAll("[^a-zA-Z0-9.-]", "_");
        if (base.endsWith(".gguf")) {
            base = base.substring(0, base.length() - ".gguf".length());
        }
        expectedSafeBase = base + "-" + java.util.UUID.randomUUID() + ".gguf";
        try {
            try (InputStream is = conn.getInputStream()) {
                installed = mm.importModel(is, expectedSafeBase, expectedSize);
            }
        } finally {
            conn.disconnect();
        }
        installedFile = new File(mm.getModelsDir(), installed.fileName);
        assertTrue("downloaded model file must exist", installedFile.exists());
        assertEquals("size must match the catalog pin", expectedSize, installedFile.length());
        assertEquals("sha256 must match the catalog pin", catalogSha256, sha256Of(installedFile));
    }

    @Test(timeout = 200_000)
    public void test2_torchCommandYieldsValidatorAcceptedTorchOn() throws Exception {
        assertNotNull("test1 must install the model first", installedFile);
        String grammar = readAsset("grammar/action_proposal.gbnf");
        LlamaBridge bridge = new LlamaBridge();
        long handle = bridge.load(installedFile.getAbsolutePath(), 1024, 2);
        assertTrue("real model must load through the JNI bridge", handle != 0L);
        try {
            String prompt = bridge.formatChat(handle, templateHint, SYSTEM_PROMPT, "turn on the torch");
            assertNotNull("formatChat must produce a prompt", prompt);
            String raw = bridge.generate(handle, prompt, grammar, 256, 90_000);
            assertNotNull("generate must return output within the bound", raw);
            System.out.println("RealModelSmoke raw proposal: " + raw);
            Map<String, String> fields = StrictJson.parseObject(raw);
            assertNotNull("proposal must parse through the app strict JSON parser", fields);
            ReasoningProposalValidator.ValidationResult vr =
                    ReasoningProposalValidator.validate(fields);
            assertNotNull("validation must run", vr);
            assertNotNull("the app's own validator must accept the whole proposal"
                    + (vr.reason != null ? "; rejected: " + vr.reason : ""), vr.action);
            assertEquals("a torch command must map to the torch action",
                    VisionAction.Type.TOGGLE_TORCH, vr.action.type);
            assertEquals("turn on must carry target on", "on", vr.action.target);
        } finally {
            bridge.unload(handle);
        }
    }

    @Test(timeout = 200_000)
    public void test3_timeQueryAfterUnloadReloadYieldsValidatorAcceptedReadTime() throws Exception {
        assertNotNull("test1 must install the model first", installedFile);
        String grammar = readAsset("grammar/action_proposal.gbnf");
        LlamaBridge bridge = new LlamaBridge();
        long first = bridge.load(installedFile.getAbsolutePath(), 1024, 2);
        assertTrue(first != 0L);
        bridge.unload(first);
        long second = bridge.load(installedFile.getAbsolutePath(), 1024, 2);
        assertTrue("reload after unload must succeed", second != 0L);
        try {
            String prompt = bridge.formatChat(second, templateHint, SYSTEM_PROMPT, "what time is it");
            String raw = bridge.generate(second, prompt, grammar, 256, 90_000);
            assertNotNull("generate after reload must return output", raw);
            Map<String, String> fields = StrictJson.parseObject(raw);
            assertNotNull("proposal must parse through the app strict JSON parser", fields);
            ReasoningProposalValidator.ValidationResult vr =
                    ReasoningProposalValidator.validate(fields);
            assertNotNull("validation must run", vr);
            assertNotNull("the app's own validator must accept the whole proposal"
                    + (vr.reason != null ? "; rejected: " + vr.reason : ""), vr.action);
            assertEquals("a time query must map to the time action",
                    VisionAction.Type.READ_TIME, vr.action.type);
        } finally {
            bridge.unload(second);
        }
    }

    private static String readAsset(String name) throws Exception {
        InputStream is = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getAssets().open(name);
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, "UTF-8"));
        }
        is.close();
        return sb.toString();
    }

    @AfterClass
    public static void removeOwnedArtifacts() throws Exception {
        ModelManager mm = new ModelManager(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        if (installed != null) {
            mm.deleteModel(installed);
        }
        // Exact-ownership sweep: delete only the unique names this class's import
        // could create (its "import-*-<safeBase>" file or a ".part" remainder if it
        // failed between the rename and the metadata save), and only when the name
        // was not present before this class ran. models.json and models.json.tmp are
        // shared publisher files (see ModelManager's metadata lock), never
        // class-owned, so they are never deleted here; deleteModel handles the
        // metadata when installed != null.
        if (expectedSafeBase == null) {
            return;
        }
        File[] files = mm.getModelsDir().listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            String n = f.getName();
            boolean owned = n.startsWith("import-")
                    && (n.endsWith("-" + expectedSafeBase)
                        || n.endsWith("-" + expectedSafeBase + ".part"));
            if (owned && !preexistingFiles.contains(n)) {
                f.delete();
            }
        }
    }
}
