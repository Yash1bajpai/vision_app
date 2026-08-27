# Vision Project Report — Phase 8: Safe Contact Name Resolution & Confirmed Composer Handoff

**Date:** 2026-08-27  
**Target Device:** iQOO Z9x I2219 (Android 16 / API 36, arm64-v8a)  
**Current Version:** 0.8.0 (versionCode: 14, compileSdk: 34, targetSdk: 34, minSdk: 26)  
**Prior Commits:** `fbe44d0` (Phase 7 new message composer handoff), `2943125` (Phase 6.2 fix & regression tests), `72a6d52` (v0.6.1 Docs), `b4f34bd` (Phase 6.1 atomic reply dispatch & production boundaries)  
**APK Output:** `/storage/emulated/0/Download/Vision-debug.apk`  
**APK SHA-256:** `2b74e451398888f1dae3f4d51c0dacb466d04ea6084cab6ff2135b3aedf7b434`  

---

## 1. Executive Summary

### Phase 8 Deliverables
1. **Selective Runtime Permission Handling:** Added `READ_CONTACTS` permission to `AndroidManifest.xml` and runtime permission requesting in `MainActivity` only when a command specifies a contact name destination. Explicit numbers, emails, telegram handles, app launches, and notification reads never check or request Contacts permission.
2. **Deterministic Fail-Closed Contact Resolution (`VisionContactResolver`):**
   - Exact full-name matches take precedence over partial/token matches.
   - Unique safe token matching matches whole whitespace/punctuation-delimited name tokens without arbitrary substring searching.
   - Zero matches (`NO_MATCH`) and multiple/ambiguous matches (`MULTIPLE_MATCHES`) fail closed without opening an external composer.
   - Malformed numbers without international country code prefix (`+`) or invalid length (<7 or >15 digits) fail closed (`MALFORMED_NUMBER`).
3. **Zero Disk Persistence & Minimal Projection:** In-memory resolution queries only `DISPLAY_NAME` and `NUMBER` columns from `ContactsContract.CommonDataKinds.Phone`. Cursors are immediately closed, and zero contact data is persisted to disk, databases, or preferences.
4. **Masked Number Confirmation:** The confirmation modal displays the resolved contact name alongside a masked phone number (e.g. `Rahul Sharma (+91 •••• 3210)`).
5. **Exact Action Binding & External Composer Handoff:** Approval binds the resolved contact name, normalized number, message payload, and channel. Opens only supported external app composers (`smsto:`, `https://wa.me/`) and transitions to `COMPOSER_OPENED`. Never silently sends, never uses Accessibility/root/ADB/Device Owner, and never claims `SENT`.
6. **Cancellation & Lifecycle Safety:** Back button, outside tap, device rotation, and Activity destruction transition proposed actions to `DENIED` with zero intent dispatch.
7. **Preserved Regressions:** Explicit international phone numbers (`+91...`), email addresses (`alice@example.com`), and Telegram usernames (`@alice123`) remain fully supported and unchanged.
8. **Deterministic JUnit 4 Test Suite:** Expanded test suite to 33 test groups covering exact match, unique safe token match, no match, duplicate/ambiguous names, malformed contact numbers, phone masking, permission-denied behavior, multiline body preservation, and explicit number regressions (33/33 tests passing offline).

---

Vision is an offline-first Android integration layer built on deterministic execution, memory-only state safety, explicit modal confirmation for external mutations, and zero disk persistence.

> **Assistant Intelligence Runtime Status:**  
> Local language models, LLM runtimes, on-device intelligence engines, network AI, embeddings, and free-form action generators remain explicitly **excluded and deferred**. This release is bounded to deterministic Android composer handoffs and confirmation safety.

---

## 2. Inverted Fail-Closed Risk Policy Specification

Defined in `VisionRiskPolicy` and enforced via `VisionAction.requiresConfirmation()`:

| Risk Tier | Policy | Action Types | Safety Behavior |
|---|---|---|---|
| **Tier SAFE** | Auto-execute immediately (NO modal dialog) | `OPEN_APP`, `READ_NOTIFICATION` *(Explicitly enumerated in `SAFE_TYPES`)* | User's typed command authorizes the read-only or local app launch action directly. Executes immediately upon validation and outputs concise `SUCCEEDED` / `FAILED` status to the Recent Activity surface. Never prompts for permission. |
| **Tier CONFIRMED** | Conversational Allow/Deny dialog REQUIRED | `REPLY_NOTIFICATION`, `SEND_MESSAGE_DIRECT`<br>*Default fallback:* `UNKNOWN`, `null`, unlisted types<br>*(Documented future: `PAYMENT`, `DELETE`, `DOWNLOAD_FILE`, `INSTALL`, `CHANGE_SETTING`)* | External communication actions require explicit approval. Notification replies bind capability identity; direct messages bind exact channel, destination (with masked number for resolved contacts), and payload, then open only a supported external composer. |

---

## 3. Deterministic JUnit 4 Test Suite Evidence

The deterministic test suite (`com.vision.app.VisionAppTest`) executes 33 test groups offline via Gradle `:app:testDebugUnitTest`:

### Execution Summary from Gradle XML (`TEST-com.vision.app.VisionAppTest.xml`)
- **Total Test Groups Executed:** 33
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
29. `test29_contactResolutionExactAndUniqueMatching`: Validates exact full-name matching, case-insensitive exact matching, unique first-name and surname token matches, multi-token subset matches, and duplicate sync entry deduplication.
30. `test30_contactResolutionAmbiguityAndNoMatchFailClosed`: Tests fail-closed behavior on 0 matches (`NO_MATCH`), ambiguous first names and surnames (`MULTIPLE_MATCHES`), multiple numbers for same contact, substring/non-token rejection (`li` vs `Alice`), and null/empty queries.
31. `test31_contactResolutionMalformedNumbersAndMasking`: Tests fail-closed rejection of unsigned phone numbers (no `+`), too short (<7 digits), non-digit strings, empty numbers, too long (>15 digits), and validates phone masking (`+91 •••• 3210`).
32. `test32_contactResolutionPermissionAndRiskPolicy`: Tests permission-denied resolution result, action destination binding (`resolvedContactName`, `resolvedNumber`), and confirmed risk tier invariants.
33. `test33_phase8DirectMessageContactCommandsAndRegressions`: End-to-end parsing coverage for WhatsApp, WhatsApp Business, and SMS contact commands, multiline contact payloads, explicit number regressions (+91 phone, email, Telegram), and rejection of invalid formats.

---

## 4. Build, Packaging & Verification

1. **Compilation:** Built completely offline with Gradle 8.7 (`:app:testDebugUnitTest :app:assembleDebug --offline`).
2. **ZIP Integrity:** Valid DEX archives and resource tables generated cleanly.
3. **Signature Verification:** `apksigner verify --verbose` -> Verified using APK Signature Scheme v2 (1 signer).
4. **Package Metadata (`aapt2 dump badging`):**
   - Application ID: `com.vision.app`
   - Version Code: `14`
   - Version Name: `0.8.0`
   - Compile SDK: `34`, Target SDK: `34`, Min SDK: `26`
   - Uses Permission: `android.permission.READ_CONTACTS`
5. **APK Artifact:** Copied to `/storage/emulated/0/Download/Vision-debug.apk`  
   **SHA-256:** `2b74e451398888f1dae3f4d51c0dacb466d04ea6084cab6ff2135b3aedf7b434`

---

## 5. Live Device Verification Checklist (Pending Physical Execution for v0.8.0)

*Note: No live physical device tests have been executed yet. The following hardware-dependent verification checklist remains scheduled for execution on the physical iQOO Z9x device:*
1. **Notification Status Lifecycle:**
   - On clean start without notifications, tap `Read latest notification` -> Verify displays `NO SUPPORTED NOTIFICATION`.
   - Post incoming notification -> Tap `Read latest notification` -> Verify displays notification sender, title, and body.
   - Dismiss notification in Android notification shade -> Tap `Read latest notification` -> Verify displays `NOTIFICATION REMOVED`.
2. **Semantic Reply Action Priority:**
   - On WhatsApp/Telegram notification with multiple actions (e.g. "Mark as read" and "Reply"), trigger `reply to <contact>: <text>`.
   - Verify `SEMANTIC_ACTION_REPLY` is selected and dispatched correctly upon user approval.
3. **Contact Name Resolution & Runtime Permission:**
   - On clean install without Contacts permission, enter `send WhatsApp message to Rahul: I will be late`.
   - Verify runtime permission dialog appears requesting Contacts permission.
   - If denied: verify UI shows `FAILED: Contacts permission was denied` and no composer opens.
   - If granted: verify contact `Rahul` resolves to `Rahul Sharma (+91 •••• 3210)`.
4. **Contact Disambiguation & Fail-Closed Safety:**
   - With multiple contacts named "Rahul" in contacts provider, enter `send WhatsApp message to Rahul: I will be late`.
   - Verify UI displays `AMBIGUOUS CONTACT: Multiple contacts match "Rahul"` and no composer opens.
   - With contact having no `+` country code (e.g. `9876543210`), verify UI displays `INVALID CONTACT NUMBER` and no composer opens.
5. **Masked Confirmation & Composer Handoff:**
   - Verify confirmation modal displays `"Tony, may I prepare this message?"` with resolved contact name, masked phone number, and full message body.
   - Tap `Deny`, Back button, or outside tap -> Verify action is marked `DENIED` and no composer opens.
   - Tap `Open composer` -> Verify WhatsApp opens with pre-filled message for `+919876543210` and Vision UI displays `COMPOSER OPENED`.
6. **Explicit Destination Regressions:**
   - Test `send SMS to +919876543210: Hello`, `send email to alice@example.com: Hello`, and `send Telegram message to @alice123: Hello`.
   - Verify explicit destinations bypass contact resolution and runtime permission requests completely.
