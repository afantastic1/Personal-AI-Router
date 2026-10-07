<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# PAIR for Android

The Android app hosts the PAIR services and the optional MNN model runtime. Model weights are supplied locally for testing; they are not stored in this repository or packaged in the APK.

On Android/arm64, the broker packages engine-manager in hosted mode and the
MNN proxy facade listens on `:14324` while the app-owned engine remains on
loopback `:14325`. Discovery advertises service key `mn` only while both
endpoints pass their health checks; engine-manager's `em` endpoint supplies
`modelsByEngine.mnn` to PAIR discovery.

## MNN loopback engine

While `PairRuntimeService` is running, it owns an OpenAI-compatible MNN engine at `127.0.0.1:14325`. The listener is never exposed on Wi-Fi or another LAN interface. It provides:

```text
GET  /healthz
GET  /v1/models
POST /v1/chat/completions
GET  /internal/models/loaded
POST /internal/models/load
POST /internal/models/unload
```

Install each model as one directory below the app-private `files/mnn/models/` directory. The directory name is the exact model ID exposed by `/v1/models`. One model may be loaded and one generation may run at a time; a simultaneous generation returns `409 engine_busy`. Stopping PAIR first stops HTTP acceptance, cancels any active generation, unloads MNN, closes port 14325, and then shuts down the broker.

Chat completions accept structured `system`, `user`, and `assistant` messages. MNN applies the model's chat template. Both streamed SSE and non-streamed responses support `max_tokens`, `temperature`, `top_p`, and `seed`; unsupported OpenAI capabilities return an explicit HTTP 400 response.

## PAIR local OpenAI API

When the PAIR runtime is running, the proxy process also hosts the unified
OpenAI-compatible endpoint at `http://127.0.0.1:14326/v1`. Configure a local
third-party chat client with that base URL and the placeholder key `pair-local`.
The key is not authentication: this listener is loopback-only. `GET /models`
lists the current union of discovered models, and `POST /chat/completions`
routes an explicit model through its engine facade and the existing PAIR node
scheduler. The Android Network screen shows the endpoint and provides a copy
action.

## Model Hub

The Models screen keeps searchable ModelScope and Hugging Face catalog entries
separate from models reported by PAIR runtime inventory. Catalog metadata can
describe installation candidates, but automatic selection only uses models
currently advertised by a runtime. Local MNN imports and verified catalog
downloads are installed under app-private `files/mnn/models/`; downloads resume
partial transfers and verify SHA-256 before publishing the model directory.
Installed models can be removed from this screen.

The unified API advertises `auto`, `auto-fast`, `auto-balanced`, and `auto-best`
in addition to explicit model IDs. `auto` maps to `auto-balanced`. The selector
filters by request capabilities, availability, context, memory, and
compatibility, then chooses a model and engine using the selected policy. The
existing engine facade still chooses the node and records workload attribution.

For the M11 device acceptance, use a third-party OpenAI-compatible chat app on
the phone, set Base URL to `http://127.0.0.1:14326/v1`, and use `pair-local` if
the app requires a key. Request a model available only on a PC (for example,
`qwen3-8b`) and verify the response streams while PAIR records the workload on
the PC engine. Then request an installed Android MNN model such as
`qwen3-1.7b` through the same URL and verify its workload is attributed to MNN.

## MNN device acceptance

Routine native-runtime acceptance uses a local MNN-format Qwen 0.6B model directory. It must contain `config.json`, `llm.mnn`, `llm.mnn.weight`, and either `tokenizer.mtok` or `tokenizer.txt`. Connect and authorize one ARM64 Android device with USB debugging or wireless ADB, then run from the repository's `android` directory:

```powershell
.\scripts\run-mnn-acceptance.ps1 -ModelDir D:\models\qwen3-0.6b-mnn
```

For the final Qwen3-1.7B device gate, pass its MNN fixture as `-ModelDir` and optionally supply the separate Qwen3-0.6B fixture for model-switch acceptance:

```powershell
.\scripts\run-mnn-acceptance.ps1 `
  -ModelDir D:\models\qwen3-1.7b-mnn `
  -ModelSwitchDir D:\models\qwen3-0.6b-mnn
```

If multiple devices are connected, select one explicitly with `-Serial <adb-serial>`. The runner builds and installs the debug app, copies the fixture through a temporary device staging directory into a unique app-private directory, then runs the instrumentation suite once with the CPU runtime and once with the OpenCL-enabled runtime. It removes its temporary staging and app-private fixture copies on exit. The test reports missing fixture files instead of skipping acceptance.

The instrumented tests cover CPU load, generation and streaming, cancellation, metrics, unload/reload, five repeated load/generate/unload cycles with post-unload PSS comparisons, and an OpenCL load/generation probe. OpenCL may return the typed `BACKEND_UNSUPPORTED` result; in that case the test still verifies that CPU remains usable. Prompts and generated text are not written to test logs.

The final planned device gate additionally uses the pinned Qwen3-1.7B MNN fixture. See `../docs/superpowers/plans/2026-10-05-pair-android-mnn-runtime.md` for its pinned snapshot and remaining acceptance requirements.

## Cluster shutdown

Using the runtime's explicit **Stop** action sends `cluster:leave` before the local Broker closes, so reachable peers remove this node from their cluster roster and it can be paired again later. Internal Broker restarts preserve membership. A force-stop, crash, or network loss can prevent the departure message from being delivered; an unreachable peer may then need an explicit leave/removal before pairing again.
