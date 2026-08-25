# Vision

Vision is an offline-first Android assistant for the iQOO Z9x. The application is designed around deterministic execution, explicit user authorization, zero background persistence, and risk-tiered execution safety.

> **Note on Assistant Intelligence Runtime:**  
> Local models, on-device LLM runtimes, network AI, embeddings, and unconstrained action generators are explicitly **excluded and deferred** from this release. Phase 6 hardens deterministic notification reliability and action observability within a bounded, zero-persistence Android integration model.

---

## Phase 6 Architecture: Deterministic Notification Reliability & Action Observability (v0.6.0)

### 1. In-Memory Transient State Model
Notification state transitions are modeled deterministically in `VisionNotificationListener.ListenerState` with zero disk or database persistence:
- **`NotificationStatus.NO_NOTIFICATION_YET`**: Initial boot or explicitly cleared state.
- **`NotificationStatus.ACTIVE_NOTIFICATION`**: A supported messaging notification is active and cached in process memory.
- **`NotificationStatus.NOTIFICATION_REMOVED`**: Active notification was dismissed by the user or cancelled by Android.

State metadata is restricted to safe operational identifiers: `key`, `packageName`, `postTime`, `hasReplyCapability`, and a monotonic `sequenceNumber`. **Never** stored or exposed in state logs: notification body, sender name, message text, PendingIntent, or RemoteInput.

### 2. Deterministic Notification Ordering & Tie-Breaking Policy
When multiple notifications or out-of-order binder callbacks arrive:
- **`newPostTime > currentPostTime`**: Accepted. The newer notification atomically replaces the active snapshot.
- **`newPostTime < currentPostTime`**: Rejected. Out-of-order stale binder callbacks cannot overwrite newer active state.
- **`newPostTime == currentPostTime`**:
  - *Same Key*: Accepted as an in-place content update for the identical conversation.
  - *Different Key*: Deterministic lexicographical tie-break (`newKey.compareTo(currentKey) >= 0`) ensures identical behavior across all CPU architectures and execution runs.
- **Removal Isolation**: Notification removals match strictly on active `key`. Removing an unrelated background or dismissed notification never erases active state.
- **Filter Invariant**: Unsupported packages and filtered ongoing/summary noise never overwrite or invalidate valid active notifications.

### 3. Reply Action Selection & RemoteInput Eligibility
When notifications expose multiple action buttons or RemoteInput fields:
- **Semantic Action Priority**: Prefers `Notification.Action.SEMANTIC_ACTION_REPLY` on supported platforms (API 28+ / Android 9 Pie through API 34+).
- **Deterministic Fallback**: Selects the first text-capable RemoteInput action if no semantic reply action is designated.
- **Data-Only Exclusion**: Excludes non-text RemoteInputs (where `allowFreeFormInput` is `false` and `choices` are empty).

### 4. Multiline Reply Integrity
Multiline composer text is preserved byte-for-byte:
- Outer leading/trailing whitespace is trimmed on submission.
- Internal line breaks (`\n`, `\r\n`), indents, and paragraph breaks are preserved identically across command parsing, the conversational Jarvis confirmation dialog, and the dispatched `RemoteInput` intent bundle.

### 5. Bounded Action Observability
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
| **Tier CONFIRMED** | Modal Allow/Deny dialog REQUIRED | `REPLY_NOTIFICATION` *(Active MVP)*<br>*Documented future members:* `PAYMENT`, `DELETE`, `DOWNLOAD_FILE`, `SEND_MESSAGE_DIRECT`, `INSTALL`, `CHANGE_SETTING`<br>*Default fallback:* `UNKNOWN` / unlisted types | Proposal binds in-memory capability identity; conversational Jarvis-style modal dialog shows exact source, recipient, and payload; re-verifies active capability atomically before dispatch. Never auto-executed. |

---

## Jarvis-Style Conversational Permission Request

For `REPLY_NOTIFICATION` (Tier CONFIRMED):
- **Dialog Title:** `"Tony, may I send this message?"`
- **Dialog Body:** `"I am ready to send this message to [Resolved Destination]:\n\n\"[Exact Outgoing Text]\"\n\nMay I proceed?"`
- **Controls:** `Allow` and `Deny` buttons.
- **Safety:** Guards against TOCTOU races, stale capabilities, and destroyed Activity lifecycles.

---

## Supported Commands

### Reading & Apps (Tier SAFE — Auto-Executed)
- **Notification Reading:**
  - `read notification` / `read notifications`
  - `read my latest notification` / `see latest notification`
  - `show my latest message` / `check messages`
  - `start reading my messages`
- **App Launching:**
  - `open whatsapp` / `open whatsapp now`
  - `open whatsapp business` / `launch whatsapp business` / `start w4b`
  - `launch telegram` / `please open telegram`
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

---

## Build & Test

```bash
# Run deterministic JUnit 4 unit tests and build debug APK offline
/data/data/com.termux/files/home/gradle/gradle-8.7/bin/gradle --no-daemon --offline :app:testDebugUnitTest :app:assembleDebug
```

The phone-local Termux toolchain uses Gradle 8.7, Android API 34, and an ARM64-native `aapt2` override.

## Resource Guardrails

The project is configured for a low-memory development device: one Gradle worker, no parallel execution, no daemon, and a 512 MB Gradle heap. Vision does not load or benchmark any language model during the deterministic integration phase.
