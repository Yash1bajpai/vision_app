package com.vision.app;

import android.app.ActivityManager;
import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages on-device model discovery, catalog, installation, and lifecycle.
 *
 * Lock discipline:
 * Lock order is always LocalModelProvider before ModelManager. ModelManager methods must
 * never call into LocalModelProvider (no callbacks under the ModelManager monitor); callers
 * must not hold the ModelManager monitor while calling synchronized provider methods.
 */
public class ModelManager {
    private static final String TAG = "ModelManager";
    public static final String PREFS_NAME = "vision_models";
    public static final String KEY_ACTIVE_MODEL_ID = "active_model_id";
    public static final String KEY_PENDING_DOWNLOAD_ID = "pending_download_id";
    public static final String KEY_PENDING_DOWNLOAD_CATALOG_ID = "pending_download_catalog_id";
    // Estimated memory overhead placeholder (1GB) until real measurements exist
    public static final long ESTIMATED_OVERHEAD_BYTES = 1024L * 1024L * 1024L;
    public static final long MAX_IMPORT_SIZE_BYTES = (long) (2.5 * 1024 * 1024 * 1024L); // 2.5 GB

    private final Context context;
    private final File modelsDir;
    private final File metadataFile;
    /** Test seam only: deterministic failure injection for the metadata replace. */
    interface FileMover {
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }
    private static final FileMover DEFAULT_FILE_MOVER = new FileMover() {
        @Override public void move(Path source, Path target, CopyOption... options) throws IOException {
            Files.move(source, target, options);
        }
    };
    private static volatile FileMover fileMover = DEFAULT_FILE_MOVER;
    static void setFileMoverForTesting(FileMover mover) {
        fileMover = mover != null ? mover : DEFAULT_FILE_MOVER;
    }

    // Serializes temporary-file creation, write, close, and publication across ModelManager
    // instances sharing this process, preventing publishers from overwriting each other's
    // shared temporary file. This is partial-file avoidance only, NOT cross-manager
    // read-modify-write coherence: two callers publishing different complete replacements
    // race, and the last publication wins. In-process only: no cross-process coordination
    // is claimed. Never call provider callbacks under this lock.
    private static final Object METADATA_FILE_LOCK = new Object();

    private final SharedPreferences prefs;
    private final AtomicBoolean modelInUse = new AtomicBoolean(false);

    public enum FitStatus {
        ESTIMATED_TO_FIT,
        WARN_LOW_MEMORY
    }

    public ModelManager(Context context) {
        this.context = context.getApplicationContext();
        File externalModels = this.context.getExternalFilesDir("models");
        if (externalModels == null) {
            this.modelsDir = new File(this.context.getFilesDir(), "models");
        } else {
            this.modelsDir = externalModels;
        }
        if (!this.modelsDir.exists()) {
            this.modelsDir.mkdirs();
        }
        this.metadataFile = new File(this.modelsDir, "models.json");
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public File getModelsDir() {
        return modelsDir;
    }

    public boolean isModelInUse() {
        return modelInUse.get();
    }

    public void setModelInUse(boolean inUse) {
        modelInUse.set(inUse);
    }

    public boolean isExternalStorageRemovable() {
        try {
            return Environment.isExternalStorageRemovable(modelsDir);
        } catch (Throwable t) {
            return false;
        }
    }

    public synchronized List<CatalogEntry> loadCatalog() {
        List<CatalogEntry> catalog = new ArrayList<>();
        try (InputStream is = context.getAssets().open("models/catalog.json")) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            String jsonStr = baos.toString(StandardCharsets.UTF_8.name());
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                catalog.add(CatalogEntry.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed loading bundled catalog.json", e);
        }
        return Collections.unmodifiableList(catalog);
    }

    public synchronized List<ModelInfo> listInstalledModels() {
        List<ModelInfo> list = new ArrayList<>();
        if (!metadataFile.exists()) {
            return list;
        }
        try (FileInputStream fis = new FileInputStream(metadataFile)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            String jsonStr = baos.toString(StandardCharsets.UTF_8.name());
            JSONArray arr = new JSONArray(jsonStr);
            String activeId = getActiveModelId();
            for (int i = 0; i < arr.length(); i++) {
                ModelInfo info = ModelInfo.fromJson(arr.getJSONObject(i));
                File modelFile = new File(modelsDir, info.fileName);
                if (modelFile.exists()) {
                    info.active = (activeId != null && activeId.equals(info.id));
                    list.add(info);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed loading models.json", e);
        }
        return list;
    }

    public synchronized void saveInstalledModels(List<ModelInfo> models) throws IOException {
        JSONArray arr = new JSONArray();
        for (ModelInfo m : models) {
            try {
                arr.put(m.toJson());
            } catch (JSONException e) {
                throw new IOException("Failed serializing model to JSON", e);
            }
        }
        // Fail-closed atomic publication. The temp file is fully written and closed first
        // (complete write), then published with one same-volume ATOMIC_MOVE | REPLACE_EXISTING
        // move, so readers observe either the complete old or the complete new metadata -
        // never a partially written file. There is deliberately no non-atomic fallback or
        // backup/restore protocol. An IOException from the move leaves the publication state
        // UNCERTAIN: the move may have completed before the failure was reported, so this
        // method neither deletes artifacts nor claims the previous metadata survived; the
        // target holds one of the two complete states and a diagnostic temp may remain.
        // Crash/power-loss durability is NOT claimed (no fsync).
        synchronized (METADATA_FILE_LOCK) {
            File tmpFile = new File(modelsDir, "models.json.tmp");
            try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            try {
                fileMover.move(tmpFile.toPath(), metadataFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // Do not delete or "repair" anything: the publication state is uncertain.
                throw new IOException("models.json save failed; publication state uncertain - the "
                        + "target holds either the previous or the new complete metadata; diagnostic "
                        + "temp may exist at " + tmpFile.getAbsolutePath(), e);
            }
        }
    }

    public String getActiveModelId() {
        return prefs.getString(KEY_ACTIVE_MODEL_ID, null);
    }

    public synchronized void setActiveModelId(String id) {
        prefs.edit().putString(KEY_ACTIVE_MODEL_ID, id).apply();
        List<ModelInfo> installed = listInstalledModels();
        for (ModelInfo m : installed) {
            m.active = (id != null && id.equals(m.id));
        }
        try {
            saveInstalledModels(installed);
        } catch (IOException e) {
            Log.e(TAG, "Failed updating active model status in models.json", e);
        }
    }

    public ModelInfo getActiveModel() {
        String activeId = getActiveModelId();
        if (activeId == null) return null;
        for (ModelInfo info : listInstalledModels()) {
            if (activeId.equals(info.id)) {
                return info;
            }
        }
        return null;
    }

    public File getActiveModelFile() {
        ModelInfo active = getActiveModel();
        if (active == null) return null;
        File f = new File(modelsDir, active.fileName);
        return f.exists() ? f : null;
    }

    public FitStatus estimateFit(long modelSizeBytes) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return FitStatus.WARN_LOW_MEMORY;
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memInfo);
        long required = modelSizeBytes + ESTIMATED_OVERHEAD_BYTES;
        return memInfo.availMem >= required ? FitStatus.ESTIMATED_TO_FIT : FitStatus.WARN_LOW_MEMORY;
    }

    public static boolean validateGgufMagic(byte[] header) {
        if (header == null || header.length < 4) return false;
        return header[0] == 0x47 && header[1] == 0x47 && header[2] == 0x55 && header[3] == 0x46; // "GGUF"
    }

    public ModelInfo importModel(InputStream sourceStream, String displayName, long sourceSize) throws IOException {
        if (sourceSize > MAX_IMPORT_SIZE_BYTES) {
            throw new IllegalArgumentException("Model exceeds size limit of 2.5 GB");
        }
        long usable = modelsDir.getUsableSpace();
        long required = (long) (sourceSize * 1.2) + 500L * 1024L * 1024L;
        if (sourceSize > 0 && usable < required) {
            throw new IOException("Insufficient disk space for model import. Free: " + usable + ", Required: " + required);
        }

        String safeBaseName = displayName.replaceAll("[^a-zA-Z0-9.-]", "_");
        if (!safeBaseName.endsWith(".gguf")) {
            safeBaseName += ".gguf";
        }
        String id = "import-" + System.currentTimeMillis();
        String finalFileName = id + "-" + safeBaseName;

        File partFile = new File(modelsDir, finalFileName + ".part");
        File finalFile = new File(modelsDir, finalFileName);

        try (FileOutputStream fos = new FileOutputStream(partFile)) {
            byte[] magic = new byte[4];
            int totalMagic = 0;
            // N1: Loop until 4 bytes or EOF before checking GGUF magic
            while (totalMagic < 4) {
                int r = sourceStream.read(magic, totalMagic, 4 - totalMagic);
                if (r == -1) break;
                totalMagic += r;
            }
            if (totalMagic < 4 || !validateGgufMagic(magic)) {
                throw new IllegalArgumentException("Invalid GGUF format: magic header missing");
            }
            fos.write(magic, 0, totalMagic);

            byte[] buf = new byte[8192];
            long totalCopied = totalMagic;
            int read;
            while ((read = sourceStream.read(buf)) != -1) {
                totalCopied += read;
                if (totalCopied > MAX_IMPORT_SIZE_BYTES) {
                    throw new IllegalArgumentException("Import stream exceeded maximum size of 2.5 GB");
                }
                fos.write(buf, 0, read);
            }
            fos.flush();
        } catch (Throwable t) {
            partFile.delete();
            if (t instanceof IllegalArgumentException) throw (IllegalArgumentException) t;
            if (t instanceof IOException) throw (IOException) t;
            throw new IOException("Import failed: " + t.getMessage(), t);
        }

        if (!partFile.renameTo(finalFile)) {
            partFile.delete();
            throw new IOException("Failed renaming imported model part file");
        }

        ModelInfo info = new ModelInfo(
                id,
                displayName,
                finalFileName,
                finalFile.length(),
                null,
                "import",
                "custom",
                System.currentTimeMillis(),
                false,
                true,
                "chatml"
        );

        List<ModelInfo> installed = listInstalledModels();
        installed.add(info);
        saveInstalledModels(installed);

        return info;
    }

    // Lock order is always LocalModelProvider before ModelManager.
    // ModelManager methods must never call into LocalModelProvider (no callbacks under the
    // ModelManager monitor); callers must not hold the ModelManager monitor while calling
    // synchronized provider methods.
    public synchronized boolean deleteModel(ModelInfo info) throws IOException, SecurityException {
        if (isModelInUse()) {
            throw new IllegalStateException("Cannot delete model while generation is in progress");
        }
        File targetFile = new File(modelsDir, info.fileName).getCanonicalFile();
        File canonicalDir = modelsDir.getCanonicalFile();
        if (!targetFile.getPath().startsWith(canonicalDir.getPath() + File.separator)) {
            throw new SecurityException("Path traversal attempt detected: " + info.fileName);
        }

        String activeId = getActiveModelId();
        if (activeId != null && activeId.equals(info.id)) {
            prefs.edit().remove(KEY_ACTIVE_MODEL_ID).apply();
        }

        boolean deleted = true;
        if (targetFile.exists()) {
            deleted = targetFile.delete();
        }

        List<ModelInfo> installed = listInstalledModels();
        List<ModelInfo> updated = new ArrayList<>();
        for (ModelInfo m : installed) {
            if (!m.id.equals(info.id)) {
                updated.add(m);
            }
        }
        saveInstalledModels(updated);
        return deleted;
    }

    public static String computeSha256(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("Failed computing SHA-256 for " + file.getName(), e);
        }
    }

    public static boolean verifySha256(File file, String expectedSha256) throws IOException {
        if (expectedSha256 == null) return false;
        String actual = computeSha256(file);
        return expectedSha256.equalsIgnoreCase(actual);
    }

    public long getPendingDownloadId() {
        return prefs.getLong(KEY_PENDING_DOWNLOAD_ID, -1L);
    }

    public String getPendingDownloadCatalogId() {
        return prefs.getString(KEY_PENDING_DOWNLOAD_CATALOG_ID, null);
    }

    public synchronized void setPendingDownload(long downloadId, String catalogId) {
        prefs.edit()
                .putLong(KEY_PENDING_DOWNLOAD_ID, downloadId)
                .putString(KEY_PENDING_DOWNLOAD_CATALOG_ID, catalogId)
                .apply();
    }

    public synchronized void clearPendingDownload() {
        prefs.edit()
                .remove(KEY_PENDING_DOWNLOAD_ID)
                .remove(KEY_PENDING_DOWNLOAD_CATALOG_ID)
                .apply();
    }

    public boolean isModelInstalled(String id) {
        if (id == null) return false;
        for (ModelInfo info : listInstalledModels()) {
            if (id.equals(info.id)) return true;
        }
        return false;
    }

    public synchronized long startDownload(CatalogEntry entry) {
        if (getPendingDownloadId() != -1L) {
            throw new IllegalStateException("Download already in progress");
        }
        if (isModelInstalled(entry.id)) {
            throw new IllegalStateException("Model is already installed: " + entry.displayName);
        }

        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return -1;

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(entry.downloadUrl));
        request.setTitle(entry.displayName);
        request.setDescription("Downloading on-device AI model");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "models/" + entry.fileName + ".part");

        long downloadId = dm.enqueue(request);
        if (downloadId != -1L) {
            setPendingDownload(downloadId, entry.id);
        }
        return downloadId;
    }

    public synchronized boolean completeDownload(CatalogEntry entry, File downloadedPartFile) throws IOException {
        if (!downloadedPartFile.exists()) {
            return false;
        }

        if (!verifySha256(downloadedPartFile, entry.sha256)) {
            downloadedPartFile.delete();
            clearPendingDownload();
            throw new IOException("SHA-256 mismatch for downloaded model: " + entry.fileName);
        }

        File destFile = new File(modelsDir, entry.fileName);
        if (!downloadedPartFile.renameTo(destFile)) {
            // Same filesystem move
            try (FileInputStream in = new FileInputStream(downloadedPartFile);
                 FileOutputStream out = new FileOutputStream(destFile)) {
                byte[] b = new byte[65536];
                int len;
                while ((len = in.read(b)) != -1) {
                    out.write(b, 0, len);
                }
            }
            downloadedPartFile.delete();
        }

        ModelInfo info = new ModelInfo(
                entry.id,
                entry.displayName,
                entry.fileName,
                destFile.length(),
                entry.sha256,
                "catalog",
                entry.licenseSpdx,
                System.currentTimeMillis(),
                false,
                true,
                entry.templateHint
        );

        // R2: Dedupe by id: re-download replaces, not duplicates
        List<ModelInfo> installed = listInstalledModels();
        List<ModelInfo> updated = new ArrayList<>();
        for (ModelInfo m : installed) {
            if (!m.id.equals(entry.id)) {
                updated.add(m);
            }
        }
        updated.add(info);
        saveInstalledModels(updated);
        clearPendingDownload();
        return true;
    }
}
