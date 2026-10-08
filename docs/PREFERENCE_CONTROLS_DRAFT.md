# Session preference controls draft

Preference controls and response styling. Built from main 8929ab1 independently of open PRs #6-#8.
This does not implement encrypted or persistent memory.

- Memory starts off for each Activity; explicit enable is required.
- Only `response_style` with `concise` or `standard` is accepted. It is metadata
  for successful battery/network/time responses: no action behavior changes, no model input, no remembered
  recipients, no approvals, no notifications, and no arbitrary free-text values.
- Inspect, delete and clear are available. Disable deletes values and opt-in.
- Values expire 60 seconds after an explicit save. Reads and invalid commands
  do not refresh retention. Background/destruction clears values and opt-in.
- Control commands bypass request-context collection and future model routing.
  Pending plans still block them, as they block other requests.
- No storage, permission, dependency, manifest, network or provider changes.

Commands:

```
enable preference memory
remember preference response_style concise
remember preference response_style standard
show preferences
forget preference response_style
clear preferences
disable preference memory
```

Verified locally on Linux, JDK 17, Gradle 8.7, SDK 34: 127/127 JVM tests
(111 baseline plus 11 policy and 5 Android UI/lifecycle tests), debug and
androidTest APK builds, debug APK v2 signature, lint 0 errors/54 warnings, and actual pixels of the preference-save
screen rendered using Robolectric native Android graphics.

Not verified: Windows, physical phone/OEM lifecycle, font scaling, or combined
behavior with PRs #6-#8. No model, encryption, disk persistence or cloud run.
PR publication and Linux/Windows CI pending at local verification. No merge authorized.

Later persistence would need its own explicit data categories, retention and
key-loss/deletion policy, storage design, and a change to the zero-disk claim.

Style scope: concise preserves percentage, charging state, network type, time and date.
Standard/off/deleted/expired values restore existing text. Errors, confirmations,
notification contents, calendar entries, help and action outcomes remain unchanged.
