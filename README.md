# Vision

Vision is an offline-first Android assistant for the iQOO Z9x. The first release is a normal APK, designed around explicit user authorization and risk-tiered execution safety.

## Risk-Tiered Confirmation Policy (v0.5.0)

Vision eliminates unnecessary prompt fatigue while maintaining security: low-risk actions are authorized directly by the typed command itself, whereas high-risk external mutations strictly require explicit modal user confirmation immediately prior to dispatch.

| Risk Tier | Policy | Action Types | Execution Flow |
|---|---|---|---|
| **Tier SAFE** | Auto-execute immediately (NO modal dialog) | `OPEN_APP`, `READ_NOTIFICATION` | Typed command is direct intent. Executes immediately upon validation and outputs concise `SUCCEEDED` / `FAILED` status to the Recent Activity surface. |
| **Tier CONFIRMED** | Modal Allow/Deny dialog REQUIRED | `REPLY_NOTIFICATION` *(Active MVP)*<br>*Documented future members:* `PAYMENT`, `DELETE`, `DOWNLOAD_FILE`, `SEND_MESSAGE_DIRECT`, `INSTALL`, `CHANGE_SETTING` | Proposal binds in-memory capability identity; modal dialog shows exact source, recipient, and payload; re-verifies active capability atomically before dispatch. Never auto-executed. |

---

## Phase 5.1 Hardened & Risk-Tiered Release (0.5.0)

- **Risk-Tiered Execution Policy:** `VisionRiskPolicy` and `VisionAction.requiresConfirmation()` centralize action gating. `OPEN_APP` and `READ_NOTIFICATION` auto-execute cleanly without modal confirmation dialogs; `REPLY_NOTIFICATION` strictly preserves proposal-time capability binding and modal confirmation dialogs.
- **Flag & Summary Filtering (F1):** `VisionNotificationListener` ignores group summaries (`FLAG_GROUP_SUMMARY`) and ongoing/foreground service noise (`FLAG_ONGOING_EVENT`, `FLAG_FOREGROUND_SERVICE`) lacking direct reply capabilities, preventing summaries from overwriting active message snapshots.
- **Strict Canonical Target Validation (F2):** Target validation requires exact canonical alias equality per app (no loose package substring paths) and whole-token or exact full-name matching for senders. Cross-app composite targets fail closed.
- **Reply Pattern Target Delimiter (F3 & F9):** When `to <target>` is specified, a colon delimiter (`:`) is strictly required before the reply body (`reply to <target>: <text>`), preventing multi-word contact names from splitting into body text. `reply to: <text>` routes to the latest notification.
- **Binder-Thread Synchronization (F4):** All listener writes (`onNotificationPosted`, `onNotificationRemoved`) and dispatch checks (`sendBoundReply`) synchronize on the same class monitor lock, eliminating binder-dispatch TOCTOU races.
- **WhatsApp Business Support (F5):** Parser generates distinct `"WhatsApp Business"` target for business/w4b requests. Launcher candidate resolution prioritizes `com.whatsapp.w4b` then `com.whatsapp`, while plain `"open whatsapp"` prioritizes `com.whatsapp`. Target validation seamlessly accepts either package.
- **Parser Routing & Gerunds (F6):** `READ_NOTIFICATION` intent is evaluated prior to `OPEN_APP`, correctly routing gerund requests like `start reading my messages` to notification reading while keeping `open messages` and `open sms` intact.
- **Group Chat Sender & Title Tracking (F7):** `NotificationReplyCapability` stores distinct `conversationTitle` and `senderPerson` metadata from `MessagingStyle` bundles, accepting either identifier in target validation.
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
- `reply to <target>: <message>` (e.g. `reply to WhatsApp: On my way!`, `reply to Alice: Yes, confirmed`, `reply to WhatsApp Business: Order ready`)
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

## Build

Open the project in Android Studio or run it with a compatible Android Gradle Plugin toolchain:

```bash
./gradlew :app:assembleDebug
```

The phone-local Termux toolchain uses Gradle 8.7, Android API 34, and an ARM64-native `aapt2` override.

## Resource guardrails

The project is configured for a low-memory development device: one Gradle worker, no parallel execution, no daemon, and a 512 MB Gradle heap. Vision does not load or benchmark any language model during the GUI phase.
