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

Local validation: 215 JVM/Robolectric tests, both APK builds and lint pass.
New regression tests cover pause without stop, a throwing availability service
and an outside-button release. The native-graphics draft screen is also checked
visually. These use fake recognition results: they do not prove real microphone
accuracy, on-device model availability, battery use or OEM-specific behavior.
Dialer and Clock handoffs retain their existing exact-target confirmation and
never claim a completed call or saved alarm.
