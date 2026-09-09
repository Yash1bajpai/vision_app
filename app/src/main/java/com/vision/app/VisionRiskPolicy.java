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
 * - READ_BATTERY
 * - READ_NETWORK
 * - READ_TIME
 * - READ_CALENDAR (read-only query; creating/modifying events stays CONFIRMED)
 * - SET_TIMER / SET_ALARM (clock app shows the pending timer/alarm for review)
 * - NAVIGATE_TO (opens navigation; the route is reviewed in the maps app)
 * - MEDIA_CONTROL (play/pause/skip transport control)
 * - SET_VOLUME (local audio setting only)
 * - TOGGLE_TORCH (reversible local hardware toggle)
 *
 * Tier CONFIRMED (modal Allow/Deny REQUIRED before execution):
 * - REPLY_NOTIFICATION (active MVP)
 * - SEND_MESSAGE_DIRECT (compose handoff; final send remains external)
 * - CREATE_CALENDAR_EVENT (writes user calendar data)
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
            VisionAction.Type.READ_NOTIFICATION,
            VisionAction.Type.READ_BATTERY,
            VisionAction.Type.READ_NETWORK,
            VisionAction.Type.READ_TIME,
            VisionAction.Type.READ_CALENDAR,
            VisionAction.Type.SET_TIMER,
            VisionAction.Type.SET_ALARM,
            VisionAction.Type.NAVIGATE_TO,
            VisionAction.Type.MEDIA_CONTROL,
            VisionAction.Type.SET_VOLUME,
            VisionAction.Type.TOGGLE_TORCH
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

    public static String formatReplyConfirmationTitle() {
        return "Tony, may I send this message?";
    }

    public static String formatReplyConfirmationMessage(String destination, String replyText) {
        String dest = (destination != null && !destination.trim().isEmpty()) ? destination.trim() : "the recipient";
        String body = replyText != null ? replyText : "";
        return "I am ready to send this message to " + dest + ":\n\n\"" + body + "\"\n\nMay I proceed?";
    }
}
