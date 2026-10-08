# Session preference controls

Preference controls and response styling, integrated on main `1447ea0` on
2026-10-08 alongside dialer, clock reminder and offline push-to-talk features.
The filename is retained so existing links keep working.
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

Current verification (2026-10-08): GitHub Actions on main `1447ea0` passed
JVM tests, debug and androidTest APK builds, and lint on Linux and Windows.
CI does not run an emulator or physical phone.

The maintainer's merge record reports 148/148 JVM tests, lint 0 errors/58
warnings, 5/5 on-device instrumentation tests and combined feature smoke checks,
including preference save, concise wording, expiry and reset. These phone checks
have not been independently rerun for this documentation update. Broader OEM
lifecycle and font-scaling checks remain open.

No model, encryption, disk persistence or cloud inference is implemented.

Later persistence would need its own explicit data categories, retention and
key-loss/deletion policy, storage design, and a change to the zero-disk claim.

Style scope: concise preserves percentage, charging state, network type, time and date.
Standard/off/deleted/expired values restore existing text. Errors, confirmations,
notification contents, calendar entries, help and action outcomes remain unchanged.
