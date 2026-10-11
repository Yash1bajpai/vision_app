# Optional autonomous cloud route (draft PR #16)

Offline means fixed-command parsing, not a live offline LLM. The Qwen model
remains disabled because wrong-but-valid actions were observed in the pilot.

## Consent and execution

`cloud setup` is the one-time consent step. Its notice says that only the typed
command is uploaded to the selected provider, not contacts, notifications, calendar contents,
prior requests or tool results. Adding the key enables the cloud route. Known
commands still use the offline parser; unknown commands use the selected provider automatically.
No repeated upload prompt or model-intent review is shown.

The same strict validator and existing action risk policy remain. Ordinary
supported actions run automatically. Risky actions still require the existing
confirmation; unknown/future types fail closed. Messages/calls/deletion/payment
are not in this cloud pilot's allowlist. Existing offline message/dialer handoffs
keep their existing gates. This is bounded action autonomy, not an unrestricted
background agent or a guarantee of correct intent understanding.

`provider use deterministic` pauses cloud for the current Activity. `cloud forget`
removes the saved ciphertext, clears key references and restores the offline
mode. A saved key enables cloud again at next launch. `cancel request`, a newer
request, timeout and backgrounding discard late responses. They cannot retract
an upload already received by the selected provider.

## Key storage

The setup defaults to Save encrypted on this device. Android Keystore holds a
non-exportable AES-256 key; preferences contain only randomized AES-GCM ciphertext
and nonce. Backup is disabled. If encryption is unavailable, setup reports
memory-only use rather than storing plaintext. The password field is masked,
excluded from saved state/autofill and the setup window uses FLAG_SECURE.

Unchecked Save uses memory only until Activity destruction. Backgrounding cancels
requests but retains the configured key so returning to the app does not require
retyping. Activity destruction clears runtime key references; a saved encrypted
key is restored on next launch. Java strings cannot promise forensic heap erasure.
No production key is bundled in code, APK, logs or saved instance state.

## Providers and network

One OpenAI-compatible adapter supports these preset full endpoints:

- Groq: https://api.groq.com/openai/v1/chat/completions
- OpenRouter: https://openrouter.ai/api/v1/chat/completions
- OpenAI: https://api.openai.com/v1/chat/completions
- Gemini: https://generativelanguage.googleapis.com/v1beta/openai/chat/completions
- Custom: full HTTPS URL ending in `/chat/completions`, port 443 only, no embedded
  credentials, query, fragment, raw IP or localhost. Redirects are not followed.
  Only configure providers you trust with the typed request and API key.

Groq defaults to `openai/gpt-oss-20b`. Other routes require the exact model ID
from that provider. No model availability or universal compatibility is claimed.
Custom compatibility means OpenAI chat-completions request/response shape, not
arbitrary third-party APIs. The selected provider, endpoint, model and key are
saved together inside encrypted storage. Switching the picker clears the entered
key. Old key-only saved records are refused rather than sent to a new destination.

Temperature 0, JSON-object response, max 512 completion tokens. Only Groq GPT-OSS
gets low reasoning/include_reasoning=false; these options are not sent elsewhere.
OpenAI/Groq use max_completion_tokens; other presets use max_tokens. Unsupported
model/parameter, payment-required, rate-limit and other errors fail closed without
retry or fallback. Provider/model feature support still needs a keyed live test.

Connect 5s/read 8s; runner 15s; request <=500 chars; response <=32KiB. No redirects,
retries, fallback or cleartext. Providers can charge based on your account; no
billing cap is enforced. No paid or live keyed call was made in this work.

Sources checked on 11 October 2026:
https://console.groq.com/docs/reasoning
https://console.groq.com/docs/openai
https://ai.google.dev/gemini-api/docs/openai
https://openrouter.ai/docs/api-reference/overview
https://platform.openai.com/docs/api-reference/chat/create

## Verification and limits

Local Linux Java 17 / SDK 34: 212 JVM/Robolectric tests, debug APK,
instrumentation APK and lint checks. Twenty-four new JVM tests cover adapter,
autonomous UI and storage. Storage tests use real AES-GCM with an injected crypto
seam, not Android Keystore hardware. Keystore device round-trip tests compile
but have not run. Five Python research-pilot tests pass separately.

The preset and Custom one-time setup dialogs was rendered with native Robolectric graphics and
visually inspected. It is a test render, not a phone screenshot. The repeated
upload and model-review dialogs were removed.

No authenticated call with this exact Java adapter to any provider or completed phone/emulator
run. Software emulator without /dev/kvm did not finish boot in the bounded
attempt and caused memory pressure; stopped. No device latency, memory, thermal
or battery measurements, or hardware Keystore verification. Keep this PR draft
until those remaining checks can be completed. Earlier playground scores are
research evidence, not adapter/device success.
