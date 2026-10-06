<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# PAIR for Android

The Android app hosts the PAIR services and the optional MNN model runtime. Model weights are supplied locally for testing; they are not stored in this repository or packaged in the APK.

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
