# Vision Project Report — Phase 7: New Message Composer Handoff

**Date:** 2026-08-26
**Target Device:** iQOO Z9x I2219 (Android 16 / API 36, arm64-v8a)  
**Current Version:** 0.7.0 (versionCode: 13, compileSdk: 34, targetSdk: 34, minSdk: 26)
**Prior Commits:** `2943125` (Phase 6.2 fix & regression tests), `72a6d52` (v0.6.1 Docs), `b4f34bd` (Phase 6.1 atomic reply dispatch & production boundaries), `205414a` (v0.6.0 Docs)
**APK Output:** `/storage/emulated/0/Download/Vision-debug.apk`  
**APK SHA-256:** `228e5daed197eacb46368b8d1b2919cf5d67af36b3333177c1b0ca55ac77ac5a`

---

## 1. Executive Summary

### Phase 7 Deliverables
1. Added `SEND_MESSAGE_DIRECT` as a distinct confirmed action for requests that do not depend on a notification.
2. Added strict explicit-destination parsing for SMS, email, WhatsApp, WhatsApp Business, and Telegram.
3. Added `DirectMessageIntentFactory`, which only constructs supported external composer handoffs and performs no delivery.
4. Added cancellation-safe confirmation handling and a distinct `COMPOSER_OPENED` terminal state so opening an external composer is not represented as a sent message.
5. Added parser and policy regression coverage; framework intent behavior remains a required physical-device verification gate.

Contact-name lookup, Accessibility automation, silent sending, arbitrary chooser fallback, root, and Device Owner are explicitly out of scope.

---

Vision is an offline-first Android integration layer built on deterministic execution, memory-only state safety, explicit modal confirmation for external mutations, and zero disk persistence.

> **Assistant Intelligence Runtime Status:**  
> Local language models, LLM runtimes, on-device intelligence engines, network AI, embeddings, and free-form action generators remain explicitly **excluded and deferred**. This release is bounded to deterministic Android composer handoffs and confirmation safety.

### Prior Phase 6.2 Record:
1. **Dialog Dismissal & Cancellation Safety:** Reply confirmation dismissal by Back or outside touch transitions a proposed reply to `DENIED` without sending.
2. **Precision Persistence Invariant Documentation:** Notification state remains in memory with zero disk persistence.
3. **Production Boundary Regression Test Suite:** The prior release added `test26_productionBoundaryRegressionSemantics`:
   - Same-key replacement notification without capability clears `latestReplyCapability` to `null`.
   - Posting `cap1` then `cap2` verifies `isCapabilityActive(cap1)` is `false` and `cap2` is active.
   - Non-matching notification removal preserves active notification and capability state.
    All prior 26 tests passed offline with 0 failures and 0 errors.

---

## 2. Inverted Fail-Closed Risk Policy Specification

Defined in `VisionRiskPolicy` and enforced via `VisionAction.requiresConfirmation()`:

| Risk Tier | Policy | Action Types | Safety Behavior |
|---|---|---|---|
| **Tier SAFE** | Auto-execute immediately (NO modal dialog) | `OPEN_APP`, `READ_NOTIFICATION` *(Explicitly enumerated in `SAFE_TYPES`)* | User's typed command authorizes the read-only or local app launch action directly. Executes immediately upon validation and outputs concise `SUCCEEDED` / `FAILED` status to the Recent Activity surface. Never prompts for permission. |
| **Tier CONFIRMED** | Conversational Allow/Deny dialog REQUIRED | `REPLY_NOTIFICATION`, `SEND_MESSAGE_DIRECT`<br>*Default fallback:* `UNKNOWN`, `null`, unlisted types<br>*(Documented future: `PAYMENT`, `DELETE`, `DOWNLOAD_FILE`, `INSTALL`, `CHANGE_SETTING`)* | External communication actions require explicit approval. Notification replies bind capability identity; direct messages bind exact channel, destination, and payload, then open only a supported external composer. |

---

## 3. Deterministic JUnit 4 Test Suite Evidence

The deterministic test suite (`com.vision.app.VisionAppTest`) executes 28 test groups offline via Gradle `:app:testDebugUnitTest`:

### Execution Summary from Gradle XML (`TEST-com.vision.app.VisionAppTest.xml`)
- **Total Test Groups Executed:** 28
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
18. `test18_outOfOrderNotificationAcceptanceAndTieBreaking`: Validates ordering policy via pure helpers and `processPostedNotification`: newer postTime accepted, older out-of-order postTime rejected, equal postTime in-place update accepted, and lexicographical tie-breaking for equal postTime with distinct keys.
19. `test19_matchingVsNonMatchingRemoval`: Verifies removal isolation via `processRemovedNotification`: removing non-matching keys leaves active snapshot/capability intact; removing matching key clears active state and records `NOTIFICATION_REMOVED` status.
20. `test20_filteredAndUnsupportedPreserveActiveState`: Verifies unsupported packages and filtered flags (summary/ongoing noise without reply) never overwrite or corrupt existing active notifications via `processPostedNotification`.
21. `test21_replyActionSelectionAndRemoteInputEligibility`: Tests RemoteInput eligibility (free-form vs choices vs data-only exclusion), candidate scoring (`SEMANTIC_ACTION_REPLY` priority score 2 vs standard score 1), and multi-candidate selection.
22. `test22_listenerStateTransitionsAndMetadataSafety`: Validates `ListenerState` lifecycle transitions (`NO_NOTIFICATION_YET` -> `ACTIVE` -> `REMOVED`) and monotonic sequence counters via `processPostedNotification` and `processRemovedNotification`.
23. `test23_multilinePayloadExactIdentityAndIntegrity`: Verifies byte-for-byte preservation of multiline payloads across parser, Jarvis confirmation dialog formatting, and CRLF line breaks.
24. `test24_riskTiersAndActionSafetyInvariants`: Enforces risk tier invariants across all action types under fail-closed security.
25. `test25_productionProcessingBoundaryComprehensive`: End-to-end verification of `processPostedNotification` and `processRemovedNotification` covering unsupported package rejection, filtered flag preservation, matching removal, sequence updates, and active status re-entry.
26. `test26_productionBoundaryRegressionSemantics`: Direct verification of capability clearing on same-key replacement with null capability, successive capability deactivation (`cap1` inactive when `cap2` posted), and preservation of active state on non-matching removal.
27. `test27_directMessageParsingAndRiskPolicy`: Strict explicit destination grammar, channel classification, multiline preservation, invalid destination rejection, and confirmed risk tier.
28. `test28_directMessageIntentFactory`: Null and unsupported-channel fail-closed behavior at the intent boundary. Positive framework intent assertions remain a device-test gate because Android framework methods are unavailable in local JVM tests.

---

## 4. Build, Packaging & Verification

1. **Compilation:** Built completely offline with Gradle 8.7 (`:app:testDebugUnitTest :app:assembleDebug --offline`).
2. **ZIP Integrity:** `unzip -t app-debug.apk` -> Clean (no CRC errors, valid DEX archives and resources).
3. **Signature Verification:** `apksigner verify --verbose` -> Verified using APK Signature Scheme v2 (1 signer).
4. **Package Metadata (`aapt dump badging`):**
   - Application ID: `com.vision.app`
    - Version Code: `13`
    - Version Name: `0.7.0`
   - Compile SDK: `34`, Target SDK: `34`, Min SDK: `26`
5. **APK Artifact:** Copied to `/storage/emulated/0/Download/Vision-debug.apk`  
    **SHA-256:** `228e5daed197eacb46368b8d1b2919cf5d67af36b3333177c1b0ca55ac77ac5a`

---

## 5. Live Device Verification Checklist (Pending Physical Execution for v0.7.0)

*Note: No live physical device tests have been executed yet. The following hardware-dependent verification checklist remains scheduled for execution on the physical iQOO Z9x device:*
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
5. **Dialog Dismissal & Lifecycle Cancellation:**
   - Trigger Jarvis dialog, press Android Back button or tap outside -> Verify dialog dismisses gracefully, action is marked `DENIED`, UI displays `CANCELLED`, and no reply intent is sent.
   - Trigger Jarvis dialog, rotate device or trigger configuration change -> Verify dialog dismisses gracefully without crashing or invoking callbacks on destroyed Activity.
6. **Tier SAFE Auto-Execution:**
    - Execute `read notification` and `open whatsapp` -> Verify immediate execution without prompt.
7. **New Message Composer Handoff:**
    - Test explicit international SMS number, email address, WhatsApp number, WhatsApp Business number, and Telegram username.
    - Verify confirmation shows exact destination and multiline body.
    - Verify `Deny`, Back, outside tap, and rotation do not open a composer.
    - Verify approval opens the intended composer and reports `COMPOSER OPENED`, never `SENT`.
    - Verify SMS/email package visibility works on Android 11+ and unavailable target apps fail cleanly.
