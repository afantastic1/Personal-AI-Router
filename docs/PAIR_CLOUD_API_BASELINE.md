<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# PAIR Cloud API Implementation Baseline

## Source baseline

- Commit: `a6cc1a173f283f18b5bc51654d9d57966337f337`
- Branch created for this work: `feature/cloud-provider`
- Existing user files `docs/PAIR_WEB_API_IMPLEMENTATION_PLAN_2026-10-08.md`
  and `third_party/` were present as untracked content and were preserved.

## Current contracts reviewed

- `android/README.md`: Android MNN is an app-owned local engine; the local
  OpenAI endpoint is at `127.0.0.1:14326` and currently documents a placeholder
  key as non-authenticating.
- `docs/developing.mdx`: Go services own behavior; Desktop and Android relay
  control and render service state; RPC changes require producer, Broker,
  consumers, tests, and contract documentation to move together.
- `services/nvpair-proxy/README.md` and `spec.md`: the Gateway currently
  exposes models discovered from local facades and forwards requests to local
  execution. Local retry, scheduling, cancellation, and workload semantics
  have their own existing specification.

## Cloud-specific finding

`services/nvpair-ui-broker/broker.go` starts the Gateway only when at least one
local facade has enabled successfully. When zero facades enable, it stops the
proxy process and returns `no engine facade could be brought up`. This is a
confirmed startup behavior from source inspection, not a newly reproduced
runtime failure. Broker lifecycle tests do not currently exercise that branch;
W3 must add cloud-only start, restart, and stop coverage before changing it.

## Test evidence collected for this stage

| Command | Result |
| --- | --- |
| `go test ./...` in `services/nvpair-proxy` | PASS (cached) |
| `go test ./...` in `services/nvpair-ui-broker` | PASS (cached) |
| `go test -run '^TestStrictModelRoutingAcrossProcesses$' -count=1 -v -timeout=45s` in `services/tests` | Built service binaries, then skipped because default ports 11435 or 1234 were already occupied; not counted as tested |

The existing `docs/M12_5_TEST_REPORT.md` is historical evidence only. Its
Android device, desktop, and broader service results were not rerun for this
baseline. No cloud provider or network request was made. A broader `go test
./...` in `services/tests` was interrupted after it remained silent for over
two minutes; its spawned scanner process was stopped. Cross-process routing
therefore remains unverified in this run.

## Existing regression coverage relevant to the baseline

`services/nvpair-proxy/gateway_test.go` already covers the local model
directory, `auto*` aliases and selection, MNN tool exclusion, explicit model
routing, request-size limits, and model list privacy. Existing proxy tests
cover stream cancellation and terminal workload reporting. The broker has no
test for cloud-only Gateway lifecycle; that is a W3 requirement.
