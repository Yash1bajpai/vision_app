package com.vision.app;

/** User-selectable reasoning route. Deterministic-only is the default and needs no model. */
public enum ProviderMode {
    DETERMINISTIC_ONLY,
    ON_DEVICE,
    CLOUD;

    /** Unknown or null names fall back to the safe default, never to a model route. */
    public static ProviderMode fromName(String name) {
        if (name != null) {
            for (ProviderMode mode : values()) {
                if (mode.name().equals(name.trim())) {
                    return mode;
                }
            }
        }
        return DETERMINISTIC_ONLY;
    }
}
