# Vision Project Report — Phase 4 Hardening & Phase 5 Notification Reply MVP

**Date:** 2026-08-23  
**Target Device:** iQOO Z9x I2219 (Android 16 / API 36, arm64-v8a)  
**Current Version:** 0.4.0 (versionCode: 5, compileSdk: 34, targetSdk: 34, minSdk: 26)  
**Phase A Commit:** `ee7796a`  
**APK Output:** `/storage/emulated/0/Download/Vision-debug.apk`

---

## 1. Executive Summary

Vision is an offline-first Android assistant designed around zero silent actions and mandatory user confirmation. This cycle completed two sequential phases:
- **Phase A (v0.3.1):** Resolved package visibility restrictions (fixing Telegram and multi-variant launcher resolution for WhatsApp, WhatsApp Business, Gmail, Messages, and Calendar), hardened notification extraction across `MessagingStyle`/`BigTextStyle`/`InboxStyle`, fixed stale notification dismissal tracking with unique stable keys (`sbn.getKey()`), and resolved all verified audit findings.
- **Phase B (v0.4.0):** Implemented Phase 5 as the smallest safe notification reply MVP. Added detection of standard Android `RemoteInput` actions on supported notifications, in-memory ephemeral reply capabilities, typed draft-reply commands, explicit source/recipient/text confirmation gating, and safe `PendingIntent` execution without root, accessibility, hidden APIs, or disk persistence.

---

## 2. Phase 5 Implementation Architecture & Safety

### 2.1 Notification Reply Capability Extraction
- On supported notification post (`onNotificationPosted`), `VisionNotificationListener` iterates `Notification.Action` entries to locate actions equipped with `RemoteInput`.
- Extracts and holds a `NotificationReplyCapability` containing:
  - Stable Notification `key` (matching `sbn.getKey()`)
  - Target `packageName`
  - Sender / title
  - Action `PendingIntent`
  - `RemoteInput` descriptor and result key
- **Memory-only Guarantee:** Retained exclusively in volatile process memory (`latestReplyCapability`). No SQLite, SharedPreferences, file storage, or external logs are used.
- **Lifecycle Cleanliness:** When the active notification is dismissed or cancelled (`onNotificationRemoved`), the corresponding in-memory capability is immediately cleared.

### 2.2 Deterministic Typed Command Grammar
`VisionActionParser` accepts the following deterministic commands:
- `reply <message>` (e.g. `reply I will be there in 5 minutes`)
- `reply: <message>` (e.g. `reply: Sounds great!`)
- `reply to <app/target>: <message>` (e.g. `reply to WhatsApp: On my way!`, `reply to Alice: Yes, confirmed`)
- `send reply <message>` (e.g. `send reply Confirmed`)
- `answer <message>` (e.g. `answer Thank you`)
- `please reply <message>`

*Note: Empty reply requests (e.g. `reply` or `reply:`) are rejected as `UNKNOWN` and produce zero side-effects.*

### 2.3 Strict Confirmation & Gating
Before any reply is dispatched:
1. An explicit confirmation dialog appears displaying the exact destination and typed reply body:
   `"Action: Reply to <target>\n\nReply text:\n\"<reply text>\"\n\nAllow Vision to send this reply?"`
2. **Deny:** Sets `action.state = DENIED` and stops immediately.
3. **Allow:** Sets `action.state = APPROVED` and dispatches solely via standard Android `RemoteInput.addResultsToIntent` and `PendingIntent.send()`.
4. Stale or cancelled `PendingIntent` instances are caught via `PendingIntent.CanceledException` and reported to the user as `FAILED` without crashing.

---

## 3. Adversarial Code Audit & Regression Review (Phases 1–5)

| Subsystem | Area | Finding / Verification | Resolution |
|---|---|---|---|
| **Phase 1** | UI & Layout | Dark theme with high contrast (`#10151D` / `#9EE6C2`) on `Theme.Material.Light.NoActionBar` | Retained consistent clean UI styling. |
| **Phase 2** | Access Flow | `ACTION_NOTIFICATION_LISTENER_SETTINGS` can fail on customized OEM ROMs | Added fallback to `Settings.ACTION_SETTINGS` in try-catch blocks. |
| **Phase 3** | Notification Read | Stale notification ghosting on dismissal | Overrode `onNotificationRemoved` with exact `sbn.getKey()` matching. |
| **Phase 4** | Launcher Resolution | Telegram (`org.telegram.messenger/.DefaultIcon`) reported unavailable | Added `<queries>` in Manifest and multi-strategy `queryIntentActivities` fallback. |
| **Phase 4** | App Mapping | WhatsApp Business (`com.whatsapp.w4b`) and AOSP Messages (`com.android.messaging`) missing | Added full package lists and fallback candidate iterations. |
| **Phase 5** | Reply Gating | Potential unapproved or silent sends | Enforced mandatory modal Allow/Deny gating with exact text display. |
| **Phase 5** | RemoteInput | Unsupported notifications (e.g. Calendar/Gmail without quick reply) | Graceful detection showing `"NO REPLYABLE NOTIFICATION"`. |
| **Phase 5** | Process Death | Ephemeral state loss on process restart | Handled as null snapshot with informative feedback `"NO SUPPORTED NOTIFICATION"`. |

---

## 4. Verification Evidence & Test Results

### Deterministic JVM Unit Tests (`com.vision.app.VisionAppTest`)
Executed all 9 test suites:
- **Test 1:** Null, empty, and whitespace parser inputs -> PASSED
- **Test 2:** Notification reading commands -> PASSED
- **Test 3:** App launch commands (WhatsApp, WhatsApp Business, Telegram, Gmail, Messages, Calendar) -> PASSED
- **Test 4:** Notification reply commands & empty text safety -> PASSED
- **Test 5:** Unknown/unsupported command safety -> PASSED
- **Test 6:** `VisionAction` state transitions and labels -> PASSED
- **Test 7:** Notification package allowlist filtering -> PASSED
- **Test 8:** Notification snapshot creation and key matching -> PASSED
- **Test 9:** `NotificationReplyCapability` structure & null-safe execution -> PASSED

*Result: 9/9 test suites passed with 0 failures.*

### Build & Package Validation
1. **Compilation:** Built offline with Gradle 8.7 (`:app:assembleDebug --offline`).
2. **ZIP Integrity:** `unzip -t app-debug.apk` -> No errors detected in compressed data.
3. **Signature Verification:** `apksigner verify --verbose` -> Verified using APK Signature Scheme v2.
4. **Metadata & Manifest Tree:**
   - Package: `com.vision.app`
   - Version Code: `5`
   - Version Name: `0.4.0`
   - Min SDK: `26`, Target SDK: `34`
   - Declared Queries: `com.whatsapp`, `com.whatsapp.w4b`, `org.telegram.messenger`, `com.google.android.gm`, `com.google.android.apps.messaging`, `com.android.messaging`, `com.google.android.calendar`
5. **Deployment:** APK copied to `/storage/emulated/0/Download/Vision-debug.apk` (22 KB).

---

## 5. Residual Risks & Unresolved Device-Only Tests

1. **OEM Background Listener Killing:** Android may kill the `NotificationListenerService` under aggressive battery optimization until exempted in system settings.
2. **App-Specific RemoteInput Quirks:** While WhatsApp, Telegram, and Google Messages implement standard `RemoteInput` keys, certain third-party or custom messaging apps may use non-standard result keys.
3. **Live Device Verification Required:**
   - On-device test of receiving a real WhatsApp / Telegram message with active reply action.
   - On-device verification of sending a typed reply and confirming receipt on the conversation partner's device.
   - On-device dismissal of a notification to confirm in-memory cleanup.
