<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# Resource Aware Gateway Selection Implementation Plan

**Design:** [Resource Aware Gateway Model Selection](../specs/2026-10-10-resource-aware-gateway-selection-design.md)

## Commit 1: Preserve deployments and expose safe resource snapshots

1. Add a read-only priority snapshot method in `services/nvpair-proxy/proxy.go`,
   copying scheduler pending and GPU-pressure data plus generation and receipt
   time while holding `priorityMu.RLock`.
2. Record snapshot receipt time when a newer scheduler generation is applied.
3. Update `gatewayInventory` in `gateway.go` to retain each engine/model/node
   deployment and attach only fresh per-node resource signals from the copied
   snapshot. Do not change existing resolver or selector behavior in this
   commit.
4. Run focused Go tests for `nvpair-proxy` and `shared/modelselection`.

## Commit 2: Score model deployments using resources

1. Add selector resource inputs that keep pending work and GPU pressure separate
   from memory pressure, with explicit unknown/freshness handling.
2. Adjust `AutoModelSelector` to score deployment candidates and compare each
   model by its strongest eligible deployment. Keep capability filters,
   deterministic engine/model/node ordering, and explicit routing unchanged.
3. Set policy weights so fresh node resources materially influence
   `auto-fast`/`auto-balanced`, while `auto-best` remains quality-led.
4. Run selector and Gateway focused Go tests.

## Commit 3: Regression coverage and contract review

1. Add regression cases for the acceptance cluster, same-model multi-node
   selection, changed resource snapshots, unknown/expired signals, and pressure
   semantic separation.
2. Add concurrent snapshot update/read coverage and verify the existing explicit
   local, cloud, and same-model failover tests continue to pass.
3. Run `go test ./...` from `services/nvpair-proxy` and
   `services/shared/modelselection` (or its containing module), plus race-enabled
   focused tests if supported by the module setup.
4. Review the diff against `.cursor/rules/proxy-inference-routing.mdc`; no
   Scheduler, Broker, telemetry, or desktop changes are expected.

## Review focus

- No score path treats missing or expired metrics as zero load.
- A node's GPU pressure cannot populate `MemoryPressure` or available memory.
- Candidate aggregation does not collapse multiple owners before scoring.
- Snapshot reads return copies and never expose mutable Proxy maps.
- Current per-request snapshots update the ranking after scheduler changes.
- Automatic model choice does not pin node dispatch or alter Proxy failover.
