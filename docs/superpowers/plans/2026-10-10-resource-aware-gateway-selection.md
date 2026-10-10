<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# Resource Aware Gateway Selection Implementation Plan

**Design:** [Resource Aware Gateway Model Selection](../specs/2026-10-10-resource-aware-gateway-selection-design.md)

## Commit 1: Preserve deployments and expose safe resource snapshots

1. Extend shared `schedulerwire.NodeRank` with `GPUPressureKnown`; Scheduler
   derives it from the existing telemetry freshness rule and includes freshness
   changes in emitted-rank equality.
2. Preserve the flag through the Broker's typed snapshot clone and Proxy's
   copied priority snapshot. Copy pending, pressure freshness, and current
   reservations while holding `priorityMu.RLock`.
3. Retain every engine/model/node deployment in `gatewayInventory`; do not age
   event-driven scheduler snapshots at the Gateway.
4. Run focused Scheduler, Broker, Proxy, and shared-wire tests.

## Commit 2: Score model deployments using resources

1. Add selector inputs with independent pending-known, GPU-pressure-known, and
   in-flight reservation values, separate from memory pressure.
2. Adjust `AutoModelSelector` to score deployment candidates and compare each
   model by its strongest eligible deployment. Keep capability filters,
   deterministic engine/model/node ordering, and explicit routing unchanged.
3. Set policy weights so fresh node resources materially influence
   `auto-fast`/`auto-balanced`, while `auto-best` remains quality-led.
4. Run selector and Gateway focused Go tests.

## Commit 3: Regression coverage and contract review

1. Add regression cases for the acceptance cluster, same-model multi-node
   selection, changed snapshots, stale pressure with an unchanged numeric value,
   overlapping requests, unknown values, and pressure semantic separation.
2. Prove the Gateway's rated node is advisory by creating newer Proxy
   reservations after model scoring and asserting the Proxy chooses another
   eligible owner.
3. Verify existing explicit local, cloud, and same-model failover tests pass.
4. Run `go test ./...` for Scheduler, Broker, Proxy, and shared, plus focused
   race-enabled concurrency tests. Physical two-device validation is reported
   separately and requires available controlled devices.
5. Review the diff against `.cursor/rules/proxy-inference-routing.mdc` and the
   service contract propagation rules.

## Review focus

- No score path treats missing or stale metrics as zero load.
- A node's GPU pressure cannot populate `MemoryPressure` or available memory.
- Candidate aggregation does not collapse multiple owners before scoring.
- Snapshot reads return copies and never expose mutable Proxy maps.
- Scheduler emits freshness changes even when pressure remains numerically 1.
- Gateway scoring observes current Proxy reservations during an overlapping
  request burst.
- Current per-request snapshots update the ranking after scheduler changes.
- Automatic model choice does not pin node dispatch or alter Proxy failover.
