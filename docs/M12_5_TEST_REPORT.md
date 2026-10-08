<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# M12.5 Test Report

## Automated results

| Layer | Command | Result |
|---|---|---|
| Android JVM tests, AndroidTest Kotlin compilation, lint, and debug APK | `cd android; .\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug` | PASS, 133 JVM tests; instrumentation sources compile; lint passes with existing manifest SDK and dependency-version advisory warnings; APK assembles |
| Go services modules | `go test ./...` in every module under `services/*` | PASS, including the full `services/tests` cross-process suite after updating duplicate-invite assertions to the plan's idempotency policy |
| Engine-manager shutdown regression | `cd services/nvpair-engine-manager; go test ./... -run TestStopAllStopsDetachedCommandDaemonBeforeReadiness -count=10` | PASS |
| Desktop lint, typecheck, unit tests, and dead-code check | `cd desktop; npm run lint; npm run typecheck; npm run test:unit; npm run dead-code:check` | PASS; 239 unit tests passed and 2 existing cases skipped; no dead-code findings |
| Desktop service contracts | `cd desktop; npm run service-contracts:check` | PASS; no contract drift |
| Public provider API read-only smoke | ModelScope owner search, Git refs and pinned file/config queries; Hugging Face author-filtered search | PASS; responses are owner-scoped and parseable; no model was downloaded or run |
| M65 runner parser | PowerShell `System.Management.Automation.Language.Parser::ParseFile` | PASS |
| Pairing persistence runner parser | PowerShell `System.Management.Automation.Language.Parser::ParseFile` | PASS |
| Two-device pairing persistence acceptance | `android/scripts/run-pairing-persistence-acceptance.ps1 -PcAddress 192.168.1.4 -Serial R52RC014KZE` | PASS on Galaxy Tab SM-T733 / Android 14; isolated PC broker accepted the invitation and the Android test verified membership and peer trust after Stop/Start |
| M10 PC-to-Android MNN acceptance | `android/scripts/run-m10-pc-to-android-acceptance.ps1 -ModelDir $env:TEMP\\pair-m10-qwen3-0.6b-mnn -ModelId Qwen3-0.6B-MNN -PcAddress 192.168.1.4 -Serial R52RC014KZE` | PASS on Galaxy Tab SM-T733 / Android 14 using `taobao-mnn/Qwen3-0.6B-MNN` at revision `34dfccda1187ded6e07ea06426da576b0b793c6b`; trusted discovery, SSE token and `[DONE]`, Android workload scheduling, cancellation, and post-cancellation generation completed |
| M65 Android-to-PC acceptance | `android/scripts/run-m65-android-to-pc-acceptance.ps1 -PcAddress 192.168.1.4 -ModelId qwen3-0.6b -Engine lmstudio -Serial R52RC014KZE` | PASS on Galaxy Tab SM-T733 / Android 14 with local LM Studio model; SSE completion and PC-side workload attribution verified |
| Notification-permission denial | Galaxy Tab SM-T733 / Android 14; deny `POST_NOTIFICATIONS`, tap Start PAIR, confirm runtime status, then Stop | PASS; runtime reached Running with permission denied, then returned to Stopped; permission remained denied |
| Model Hub live ModelScope install | Model Hub on Galaxy Tab SM-T733 / Android 14; search and Inspect `MNN/Qwen3-0.6B-MNN`, then Install | PASS; inspection pinned the repository and verified provider SHA-256 metadata; install completed with `config.json`, `llm_config.json`, graph, 450,810,338-byte weight, and tokenizer in app-private storage |
| Installed Model Hub package runtime inference | `cd android; .\gradlew.bat :app:connectedDebugAndroidTest '-PpairMnnDeviceModelDir=/data/user/0/com.nv.pair/files/mnn/models/Qwen3-0.6B-MNN'` | PASS on Galaxy Tab SM-T733 / Android 14; 39 instrumentation tests completed, 3 conditional scenarios skipped; installed package loaded and served completion, streamed token plus `[DONE]`, recovered after client disconnect, and unloaded |
| SPDX scan | `node scripts/spdx-headers.mjs` | One missing header is reported for the user-provided, untracked engineering plan; no tracked implementation file is missing a header |
| Whitespace validation | `git diff --check` | PASS |

The Android build printed an SDK XML compatibility warning (installed command line tools understand XML through v3; the SDK contains v4 metadata). Compilation and tests still completed successfully.

## Not run

- On-device Model Hub download/install passed with the live ModelScope provider. Android OS process-kill recovery remains **NOT RUN**. M10, M65, pairing persistence, and notification-permission denial also passed on the connected Galaxy Tab.
- Automated provider tests use injected local fixtures; the separate public read-only smoke did not exercise Android's runtime network stack or download model weights.
- The repository-root `make check` target was not run on Windows; its Android-independent desktop checks and the Go suites were run directly.
- Go race testing could not run on this Windows environment: Go first reported that `-race` requires CGo; with `CGO_ENABLED=1`, the compiler failed because `gcc` is not installed.

## Added regression coverage

- Stop preserves membership across runtime restart.
- The device acceptance scenario pairs with the isolated PC broker and checks cluster membership and peer trust after Android Stop/Start; it passed on a Galaxy Tab SM-T733.
- Duplicate pending invite returns a typed `invite-in-progress` error without replacing the active PIN session, and an already paired peer is rejected.
- Peer relation resolver distinguishes paired/offline, unknown identity and duplicate identity signals.
- Adapter/repository tests cover owner-scoped search, ModelScope and Hugging Face pagination, no search-time N+1 requests, typed network/response failures, immutable ModelScope and HF revisions, nested MNN artifacts, `.mtok`, and safe hidden repository metadata such as `.gitattributes`.
- Provider contract tests cover typed HTTP categories, malformed response and network failures, ModelScope snake-case pagination, Git ref advertisement parsing and branch-to-commit pinning, rejection when refs omit the selected branch, Hugging Face immutable revisions, and mixed per-file ModelScope `Revision` metadata.
- Downloader and installer tests cover resumed progress and verification, partial artifact recovery after installer reconstruction, recovery from an interrupted initial task-manifest write when no artifacts exist, hash mismatch handling, a server ignoring Range, invalid resume ranges, a transient 503 retry, invalid-partial recovery after HTTP 416, and the explicit-confirmation/local-digest manifest path when a pinned source has no provider checksum. Installer reconstruction uses a local test server; it does not establish Android OS process-kill or device behavior.
- MNN lifecycle tests confirm a loaded model is unloaded before file deletion and an active generation prevents deletion; catalog tests exclude hidden installer staging directories.
- Model resolver tests reject packages missing `llm_config.json`; the JNI load-failure path no longer destroys a failed candidate twice.
