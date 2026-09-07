# Vision

Vision is an offline-first Android assistant for the iQOO Z9x. The application is designed around deterministic execution, explicit user authorization, zero disk persistence, and risk-tiered execution safety.

> **Note on Assistant Intelligence Runtime:**
> Local models, on-device LLM runtimes, network AI, embeddings, and unconstrained action generators are explicitly **excluded and deferred** from this release. Phase 9 adds only the trusted-boundary plumbing for a future model — the `ReasoningProvider` interface plus strict fail-closed proposal validation (interface + validation only; still no model, no network, no embeddings). Phase 8 adds safe, deterministic in-memory contact name resolution and permission lifecycle recovery for confirmed external-composer handoffs while maintaining strict zero-disk persistence and confirmation safety.

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
- **Phone Number Masking:** The confirmation dialog and activity surface display the resolved contact name alongside a masked phone number (e.g., `Rahul Sharma (+91 •••• 3210)`), concealing middle digits while confirming identity. Short numbers (fewer than 11 digits) reveal only one leading and two trailing digits.
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

## Resource Guardrails

The project is configured for a low-memory development device: one Gradle worker, no parallel execution, no daemon, and a 512 MB Gradle heap. Vision does not load or benchmark any language model during the deterministic integration phase.
