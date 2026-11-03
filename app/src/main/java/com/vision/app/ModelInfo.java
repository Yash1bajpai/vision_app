package com.vision.app;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Metadata record for an installed or active on-device model.
 */
public final class ModelInfo {
    public final String id;
    public final String displayName;
    public final String fileName;
    public final long sizeBytes;
    public final String sha256;          // null for user-imported models
    public final String source;          // "catalog" | "import"
    public final String licenseSpdx;
    public final long addedAt;           // epoch timestamp in ms
    public boolean active;
    public boolean licenseAccepted;
    public final String templateHint;     // e.g. "llama3" | "chatml"

    public ModelInfo(
            String id,
            String displayName,
            String fileName,
            long sizeBytes,
            String sha256,
            String source,
            String licenseSpdx,
            long addedAt,
            boolean active,
            boolean licenseAccepted,
            String templateHint) {
        this.id = id != null ? id : "";
        this.displayName = displayName != null ? displayName : "";
        this.fileName = fileName != null ? fileName : "";
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.source = source != null ? source : "import";
        this.licenseSpdx = licenseSpdx != null ? licenseSpdx : "";
        this.addedAt = addedAt > 0 ? addedAt : System.currentTimeMillis();
        this.active = active;
        this.licenseAccepted = licenseAccepted;
        this.templateHint = templateHint != null ? templateHint : "";
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("displayName", displayName);
        obj.put("fileName", fileName);
        obj.put("sizeBytes", sizeBytes);
        if (sha256 != null) {
            obj.put("sha256", sha256);
        } else {
            obj.put("sha256", JSONObject.NULL);
        }
        obj.put("source", source);
        obj.put("licenseSpdx", licenseSpdx);
        obj.put("addedAt", addedAt);
        obj.put("active", active);
        obj.put("licenseAccepted", licenseAccepted);
        obj.put("templateHint", templateHint);
        return obj;
    }

    public static ModelInfo fromJson(JSONObject obj) throws JSONException {
        String id = obj.getString("id");
        String displayName = obj.optString("displayName", id);
        String fileName = obj.getString("fileName");
        long sizeBytes = obj.optLong("sizeBytes", 0L);
        String sha256 = obj.isNull("sha256") ? null : obj.optString("sha256", null);
        String source = obj.optString("source", "import");
        String licenseSpdx = obj.optString("licenseSpdx", "");
        long addedAt = obj.optLong("addedAt", System.currentTimeMillis());
        boolean active = obj.optBoolean("active", false);
        boolean licenseAccepted = obj.optBoolean("licenseAccepted", false);
        String templateHint = obj.optString("templateHint", "");

        return new ModelInfo(
                id, displayName, fileName, sizeBytes, sha256,
                source, licenseSpdx, addedAt, active, licenseAccepted, templateHint
        );
    }
}
