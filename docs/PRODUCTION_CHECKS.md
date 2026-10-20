# Android production checks

The emulator workflow now runs all Android instrumentation rather than filtering
to one preference test class. It keeps a read-only repository token, bounded
startup/test commands and short-lived diagnostics on failure. It fails when
a total other than the current ten tests execute, any fail or any are skipped.
It runs on changes to app code or its workflow; it can also be run manually.

The baseline covers cloud-key Keystore round trip/clear using a synthetic key,
message-confirmation Deny/dismiss/recreation, help-command rendering, synthetic voice-session cancellation/recreation and the two saved-preference tests.
It does not make calls, send messages, use real API keys or contact providers.

This is a production-hardening batch, not production certification. Emulator
checks cannot prove hardware-backed storage, actual speech accuracy, OEM Clock
or dialer compatibility, real-phone battery/thermal behavior or readiness to
publish a release. Those remain separate checks. No release is made by this PR.
