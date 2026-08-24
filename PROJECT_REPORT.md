# Vision Project Report — Phase 5.1 Hardened Audit Fixes & Risk-Tiered Confirmation Policy

**Date:** 2026-08-24  
**Target Device:** iQOO Z9x I2219 (Android 16 / API 36, arm64-v8a)  
**Current Version:** 0.5.0 (versionCode: 7, compileSdk: 34, targetSdk: 34, minSdk: 26)  
**Prior Commits:** `ee7796a` (Phase 4 Hardening), `8bc9a78` (Phase 5 MVP), `e20f9d0` (Phase 5 TOCTOU Fix)  
**APK Output:** `/storage/emulated/0/Download/Vision-debug.apk`

---

## 1. Executive Summary

Vision is an offline-first Android assistant designed around zero silent actions, zero unapproved execution, and strict safety guardrails.

In v0.5.0, two major enhancements were implemented:
1. **Verified Audit Findings Fixed (F1–F11):** All verified defects from deep codebase audits were addressed, covering summary notification flag filtering, strict canonical target validation, reply colon delimiters, binder-thread synchronization, distinct WhatsApp Business routing, parser gerund handling, group chat metadata tracking, scrolling activity logs, component parsing for notification listener access, and clean destination display formatting.
2. **New Risk-Tiered Confirmation Policy:** Replaced blanket confirmation prompting with an explicit, risk-tiered policy (`VisionRiskPolicy`). The typed command itself directly authorizes Tier SAFE operations (`OPEN_APP`, `READ_NOTIFICATION`) to auto-execute cleanly without modal dialog friction. Tier CONFIRMED operations (`REPLY_NOTIFICATION` and future destructive/external actions) strictly enforce proposal-time capability binding and modal confirmation dialogs immediately before dispatch.

---

## 2. Risk-Tiered Confirmation Policy Specification

The central risk policy is defined in `VisionRiskPolicy` and checked via `VisionAction.requiresConfirmation()`:

| Risk Tier | Policy | Action Types | Behavior & Safety Mechanism |
|---|---|---|---|
| **Tier SAFE** | Auto-execute immediately (NO dialog) | `OPEN_APP`, `READ_NOTIFICATION` | The user's explicit typed command authorizes the read-only or local app launch action. Executed immediately with status (`SUCCEEDED` / `FAILED`) displayed in `activityText`. |
| **Tier CONFIRMED** | Modal Allow/Deny dialog REQUIRED | `REPLY_NOTIFICATION`<br>*(Documented future: `PAYMENT`, `DELETE`, `DOWNLOAD_FILE`, `SEND_MESSAGE_DIRECT`, `INSTALL`, `CHANGE_SETTING`)* | High-risk external actions mutate device state or send communications. Strictly binds in-memory capability identity at proposal time, validates targets conservatively, requires explicit modal confirmation dialog showing exact source/recipient/payload, and re-verifies active capability atomically at dispatch. |

---

## 3. Verified Audit Findings & Fix Mapping (F1–F11, F13)

| ID | Severity | Finding Summary | Resolution in v0.5.0 |
|---|---|---|---|
| **F1** | HIGH | `VisionNotificationListener.onNotificationPosted` snapshot overwrite by summaries or background noise | Implemented `shouldIgnoreNotification(flags, hasReplyAction)` pure helper: drops notifications where `(flags & FLAG_GROUP_SUMMARY) != 0`, and drops `FLAG_ONGOING_EVENT` / `FLAG_FOREGROUND_SERVICE` noise lacking direct `RemoteInput` reply actions before modifying process snapshots. |
| **F2** | HIGH | Loose `normPkg.contains(normTarget)` substring matching and bidirectional loose sender substring matching | Removed all package substring matching. Replaced with strict equality against canonical app aliases (`whatsapp`/`wa`, `telegram`/`tg`, `gmail`/`google mail`, `messages`/`sms`, `calendar`). Hardened sender matching to exact full-name or whole-token matching. Cross-app composite targets fail closed. |
| **F3** | MED | `REPLY_PATTERN` silently split multi-word recipient names when missing delimiter | When `to <target>` is present, strictly requires a colon (`:`) delimiter before reply text (`reply to <target>: <text>`). Space-separated commands without colon (e.g. `reply to Bob Smith thanks`) fail safely as `UNKNOWN`. Space-separated `reply <text>` without `to` remains supported. |
| **F4** | MED | Missing synchronization between listener binder callbacks and reply dispatch | Synchronized `onNotificationPosted`, `onNotificationRemoved`, and `sendBoundReply` on `VisionNotificationListener.class` monitor lock to eliminate binder-thread dispatch races. |
| **F5** | MED | WhatsApp Business package priority and parser target distinction | Parser produces distinct `"WhatsApp Business"` target when command contains `business` or `w4b`. Candidate package resolution tries `com.whatsapp.w4b` first then `com.whatsapp` for `"WhatsApp Business"`, while `"WhatsApp"` prioritizes `com.whatsapp`. Target validation accepts either package for plain `"whatsapp"`. |
| **F6** | MED | Parser verb order caused gerund commands to route to `OPEN_APP` | Reordered parser to evaluate `READ_NOTIFICATION` intent before `OPEN_APP`. Expanded read verb set to include `reading` and `checking`, correctly routing `start reading my messages` to notification reading while preserving `open messages` and `open sms`. |
| **F7** | LOW | Group chat `MessagingStyle` collapsed sender and conversation title | Extended `NotificationReplyCapability` to store distinct `conversationTitle` and `senderPerson` metadata; `validateTarget` and dialog display accept and format either identifier. |
| **F8** | LOW | Long notification content clipped in `MainActivity` | Attached `ScrollingMovementMethod` to `activityText` to enable smooth scrolling of long notification bodies. |
| **F9** | LOW | Commands formatted as `reply to: <text>` failed | Added parser regex support for `reply to: <text>` (empty target resolving to latest notification). |
| **F10** | LOW | `isNotificationAccessEnabled()` used raw substring matching | Replaced raw substring check with proper parsing of colon-delimited `ComponentName.unflattenFromString()` entries matching `getPackageName()`. |
| **F11** | LOW | Redundant `from WhatsApp (WhatsApp)` formatting when sender name equaled source app | Updated `formatDestinationDisplay` to show single app name when sender is empty or equals the source name. |
| **F13** | DOCS | Stale `auditreport.md` header | Added one-line note at the top of `auditreport.md` noting that v0.3.0 audit findings are superseded by `PROJECT_REPORT.md`. |

---

## 4. Verification Evidence & Test Results

### Deterministic JVM Unit Tests (`com.vision.app.VisionAppTest`)
Executed all 15 deterministic test suites:
- **Test 1:** Null, empty, and whitespace parser inputs -> PASSED
- **Test 2:** Notification reading commands & gerund routing (`start reading my messages`) -> PASSED
- **Test 3:** App launch commands (WhatsApp, WhatsApp Business, Telegram, Gmail, Messages, Calendar) -> PASSED
- **Test 4:** Notification reply commands (colon requirement, `reply to:`, empty text safety, space-separated target rejection) -> PASSED
- **Test 5:** Unknown/unsupported command safety -> PASSED
- **Test 6:** `VisionAction` state transitions, labels, and Risk Policy tiering -> PASSED
- **Test 7:** Notification package allowlist filtering -> PASSED
- **Test 8:** Notification snapshot creation and key matching -> PASSED
- **Test 9:** `NotificationReplyCapability` structure, group metadata (`conversationTitle`, `senderPerson`), & null safety -> PASSED
- **Test 10:** Hardened target validation (strict canonical aliases, whole-token sender match, loose substring rejection, group chat validation) -> PASSED
- **Test 11:** Notification replacement between proposal and approval (TOCTOU prevention) -> PASSED
- **Test 12:** Notification removal / stale key dispatch prevention -> PASSED
- **Test 13:** Capability active match verification and bound lifecycle flow -> PASSED
- **Test 14:** F1 Flag filtering logic (`shouldIgnoreNotification` pure helper: summaries, ongoing/foreground noise) -> PASSED
- **Test 15:** Risk policy tier verification (`VisionRiskPolicy` SAFE vs CONFIRMED, documented future types) -> PASSED

*Result: 15/15 test suites passed with 0 failures.*

### Build & Package Validation
1. **Compilation:** Built offline with Gradle 8.7 (`:app:testDebugUnitTest :app:assembleDebug --offline`).
2. **ZIP Integrity:** `unzip -t app-debug.apk` -> No errors detected in compressed data (META-INF, classes.dex, classes2.dex, AndroidManifest.xml, resources.arsc).
3. **Signature Verification:** `apksigner verify --verbose` -> Verified using APK Signature Scheme v2 (1 signer).
4. **Metadata & Manifest Tree:**
   - Package: `com.vision.app`
   - Version Code: `7`
   - Version Name: `0.5.0`
   - Compile SDK: `34`, Target SDK: `34`, Min SDK: `26`
   - Declared Queries: `com.whatsapp`, `com.whatsapp.w4b`, `org.telegram.messenger`, `com.google.android.gm`, `com.google.android.apps.messaging`, `com.android.messaging`, `com.google.android.calendar`
5. **Deployment:** APK copied to `/storage/emulated/0/Download/Vision-debug.apk` (SHA-256: `44e92dccfac18e4b4441482ce3f7f73a93303c9ef5650d6484f91b39956fe7cd`).

---

## 5. Residual Risks & Live Device Verification Checklist

The following hardware-dependent paths must be tested on the physical device:
1. **Tier SAFE Auto-Execution:**
   - Verify typing `open whatsapp` immediately launches WhatsApp without showing any confirmation dialog.
   - Verify typing `read notification` immediately renders the latest notification content into Recent Activity without showing any confirmation dialog.
   - Verify clicking the UI button `Read latest notification` immediately renders the latest notification without showing a confirmation dialog.
2. **Tier CONFIRMED Reply Modal Flow:**
   - Verify typing `reply to Alice: On my way` displays the modal dialog showing `Action: Reply to WhatsApp (Alice)` and payload `"On my way"`.
   - Verify tapping `Deny` cancels the action with `DENIED` status.
   - Verify tapping `Allow` dispatches the reply and shows `SUCCEEDED`.
3. **F1 Summary Flag Filtering on Live WhatsApp Notifications:**
   - Receive multiple messages in a chat to trigger Android's summary notification; verify that the individual message content is preserved and readable rather than replaced by "2 new messages".
4. **F5 WhatsApp Business:**
   - On a device with WhatsApp Business installed, verify typing `open whatsapp business` or `start w4b` prioritizes opening WhatsApp Business.
5. **F7 Group Chat Reply:**
   - Receive a message in a WhatsApp/Telegram group chat; verify typing `reply to <Group Name>: text` or `reply to <Sender Name>: text` validates successfully and formats the dialog as `WhatsApp (<Group Name> - <Sender Name>)`.
