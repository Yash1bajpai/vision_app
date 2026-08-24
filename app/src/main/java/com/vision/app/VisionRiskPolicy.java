package com.vision.app;

import java.util.EnumSet;
import java.util.Set;

/**
 * Central risk policy governing whether an action requires explicit modal user confirmation.
 *
 * Tier SAFE (auto-execute immediately, NO modal dialog):
 * - OPEN_APP
 * - READ_NOTIFICATION
 *
 * Tier CONFIRMED (modal Allow/Deny REQUIRED before execution):
 * - REPLY_NOTIFICATION (active MVP)
 *
 * Future high-risk types documented for upcoming phases:
 * - PAYMENT
 * - DELETE
 * - DOWNLOAD_FILE
 * - SEND_MESSAGE_DIRECT
 * - INSTALL
 * - CHANGE_SETTING
 */
public final class VisionRiskPolicy {
    public enum RiskTier {
        SAFE,
        CONFIRMED
    }

    private static final Set<VisionAction.Type> CONFIRMED_TYPES = EnumSet.of(
            VisionAction.Type.REPLY_NOTIFICATION
            // Documented future high-risk types:
            // PAYMENT, DELETE, DOWNLOAD_FILE, SEND_MESSAGE_DIRECT, INSTALL, CHANGE_SETTING
    );

    private VisionRiskPolicy() { }

    public static RiskTier getRiskTier(VisionAction.Type type) {
        if (type != null && CONFIRMED_TYPES.contains(type)) {
            return RiskTier.CONFIRMED;
        }
        return RiskTier.SAFE;
    }

    public static boolean requiresConfirmation(VisionAction.Type type) {
        return getRiskTier(type) == RiskTier.CONFIRMED;
    }
}
