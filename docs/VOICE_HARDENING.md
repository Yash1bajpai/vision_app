# Voice lifecycle hardening

This patch tightens the existing offline push-to-talk draft flow. It does not
add background listening, online recognition, automatic command submission,
new Android permissions or new action types.

- Stop the microphone at `onPause`, rather than waiting for `onStop`.
- A release outside the button cancels instead of finishing the recording.
- Treat an unavailable/throwing local speech-service check as unavailable.
- Show that an offline draft is finishing while waiting for its result.
- Keep generation-bound results, the 30-second timeout, edit protection and
  explicit Send requirement.

Local validation: 238 JVM/Robolectric tests, both APK builds and lint pass.
New regression tests cover pause without stop, a throwing availability service
and an outside-button release. The native-graphics draft screen is also checked
visually. These use fake recognition results: they do not prove real microphone
accuracy, on-device model availability, battery use or OEM-specific behavior.
Dialer and Clock handoffs retain their existing exact-target confirmation and
never claim a completed call or saved alarm.

## Cancellation status follow-up (2026-11-06)

Cancelling an active voice session now replaces the listening/finishing output
with a visible cancellation result as well as stopping the recognizer. Calls
to cancel an already-finished session do not overwrite a reviewable draft or
another action's result.

The focused JVM regression suites verify that pause changes the active
listening status, rejects a late result, and preserves a completed draft across
background/return. On a physical I2219 with Android 16, a real recording was
active before Home and ended on backgrounding; reopening showed cancellation
instead of the stale listening instruction. A separate real spoken-phrase check
produced a reviewable draft, stopped the microphone on release, and retained the
unsubmitted draft and its status after Home/reopen without executing it.
These phone checks are not proof of all delayed-callback races or Activity
recreation.
