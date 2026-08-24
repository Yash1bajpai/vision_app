# Vision

Vision is an offline-first Android assistant for the iQOO Z9x. The first release is a normal APK, designed around explicit user authorization and risk-tiered execution safety.

## Risk-Tiered Confirmation Policy (v0.5.2 — Inverted Fail-Closed)

Vision eliminates unnecessary prompt fatigue while maintaining security: low-risk actions are authorized directly by the typed command itself, whereas high-risk external mutations strictly require explicit modal user confirmation immediately prior to dispatch.

Under the inverted fail-closed model (N33), only explicitly designated `SAFE_TYPES` auto-execute without confirmation. Any unlisted, future, or unparsed action type defaults to requiring explicit user confirmation (`RiskTier.CONFIRMED`).

| Risk Tier | Policy | Action Types | Execution Flow |
|---|---|---|---|
| **Tier SAFE** | Auto-execute immediately (NO modal dialog) | `OPEN_APP`, `READ_NOTIFICATION` | Typed command is direct intent. Executes immediately upon validation and outputs concise `SUCCEEDED` / `FAILED` status to the Recent Activity surface. |
| **Tier CONFIRMED** | Modal Allow/Deny dialog REQUIRED | `REPLY_NOTIFICATION` *(Active MVP)*<br>*Documented future members:* `PAYMENT`, `DELETE`, `DOWNLOAD_FILE`, `SEND_MESSAGE_DIRECT`, `INSTALL`, `CHANGE_SETTING`<br>*Default fallback:* `UNKNOWN` / unlisted types | Proposal binds in-memory capability identity; conversational Jarvis-style modal dialog shows exact source, recipient, and payload; re-verifies active capability atomically before dispatch. Never auto-executed. |

---

## Pre-Phase-6 UX Refinement & Release Hardening (0.5.2)

- **Jarvis-Style Conversational Permission Request:** Reply confirmation dialog redesigned to be conversational, clear, and respectful:
  - **Title:** `"Tony, may I send this message?"`
  - **Body:** `"I am ready to send this message to [Destination]:\n\n\"[Exact Outgoing Text]\"\n\nMay I proceed?"`
  - **Controls:** Explicit `Allow` and `Deny` buttons.
  - **Safety:** Preserves all lifecycle guards (`isFinishing() || isDestroyed()`) and bound-capability TOCTOU validation. Never auto-executes and never implies permission for read-only or app launch actions.
- **Multiline Message Composer & Enter Key:**
  - `EditText` supports true multiline input (`TYPE_TEXT_FLAG_MULTI_LINE`, `TYPE_TEXT_FLAG_CAP_SENTENCES`, `IME_ACTION_NONE | IME_FLAG_NO_ENTER_ACTION`).
  - Pressing `Enter` inserts a newline while typing rather than submitting prematurely.
  - Arrow button (`↑`) remains the explicit send control, trimming outer whitespace while preserving internal formatting and newlines.
  - Supports vertical scrolling and dynamic height expansion (1 to 5 lines).
  - Discoverable hint: `"Ask Vision anything... (Enter for newline)"`.
  - All input clearing paths (`input.setText("")`) work seamlessly with multiline messages.
- **Multiline Parser Preservation:** `VisionActionParser` preserves embedded newlines in reply bodies (e.g. `reply to Alice:\nLine 1\nLine 2`) while properly normalizing whitespace for command intents (`OPEN_APP`, `READ_NOTIFICATION`).
- **Comprehensive JUnit 4 Suite (17 Tests):** Added pure formatting tests (`test17_jarvisStyleReplyConfirmationFormatting`) and multiline parser tests (`test16_multilineReplyAndCommandParsing`), bringing verified test count to 17 groups with 0 failures and 0 errors.

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

## Earlier phases

- Native Android project shell (Portrait-first, `#10151D` / `#9EE6C2` palette)
- Offline readiness status
- Local command composer and activity log
- In-memory notification extraction (`MessagingStyle`, `BigTextStyle`, `InboxStyle`)
- Zero persistence: notifications and replies are held strictly in transient process memory
- Zero accessibility, zero root, zero hidden APIs, and zero silent actions
- Fail-closed risk policy (N33) and TOCTOU capability binding (F1-F11)

## Build & Test

```bash
# Run real JUnit 4 unit tests and build debug APK offline
/data/data/com.termux/files/home/gradle/gradle-8.7/bin/gradle --no-daemon --offline :app:testDebugUnitTest :app:assembleDebug
```

The phone-local Termux toolchain uses Gradle 8.7, Android API 34, and an ARM64-native `aapt2` override.

## Resource guardrails

The project is configured for a low-memory development device: one Gradle worker, no parallel execution, no daemon, and a 512 MB Gradle heap. Vision does not load or benchmark any language model during the GUI phase.
