# Vision Project Report — Phase 4 Hardened & Phase A Audit Remediation

**Date:** 2026-08-23  
**Target Device:** iQOO Z9x I2219 (Android 16 / API 36, arm64-v8a)  
**Version:** 0.3.1 (versionCode: 4, compileSdk: 34, targetSdk: 34, minSdk: 26)  
**APK Output:** `/storage/emulated/0/Download/Vision-debug.apk`

---

## 1. Summary of Phase A Changes

1. **Android Package Visibility (`<queries>`) & Launcher Resolution:**
   - Fixed package visibility restriction causing Telegram (`org.telegram.messenger`) with launcher activity `org.telegram.messenger/.DefaultIcon` to report unavailable.
   - Added explicit `<queries>` declarations for `com.whatsapp`, `com.whatsapp.w4b`, `org.telegram.messenger`, `com.google.android.gm`, `com.google.android.apps.messaging`, `com.android.messaging`, and `com.google.android.calendar`, along with launcher and calendar intent filters.
   - Implemented multi-package fallback candidate resolution and explicit component lookup via `PackageManager.queryIntentActivities`.

2. **In-Memory Notification Extraction & Lifecycle:**
   - Hardened `VisionNotificationListener` against null references for `StatusBarNotification`, `Notification`, and `Bundle extras`.
   - Added hierarchical text extraction chain: `MessagingStyle` (`android.messages` with `Person` sender extraction on API 28+), `BigTextStyle` (`android.bigText`), `InboxStyle` (`android.textLines`), standard `android.text`, and summary/info texts.
   - Implemented stable identity tracking (`sbn.getKey()`) so dismissing another notification does not inadvertently clear the active in-memory snapshot.
   - Retained strict zero-persistence guarantee: notification content exists only in process memory.

3. **User Confirmation & State Machine:**
   - Preserved exact Allow/Deny confirmation dialogs for all user-initiated commands and direct button actions.
   - Decoupled typed-action execution from manual button flow to eliminate dead dialog branches and guarantee symmetric state updates (`PROPOSED` -> `APPROVED`/`DENIED` -> `RUNNING` -> `SUCCEEDED`/`FAILED`).
   - Added fallback handling for notification settings intent failures (`ActivityNotFoundException` / `SecurityException`).
   - Null-checked soft keyboard interactions (`InputMethodManager` and window token).

---

## 2. Audit Report Dispositions (auditreport.md)

| ID | Finding | Severity | Disposition | Action Taken |
|---|---|---|---|---|
| **C1** | NPE when `notification.extras` is null | CRITICAL | **Accepted** | Added null checks across `StatusBarNotification`, `Notification`, and `Bundle extras`. |
| **C2** | `ActivityNotFoundException` on settings intent | CRITICAL | **Accepted** | Used `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` in try-catch with fallback to `Settings.ACTION_SETTINGS`. |
| **C3** | NPE on `hideSoftInputFromWindow` | CRITICAL | **Accepted** | Added null checks for both `InputMethodManager` and view window token. |
| **H1** | Dead code in `requestNotificationRead()` | HIGH | **Accepted** | Split direct button flow from `executeNotificationRead(action)` with clean state transitions. |
| **H2** | Stale `latestNotification` after dismissal | HIGH | **Partially Accepted / Corrected** | Overrode `onNotificationRemoved`, but matched by unique `sbn.getKey()` instead of package name alone to avoid clearing on unrelated notifications. |
| **H3** | Over-strict parser regex | HIGH | **Accepted** | Broadened regex and whitespace normalization in `VisionActionParser` to handle natural phrasing and optional prefixes/suffixes. |
| **H4** | Truncated notification content (`bigText` missing) | HIGH | **Accepted** | Added full fallback chain supporting `MessagingStyle`, `bigText`, `textLines`, and `text`. |
| **H5** | `executeAction()` leaves `UNKNOWN` in `RUNNING` | HIGH | **Accepted** | Added explicit `else` branch setting `action.state = State.FAILED` and displaying error notice. |
| **M1** | `VisionActionParser` NPE on null/empty input | MEDIUM | **Accepted** | Added null/empty checks returning `VisionAction.Type.UNKNOWN`. |
| **M2** | Implicit Messages package mapping | MEDIUM | **Accepted** | Added explicit candidate mapping supporting both Google Messages and AOSP Messages, plus WhatsApp Business. |
| **M3** | Target extraction fragile substring bounds | MEDIUM | **Accepted** | Replaced fragile index slicing with deterministic enum/mapping logic. |
| **L1** | `suppressUnsupportedCompileSdk=34` | LOW | **Noted** | Required for local AGP 8.5.2 toolchain on Termux ARM64 environment. |
| **L2** | Exported `VisionNotificationListener` | LOW | **Noted / Compliant** | Required for system binding under `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`. |
| **L3** | Minimal dark UI styling on Material Light base | LOW | **Noted / Compliant** | Intentional high-contrast styling preserved per project guidelines. |

---

## 3. Test & Verification Evidence

### JVM Unit Tests
Ran deterministic unit test suite (`com.vision.app.VisionAppTest`):
- **Test 1:** Null, empty, and whitespace input handling -> PASSED
- **Test 2:** Notification read commands (all variants) -> PASSED
- **Test 3:** App launch commands (WhatsApp, WhatsApp Business, Telegram, Gmail, Messages, Calendar) -> PASSED
- **Test 4:** Unknown/unsupported command safety -> PASSED
- **Test 5:** `VisionAction` state transitions and labels -> PASSED
- **Test 6:** Notification package allowlist filtering -> PASSED
- **Test 7:** Notification snapshot creation and key matching -> PASSED

*Result: 7/7 test suites passed.*

### Build & Package Validation
1. **Compilation:** Built offline via Gradle 8.7 (`:app:assembleDebug --offline`) with low-memory parameters.
2. **ZIP Integrity:** `unzip -t app-debug.apk` -> No errors detected.
3. **APK Signature:** `apksigner verify --verbose` -> Verified using APK Signature Scheme v2.
4. **Manifest / Metadata:**
   - Package: `com.vision.app`
   - Version Code: `4`
   - Version Name: `0.3.1`
   - Min SDK: `26`, Target SDK: `34`
   - Queries: `com.whatsapp`, `com.whatsapp.w4b`, `org.telegram.messenger`, `com.google.android.gm`, `com.google.android.apps.messaging`, `com.android.messaging`, `com.google.android.calendar`
5. **Deployment:** APK copied to `/storage/emulated/0/Download/Vision-debug.apk` (20 KB).

---

## 4. Unresolved Device-Only Tests

The following require live on-device interaction:
1. Notification Listener Service binding trigger after system toggle in Android Settings.
2. Direct launch verification of Telegram on devices where `org.telegram.messenger/.DefaultIcon` is active.
3. WhatsApp Business launch verification when both `com.whatsapp` and `com.whatsapp.w4b` are installed.
4. Real notification dismissal event dispatching from system status bar.
