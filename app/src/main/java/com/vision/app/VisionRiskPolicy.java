package com.vision.app;

import java.util.EnumSet;
import java.util.Set;

/**
 * Central risk policy governing whether an action requires explicit modal user confirmation.
 *
 * Inverted fail-closed policy (N33):
 * Only explicitly designated SAFE_TYPES auto-execute without modal confirmation.
 * Any other action type (current UNKNOWN, REPLY_NOTIFICATION, or future action types)
 * defaults to requiring modal confirmation (Tier CONFIRMED).
 *
 * Tier SAFE (auto-execute immediately, NO modal dialog):
 * - OPEN_APP
 * - READ_NOTIFICATION
 *
 * Tier CONFIRMED (modal Allow/Deny REQUIRED before execution):
 * - REPLY_NOTIFICATION (active MVP)
 * - UNKNOWN / null / any unlisted future action types (fail-closed default)
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

    private static final Set<VisionAction.Type> SAFE_TYPES = EnumSet.of(
            VisionAction.Type.OPEN_APP,
            VisionAction.Type.READ_NOTIFICATION
    );

    private VisionRiskPolicy() { }

    public static RiskTier getRiskTier(VisionAction.Type type) {
        if (type != null && SAFE_TYPES.contains(type)) {
            return RiskTier.SAFE;
        }
        return RiskTier.CONFIRMED;
    }

    public static boolean requiresConfirmation(VisionAction.Type type) {
        return getRiskTier(type) == RiskTier.CONFIRMED;
    }
}
