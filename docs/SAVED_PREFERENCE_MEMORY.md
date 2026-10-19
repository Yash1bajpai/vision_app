# Saved response-style preference (review branch)

This batch adds one local saved preference alongside the existing 60-second
session mode. It does not add conversation memory or change action authority.

## Use

1. Type `enable saved preference memory`. Review the notice and tap
   **Enable saved memory**. Cancel, Back, backgrounding or **Keep session only**
   does not add opt-in. Nothing from the session is copied automatically.
2. Type `save preference response_style concise` or
   `save preference response_style standard` to save the one allowed value.
3. `show preferences` shows both session and saved modes. `show saved preferences`
   shows only saved mode, its value and its expiry date/time.
4. `forget preference response_style` and `clear preferences` delete both session
   and saved values but retain their separate opt-ins. The saved-only equivalents
   are `forget saved preference response_style` and `clear saved preferences`.
5. `disable saved preference memory` deletes saved values and its opt-in.
   `disable preference memory` disables and clears both modes. Neither removes
   the cloud configuration; `cloud forget` remains separate.

The existing `enable preference memory` and `remember preference response_style
concise|standard` commands remain session-only. A currently set session value
takes precedence over the saved value until its 60-second expiry or background
reset. Otherwise, a valid saved value can apply after return or restart.

The only use is successful battery, network and time wording. Facts are not
removed. Errors, confirmations, notification/calendar contents, action outcomes,
provider prompts and action approvals are not styled or remembered by this
feature. Preference controls are handled locally before request-context
collection or provider routing. An executing plan still blocks new controls.

## Storage and retention

- Saved mode starts off. Its opt-in is separate from session memory and cloud
  setup. Saving requires both notice acceptance and a separate explicit save.
- Each explicit save expires exactly 30 days later, using device wall-clock time.
  Reads, enable commands and app restarts do not renew that deadline. Re-saving
  explicitly replaces the value and renews the deadline.
- Expiry is checked before loading/using a value. An expired value is removed;
  an empty opted-in record remains until disable. This is not a background
  scheduled wipe while the app is closed.
- A versioned record holds only opt-in, the enum, save/expiry timestamps and a
  last-observed clock timestamp. Only ciphertext is written to the private
  `vision_saved_preferences_v1` file. AES-256-GCM uses its own Android Keystore
  alias, `vision.preference.memory.v1`, not the cloud key. Random IVs and an
  authenticated domain label separate records. Backups remain disabled.
- The last-observed timestamp detects device-clock rollback across restarts.
  A rollback, malformed record, unknown schema, lost key, failed save or failed
  decryption rejects the saved preference. It reports unavailable and offers
  session-only use; it never claims a failed save succeeded. No plaintext fallback.
  A forward clock change can expire a value early. There is no trusted server clock.
- Disable attempts both ciphertext deletion and separate key invalidation. If
  deletion fails it reports failure and does not reuse the value in that process.
  This is logical deletion, not a promise of forensic erasure from flash media.
- Preference output is excluded from saved Activity state; there is no logging
  or model-input path for saved preference records. The recent-activity area
  scrolls so the notice and saved-mode expiry/deletion controls remain reachable.

No chat, contacts, notifications, calendar contents, recipient memory, tool
results, API keys, model outputs or permissions are added to preference storage.
Existing cloud-key storage and the separate short-lived request context are
unchanged.

## Verification for this local review patch (2026-10-15)

Linux with Java 17, Android SDK 34 and Gradle 8.7:

```
gradle testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
```

235 JVM/Robolectric tests pass. Debug and instrumentation APKs build. Lint has
0 errors and 69 warnings. Tests cover separate opt-in and cancellation, no session
migration, enum-only saves, expiry/re-save/restart, rollback, malformed records,
unavailable encryption, deletion, cloud-store separation, saved-state exclusion,
local input routing and status wording. Native-graphics Robolectric screenshots
were inspected at 360dp width, including scrolling to the end of long output.
These are simulated Android Activity tests, not a live phone or emulator.

AES-GCM tests use generated synthetic JVM keys. Two new instrumentation tests
exercise actual Android Keystore and Activity recreation. The recorded CI run on
2026-10-19 passed both tests (2/2, zero failures) on an API 28 x86_64 emulator:
https://github.com/Yash1bajpai/vision_app/actions/runs/39296587982

The earlier physical-device 2/2 claim was not backed by recovered test output;
it is not independent verification and is superseded here by recorded emulator
evidence. Emulator Keystore success does not prove hardware backing or behavior
on the maintainer's physical phone. Run instrumentation only in an isolated test
installation/profile: it clears saved preference test data.

The targeted workflow uses an explicit shared AVD directory for avdmanager and
the emulator, checks AVD discovery before boot and bounds blocking commands.
Linux and Windows test/build/lint CI also passed for the workflow correction.
No provider/API calls, paid compute or release verification are claimed. Earlier
maintainer-reported phone testing applies only to the code and checks reported.
