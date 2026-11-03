package com.vision.app;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * An entry in the bundled model catalog.
 */
public final class CatalogEntry {
    public final String id;
    public final String displayName;
    public final String fileName;
    public final long sizeBytes;
    public final String sha256;
    public final String downloadUrl;
    public final String upstreamRepo;
    public final String upstreamCommit;
    public final String licenseSpdx;
    public final String licenseUrl;
    public final String templateHint;
    public final int defaultCtx;

    public CatalogEntry(
            String id,
            String displayName,
            String fileName,
            long sizeBytes,
            String sha256,
            String downloadUrl,
            String upstreamRepo,
            String upstreamCommit,
            String licenseSpdx,
            String licenseUrl,
            String templateHint,
            int defaultCtx) {
        this.id = id;
        this.displayName = displayName;
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.downloadUrl = downloadUrl;
        this.upstreamRepo = upstreamRepo;
        this.upstreamCommit = upstreamCommit;
        this.licenseSpdx = licenseSpdx;
        this.licenseUrl = licenseUrl;
        this.templateHint = templateHint;
        this.defaultCtx = defaultCtx;
    }

    public static CatalogEntry fromJson(JSONObject obj) throws JSONException {
        return new CatalogEntry(
                obj.getString("id"),
                obj.getString("displayName"),
                obj.getString("fileName"),
                obj.getLong("sizeBytes"),
                obj.getString("sha256"),
                obj.getString("downloadUrl"),
                obj.optString("upstreamRepo", ""),
                obj.optString("upstreamCommit", ""),
                obj.optString("licenseSpdx", ""),
                obj.optString("licenseUrl", ""),
                obj.optString("templateHint", "chatml"),
                obj.optInt("defaultCtx", 1024)
        );
    }
}
