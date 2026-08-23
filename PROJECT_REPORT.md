# Vision Project Report — Phase 5 Hardened & TOCTOU-Proof Notification Reply

**Date:** 2026-08-23  
**Target Device:** iQOO Z9x I2219 (Android 16 / API 36, arm64-v8a)  
**Current Version:** 0.4.1 (versionCode: 6, compileSdk: 34, targetSdk: 34, minSdk: 26)  
**Prior Commits:** `ee7796a` (Phase 4 Hardening), `8bc9a78` (Phase 5 MVP)  
**APK Output:** `/storage/emulated/0/Download/Vision-debug.apk`

---

## 1. Executive Summary

Vision is an offline-first Android assistant designed around zero silent actions, zero unapproved execution, and mandatory user confirmation.

In release v0.4.0, an adversarial review identified a release-blocking safety defect in Phase 5:
- **Defect Description:** `MainActivity` built reply confirmations from user-typed command targets (`VisionAction.target`, e.g., "Alice" or "WhatsApp") and deferred capability resolution until after the user tapped "Allow". At dispatch time, it fetched whatever notification happened to be latest in memory (`getLatestReplyCapability()`).
- **Safety Hazards:**
  1. **TOCTOU Race Condition / Retargeting:** If a new notification arrived while the confirmation dialog was open, the user-approved reply text would be dispatched to the *new* notification's sender instead of the intended one (e.g. replying to Alice could send to Bob).
  2. **False Recipient Claim:** If the active notification was from Bob on WhatsApp but the user typed `reply to Alice: ...`, the dialog claimed "Action: Reply to Alice" but would actually send to Bob.
- **Resolution in v0.4.1:**
  1. **Proposal-Time Resolution & Binding:** Resolves and binds the exact in-memory `NotificationReplyCapability` identity before displaying any confirmation dialog.
  2. **Conservative Target Validation:** Explicit targets in commands are conservatively validated against the bound notification's source app and sender/title; mismatches fail immediately with zero dialog and zero send.
  3. **Exact Source & Recipient Display:** Confirmation dialog displays the actual resolved app source and actual sender/title recipient along with the exact reply body.
  4. **Atomic Re-verification at Dispatch:** `sendBoundReply` synchronizes and verifies immediately before `PendingIntent.send()` that the bound capability is still the active matching capability. If dismissed, replaced, or expired, dispatch aborts safely with zero send.
  5. **Deterministic Testing:** Expanded test suite to 13 deterministic JVM test suites (target mismatch, TOCTOU replacement, stale/removed key, lifecycle binding).

---

## 2. Phase 5 Hardened Architecture & Safety Mechanics

### 2.1 Capability Binding & Proposal-Time Resolution
- When a reply command is submitted in the composer, `MainActivity.handleReplyAction()` queries `VisionNotificationListener.getLatestReplyCapability()`.
- If no reply capability is present (or notification access is disabled), the action immediately fails with descriptive UI feedback; no confirmation dialog is created.
- The active capability is captured as an immutable reference (`final NotificationReplyCapability boundCap = replyCap;`).

### 2.2 Conservative Target Validation (`VisionNotificationListener.validateTarget`)
- If the command specifies a target (e.g., `reply to WhatsApp: ...`, `reply to Alice: ...`), `validateTarget` validates:
  - **App Source Matching:** Target is checked against package name, canonical source name (`WhatsApp`, `Telegram`, `Gmail`, `Messages`, `Calendar`), and known aliases (`wa`, `tg`, `sms`, `google mail`).
  - **Sender/Title Matching:** Target is checked against `senderOrTitle` via case-insensitive exact matching and conservative substring matching (length >= 2).
  - **Rejection on Mismatch:** If the user specifies "Alice" but the active notification is from "Bob", `validateTarget` returns `false`. `MainActivity` halts with `"TARGET MISMATCH: The active notification is from WhatsApp (Bob), not \"Alice\". No reply was sent."` Zero dialog is shown, preventing confusing false confirmations.
  - **Default/Unspecified Targets:** Commands like `reply <msg>` or `reply to latest notification: <msg>` accept any active replyable notification and display the true recipient in the dialog.

### 2.3 Exact Source and Recipient Confirmation
- Before sending, a modal dialog renders:
  ```
  Action: Reply to WhatsApp (Alice)

  Reply text:
  "I will be there soon"

  Allow Vision to send this reply?
  ```
- **Deny:** Sets `action.state = DENIED` and stops immediately.
- **Allow:** Passes `boundCap` to `executeBoundNotificationReply`.

### 2.4 Atomic Active Match Re-verification at Dispatch (`sendBoundReply`)
- Inside `VisionNotificationListener.sendBoundReply(Context, NotificationReplyCapability, String)`:
  - Synchronized method guarantees thread safety against concurrent incoming `onNotificationPosted` or `onNotificationRemoved` calls.
  - Verifies `latestReplyCapability == boundCapability && latestReplyCapability.key.equals(boundCapability.key)`.
  - If the notification was dismissed (`onNotificationRemoved`), replaced by a newer notification (`onNotificationPosted`), or process memory reset, returns `ReplyResult.STALE_OR_REMOVED`.
  - `MainActivity` reports `"FAILED: The notification was dismissed, replaced, or expired before the reply could be sent. Please make a new request."`
  - Prevents all TOCTOU redirects and stale dispatches.

---

## 3. Adversarial Code Audit & Regression Review (Phases 1–5)

| Subsystem | Area | Finding / Verification | Resolution |
|---|---|---|---|
| **Phase 1** | UI & Layout | Dark theme with high contrast (`#10151D` / `#9EE6C2`) on `Theme.Material.Light.NoActionBar` | Retained consistent clean UI styling. |
| **Phase 2** | Access Flow | `ACTION_NOTIFICATION_LISTENER_SETTINGS` can fail on customized OEM ROMs | Added fallback to `Settings.ACTION_SETTINGS` in try-catch blocks. |
| **Phase 3** | Notification Read | Stale notification ghosting on dismissal | Overrode `onNotificationRemoved` with exact `sbn.getKey()` matching. |
| **Phase 4** | Launcher Resolution | Telegram (`org.telegram.messenger/.DefaultIcon`) reported unavailable | Added `<queries>` in Manifest and multi-strategy `queryIntentActivities` fallback. |
| **Phase 4** | App Mapping | WhatsApp Business (`com.whatsapp.w4b`) and AOSP Messages (`com.android.messaging`) missing | Added full package lists and fallback candidate iterations. |
| **Phase 5** | TOCTOU Race | New notification arriving while confirmation dialog is open | Bound capability identity at proposal time; atomic equality check at dispatch time. |
| **Phase 5** | Target Misdirection | Explicit user target mismatched with active notification | Added conservative `validateTarget` rejecting mismatches with zero send. |
| **Phase 5** | Dialog Accuracy | Dialog showed user input target rather than actual recipient | Dialog constructed from bound capability's true source app and sender title. |
| **Phase 5** | Memory Ephemerality | Zero persistence of notification content or replies | No SQLite, SharedPreferences, files, or background persistence used. |

---

## 4. Verification Evidence & Test Results

### Deterministic JVM Unit Tests (`com.vision.app.VisionAppTest`)
Executed all 13 test suites:
- **Test 1:** Null, empty, and whitespace parser inputs -> PASSED
- **Test 2:** Notification reading commands -> PASSED
- **Test 3:** App launch commands (WhatsApp, WhatsApp Business, Telegram, Gmail, Messages, Calendar) -> PASSED
- **Test 4:** Notification reply commands & empty text safety -> PASSED
- **Test 5:** Unknown/unsupported command safety -> PASSED
- **Test 6:** `VisionAction` state transitions and labels -> PASSED
- **Test 7:** Notification package allowlist filtering -> PASSED
- **Test 8:** Notification snapshot creation and key matching -> PASSED
- **Test 9:** `NotificationReplyCapability` structure & null-safe execution -> PASSED
- **Test 10:** Target validation and conservative mismatch rejection -> PASSED
- **Test 11:** Notification replacement between proposal and approval (TOCTOU prevention) -> PASSED
- **Test 12:** Notification removal / stale key dispatch prevention -> PASSED
- **Test 13:** Capability active match verification and bound lifecycle flow -> PASSED

*Result: 13/13 test suites passed with 0 failures.*

### Build & Package Validation
1. **Compilation:** Built offline with Gradle 8.7 (`:app:assembleDebug --offline`).
2. **ZIP Integrity:** `unzip -t app-debug.apk` -> No errors detected in compressed data (META-INF, classes.dex, classes2.dex, AndroidManifest.xml, resources.arsc).
3. **Signature Verification:** `apksigner verify --verbose` -> Verified using APK Signature Scheme v2 (1 signer).
4. **Metadata & Manifest Tree:**
   - Package: `com.vision.app`
   - Version Code: `6`
   - Version Name: `0.4.1`
   - Compile SDK: `34`, Target SDK: `34`, Min SDK: `26`
   - Declared Queries: `com.whatsapp`, `com.whatsapp.w4b`, `org.telegram.messenger`, `com.google.android.gm`, `com.google.android.apps.messaging`, `com.android.messaging`, `com.google.android.calendar`
5. **Deployment:** APK copied to `/storage/emulated/0/Download/Vision-debug.apk` (24,809 bytes).

---

## 5. Residual Risks & Unresolved Device-Only Tests

1. **OEM Background Listener Killing:** Android may kill `NotificationListenerService` under aggressive battery optimization until exempted in system settings.
2. **App-Specific RemoteInput Quirks:** While WhatsApp, Telegram, and Google Messages implement standard `RemoteInput` keys, certain custom messaging app variants may use alternative keys.
3. **Live Device Verification Required (Do Not Fabricate):**
   - On-device test of receiving a real WhatsApp / Telegram message with active reply action.
   - On-device test of drafting a reply, verifying dialog source and sender display, and confirming delivery on conversation partner's device.
   - On-device verification of typing `reply to Alice: ...` when a message from Bob is active, confirming `TARGET MISMATCH` with zero send.
   - On-device verification of receiving a newer message while dialog is open, tapping Allow, and confirming safe failure (`STALE_OR_REMOVED`) without misdirection.

