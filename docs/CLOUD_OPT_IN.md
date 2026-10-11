# Optional cloud implementation (draft PR #16)

The compatibility pilot remains historical evidence, not a claim this adapter or
Qwen works on a real phone. Offline means the fixed-command parser here, not a
live offline LLM. Qwen is not enabled.

## New path

1. Start offline. `cloud setup` opens a masked key field. No key is bundled,
   logged, saved to preferences, or placed in saved state. Android secure-window
   protection is enabled at setup. Java strings cannot promise forensic erasure;
   clearing removes app references, not a secure wipe of heap pages.
2. `provider use cloud` opts in. Known commands still never consult the provider.
3. Unknown requests show the exact upload text. Deny sends nothing. Allow once
   sends just that command and a fixed schema prompt to Groq. Prior hints are
   ignored. This is a disclosure confirmation, not action approval.
4. The response must finish normally, have one assistant choice, no tool calls,
   and parse as one strict four-string object. The shared validator and a narrower
   cloud allowlist reject malformed, injected and personal-data actions.
5. Review the proposed type/target against the request before dispatch. Existing
   action confirmation and risk policy remain downstream. Deny does nothing.
6. New commands invalidate pending consent. Cancel, timeout and backgrounding
   discard late results. Backgrounding also removes the provider and clears key
   references; canceled uploads cannot be recalled from the server.

## Network and billing

- Fixed endpoint: https://api.groq.com/openai/v1/chat/completions
- Model: `openai/gpt-oss-20b`, temperature 0, low reasoning, max 512 completion
  tokens, JSON-object response format, reasoning omitted from the response. HTTPS only, no redirects or retries.
- Connect 5s/read 8s; outer runner 15s; request <=500 characters; body <=32KiB.
- No error body or key is exposed. Missing, bad or rate-limited responses produce
  no proposal, never a fallback or automatic retry.
- Bring an account/key restricted to free use. The app cannot inspect or enforce
  account billing. No paid call is authorized by this implementation.

API parameters checked against https://console.groq.com/docs/reasoning and
https://console.groq.com/docs/openai on 11 October 2026.

## Verification

Local Java 17 + Android SDK 34: 202 JVM/Robolectric tests pass, debug APK and
instrumentation APK assemble, lint passes. Fourteen new JVM tests (plus three unrun device tests) cover payload privacy,
key clearing, malformed/truncated/forbidden output, no retry, default mode,
per-request upload denial, model-intent denial, stale consent and lifecycle loss.

Native Robolectric graphics renders of setup, upload preview and action review
were opened and inspected at 480px width: text and buttons fit without clipping.
This checks dialog layout, not a physical device screenshot.

Software Android 28 emulator attempted without /dev/kvm; boot did not complete
within the bounded attempt and caused memory pressure. No device/visual success
is claimed.

Pending: authenticated API call with this exact Java adapter, full physical-phone
run and Android-device latency/memory/battery measurements. A fake transport in
adapter/UI tests is not a live Groq success. Earlier playground scores in the
compatibility report are separate from this implementation.
