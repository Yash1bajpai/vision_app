package com.vision.app;

import java.util.EnumMap;
import java.util.Map;

/**
 * Maps the selected mode to a provider. Deterministic-only is always available and uses the
 * no-op provider. A mode with no registered provider is unavailable: there is no silent
 * fallback to another mode, and in particular no automatic switch to a cloud route.
 */
public final class ProviderSelector {
    private final Map<ProviderMode, ReasoningProvider> providers = new EnumMap<>(ProviderMode.class);
    private ProviderMode mode = ProviderMode.DETERMINISTIC_ONLY;

    public ProviderSelector() {
        providers.put(ProviderMode.DETERMINISTIC_ONLY, new NoOpReasoningProvider());
    }

    /** Registers a model route. The deterministic-only route cannot be replaced. */
    public synchronized void register(ProviderMode routeMode, ReasoningProvider provider) {
        if (routeMode == null || provider == null || routeMode == ProviderMode.DETERMINISTIC_ONLY) {
            throw new IllegalArgumentException("only ON_DEVICE or CLOUD can be registered");
        }
        providers.put(routeMode, provider);
    }

    public synchronized boolean isAvailable(ProviderMode candidate) {
        return candidate != null && providers.containsKey(candidate);
    }

    public synchronized ProviderMode mode() { return mode; }

    /** Selecting an unavailable mode is refused and leaves the current mode unchanged. */
    public synchronized boolean select(ProviderMode candidate) {
        if (!isAvailable(candidate)) {
            return false;
        }
        mode = candidate;
        return true;
    }

    /** Provider for the active mode; never null because the active mode is always available. */
    public synchronized ReasoningProvider active() { return providers.get(mode); }
}
