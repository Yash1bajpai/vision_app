# Offline and cloud compatibility spike

Measured 2026-10-10. Research harness only: nothing is connected to MainActivity,
no Android action is executed, no INTERNET permission or API key is added to the APK.
The app still defaults to deterministic offline commands. Cloud is explicit opt-in.

## Results

| Run | Correct intent or safe rejection | Notes |
| --- | --- | --- |
| Qwen first smoke | 4/12 | Weak prompt, plus harness timer contract mistake |
| Qwen tuned smoke | 12/12 | Prompt tuned on these tasks; not an independent benchmark |
| GPT-OSS cloud smoke | 12/12 | Same tuned prompt/tasks, Groq Playground |
| Qwen new holdout | 9/12 | 8/12 literal targets; 90s equals 1m 30s |
| GPT-OSS cloud holdout | 11/12 | Abilities paraphrase returned {} instead of SHOW_HELP |

Both sets have ten supported single-action requests and two unsupported/unsafe requests.
All raw final outputs and per-case validator results are in tools/provider-pilot.
Each output was passed through production StrictJson and ReasoningProposalValidator.
No post-processing repaired a model output. Equivalent duration scoring is the only
normalization. Holdout prompts were written after smoke prompt tuning, without
seeing holdout outputs. This is a small single-run test, not a statistical benchmark.

Qwen holdout misses: connectivity became SHOW_HELP, torch-off became torch-on,
and an install/bypass request became SET_VOLUME:0. These passed the schema validator
but represented the wrong intent. The validator is a syntax/capability boundary,
not a proof that a model understood the user. Do not wire this model into execution
based on the 12/12 tuned smoke result. Deterministic-first parsing remains important.

Cloud rejected all four unsafe requests across both sets. Qwen's smoke unsafe
outputs were rejected, but one holdout unsafe request produced a valid volume action.
No actual volume/torch/payment/install action ran in this experiment.

## Offline provenance and host

- Original model: Qwen3-1.7B, Apache 2.0.
- Q4_K_M conversion: bartowski/Qwen_Qwen3-1.7B-GGUF, revision
  dcb19155b962dbb6389f4691a982043a8e651022.
- File: Qwen_Qwen3-1.7B-Q4_K_M.gguf, about 1.28 GB (decimal).
- SHA-256: 72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb.
- llama.cpp b11541, f2918cabb, Linux x86_64 CPU, two threads, context 2048,
  batch 128, microbatch 64, no GPU. Temperature 0.7, thinking disabled.
- First response about 5.3-5.8 seconds; warm responses about 1.8-2.3 seconds
  including the Java validator launch. These are host timings, not phone estimates.
- Official Qwen GGUF currently supplies Q8_0; this Q4_K_M is a third-party conversion.

## Cloud route and privacy

The live Groq account was confirmed Free/$0 before requests. GPT-OSS 20B was
selected in Playground; only the synthetic commands in this repository were sent.
No payment method, upgrade, key creation, settings change or production-key use.
Global and inference ZDR controls were present but disabled, left unchanged on the
shared account. Groq's ordinary reliability/abuse retention can be up to 30 days.

Playground temperature 1, medium reasoning, max completion 2048, streaming.
Different generation settings make this a same-task comparison, not controlled
latency or sampling comparison. ui_seconds includes browser work and is NOT model
latency. No account key was extracted. The Python API path is prepared but not
live-verified; it requires VISION_GROQ_API_KEY in the owner's environment plus
--cloud-opt-in --verified-free-tier. Manual Free verification is not an automatic
spending guard if the account is upgraded later. Do not use on a paid account.

## Reproduce offline

Use Java 17 and a pinned llama.cpp build. Download the pinned model separately;
weights are not committed or bundled. Start llama-server on 127.0.0.1 port 18080:

```sh
llama-server -m /path/to/model.gguf --host 127.0.0.1 --port 18080 \
  -c 2048 -b 128 -ub 64 -t 2 --no-warmup
./tools/provider-pilot/compile_boundary.sh /tmp/vision-boundary
python3 tools/provider-pilot/run_pilot.py --classes /tmp/vision-boundary \
  --output /tmp/smoke.json
python3 tools/provider-pilot/run_pilot.py --cases holdout-cases.json \
  --classes /tmp/vision-boundary --output /tmp/holdout.json
python3 tools/provider-pilot/test_pilot.py
```

The offline recorded runs used max_tokens 160; the prepared client retains that
cap and now rejects unfinished outputs. That failure check has not been rerun on
the models. The initial prompt and mistaken timer case are retained separately. There
are no retries or automatic offline-to-cloud fallback. Result files contain only
synthetic requests, raw proposals and measurements, never credentials.

## Verification boundaries

Verified locally: real GGUF model loading/generation; production Java boundary
on every final output; 57 existing pure-Java safety/provider JUnit tests;
5 Python CLI privacy/opt-in gate tests. No Android SDK/Robolectric suite, emulator,
NDK/JNI compilation, APK model loading, device memory/thermal/battery measurement,
or iQOO Z9x run was performed. Native on-device integration remains separate work.
This patch changes research tooling/docs only, not production behavior.

## Sources

- https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF
- https://huggingface.co/Qwen/Qwen3-1.7B-GGUF
- https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/blob/main/LICENSE
- https://console.groq.com/docs/model/openai/gpt-oss-20b
- https://console.groq.com/docs/your-data
- https://console.groq.com/docs/billing-faqs
