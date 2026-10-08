## Plan lifetime and short-lived context

Adds bounded plan lifetime and volatile request context.

- Plans have a 60-second total budget measured with Android's monotonic clock.
  Each step, confirmation and permission result checks the deadline before doing work.
- `cancel plan`, Back, backgrounding and activity destruction stop the remaining steps.
  Pending confirmations and permission callbacks are cleared. Stale callbacks cannot
  advance a replacement plan. Cancellation cannot undo actions already dispatched.
- Session context holds at most two prior typed requests, up to 512 characters each,
  for 60 seconds after the last request. Oversized requests are omitted, not truncated.
  `forget context`, plan cancellation/timeout, backgrounding and destruction clear it.
  No notification text, contact records, tool results, approvals or reply capabilities
  are added to context; typed requests themselves can contain private information.
- Context is an untrusted hint to an optional provider, never permission or remembered
  recipients. Parser-first routing, strict proposal validation and per-step confirmation
  remain unchanged. The production provider is still `NoOpReasoningProvider`, so no
  model, automatic follow-up resolution, network or disk storage is introduced.
- Going to another app stops any plan still pending when Vision reaches `onStop`.
  Runtime permission dialogs normally pause without stopping the Activity; if an OEM
  stops it, the plan is cancelled and the user must make the request again.

Verification re-run (2026-10-05, Linux / JDK 17 / Gradle 8.7 / Android SDK 34):
111 JVM tests pass (91 baseline + 20 new), including Robolectric Android lifecycle,
permission and dialog tests. Debug and androidTest APKs build; debug APK v2 signature
verifies. Lint: 0 errors, 58 warnings (53 baseline; new warnings are UI text/i18n).
The cancellation screen was rendered and visually inspected with Robolectric native
Android graphics, not an emulator or phone. No new on-device instrumentation run was
performed. The Android checks workflow runs JVM tests, APK builds and lint on Linux
and Windows; it does not run an emulator or prove phone behavior. Real permission-dialog/OEM lifecycle behavior, background transitions and
third-party incoming notification replies still need a phone test. The plan path is
injected only in tests until a real provider is attached.

## Recently merged: offline capabilities, test harness, notification safety

Three feature branches merged 2026-10-01. No model, new permission or persistent storage.

**Offline capability registry.** `help`, `commands`, `show commands`, `show capabilities` and
`what can you do?` render `VisionToolRegistry` - a read-only list of supported actions,
examples, input rules and confirmation requirements. `VisionToolRegistry.toJson()` exports
versioned static metadata for a future model adapter; it does not connect a model, execute
tools or approve actions, and the four-field proposal validator remains mandatory. Play and
pause now send separate Android media keys instead of a play/pause toggle; dispatch is not
proof that a media app changed playback. Calendar event parsing handles uppercase
TODAY/TOMORROW and AM/PM and rejects invalid twelve-hour times. MessagingStyle decoding is
gated to API 30+; Android 26-29 keeps the big-text, text-line and standard-text fallbacks.

**Android test harness.** `app/src/androidTest` launches the real `MainActivity` and drives
the composer: help renders `VisionToolRegistry.helpText()` exactly (including aliases), and
dialog cancellation is covered for Deny, dismissal and activity recreation. The instrumented
dialog tests wait for the main looper before asserting, because button clicks and dismissal
callbacks are posted messages. JVM tests cover the help-text contract and
`VisionCompletionGate`, a one-shot latch now used by the plan-step completion path.
Run with `gradle connectedDebugAndroidTest`; the dialog tests need an SMS-capable composer app.

**Notification safety.** Listener disconnect/destruction drops cached notification text and
reply capabilities; posted reply capabilities must match the notification key and source
package; a successful dispatch consumes the bound capability so one confirmation cannot send
twice; and the UI reports `REPLY REQUESTED` rather than claiming delivery. Covered by 7
lifecycle/identity regressions and 5 Robolectric Android-framework tests that use synthetic
app-scoped broadcasts only.

Verification (2026-10-01, iQOO Z9x / Android 16, JDK 17, Gradle 8.7, Android SDK 34):
**91/91 JVM tests**, **5/5 on-device instrumentation tests**, debug and androidTest APKs
build, lint **0 errors / 53 warnings**. Device smoke: `help` rendered the registry text,
`check battery` reported the live battery state, `pause music` dispatched the explicit
`KEYCODE_MEDIA_PAUSE`, and with the listener enabled via adb the app reported
"Notifications connected" and ignored an unsupported-package notification. Robolectric
simulates Android APIs, not a phone, so a live reply to a real third-party messaging
notification still requires an actual incoming message.

Standard SDK build (JDK 17, Gradle 8.7, Android SDK 34):

```sh
gradle -Pandroid.aapt2FromMavenOverride="$ANDROID_HOME/build-tools/34.0.0/aapt2" \
  testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
```

The historical release notes below describe earlier releases.

# Vision

Vision is an offline-first Android assistant for the iQOO Z9x. The application is designed around deterministic execution, explicit user authorization, zero disk persistence, and risk-tiered execution safety.

> **Note on Assistant Intelligence Runtime:**
> **Current release: `v0.12.0`** — Phase 11 added device-status reads (battery, network, time, calendar) and Phase 12 added daily-driver intents (timers, alarms, navigation, media, volume, torch, calendar events); the full phase-by-phase record is in `PROJECT_REPORT.md`.
> Local models, on-device LLM runtimes, network AI, embeddings, and unconstrained action generators are explicitly **excluded and deferred** from this release. Phase 10 adds only trusted-boundary plumbing for a future model — bounded multi-step plan proposals on top of the Phase 9 single-proposal boundary (grammar + validation + sequential per-action execution policy only; still no model, no network, no embeddings). Phase 9 added the `ReasoningProvider` interface plus strict fail-closed proposal validation. Phase 8 adds safe, deterministic in-memory contact name resolution and permission lifecycle recovery for confirmed external-composer handoffs while maintaining strict zero-disk persistence and confirmation safety.

---

## Trusted Plan Boundary: Bounded Multi-Step Plans (Phase 10, still the live boundary in v0.12.0)

Phase 10 extends the trusted proposal boundary from a single action to a bounded plan, following the same fail-closed philosophy. Production behavior is **identical to v0.9.3** (the `NoOpReasoningProvider` never proposes anything); the plan path exists only for a future model and is fully covered by tests.

### 1. Plan Grammar (`StrictJson.parseArray`)
Providers may return a single flat JSON object (Phase 9 single action) or a single JSON array of flat string-only objects (Phase 10 plan). The array grammar inherits every strictness rule: string-only flat elements, at most 16 keys per element, at most 8 elements, 8192-character cap, duplicate keys, trailing garbage, nesting, and non-string values all rejected. Fail-closed: any violation returns `null`.

### 2. Plan Validation (`ReasoningPlanValidator`)
Every step must independently survive the exact Phase 9 `ReasoningProposalValidator` rules — exact four-key schema, supported types only, per-type target/text/channel rules. There are **no plan-level keys and no plan-level semantics**: a step smuggling `approved`, `risk`, or `skip_confirmation` rejects the whole plan (the invalid step index is reported). Plans are bounded at `VisionPlan.MAX_ACTIONS = 3`; empty plans and oversized plans are rejected.

### 3. Sequential Execution (`VisionPlanExecutor` policy, MainActivity)
Plans execute strictly one step at a time through the **same handlers, same risk policy, and same modal confirmations as typed commands**:
- A CONFIRMED step still shows its own Allow/Deny dialog; **approval of one step never approves a later step**.
- A step ending `DENIED` or `FAILED` halts the plan; remaining steps are never offered.
- Only `SUCCEEDED` or `COMPOSER_OPENED` advances to the next step.
- A plan in progress blocks new commands ("PLAN IN PROGRESS").
- Plans are in-memory only and die with the Activity — no step ever resumes after lifecycle loss (fail-closed).
- The activity surface reports `PLAN STARTED`, `PLAN COMPLETED`, or `PLAN STOPPED` with the step count; individual step outcomes keep their existing per-action messages.

### 4. Coordinator Routing (`ReasoningCoordinator.coordinateFull`)
Parser-first, unchanged: the deterministic parser is always consulted first; the provider is consulted only for `UNKNOWN` requests; array vs. object output selects the plan path vs. the single-action path; any parse or validation failure yields `UNKNOWN` with the original request preserved. `coordinate()` remains as a delegate with byte-identical behavior for single actions.

### 5. Default Behavior Unchanged
`MainActivity` still wires `NoOpReasoningProvider`; with no provider output there are no plans, and the v0.9.3 command corpus behaves identically through `coordinateFull` (regression-tested, `test56`).

### 6. Zero New Attack Surface
No new permissions, no network, no disk persistence, no new dependencies. Provider output is never logged. The evaluation suite covers: array grammar acceptance/malformed rejection (`test50`), plan validation and bounds (`test51`), plan immutability (`test52`), coordinator routing and parser-first guarantees (`test53`), step-advance/halt policy and per-step risk tiers (`test54`), prompt-injection payload semantics and plan-level smuggling rejection (`test55`), and the v0.9.x regression corpus (`test56`) — **58/58 test groups** once Phase 11 device-status reads (`test57`) and Phase 12 daily-driver intents (`test58`) landed.

---

## Phase 9 Architecture: Reasoning Adapter (v0.9.0)

### 1. Model Proposes, Deterministic Layer Disposes
A future reasoning model is only ever consulted through the `ReasoningProvider` interface, and **only** when the deterministic parser returns `UNKNOWN`:
- **Deterministic Fast Path:** `ReasoningCoordinator.coordinate()` always runs `VisionActionParser.parse()` first. Parser-understood commands are returned immediately and the provider is never consulted (verified in tests via `MockReasoningProvider.callCount == 0`).
- **UNKNOWN-Only Consultation:** The provider is consulted exclusively for requests the deterministic layer cannot understand. A `null` provider, a `null` proposal, or a blank proposal all keep the request `UNKNOWN` with the original request text preserved.

### 2. Strict Proposal Schema
Provider output must be a single flat JSON object with exactly the four string keys `type`, `target`, `text`, and `channel`. Raw output is parsed by a hand-rolled `StrictJson` parser with zero dependencies:
- **String-Only Flat Grammar:** Exactly one root object; keys and values must both be strings. Numbers, booleans, `null` literals, nested objects, and arrays are rejected.
- **Bounded Size:** At most 16 unique keys and 8192 total characters.
- **Grammar Rejections:** Duplicate keys, trailing garbage after the root object, unknown escape sequences, and raw control characters inside strings are all rejected.
- **Never Throws:** Any violation returns `null` instead of throwing; callers treat `null` as invalid input.

### 3. Fail-Closed Validation (`ReasoningProposalValidator`)
The validator enforces the exact four-key schema — missing keys (`MISSING_KEYS`) and any extra key (`EXTRA_KEYS`) are rejected before an action can exist:
- **Exact Type Guard:** `type` must be exactly one of the four supported action type strings (`READ_NOTIFICATION`, `REPLY_NOTIFICATION`, `SEND_MESSAGE_DIRECT`, `OPEN_APP`). Hallucinated or future types (`PAYMENT`, `INSTALL`, `DELETE`, `CHANGE_SETTING`, …) are rejected as `INVALID_TYPE`.
- **Per-Type Target Rules:** `OPEN_APP` accepts canonical app names only (WhatsApp, WhatsApp Business, Telegram, Gmail, Messages, Calendar — normalized to canonical casing; package names and unsupported apps rejected); `SEND_MESSAGE_DIRECT` destinations must pass the existing parser validators per channel (`VisionActionParser.isValidDirectDestination`); `READ_NOTIFICATION` accepts only an empty or `latest notification` target; reply targets must be valid contact names, app names, or `latest notification` (max 70 characters).
- **Per-Type Text & Channel Rules:** Text is required for messaging actions (`REPLY_NOTIFICATION`, `SEND_MESSAGE_DIRECT`, max 2000 characters) and forbidden otherwise; `channel` must be exactly one of the five supported channels (`sms`, `whatsapp`, `whatsapp_business`, `email`, `telegram`) for direct sends and empty for every other type.
- **Fail-Closed Outcome:** Any rejection yields `UNKNOWN` with the original request preserved — never an exception, never a guessed action.

### 4. No Risk-Tier Influence
The proposal schema has no field that can influence risk:
- Proposals smuggling keys such as `risk`, `requires_confirmation`, or `approved` are rejected as extra keys before any action is created.
- Every accepted proposal flows through the **same** `VisionRiskPolicy` tiers and modal confirmations as typed commands. The coordinator itself never consults `VisionRiskPolicy`; risk policy is applied downstream, unchanged.

### 5. Prompt-Injection Defense
- Proposal `text` is message **content** bound to the confirmation dialog: it is displayed verbatim in the modal body and dispatched as the message payload, never interpreted as an instruction (injection-style text keeps its type, target, and confirmation requirements).
- Untrusted notification or message content is never given to providers in this phase — providers receive only the user's typed request.

### 6. Default Behavior Unchanged
- Production wiring (`MainActivity`) uses `NoOpReasoningProvider`, which always returns `null`, so v0.9.0 behavior is identical to v0.8.0. This is regression-tested with a command corpus routed through the coordinator.
- `MockReasoningProvider` (deterministic, no network, call counting) exists for tests and future instrumentation.

### 7. Zero New Attack Surface
- No new permissions, no network, no disk persistence, and no new dependencies.
- Provider output is never logged.

---

## Phase 8 Architecture: Safe Contact Name Resolution, Lifecycle State Recovery & Confirmed Composer Handoff (v0.8.0)

### 1. Zero-Disk-Persistence Transient Contact Resolution
Contact resolution is performed entirely in volatile process memory during action evaluation:
- **Zero Disk Persistence:** No contact databases, cache files, shared preferences, or serialized contact snapshots are ever written to disk.
- **Minimal Query Projection:** Android `ContactsContract.CommonDataKinds.Phone` is queried strictly for `DISPLAY_NAME` and `NUMBER` columns; no avatars, emails, notes, postal addresses, or metadata are requested or exposed.
- **Immediate Cursor Release:** Content resolver cursors are read into transient objects and closed immediately within `try-with-resources` blocks.

### 2. Deterministic, Fail-Closed Contact Matching Policy
`VisionContactResolver` applies an exact-first, fail-closed matching algorithm:
1. **Exact Full-Name Match:** Exact match (`contact.displayName.equalsIgnoreCase(query)`) takes precedence. If multiple entries exist for the same name with identical phone numbers (e.g. SIM + Google sync), duplicates are safely deduplicated.
2. **Unique Safe Token Match:** If no exact match exists, queries match against whole whitespace/punctuation-delimited name tokens (e.g. `Rahul` matches `Rahul Sharma`). Substring matches without token boundaries (such as `li` against `Alice` or `ver` against `Verma`) are strictly rejected.
3. **Fail-Closed Ambiguity Guard:** If zero contacts match (`NO_MATCH`) or multiple distinct contacts/phone numbers match (`MULTIPLE_MATCHES`), the action strictly fails closed without opening any composer.
4. **Malformed Number Validation:** Contact phone numbers must normalize to valid international numbers with country codes (`+` followed by 7–15 digits). Unsigned or malformed contact numbers fail closed (`MALFORMED_NUMBER`).

### 3. Masked Confirmation & Bound Action Security
- **Phone Number Masking:** The confirmation dialog and activity surface display the resolved contact name alongside a masked phone number (e.g., `Rahul Sharma (+91 •••• 3210)`), concealing middle digits while confirming identity. Numbers with at least 11 digits reveal 2 leading and 4 trailing digits; shorter numbers reveal 1 leading and 2 trailing digits.
- **Bound Action Integrity:** User approval binds the exact resolved name, normalized international number, message body, and target channel.
- **Composer Handoff Only:** Approval opens only the explicit external application composer (`smsto:`, `mailto:`, `https://wa.me/`, `https://t.me/`) with package visibility guards (`com.whatsapp`, `com.whatsapp.w4b`, `org.telegram.messenger`). Vision reports `COMPOSER OPENED`, **never** `SENT`.
- **Cancellation Safety:** Modal Allow/Deny dialog handles Back button, outside tap, and Activity destruction by transitioning the action to `DENIED` with zero intent dispatch (note: `MainActivity` is portrait-locked).

### 4. Selective Runtime Permission Handling & Lifecycle State Recovery
- `READ_CONTACTS` is declared in `AndroidManifest.xml` and requested at runtime **only** when a command specifies a contact-name destination (e.g. `send WhatsApp message to Rahul: ...`).
- Commands with explicit phone numbers, email addresses, Telegram usernames, app launches, or notification reads **never** check or request Contacts permission.
- **Permission Flow Lifecycle Recovery:** When an Activity recreation or configuration change (e.g. system memory reclamation, 'Don't keep activities', system dark mode / font scale changes; note `MainActivity` is portrait-locked) occurs while the system permission dialog is displayed, the pending action metadata is safely preserved across instances using Android's transient `onSaveInstanceState` / `onCreate(savedInstanceState)` mechanism without writing message content or contacts to disk.
- **Fail-Closed Guarantee:** If state recovery is corrupted or exact recovery is impossible upon permission grant, Vision fails closed cleanly without crashing or opening an external composer. Permission denial callbacks immediately transition the pending action to `FAILED` with an informative status message.

---

## Phase 7 Architecture: Confirmed Direct Message Composer Handoff (v0.7.0)

### 1. Direct Message Intent Factory
- `DirectMessageIntentFactory` deterministically constructs external composer intents for WhatsApp (`https://wa.me/`), WhatsApp Business, SMS (`smsto:`), Email (`mailto:`), and Telegram (`https://t.me/`).
- Package visibility and component resolution are verified before prompting the user.

### 2. Strict Explicit Destination Validation
- Explicit international phone numbers require leading `+` and 7–15 digits.
- Email destinations require standard RFC-compliant format.
- Telegram destinations require `@username` format.

---

## Phase 6 Architecture: Deterministic Reliability & Production Boundaries (v0.6.0 – v0.6.2)

### 1. In-Memory Transient State Model
Notification state transitions are modeled deterministically in `VisionNotificationListener.ListenerState` with zero disk or database persistence:
- **`NotificationStatus.NO_NOTIFICATION_YET`**: Initial boot or explicitly cleared state.
- **`NotificationStatus.ACTIVE_NOTIFICATION`**: A supported messaging notification is active and cached in process memory.
- **`NotificationStatus.NOTIFICATION_REMOVED`**: Active notification was dismissed by the user or cancelled by Android.

State metadata is restricted to safe operational identifiers: `key`, `packageName`, `postTime`, `hasReplyCapability`, and a monotonic `sequenceNumber`. **Never** stored or exposed in state logs: notification body, sender name, message text, PendingIntent, or RemoteInput.

### 2. Deterministic Notification Ordering, Tie-Breaking & Processing Boundaries
`VisionNotificationListener` routes Android notification callbacks through deterministic production processing methods:
- **`processPostedNotification(...)`**:
  - Rejects unsupported packages immediately without modifying state.
  - Filters ongoing/summary noise without reply action while preserving existing active state.
  - Enforces `postTime` ordering (`newPostTime > currentPostTime` accepted; older rejected).
  - Handles equal timestamps via same-key update or lexicographical tie-break (`newKey.compareTo(currentKey) >= 0`).
  - Atomically increments monotonic sequence counters and updates `ListenerState`.
- **`processRemovedNotification(removeKey)`**:
  - Matches strictly on the active notification's key, preserving active state on non-matching keys.
  - Clears active snapshot/capability and transitions to `NotificationStatus.NOTIFICATION_REMOVED`.

### 3. F2 Atomic Validation-to-Dispatch in `sendBoundReply`
- Validates bound capability identity and dispatches `PendingIntent.send()` inside the single synchronized monitor (`synchronized(VisionNotificationListener.class)`).
- External IPC dispatch is intentionally retained inside the lock to guarantee atomic validation-to-dispatch, preventing stale-dispatch windows if `onNotificationRemoved` concurrently dismisses or replaces capabilities.

### 4. Reply Action Selection & RemoteInput Eligibility
When notifications expose multiple action buttons or RemoteInput fields:
- **Semantic Action Priority**: Prefers `Notification.Action.SEMANTIC_ACTION_REPLY` on supported platforms (API 28+ / Android 9 Pie through API 34+).
- **Deterministic Fallback**: Selects the first text-capable RemoteInput action if no semantic reply action is designated.
- **Data-Only Exclusion**: Excludes non-text RemoteInputs (where `allowFreeFormInput` is `false` and `choices` are empty).

### 5. Multiline Reply Integrity
Multiline composer text is preserved byte-for-byte:
- Outer leading/trailing whitespace is trimmed on submission.
- Internal line breaks (`\n`, `\r\n`), indents, and paragraph breaks are preserved identically across command parsing, the conversational Jarvis confirmation dialog, and the dispatched `RemoteInput` intent bundle.

### 6. Bounded Action Observability
`MainActivity` maintains bounded, single-slot observability on the `Recent Activity` surface:
- Shows only the immediate result (`SUCCEEDED`, `FAILED`, `DENIED`) and user-visible metadata of the most recent action.
- Distinguishes exact failure reasons without revealing hidden or private notification content:
  - *Notification access disabled*
  - *No supported notification yet*
  - *Notification removed / dismissed*
  - *Latest notification not replyable*
  - *Target mismatch*
  - *Action cancelled or expired by Android*
- Zero long-term action logs or notification history are stored on disk.

---

## Risk-Tiered Confirmation Policy (Inverted Fail-Closed)

Under the inverted fail-closed model (N33), only explicitly designated `SAFE_TYPES` auto-execute without modal confirmation. All external mutations strictly require explicit modal user confirmation (`RiskTier.CONFIRMED`).

| Risk Tier | Policy | Action Types | Execution Flow |
|---|---|---|---|
| **Tier SAFE** | Auto-execute immediately (NO modal dialog) | `OPEN_APP`, `READ_NOTIFICATION` | Typed command is direct intent. Executes immediately upon validation and outputs concise `SUCCEEDED` / `FAILED` status to the Recent Activity surface. |
| **Tier CONFIRMED** | Modal Allow/Deny dialog REQUIRED | `REPLY_NOTIFICATION`, `SEND_MESSAGE_DIRECT`<br>*Documented future members:* `PAYMENT`, `DELETE`, `DOWNLOAD_FILE`, `INSTALL`, `CHANGE_SETTING`<br>*Default fallback:* `UNKNOWN` / unlisted types | Dialog shows the exact destination (with masked number for resolved contacts) and payload. Notification replies re-verify bound capability identity; direct messages open only an approved external composer and never claim delivery. Never auto-executed. |

---

## Jarvis-Style Conversational Permission Requests

### For `REPLY_NOTIFICATION` (Tier CONFIRMED)
- **Dialog Title:** `"Tony, may I send this message?"`
- **Dialog Body:** `"I am ready to send this message to [Resolved Destination]:\n\n\"[Exact Outgoing Text]\"\n\nMay I proceed?"`
- **Controls:** `Allow` and `Deny` buttons.
- **Safety:** Handles Back button / outside-tap dismissal gracefully without executing replies, preserves `activeDialog` tracking, and guards against TOCTOU races, stale capabilities, and destroyed Activity lifecycles.

### For `SEND_MESSAGE_DIRECT` (Tier CONFIRMED)
- **Dialog Title:** `"Tony, may I prepare this message?"`
- **Dialog Body:** `"I am ready to open the [CHANNEL] composer for [Resolved Contact Name (Masked Number)]:\n\n\"[Exact Outgoing Text]\"\n\nThe message will not be reported as sent until you send it in that app.\n\nMay I proceed?"`
- **Controls:** `Open composer` and `Deny` buttons.
- **Safety:** Handles Back button / outside-tap dismissal gracefully without opening composers, preserves `activeDialog` tracking, and guards against lifecycle destruction.

---

## Supported Commands

### Reading & Apps (Tier SAFE — Auto-Executed)
- **Notification Reading:**
  - `read notification` / `read notifications`
  - `read my latest notification` / `see latest notification`
  - `show my latest message` / `check messages`
  - `start reading my messages`
- **App Launching:**
  - `open whatsapp` / `open whatsapp now` / `open wa`
  - `open whatsapp business` / `launch whatsapp business` / `start w4b`
  - `launch telegram` / `please open telegram` / `open tg`
  - `start gmail`
  - `open messages` / `open sms` / `launch messages`
  - `open calendar` / `launch calendar`

### Notification Replies (Tier CONFIRMED — Modal Allow/Deny Required)
- `reply <message>` (e.g. `reply I'll be there soon`)
- `reply:\n<multiline message>` (e.g. `reply:\nLine 1\nLine 2`)
- `reply: <message>` (e.g. `reply: Sounds great!`)
- `reply to: <message>` (e.g. `reply to: Sounds great!`)
- `reply to <target>: <message>` (e.g. `reply to WhatsApp: On my way!`, `reply to Alice: Yes, confirmed`, `reply to tokyo: hi`, `reply to WhatsApp Business: Order ready`)
- `reply to <target>:\n<multiline message>`
- `send reply <message>` (e.g. `send reply Confirmed`)
- `answer <message>` (e.g. `answer Thank you`)

### Direct Message Composer Handoff with Contact Names (Tier CONFIRMED — Phase 8)
- `send WhatsApp message to Rahul: I will be late`
- `send WhatsApp message to Rahul Sharma: I will be late`
- `send WhatsApp Business message to Rahul: I will be late`
- `send message to Rahul: I will be late` (defaults to SMS)
- `send SMS to Rahul: I will be late`
- `send WhatsApp message to Rahul:\nMeeting at 5 PM\nRoom 2B` (multiline body preserved)

### Direct Message Composer Handoff with Explicit Destinations (Tier CONFIRMED — Phase 7)
- `send message to +919876543210: Hello`
- `send a message to +919876543210: Hello` (optional a/an/the and "new" phrasing)
- `send SMS to +919876543210: Hello`
- `send email to alice@example.com: Meeting confirmed`
- `send WhatsApp message to +919876543210: On my way`
- `send WhatsApp Business message to +919876543210: On my way`
- `send Telegram message to @alice123: Hello`

After approval, Vision reports `COMPOSER OPENED`, not `SENT`.

---

## Build & Test

```bash
# Run deterministic JUnit 4 unit tests and build debug APK offline
/data/data/com.termux/files/home/gradle/gradle-8.7/bin/gradle --no-daemon --offline :app:testDebugUnitTest :app:assembleDebug
```

The phone-local Termux toolchain uses Gradle 8.7, Android API 34, and an ARM64-native `aapt2` override.

**PC (Windows) builds:** the tracked `gradle.properties` keeps the Termux `aapt2` path; a PC build supplies its own override on the command line so no repo file changes between environments:
```bash
gradle :app:testDebugUnitTest :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=C:/Yash/android-sdk/build-tools/34.0.0/aapt2.exe
```
Requires JDK 17, Gradle 8.7, Android SDK platform 34 + build-tools 34.0.0, and a git-ignored `local.properties` pointing `sdk.dir` at the PC SDK. Verified 2026-09-07: 49/49 test groups green on the PC JVM and an APK (v0.9.2, v2-signed) built, installed, and verified on the iQOO Z9x via ADB (see PROJECT_REPORT Section 5).

## Resource Guardrails

The project is configured for a low-memory development device: one Gradle worker, no parallel execution, no daemon, and a 512 MB Gradle heap. Vision does not load or benchmark any language model during the deterministic integration phase.

## Numeric dialer handoff

`call +15555550123` or `dial +15555550123` asks for confirmation with the exact
international number, then uses ACTION_DIAL. Vision does not place the call.
The final Call tap stays in the phone app. No CALL_PHONE permission or contact
lookup. Short/local numbers, extensions, USSD and trailing instructions fail closed.
Device/OEM dialer behavior needs phone verification.

## Labeled clock reminder handoff

`remind me Drink Water at 7 pm` asks for confirmation and opens a labeled clock
alarm UI. This is not an internal dated-reminder scheduler. Dates, relative days,
recurrence and relative durations are rejected. Check the next occurrence and
saved alarm in Clock. Vision reports CLOCK OPENED, never that a reminder was saved.
No new permission or storage. Device/OEM clock behavior needs phone verification.

## Local preparation: offline push-to-talk

Hold to talk starts only Android 12+ on-device speech recognition, when the
installed device speech service reports local recognition available. Release asks
it to finish. No network recognizer or external voice activity fallback is used.
This adds RECORD_AUDIO permission and asks for it only on the first voice attempt;
after permission approval the user must hold again. Older Android or missing local
speech support keeps typed input available.

Voice fills a draft only. It never taps Send, runs a tool or approves a confirmation.
The user checks the text and taps Send. Backgrounding, destruction, manual Send,
replacement recording or 30-second timeout cancels recognition. Late results and
results after manual draft edits cannot replace the draft. No audio/text persistence
is added by Vision. Device speech-service behavior is outside this app's control.

Local Linux verification, 2026-10-05: 119 JVM tests pass, debug and androidTest
APKs build, lint 0 errors / 62 warnings. UI and generation-bound draft behavior
are covered with a fake voice backend; native Android pixels were inspected.
Actual microphone audio, on-device speech model availability, recognition accuracy,
permission dialogs and OEM lifecycle need phone verification. Windows CI has not
run for this local branch. Not pushed or merged.
