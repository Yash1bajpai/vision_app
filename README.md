# Vision

Vision is an offline-first Android assistant for the iQOO Z9x. The first release is a normal APK, designed around explicit user authorization and risk-tiered execution safety.

## Risk-Tiered Confirmation Policy (v0.5.1 — Inverted Fail-Closed)

Vision eliminates unnecessary prompt fatigue while maintaining security: low-risk actions are authorized directly by the typed command itself, whereas high-risk external mutations strictly require explicit modal user confirmation immediately prior to dispatch.

Under the inverted fail-closed model (N33), only explicitly designated `SAFE_TYPES` auto-execute without confirmation. Any unlisted, future, or unparsed action type defaults to requiring explicit user confirmation (`RiskTier.CONFIRMED`).

| Risk Tier | Policy | Action Types | Execution Flow |
|---|---|---|---|
| **Tier SAFE** | Auto-execute immediately (NO modal dialog) | `OPEN_APP`, `READ_NOTIFICATION` | Typed command is direct intent. Executes immediately upon validation and outputs concise `SUCCEEDED` / `FAILED` status to the Recent Activity surface. |
| **Tier CONFIRMED** | Modal Allow/Deny dialog REQUIRED | `REPLY_NOTIFICATION` *(Active MVP)*<br>*Documented future members:* `PAYMENT`, `DELETE`, `DOWNLOAD_FILE`, `SEND_MESSAGE_DIRECT`, `INSTALL`, `CHANGE_SETTING`<br>*Default fallback:* `UNKNOWN` / unlisted types | Proposal binds in-memory capability identity; modal dialog shows exact source, recipient, and payload; re-verifies active capability atomically before dispatch. Never auto-executed. |

---

## Phase 5.2 Release & Audit Hardening (0.5.1)

- **Real JUnit 4 Test Suite Execution (Task 1):** Full test suite converted from standalone main to JUnit 4 `@Test` methods with `testImplementation "junit:junit:4.13.2"`, producing real XML execution reports under `app/build/test-results/testDebugUnitTest/` with 15 test groups and 0 failures.
- **Fail-Closed Risk Policy (N33):** Inverted `VisionRiskPolicy` to fail-closed with an explicit `SAFE_TYPES` EnumSet (`OPEN_APP`, `READ_NOTIFICATION`). All other current or future action types default to `RiskTier.CONFIRMED` requiring explicit modal confirmation.
- **Atomic Filter & Write Synchronization (N2):** `VisionNotificationListener.onNotificationPosted` checks `shouldIgnoreNotification` inside the `synchronized (VisionNotificationListener.class)` block, ensuring flag filtering and snapshot/capability writes are atomic together.
- **Replyable Group Summary Support (N9):** Group summary notifications (`FLAG_GROUP_SUMMARY`) are only dropped when they lack a `RemoteInput` reply action. Group summaries carrying direct reply capabilities are accepted.
- **Hardened Reply Target Token Boundaries (N13):** Added `\b` word boundary to `REPLY_TO_PATTERN` so recipient names starting with "to" (e.g. Tokyo, Tom, Tony) and colons in message bodies (`reply to Alice: hello: world`) route cleanly without swallowing target tokens.
- **AlertDialog Lifecycle & Memory Safety (N19):** `MainActivity` tracks `activeDialog`, dismisses dialogs cleanly in `onDestroy()`, and guards all positive/negative dialog callbacks with `isFinishing() || isDestroyed()` checks to prevent execution against destroyed activities.
- **Flag & Summary Filtering (F1):** Filters background service noise lacking direct reply actions before modifying process snapshots.
- **Strict Canonical Target Validation (F2):** Requires exact canonical alias equality per app and whole-token / full-name matching for senders.
- **Reply Pattern Target Delimiter (F3 & F9):** When `to <target>` is specified, a colon delimiter (`:`) is strictly required before the reply body (`reply to <target>: <text>`). `reply to: <text>` routes to the latest notification.
- **Binder-Thread Synchronization (F4):** All listener writes and dispatch checks synchronize on the same class monitor lock.
- **WhatsApp Business Support (F5):** Distinct `"WhatsApp Business"` target handling and package priority resolution.
- **Parser Routing & Gerunds (F6):** `READ_NOTIFICATION` intent is evaluated prior to `OPEN_APP`, correctly routing gerund requests like `start reading my messages`.
- **Group Chat Sender & Title Tracking (F7):** `NotificationReplyCapability` stores distinct `conversationTitle` and `senderPerson` metadata from `MessagingStyle` bundles.
- **Scrollable Activity Log (F8):** `activityText` is backed by `ScrollingMovementMethod` for reading long messages.
- **Accurate Component Listener Check (F10):** `isNotificationAccessEnabled()` parses flattened `ComponentName` entries instead of raw substring matching.
- **Clean Destination Formatting (F11):** Eliminates redundant `from WhatsApp (WhatsApp)` formatting when sender name equals the source application.

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
- `reply: <message>` (e.g. `reply: Sounds great!`)
- `reply to: <message>` (e.g. `reply to: Sounds great!`)
- `reply to <target>: <message>` (e.g. `reply to WhatsApp: On my way!`, `reply to Alice: Yes, confirmed`, `reply to tokyo: hi`, `reply to WhatsApp Business: Order ready`)
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

## Build & Test

```bash
# Run real JUnit 4 unit tests and build debug APK offline
/data/data/com.termux/files/home/gradle/gradle-8.7/bin/gradle --no-daemon --offline :app:testDebugUnitTest :app:assembleDebug
```

The phone-local Termux toolchain uses Gradle 8.7, Android API 34, and an ARM64-native `aapt2` override.

## Resource guardrails

The project is configured for a low-memory development device: one Gradle worker, no parallel execution, no daemon, and a 512 MB Gradle heap. Vision does not load or benchmark any language model during the GUI phase.
