# Vision

Vision is an offline-first Android assistant for the iQOO Z9x. The first release is a normal APK, designed around explicit user confirmation before every meaningful action.

## Phase 5 Notification Reply MVP (0.4.0)

- Smallest safe notification reply implementation using standard Android `RemoteInput` and notification action `PendingIntent`s
- In-memory retention of latest reply capability only (no SQLite, SharedPreferences, files, or background persistence)
- Deterministic typed commands for drafting and sending replies:
  - `reply <message>` (e.g. `reply I'll be there in 5 minutes`)
  - `reply: <message>` (e.g. `reply: Sounds great!`)
  - `reply to <app/target>: <message>` (e.g. `reply to WhatsApp: On my way!`, `reply to Alice: Yes, confirmed`)
  - `send reply <message>` (e.g. `send reply Confirmed`)
  - `answer <message>` (e.g. `answer Thank you`)
- Strict confirmation gating: dialog explicitly displays target source/recipient and exact reply text with immediate mandatory Allow/Deny gating before dispatch
- Zero accessibility, zero root, zero hidden APIs, and zero silent actions
- Safe handling of expired/cancelled `PendingIntent`s, unsupported notifications without reply actions, and process restarts

## Phase 4 Hardened (0.3.1)

- Deterministic typed-command parser for notification reading and supported app opening
- Package visibility queries for Android 11+ (API 30-36) allowlisting WhatsApp, Telegram, Gmail, Messages, and Calendar
- Robust launcher resolution handling Telegram (`org.telegram.messenger`), WhatsApp (`com.whatsapp` and `com.whatsapp.w4b`), Gmail (`com.google.android.gm`), Messages (`com.google.android.apps.messaging` and `com.android.messaging`), and Calendar (`com.google.android.calendar`)
- Full in-memory notification extraction supporting `MessagingStyle`, `BigTextStyle` (`android.bigText`), and `InboxStyle` (`android.textLines`)
- Stable notification key tracking to clear in-memory snapshots only on dismissal of the matching active notification
- Structured action types and lifecycle states: proposed, approved, denied, running, succeeded, and failed
- Strict Allow/Deny confirmation dialogs before every action execution
- Unknown requests fail safely without execution
- Notification content remains strictly in process memory

## Earlier phases

- Native Android project shell
- Portrait-first Vision interface
- Offline/model readiness status
- Local command composer
- Recent activity surface
- Mandatory allow/deny confirmation dialog for every command
- In-memory notification snapshots for Gmail, WhatsApp, Telegram, Messages, and Calendar
- Explicit confirmation before reading notification content
- Android notification-access status and settings link

The local model, voice input, and app adapters will be added in later verified phases. Notification content is held only in memory.

## Build

Open the project in Android Studio or run it with a compatible Android Gradle Plugin toolchain:

```bash
./gradlew :app:assembleDebug
```

The phone-local Termux toolchain uses Gradle 8.7, Android API 34, and an ARM64-native `aapt2` override.

## Resource guardrails

The project is configured for a low-memory development device: one Gradle worker, no parallel execution, no daemon, and a 512 MB Gradle heap. Vision does not load or benchmark any language model during the GUI phase.
