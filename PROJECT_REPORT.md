# Vision Project Report — Phase 6: Deterministic Notification Reliability & Action Observability

**Date:** 2026-08-25  
**Target Device:** iQOO Z9x I2219 (Android 16 / API 36, arm64-v8a)  
**Current Version:** 0.6.0 (versionCode: 10, compileSdk: 34, targetSdk: 34, minSdk: 26)  
**Prior Commits:** `3c0a589` (v0.5.2 Docs), `3197478` (Jarvis reply confirmation & multiline composer), `96e14b0` (Historical N28 disposition), `cea6aa5` (v0.5.1 Docs), `e58a77f` (N2/N9/N13/N19/N33 Fixes)  
**APK Output:** `/storage/emulated/0/Download/Vision-debug.apk`  
**APK SHA-256:** `23791f12b8ddd21318fbbda8f84dbed357db7fd9c954007da68af22bd4b58c78`

---

## 1. Executive Summary

Vision is an offline-first Android integration layer built on deterministic execution, memory-only state safety, explicit modal confirmation for external mutations, and zero background persistence.

> **Assistant Intelligence Runtime Status:**  
> Local language models, LLM runtimes, on-device intelligence engines, network AI, embeddings, and free-form action generators are explicitly **excluded and deferred** from Phase 6. The scope of this release is strictly bounded deterministic Android integration hardening and action observability.

### Phase 6 Core Deliverables:
1. **Explicit In-Memory State Model:** Implemented `VisionNotificationListener.ListenerState` tracking `NotificationStatus` (`NO_NOTIFICATION_YET`, `ACTIVE_NOTIFICATION`, `NOTIFICATION_REMOVED`), stable keys, package names, timestamps, reply capability status, and monotonic sequence counters. No notification bodies, sender strings, reply texts, PendingIntents, or RemoteInputs are ever logged or persisted.
2. **Deterministic Notification Ordering & Tie-Breaking:** Enforced `postTime` validation where newer timestamps replace active state, older timestamps arriving out-of-order over IPC are rejected, and equal timestamps resolve deterministically via key matching (in-place update) or lexicographical tie-breaking (`newKey.compareTo(currentKey) >= 0`).
3. **Removal & Filter State Isolation:** Notification removals match strictly on the active notification's key. Unrelated dismissals, unsupported package callbacks, and filtered summary/ongoing noise never erase or mutate valid active state.
4. **Hardened Reply Action Selection & RemoteInput Eligibility:** Prefers `Notification.Action.SEMANTIC_ACTION_REPLY` on API 28+ with fallback to the first text-capable action. Rejects data-only RemoteInputs lacking free-form input and choices.
5. **Exact Multiline Reply Integrity:** Trims outer whitespace while preserving internal formatting and line breaks (`\n`, `\r\n`) byte-for-byte across parser, Jarvis confirmation dialog, and RemoteInput dispatch bundle.
6. **Bounded Action Observability:** `MainActivity` distinguishes specific failure and status reasons (`NOTIFICATION REMOVED`, `NO SUPPORTED NOTIFICATION`, `NO REPLYABLE NOTIFICATION`, `TARGET MISMATCH`, `FAILED INTENT`) on the `Recent Activity` surface without retaining multi-action logs or exposing private data.
7. **Expanded Deterministic JUnit 4 Test Suite (24 Tests):** Added 7 comprehensive test groups (Tests 18–24) covering out-of-order rejection, tie-breaking, removal isolation, candidate scoring, state lifecycle, multiline byte identity, and fail-closed invariants. All 24 tests execute offline on the JVM with 0 failures and 0 errors.

---

## 2. Inverted Fail-Closed Risk Policy Specification

Defined in `VisionRiskPolicy` and enforced via `VisionAction.requiresConfirmation()`:

| Risk Tier | Policy | Action Types | Safety Behavior |
|---|---|---|---|
| **Tier SAFE** | Auto-execute immediately (NO modal dialog) | `OPEN_APP`, `READ_NOTIFICATION` *(Explicitly enumerated in `SAFE_TYPES`)* | User's typed command authorizes the read-only or local app launch action directly. Executes immediately upon validation and outputs concise `SUCCEEDED` / `FAILED` status to the Recent Activity surface. Never prompts for permission. |
| **Tier CONFIRMED** | Conversational Jarvis Allow/Deny dialog REQUIRED | `REPLY_NOTIFICATION`<br>*Default fallback:* `UNKNOWN`, `null`, unlisted types<br>*(Documented future: `PAYMENT`, `DELETE`, `DOWNLOAD_FILE`, `SEND_MESSAGE_DIRECT`, `INSTALL`, `CHANGE_SETTING`)* | High-risk external actions mutate device state or send communications. Strictly binds in-memory capability identity at proposal time, validates targets conservatively, requires conversational Jarvis confirmation modal showing exact destination and payload, and re-verifies active capability atomically at dispatch. |

---

## 3. Deterministic JUnit 4 Test Suite Evidence

The deterministic test suite (`com.vision.app.VisionAppTest`) executes 24 test groups offline via Gradle `:app:testDebugUnitTest`:

### Execution Summary from Gradle XML (`TEST-com.vision.app.VisionAppTest.xml`)
- **Total Test Groups Executed:** 24
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
18. `test18_outOfOrderNotificationAcceptanceAndTieBreaking`: Validates ordering policy: newer postTime accepted, older out-of-order postTime rejected, equal postTime in-place update accepted, and lexicographical tie-breaking for equal postTime with distinct keys.
19. `test19_matchingVsNonMatchingRemoval`: Verifies removal isolation: removing non-matching keys leaves active snapshot/capability intact; removing matching key clears active state and records `NOTIFICATION_REMOVED` status.
20. `test20_filteredAndUnsupportedPreserveActiveState`: Verifies unsupported packages and filtered flags (summary/ongoing noise without reply) never overwrite or corrupt existing active notifications.
21. `test21_replyActionSelectionAndRemoteInputEligibility`: Tests RemoteInput eligibility (free-form vs choices vs data-only exclusion), candidate scoring (`SEMANTIC_ACTION_REPLY` priority score 2 vs standard score 1), and multi-candidate selection.
22. `test22_listenerStateTransitionsAndMetadataSafety`: Validates `ListenerState` lifecycle transitions (`NO_NOTIFICATION_YET` -> `ACTIVE` -> `REMOVED`), helper accessors, monotonic sequence counter increments, and transient metadata safety.
23. `test23_multilinePayloadExactIdentityAndIntegrity`: Verifies byte-for-byte preservation of multiline payloads across parser, Jarvis confirmation dialog formatting, and CRLF line breaks.
24. `test24_riskTiersAndActionSafetyInvariants`: Enforces risk tier invariants across all action types under fail-closed security.

---

## 4. Build, Packaging & Verification

1. **Compilation:** Built completely offline with Gradle 8.7 (`:app:testDebugUnitTest :app:assembleDebug --offline`).
2. **ZIP Integrity:** `unzip -t app-debug.apk` -> Clean (no CRC errors, valid DEX archives and resources).
3. **Signature Verification:** `apksigner verify --verbose` -> Verified using APK Signature Scheme v2 (1 signer).
4. **Package Metadata (`aapt dump badging`):**
   - Application ID: `com.vision.app`
   - Version Code: `10`
   - Version Name: `0.6.0`
   - Compile SDK: `34`, Target SDK: `34`, Min SDK: `26`
5. **APK Artifact:** Copied to `/storage/emulated/0/Download/Vision-debug.apk`  
   **SHA-256:** `23791f12b8ddd21318fbbda8f84dbed357db7fd9c954007da68af22bd4b58c78`

---

## 5. Live Device Verification Checklist (Pending Physical Execution for v0.6.0)

The following hardware-dependent paths require verification on the physical iQOO Z9x device:
1. **Notification Status Lifecycle:**
   - On clean start without notifications, tap `Read latest notification` -> Verify displays `NO SUPPORTED NOTIFICATION`.
   - Post incoming notification -> Tap `Read latest notification` -> Verify displays notification sender, title, and body.
   - Dismiss notification in Android notification shade -> Tap `Read latest notification` -> Verify displays `NOTIFICATION REMOVED`.
2. **Out-of-Order Notification Delivery:**
   - Simulate rapid incoming messages -> Verify newest message (by postTime) is retained as active snapshot.
3. **Semantic Reply Action Priority:**
   - On WhatsApp/Telegram notification with multiple actions (e.g. "Mark as read" and "Reply"), trigger `reply to <contact>: <text>`.
   - Verify `SEMANTIC_ACTION_REPLY` is selected and dispatched correctly upon user approval.
4. **Multiline Reply Dispatch:**
   - Send multiline reply with paragraphs and newlines -> Verify receiving device displays identical multiline message formatting.
5. **Lifecycle & Dialog Dismissal:**
   - Trigger Jarvis dialog, rotate device or trigger configuration change -> Verify dialog dismisses gracefully without crashing or invoking callbacks on destroyed Activity.
6. **Tier SAFE Auto-Execution:**
   - Execute `read notification` and `open whatsapp` -> Verify immediate execution without prompt.

