# Vision Project Report — Phase 5.2 Nemotron Audit Hardening & Real JUnit Test Conversion

**Date:** 2026-08-24  
**Target Device:** iQOO Z9x I2219 (Android 16 / API 36, arm64-v8a)  
**Current Version:** 0.5.1 (versionCode: 8, compileSdk: 34, targetSdk: 34, minSdk: 26)  
**Prior Commits:** `bebd52d` (v0.5.0 Release & Risk Policy), `a47bd6a` (F1-F11 Audit Fixes)  
**APK Output:** `/storage/emulated/0/Download/Vision-debug.apk`

---

## 1. Executive Summary

Vision is an offline-first Android assistant designed around zero silent actions, zero unapproved execution, and strict safety guardrails.

In v0.5.1, two major milestones and critical audit remediations were accomplished:
1. **Real JUnit 4 Test Suite Execution (Task 1):** Converted the deterministic test suite to real JUnit 4 `@Test` methods with `testImplementation "junit:junit:4.13.2"`. Gradle `:app:testDebugUnitTest` now executes the full test suite and outputs valid XML test results (`TEST-com.vision.app.VisionAppTest.xml`) proving 15/15 test groups executed with 0 failures and 0 errors.
2. **Second-Audit (Nemotron) Verified Fixes & Triage:**
   - **N2 (MED):** Moved notification flag check inside the `synchronized (VisionNotificationListener.class)` block to make flag evaluation and snapshot/capability writes atomic against concurrent removals.
   - **N9 (MED):** Modified group-summary handling so that `FLAG_GROUP_SUMMARY` notifications carrying a direct `RemoteInput` reply action are accepted rather than unconditionally dropped.
   - **N13 (MED):** Hardened reply regex token boundaries with `\b`, ensuring targets beginning with "to" (`reply to tokyo: hi`, `reply to Tom: hi`) and message bodies containing colons (`reply to Alice: hello: world`) route without target swallowing or truncation.
   - **N19 (MED):** Resolved activity recreation / configuration-change lifecycle vulnerability by tracking `activeDialog`, dismissing active dialogs in `onDestroy()`, and guarding all dialog callbacks with `isFinishing() || isDestroyed()` checks.
   - **N28 (CRITICAL/PROCESS):** Identified that `VisionAppTest.java` was a plain `public static void main()` class with no JUnit `@Test` methods, causing Gradle to execute zero tests and rendering prior green test claims vacuous; fixed by `db085f3` converting the suite to 15 real JUnit 4 `@Test` methods (`tests=15 failures=0 errors=0`).
   - **N33 (MED):** Inverted `VisionRiskPolicy` from fail-open to **Fail-Closed**: only an explicit `SAFE_TYPES` EnumSet (`OPEN_APP`, `READ_NOTIFICATION`) auto-executes; all other types (including `UNKNOWN`, `null`, and unlisted future types) strictly require modal confirmation (`RiskTier.CONFIRMED`).

---

## 2. Nemotron Second-Audit Triage & Resolution Table

| ID | Severity | Finding Summary | Disposition & Resolution in v0.5.1 |
|---|---|---|---|
| **N2** | MEDIUM | Check-then-act gap in `onNotificationPosted` between flag checking and synchronized state write | **FIXED:** Moved `shouldIgnoreNotification` check inside the `synchronized (VisionNotificationListener.class)` block, ensuring flag filtering and snapshot/reply-capability assignments occur atomically on the class monitor lock. |
| **N9** | MEDIUM | Unconditional drop of `FLAG_GROUP_SUMMARY` drops actionable group reply capabilities | **FIXED:** Updated `shouldIgnoreNotification(flags, hasReplyAction)` to drop `FLAG_GROUP_SUMMARY` ONLY when `hasReplyAction == false`. Group summaries with direct `RemoteInput` actions are accepted and made available for reply. |
| **N13** | MEDIUM | Potential target swallowing or body truncation for reply targets beginning with "to" or bodies with colons | **FIXED & TESTED:** Added `\b` word boundary to `REPLY_TO_PATTERN` (`reply\s+to\b...`). Verified empirically that `"reply to tokyo: hi"`, `"reply to Tom: hi"`, and `"reply to Alice: hello: world"` parse cleanly with correct target and body isolation, while `"reply to: text"` preserves default latest-notification routing. |
| **N19** | MEDIUM (was HIGH) | `AlertDialog` callbacks firing against destroyed Activity after configuration / theme change | **FIXED:** `MainActivity` tracks `activeDialog`, cleanly dismisses it in `onDestroy()`, and guards all positive and negative dialog callbacks with `if (isFinishing() || isDestroyed()) return;`. Prevents leaked windows and ensures dead activities perform no actions. |
| **N28** | CRITICAL / PROCESS | `VisionAppTest.java` was a plain `public static void main()` class with no JUnit `@Test` methods; Gradle executed zero tests and prior green test claims were vacuous | **FIXED in `db085f3`:** Converted the deterministic test suite to 15 real JUnit 4 `@Test` methods using `testImplementation "junit:junit:4.13.2"`. Gradle `:app:testDebugUnitTest` now executes the full suite with XML evidence confirming `tests=15 failures=0 errors=0`. |
| **N33** | MEDIUM | `VisionRiskPolicy` defaulted to SAFE for unlisted action types (fail-open) | **FIXED:** Inverted risk policy to **FAIL-CLOSED**. Replaced `CONFIRMED_TYPES` with an explicit `SAFE_TYPES` EnumSet containing only `OPEN_APP` and `READ_NOTIFICATION`. All other types (including `UNKNOWN`, `null`, and future actions) return `RiskTier.CONFIRMED` and `requiresConfirmation() == true`. |

---

## 3. Inverted Fail-Closed Risk Policy Specification

Defined in `VisionRiskPolicy` and enforced via `VisionAction.requiresConfirmation()`:

| Risk Tier | Policy | Action Types | Safety Behavior |
|---|---|---|---|
| **Tier SAFE** | Auto-execute immediately (NO modal dialog) | `OPEN_APP`, `READ_NOTIFICATION` *(Explicitly enumerated in `SAFE_TYPES`)* | User's typed command authorizes the read-only or local app launch action directly. Executes immediately and updates `activityText` with `SUCCEEDED` / `FAILED`. |
| **Tier CONFIRMED** | Modal Allow/Deny dialog REQUIRED | `REPLY_NOTIFICATION`<br>*Default fallback:* `UNKNOWN`, `null`, unlisted types<br>*(Documented future: `PAYMENT`, `DELETE`, `DOWNLOAD_FILE`, `SEND_MESSAGE_DIRECT`, `INSTALL`, `CHANGE_SETTING`)* | High-risk external actions mutate device state or send communications. Strictly binds in-memory capability identity at proposal time, validates targets conservatively, requires explicit modal confirmation dialog showing exact source/recipient/payload, and re-verifies active capability atomically at dispatch. |

---

## 4. Deterministic JUnit 4 Test Suite Conversion & Evidence

In v0.5.0, `VisionAppTest.java` was structured as a standalone `public static void main()` runner with zero JUnit `@Test` methods, which caused Gradle `:app:testDebugUnitTest` to execute zero tests (0 executed tests) despite passing builds. In v0.5.1, the entire test suite (`com.vision.app.VisionAppTest`) has been converted to JUnit 4 `@Test` methods using `testImplementation "junit:junit:4.13.2"`, resolving finding N28 (fixed in `db085f3`):

### Execution Summary from Gradle XML (`TEST-com.vision.app.VisionAppTest.xml`)
- **Version Comparison:** v0.5.0 executed 0 tests -> v0.5.1 executes 15 tests
- **Total Test Groups Executed:** 15
- **Failures:** 0
- **Errors:** 0
- **Skipped:** 0
- **Execution Mode:** Offline deterministic JVM unit tests (`:app:testDebugUnitTest --offline`)

### Test Case Breakdown
1. `test01_nullAndEmptyParserInputs`: Null, empty string, and whitespace input safety.
2. `test02_notificationReadingRequests`: 15 natural variations of read requests including gerund routing (`start reading my messages`).
3. `test03_appLaunchingRequests`: Launch commands for WhatsApp, WhatsApp Business (`w4b`), Telegram, Gmail, Messages, Calendar.
4. `test04_notificationReplyRequests`: Reply colon syntax (`reply to <target>: <text>`), N13 word boundaries (`tokyo`, `Tom`, `tony`), colon-in-body handling, and rejection of space-separated missing-colon commands.
5. `test05_unrecognizedRequests`: Unknown command routing safety.
6. `test06_actionStateMachineAndRiskPolicy`: State transitions (`PROPOSED` -> `APPROVED` -> `RUNNING` -> `SUCCEEDED`), action labels, and N33 fail-closed confirmation requirements.
7. `test07_notificationPackageAllowlist`: Allowlisted messaging apps vs rejected untrusted packages.
8. `test08_notificationSnapshotCreationAndKeyMatching`: In-memory notification snapshot fields and null safety.
9. `test09_replyCapabilityStructureAndSafety`: `NotificationReplyCapability` group chat metadata (`conversationTitle`, `senderPerson`) and null intent protection.
10. `test10_targetValidationHardening`: Strict canonical app alias matching, whole-token sender matching, loose substring rejection, and group member validation.
11. `test11_notificationReplacementToctouRace`: Replaced notification capability rejected as `STALE_OR_REMOVED` upon approval.
12. `test12_notificationRemovalStaleKeyDispatch`: Dismissed notification capability rejected as `STALE_OR_REMOVED`, invalid arguments guarded.
13. `test13_capabilityActiveMatchAndLifecycle`: Capability active matching and package source name resolution.
14. `test14_flagFilteringLogic`: Pure helper flag filtering verifying N9 group summary acceptance with reply actions, ongoing/foreground service filtering, and standard notification acceptance.
15. `test15_riskPolicyTierVerification`: Comprehensive N33 fail-closed verification (`SAFE_TYPES` for `OPEN_APP`/`READ_NOTIFICATION`, `CONFIRMED` for `REPLY_NOTIFICATION`, `UNKNOWN`, and `null`).

---

## 5. Build, Packaging & Verification

1. **Compilation:** Built completely offline with Gradle 8.7 (`:app:testDebugUnitTest :app:assembleDebug --offline`).
2. **ZIP Integrity:** `unzip -t app-debug.apk` -> Clean (no CRC errors, valid DEX archives and resources).
3. **Signature Verification:** `apksigner verify --verbose` -> Verified using APK Signature Scheme v2 (1 signer).
4. **Package Metadata:**
   - Application ID: `com.vision.app`
   - Version Code: `8`
   - Version Name: `0.5.1`
   - Compile SDK: `34`, Target SDK: `34`, Min SDK: `26`
5. **APK Artifact:** Copied to `/storage/emulated/0/Download/Vision-debug.apk` (SHA-256: `21882edd2851f3575723e8fffdfe5de6b8cf34052509592adf1c2f16a49c0267`).

---

## 6. Live Device Verification Checklist

The following hardware-dependent paths should be verified on the physical device:
1. **Tier SAFE Immediate Execution:**
   - Type `open telegram` -> Launches Telegram without showing confirmation dialog.
   - Type `read notification` -> Immediately displays latest notification text in Recent Activity.
2. **Tier CONFIRMED Modal Confirmation & Lifecycle (N19):**
   - Type `reply to Alice: I'll be there soon` -> Displays modal confirmation dialog.
   - Rotate screen / trigger configuration change -> Verify dialog dismisses or re-creates safely without crashing or executing stale callbacks.
   - Tap `Allow` -> Dispatches reply via RemoteInput and displays `SUCCEEDED`.
   - Tap `Deny` -> Halts execution with `DENIED` status.
3. **N9 Group Summary Replies:**
   - In a WhatsApp/Telegram group with multiple messages, verify direct reply works from the summary capability without dropping.
4. **N13 Target Parsing:**
   - Type `reply to tokyo: hi` -> Verifies dialog targets `tokyo` with message `"hi"`.
   - Type `reply to: hello` -> Verifies dialog targets `latest notification` with message `"hello"`.

