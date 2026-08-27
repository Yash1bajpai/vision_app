# Vision

Vision is an offline-first Android assistant for the iQOO Z9x. The application is designed around deterministic execution, explicit user authorization, zero disk persistence, and risk-tiered execution safety.

> **Note on Assistant Intelligence Runtime:**  
> Local models, on-device LLM runtimes, network AI, embeddings, and unconstrained action generators are explicitly **excluded and deferred** from this release. Phase 8 adds safe, deterministic in-memory contact name resolution for confirmed external-composer handoffs while maintaining strict zero-disk persistence and confirmation safety.

---

## Phase 8 Architecture: Safe Contact Name Resolution & Confirmed Composer Handoff (v0.8.0)

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
- **Phone Number Masking:** The confirmation dialog and activity surface display the resolved contact name alongside a masked phone number (e.g., `Rahul Sharma (+91 •••• 3210)`), concealing middle digits while confirming identity.
- **Bound Action Integrity:** User approval binds the exact resolved name, normalized international number, message body, and target channel.
- **Composer Handoff Only:** Approval opens only the explicit external application composer (`smsto:`, `mailto:`, `https://wa.me/`, `https://t.me/`) with package visibility guards (`com.whatsapp`, `com.whatsapp.w4b`, `org.telegram.messenger`). Vision reports `COMPOSER OPENED`, **never** `SENT`.
- **Cancellation Safety:** Modal Allow/Deny dialog handles Back button, outside tap, device rotation, and Activity destruction by transitioning the action to `DENIED` with zero intent dispatch.

### 4. Selective Runtime Permission Handling
- `READ_CONTACTS` is declared in `AndroidManifest.xml` and requested at runtime **only** when a command specifies a contact-name destination (e.g. `send WhatsApp message to Rahul: ...`).
- Commands with explicit phone numbers, email addresses, Telegram usernames, app launches, or notification reads **never** check or request Contacts permission.
- If permission is denied or revoked, Vision fails closed cleanly without crashing or opening an external composer.

---

## Risk-Tiered Confirmation Policy (Inverted Fail-Closed)

Under the inverted fail-closed model (N33), only explicitly designated `SAFE_TYPES` auto-execute without modal confirmation. All external mutations strictly require explicit modal user confirmation (`RiskTier.CONFIRMED`).

| Risk Tier | Policy | Action Types | Execution Flow |
|---|---|---|---|
| **Tier SAFE** | Auto-execute immediately (NO modal dialog) | `OPEN_APP`, `READ_NOTIFICATION` | Typed command is direct intent. Executes immediately upon validation and outputs concise `SUCCEEDED` / `FAILED` status to the Recent Activity surface. |
| **Tier CONFIRMED** | Modal Allow/Deny dialog REQUIRED | `REPLY_NOTIFICATION`, `SEND_MESSAGE_DIRECT`<br>*Documented future members:* `PAYMENT`, `DELETE`, `DOWNLOAD_FILE`, `INSTALL`, `CHANGE_SETTING`<br>*Default fallback:* `UNKNOWN` / unlisted types | Dialog shows the exact destination (with masked number for resolved contacts) and payload. Notification replies re-verify bound capability identity; direct messages open only an approved external composer and never claim delivery. Never auto-executed. |

---

## Jarvis-Style Conversational Permission Request

For `SEND_MESSAGE_DIRECT` (Tier CONFIRMED):
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

### Direct Message Composer Handoff with Contact Names (Tier CONFIRMED — Phase 8)
- `send WhatsApp message to Rahul: I will be late`
- `send WhatsApp message to Rahul Sharma: I will be late`
- `send WhatsApp Business message to Rahul: I will be late`
- `send message to Rahul: I will be late` (defaults to SMS)
- `send SMS to Rahul: I will be late`
- `send WhatsApp message to Rahul:\nMeeting at 5 PM\nRoom 2B` (multiline body preserved)

### Direct Message Composer Handoff with Explicit Destinations (Tier CONFIRMED — Phase 7)
- `send message to +919876543210: Hello`
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
