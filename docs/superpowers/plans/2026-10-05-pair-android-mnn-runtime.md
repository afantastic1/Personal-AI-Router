<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# PAIR Android MNN Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the M7 Android MNN CPU runtime with model load/unload, text generation, streaming, cancellation, backend selection, status, and metrics, then verify it on the attached ARM64 device.

**Architecture:** Android owns the MNN lifecycle through `MnnEngineHost` and `MnnModelManager`; Kotlin control APIs never carry inference through PAIR JSON-RPC. A small JNI adapter wraps MNN 3.6.1's LLM API, with a serialized native session and token callbacks. CPU is mandatory; OpenCL is an optional backend reported only when the built runtime/device supports it. Model weights stay outside the APK.

**Tech Stack:** Kotlin, Android NDK 27.2.12479018, CMake, C++17/JNI, MNN 3.6.1 (tag commit `d407447ed56c4121a11ccbd266dc184ca1ead0c2`), JUnit, Android instrumentation.

**Spec:** `docs/PAIR_ANDROID_IMPLEMENTATION_PLAN.md`, §§60–62, 74, 117, 125.

## Global Constraints

- Keep Android as the MNN runtime host; do not put model inference requests, prompts, generated text, or token streams on PAIR JSON-RPC.
- CPU model load and inference are required; OpenCL is optional and must not be required to build or pass the CPU gate. Since the attached device exposes `/vendor/lib64/libOpenCL.so`, include a real OpenCL load/generation probe after CPU passes; report unsupported only if runtime initialization or inference actually fails.
- Preserve the plan's Qwen 1.7B target as final device acceptance; use Qwen 0.6B for routine JNI/device iteration. Keep weights outside the APK and accept local model directories for tests.
- Never log prompts, messages, responses, pairing PINs, or model key material.
- Keep MNN's native lifecycle behind Kotlin runtime interfaces; do not expose JNI handles or MNN objects to UI.
- Build only `arm64-v8a`; ensure every produced shared object supports 16 KB page alignment. With the pinned NDK r27.2, pass both `-Wl,-z,max-page-size=16384` and `-Wl,-z,common-page-size=16384` to every project-built shared object, including MNN and `libpair_mnn.so`.
- Preserve existing staged and unstaged work in the shared checkout; do not stage or commit unrelated files.

## Review Focus

- Invalid, incomplete, or unsupported model config must fail with a safe actionable error, not crash the app or log model input.
- Cancellation racing with completion/unload must not call a destroyed MNN handle or leave generation running.
- Repeated load/generate/unload cycles must release native memory and allow a later model load.
- JNI callbacks from native worker threads must be attached safely and released on every exit path.
- Optional OpenCL selection on a CPU-only or unsupported device must return an explicit unsupported-backend result while CPU remains usable.

---

### Task 1: Pin and build the Android MNN native runtime

**Files:**
- Create: `android/scripts/build-mnn.ps1`
- Create: `android/scripts/verify-mnn-binaries.ps1`
- Create: `android/app/src/main/cpp/CMakeLists.txt`
- Create: `android/app/src/main/cpp/NativeMnn.cpp` (minimal JNI target bootstrap; extended in Task 3)
- Modify: `android/app/build.gradle.kts`
- Modify: `android/.gitignore`

**Interfaces:**
- Produces generated ARM64 MNN headers and shared libraries for MNN 3.6.1 plus the PAIR JNI library; generated artifacts remain under ignored Android build/cache directories.
- The build task accepts a local MNN source/cache directory and otherwise fetches the exact pinned source revision; it must verify the resolved commit before building.
- The build prerequisite check requires Ubuntu-24.04 WSL packages `build-essential`, `cmake`, `ninja-build`, and `python3`, plus Android SDK NDK 27.2.12479018. It must stop with exact install guidance rather than silently selecting NDK 30.

- [x] **Step 1: Add a native artifact verifier test**

Create `verify-mnn-binaries.ps1` to assert the pinned source revision, ARM64 ELF machine, required `libMNN` / `libllm` artifacts, `libpair_mnn.so`, and 16 KB ELF load-segment alignment. The verifier must fail with a specific missing-artifact or mismatch message.

- [x] **Step 2: Run the verifier to verify it fails before artifacts exist**

Run: `powershell -NoProfile -File android/scripts/verify-mnn-binaries.ps1 -ArtifactDir android/build/generated/mnn/arm64-v8a`
Expected: nonzero exit identifying the first missing required artifact.

- [x] **Step 3: Implement the pinned MNN Android build and staging**

Install Android SDK NDK 27.2.12479018 with `sdkmanager --install "ndk;27.2.12479018"` and required WSL packages, then build the exact pinned MNN commit with LLM and CPU enabled using the official Ubuntu-24.04 Android configuration. Pass both `-Wl,-z,max-page-size=16384` and `-Wl,-z,common-page-size=16384` to every produced shared object, including MNN. Build OpenCL as a separate optional variant only after CPU is usable. Keep generated outputs ignored and stage only required ARM64 libraries/headers.

- [x] **Step 4: Run the verifier and Android native build**

Run from `android/`: `.\gradlew.bat :app:externalNativeBuildDebug`
Expected: all required objects identify as AArch64 ELF and have 16 KB-compatible load segments; the PAIR JNI target links successfully against the pinned MNN runtime.

### Task 2: Define the Kotlin runtime contract and lifecycle

**Files:**
- Create: `android/app/src/main/java/com/nv/pair/mnn/MnnModels.kt`
- Create: `android/app/src/main/java/com/nv/pair/mnn/MnnRuntime.kt`
- Create: `android/app/src/main/java/com/nv/pair/mnn/MnnModelManager.kt`
- Create: `android/app/src/main/java/com/nv/pair/mnn/MnnEngineHost.kt`
- Test: `android/app/src/test/java/com/nv/pair/mnn/MnnEngineHostTest.kt`
- Test: `android/app/src/test/java/com/nv/pair/mnn/MnnModelManagerTest.kt`

**Interfaces:**
- `MnnModelDescriptor(modelId: String, configPath: String, displayName: String?)` identifies the logical model separately from local storage. `MnnRuntime.loadModel(model: MnnModelDescriptor, backend: MnnBackend): MnnResult<Unit>`; the manager resolves `configPath` internally. Remaining methods: `generate(requestId: Long, request: MnnGenerationRequest, onToken: (String) -> Unit): MnnResult<MnnGenerationResult>`, `cancel(requestId: Long)`, `unloadModel()`, `getStatus(): MnnRuntimeStatus`, `getLoadedModel(): MnnLoadedModel?`, and `getMetrics(): MnnRuntimeMetrics`.
- `MnnEngineHost` serializes load/unload/generate operations, exposes state (`UNLOADED`, `LOADING`, `READY`, `GENERATING`, `ERROR`), and delegates native work through an injectable `MnnRuntime` implementation.
- `MnnModelManager` validates a model directory/config before load and records only model metadata and local paths; it does not download models in M7.

- [x] **Step 1: Write focused lifecycle and model-validation tests**

Test named outcomes: rejects missing model config; load success reaches `READY`; load failure reaches `ERROR` and leaves no loaded model; generation emits tokens and returns metrics; cancellation reaches a terminal state; unload releases the loaded model; reload after unload succeeds; OpenCL-unavailable returns a typed error without disabling CPU.

- [x] **Step 2: Run the focused tests and verify they fail for missing M7 types/behavior**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.nv.pair.mnn.*"`
Expected: compilation/test failure because the M7 runtime types and behavior do not yet exist.

- [x] **Step 3: Implement the typed M7 domain and serialized host**

Use an injected runtime interface so lifecycle and cancellation races are testable without JNI. Ensure unload waits for any active generation to stop before releasing the native session.

- [x] **Step 4: Run focused and full Android unit tests**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: all M7 tests and the existing Android unit suite pass.

### Task 3: Implement JNI load, generation, streaming, cancellation, and release

**Files:**
- Modify: `android/app/src/main/cpp/NativeMnn.cpp`
- Create: `android/app/src/main/cpp/NativeMnn.h`
- Create: `android/app/src/main/java/com/nv/pair/mnn/NativeMnn.kt`
- Modify: `android/app/src/main/cpp/CMakeLists.txt`
- Modify: `android/app/build.gradle.kts`
- Test: `android/app/src/androidTest/java/com/nv/pair/mnn/NativeMnnContractInstrumentedTest.kt`

**Interfaces:**
- `NativeMnn` implements the native side of `MnnRuntime`; it exposes typed Kotlin operations and never exposes a raw native pointer outside its private implementation.
- Native generation reports incremental UTF-8 token chunks and accepts request-ID cancellation; each native session is destroyed exactly once after cancellation/generation has quiesced.

- [x] **Step 1: Add failing bridge contract tests**

Test native version reporting, missing-library/error translation, invalid model path rejection, one-token callback delivery, cancellation of an active request, and idempotent unload. Test assertions must not include or log the prompt/response contents beyond the exact synthetic token callback needed for that assertion.

- [ ] **Step 2: Run the bridge tests before adding JNI implementation**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest -PpairMnnModelDir=<qwen-0.6b-directory>`
Expected: failure because the native bridge implementation is absent.

- [x] **Step 3: Implement the MNN 3.6.1 JNI adapter**

Use the pinned MNN sequence: `Llm::createLLM(configPath)`; `set_config(runtimeConfig)`; check `load()`; `apply_chat_template` and `tokenizer_encode`; `generate_init(customOstream)`; prefill with `generate(inputIds, 0)`; then check PAIR's atomic cancellation flag before each `generate(1)`. A bounded UTF-8 stream forwards chunks to Kotlin. Collect metrics after the loop. Do not mutate MNN's internal `LlmStatus::USER_CANCEL`; PAIR owns cancellation state. Destroy the LLM only after generation quiesces. Never log prompt/config/response content.

- [x] **Step 4: Run JNI contract tests and native compile checks**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest -PpairMnnModelDir=<qwen-0.6b-directory>`
Expected: native contract tests pass on the ARM64 device and the JNI library links against the pinned MNN runtime.

### Task 4: Verify the Android runtime on ARM64 with Qwen 0.6B

**Files:**
- Create: `android/app/src/androidTest/java/com/nv/pair/mnn/MnnRuntimeInstrumentedTest.kt`
- Create: `android/scripts/run-mnn-acceptance.ps1`
- Create: `android/README.md`
- Modify: `android/app/build.gradle.kts`

**Interfaces:**
- The routine JNI/device runner receives a local Qwen 0.6B MNN-format directory and transfers it to app-private test storage; it never embeds weights in the APK or repository. Use this fast fixture for JNI, streaming, cancellation, and lifecycle iteration.
- Instrumentation reads the model directory from an explicit test argument and fails (does not silently skip) when the required model fixture is absent.

- [x] **Step 1: Write device acceptance tests**

Cover CPU load, non-empty generation, incremental streaming, cancellation, repeated inference, unload/reload, metrics, and a real OpenCL load+generation probe (or a typed unsupported result while CPU remains usable). Repeat load/generate/unload five times; require no crash/UAF, successful reload, no progressive monotonic PSS growth, and fifth post-unload PSS within a documented test tolerance of the first post-unload sample. Report samples; do not require PSS to equal process baseline.

- [x] **Step 2: Run the tests without a model fixture and verify they fail with the missing-fixture diagnostic**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest`
Expected: explicit fixture validation failure because no model directory is supplied; no skipped M7 acceptance tests.

- [x] **Step 3: Implement the fixture transfer and instrumentation harness**

Accept the externally stored Qwen 0.6B MNN model directory, validate its required files, transfer it to app-private device storage, and run focused runtime acceptance without logging prompt or generated text.

- [x] **Step 4: Run complete verification**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest -PpairMnnModelDir=<qwen-0.6b-directory>`
Expected: build/lint/unit/device suites pass on the attached ARM64 device; CPU gates pass, and OpenCL either passes a real probe or returns a clear unsupported result without affecting CPU.

### Task 5: Final device acceptance with Qwen 1.7B

**Files:**
- Extend: `android/app/src/androidTest/java/com/nv/pair/mnn/MnnRuntimeInstrumentedTest.kt`
- Extend: `android/scripts/run-mnn-acceptance.ps1`
- Extend: `android/README.md`

**Fixture:** Apache-2.0 `taobao-mnn/Qwen3-1.7B-MNN` snapshot `7f918712ca8bbcce68ce1d2f56160ee03ceaa118`, supplied from an external local directory. Model weights remain outside the APK/repository. Optionally supply the separate 0.6B fixture for model-switch coverage.

- [x] **Step 1: Run full acceptance using the 1.7B fixture**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest -PpairMnnModelDir=<qwen-1.7b-directory> -PpairMnnModelSwitchFixture=<qwen-0.6b-directory>`
Expected: CPU load/generate/stream/cancel/metrics/unload/reload pass on ARM64; OpenCL is actually load+generation tested or returns a typed unsupported result, with CPU still usable.

---
