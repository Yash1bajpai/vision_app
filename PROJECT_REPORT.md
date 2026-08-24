# Vision Project Report — Pre-Phase-6 UX Refinement & Release 0.5.2

**Date:** 2026-08-24  
**Target Device:** iQOO Z9x I2219 (Android 16 / API 36, arm64-v8a)  
**Current Version:** 0.5.2 (versionCode: 9, compileSdk: 34, targetSdk: 34, minSdk: 26)  
**Prior Commits:** `96e14b0` (Historical N28 disposition), `cea6aa5` (v0.5.1 Docs), `e58a77f` (N2/N9/N13/N19/N33 Fixes), `db085f3` (JUnit 4 conversion)  
**APK Output:** `/storage/emulated/0/Download/Vision-debug.apk`  
**APK SHA-256:** `066182b8127c581179378722759c86de27fdbca2bf1436cc9e8ecd3b8dc0b749`

---

## 1. Executive Summary

Vision is an offline-first Android assistant designed around zero silent actions, zero unapproved execution, and strict safety guardrails.

Release v0.5.2 implements two core UX refinements ahead of Phase 6 while strictly preserving the existing safety architecture and fail-closed risk policy:
1. **Jarvis-Style Permission Request for Outgoing Messages:**
   - The reply confirmation modal dialog immediately preceding reply dispatch has been redesigned with respectful, clear, and conversational Jarvis semantics.
   - **Dialog Title:** `"Tony, may I send this message?"`
   - **Dialog Body:** `"I am ready to send this message to [Resolved Destination]:\n\n\"[Exact Outgoing Text]\"\n\nMay I proceed?"`
   - **Action Controls:** Explicit `Allow` and `Deny` buttons.
   - **Lifecycle & TOCTOU Integrity:** All activity lifecycle guards (`isFinishing() || isDestroyed()`) and bound-capability instance validations are preserved.
   - **Read-Only / Safe Action Isolation:** Read-only actions (`READ_NOTIFICATION`) and local app launching (`OPEN_APP`) strictly remain Tier SAFE (auto-executed) with no dialog and no implication of external communication permission.
2. **Multiline Message Composer & Enter Key:**
   - Converted single-line `EditText` composer to a true multiline message composer.
   - `Enter` inserts a newline character (`\n`) during typing without submitting prematurely (`inputType="textMultiLine|textCapSentences"`, `imeOptions="actionNone|flagNoEnterAction"`).
   - The explicit arrow button (`↑`) serves as the sole send control, trimming outer whitespace while preserving internal formatting and embedded newlines.
   - Dynamic height expansion (1 to 5 lines) with smooth vertical scrolling (`ScrollingMovementMethod`, `verticalScrollBarEnabled=true`).
   - Discoverable composer hint: `"Ask Vision anything... (Enter for newline)"`.
   - Complete support for multiline input across all command clearing paths (`input.setText("")`).
3. **Multiline Parser Preservation:**
   - `VisionActionParser` preserves embedded newlines in reply bodies (e.g. `reply to Alice:\nLine 1\nLine 2`) while properly normalizing whitespace for command intents (`OPEN_APP`, `READ_NOTIFICATION`).
4. **Deterministic JUnit 4 Test Suite Expansion:**
   - Added `test16_multilineReplyAndCommandParsing` and `test17_jarvisStyleReplyConfirmationFormatting`, increasing verified test count to 17/17 tests passing with 0 failures and 0 errors.

---

## 2. Inverted Fail-Closed Risk Policy Specification

Defined in `VisionRiskPolicy` and enforced via `VisionAction.requiresConfirmation()`:

| Risk Tier | Policy | Action Types | Safety Behavior |
|---|---|---|---|
| **Tier SAFE** | Auto-execute immediately (NO modal dialog) | `OPEN_APP`, `READ_NOTIFICATION` *(Explicitly enumerated in `SAFE_TYPES`)* | User's typed command authorizes the read-only or local app launch action directly. Executes immediately and updates `activityText` with `SUCCEEDED` / `FAILED`. Never prompts for permission. |
| **Tier CONFIRMED** | Conversational Jarvis Allow/Deny dialog REQUIRED | `REPLY_NOTIFICATION`<br>*Default fallback:* `UNKNOWN`, `null`, unlisted types<br>*(Documented future: `PAYMENT`, `DELETE`, `DOWNLOAD_FILE`, `SEND_MESSAGE_DIRECT`, `INSTALL`, `CHANGE_SETTING`)* | High-risk external actions mutate device state or send communications. Strictly binds in-memory capability identity at proposal time, validates targets conservatively, requires conversational Jarvis confirmation modal showing exact destination and payload, and re-verifies active capability atomically at dispatch. |

---

## 3. Deterministic JUnit 4 Test Suite Evidence

The deterministic test suite (`com.vision.app.VisionAppTest`) executes 17 test groups offline via Gradle `:app:testDebugUnitTest`:

### Execution Summary from Gradle XML (`TEST-com.vision.app.VisionAppTest.xml`)
- **Total Test Groups Executed:** 17
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
16. `test16_multilineReplyAndCommandParsing`: Multiline command input preservation, verifying embedded newlines and paragraph breaks in reply bodies with and without target specs, and multiline normalization for safe app/read commands.
17. `test17_jarvisStyleReplyConfirmationFormatting`: Jarvis dialog title (`"Tony, may I send this message?"`) and message formatting (`"I am ready to send this message to [Destination]:\n\n\"[Payload]\"\n\nMay I proceed?"`), null fallback safety, and destination display formatting.

---

## 4. Build, Packaging & Verification

1. **Compilation:** Built completely offline with Gradle 8.7 (`:app:testDebugUnitTest :app:assembleDebug --offline`).
2. **ZIP Integrity:** `unzip -t app-debug.apk` -> Clean (no CRC errors, valid DEX archives and resources).
3. **Signature Verification:** `apksigner verify --verbose` -> Verified using APK Signature Scheme v2 (1 signer).
4. **Package Metadata (`aapt dump badging`):**
   - Application ID: `com.vision.app`
   - Version Code: `9`
   - Version Name: `0.5.2`
   - Compile SDK: `34`, Target SDK: `34`, Min SDK: `26`
5. **APK Artifact:** Copied to `/storage/emulated/0/Download/Vision-debug.apk`  
   **SHA-256:** `066182b8127c581179378722759c86de27fdbca2bf1436cc9e8ecd3b8dc0b749`

---

## 5. Live Device Verification Checklist (Pending Physical Execution for v0.5.2)

The following hardware-dependent paths must be verified on the physical iQOO Z9x device for v0.5.2:
1. **Multiline Message Composer & Enter Key:**
   - Tap composer and type `reply to Alice:` followed by `Enter`. Verify cursor moves to line 2.
   - Type multiple paragraphs. Verify `EditText` expands up to 5 lines and scrolls smoothly.
   - Tap `↑` (Send button). Verify full multiline text is submitted with embedded newlines intact.
   - Verify typing `open\nwhatsapp` normalizes and opens WhatsApp without prompt.
2. **Jarvis-Style Permission Request:**
   - Trigger a reply command (e.g. `reply to Alice: On my way!`).
   - Verify modal dialog displays:
     - **Title:** `Tony, may I send this message?`
     - **Message:** `I am ready to send this message to WhatsApp (Alice):\n\n"On my way!"\n\nMay I proceed?`
     - **Buttons:** `Allow` and `Deny`.
   - Tap `Allow` -> Verifies reply is dispatched via `RemoteInput` and displays `SUCCEEDED` in Recent Activity.
   - Tap `Deny` -> Verifies execution is halted with `DENIED` status and no communication is dispatched.
3. **Lifecycle & Orientation Guard:**
   - With Jarvis dialog open, rotate device / trigger theme change. Verify dialog dismisses or re-creates safely without crashing or triggering callbacks on destroyed Activity instance.
4. **Tier SAFE Non-Confirmation:**
   - Type `read notification` and `open telegram`. Verify both execute immediately with zero confirmation modals.

