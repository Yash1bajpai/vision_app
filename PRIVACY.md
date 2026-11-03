# Vision privacy contract

What the app stores, where, and for how long. Verified against `main` (v0.12.0), 2026-11-02.

## Persists on device (encrypted, Android Keystore AES-GCM, non-exportable key)

| Data | Store | When written | Lifetime | Removal |
|---|---|---|---|---|
| Cloud provider API key | `CloudKeyStore` (`vision_cloud_key`) | Only when the user pastes a key in Cloud setup | Until the user chooses Forget | Forget in app; decrypt failure wipes it |
| Response-style preference (`concise`/`standard`) | `PreferenceMemoryStore` (`vision_saved_preferences_v1`) | Only after separate opt-in | 30 days, then auto-expires | View/delete/clear in app; tamper or clock rollback fails closed and wipes |
| Installed on-device models & metadata | `getExternalFilesDir("models")` (`models.json`) | Only when the user downloads from catalog or imports | Until deleted by user | Delete in Settings; wiped on app uninstall |

No plaintext fallback, no logging, no other records. `allowBackup="false"`, `usesCleartextTraffic="false"`.

## In memory only (never written to app storage)

- Session context: the last 2 user requests, 60-second TTL, max 512 chars each. Requests only - never tool results, approvals, or capabilities.
- Plan state, pending confirmations, provider request state: fail-closed; a plan dies with the Activity and never resumes after lifecycle loss.
- Voice input: on-device Android speech recognizer; no audio is stored.
- Notification snapshots and reply capabilities: read on demand, held in memory, reply capability single-use.
- On-device reasoning model weights and inference state: processed strictly locally via embedded llama.cpp in app memory; unloaded when backgrounded or after 5 minutes of inactivity; never transmitted off-device.

## Instance-state Bundle (Android-managed, survives rotation/process death)

The Bundle carries NO user content. Only the pending calendar-READ marker (a fixed type string and state enum) is saved so a safe read can resume after a permission dialog. Pending message actions, calendar event writes, and the activity log are never saved: losing the Activity cancels them (fail-closed), matching the plan-step rule, and a permission granted after recreation shows an explicit "please repeat your request" message instead of acting.

## Leaves the device

Only when the user has added a cloud key AND the offline parser cannot handle a request (and on-device mode is not selected): the request text (plus at most the live 60-second session hints) goes to the chosen provider (Groq, OpenRouter, OpenAI, Gemini, or a custom HTTPS endpoint) over HTTPS. Provider usage may cost money.

On-device model processing runs strictly locally and NEVER leaves the device. The model catalog is BUNDLED in the app (zero outbound catalog fetches). Model weights are downloaded directly via Android DownloadManager only upon explicit user action from the bundled catalog after license acceptance. License acceptance records are stored locally in `models.json`.

Nothing else leaves the device; there is no analytics, crash reporting, or account.
## Read on demand, never stored

Contacts (for recipient disambiguation), calendar contents, notification contents, location (maps handoff only).
