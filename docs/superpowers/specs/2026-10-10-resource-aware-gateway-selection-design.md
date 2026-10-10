<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# Resource Aware Gateway Model Selection

## Goal

Change only automatic model selection so the Gateway ranks available deployed
models using current official Scheduler resource snapshots. NVIDIA Proxy keeps
node placement, pending-work balancing, GPU-pressure balancing, reservations,
and same-model failover. No task classification, cross-model retry, Broker,
Scheduler, or telemetry changes are included. Explicit local model routing and
Cloud Provider routing retain their current behavior.

## Data flow

For each enabled engine, Gateway inventory retains one candidate per advertised
`(engine, model, node)` deployment. Each candidate receives the node ID and
whether that model is loaded there. Gateway obtains an immutable value snapshot
of Proxy's per-node pending and GPU-pressure maps through a method guarded by
`priorityMu`; it never reads those maps directly. The snapshot carries its
generation and receipt time. A bounded freshness window makes delayed snapshots
unknown rather than falsely idle.

Resource fields stay semantically separate: scheduler `GPUPressure` affects a
GPU-pressure score only and is never interpreted as memory pressure or free
memory. Missing or expired pending/pressure signals use the selector's neutral
unknown score. They are not synthesized as zero. Per-node scores are never
averaged together.

The selector first applies existing availability, compatibility, and capability
filters. It scores each deployment, then compares models using their strongest
eligible deployment's resource/quality score. The selected model's deployment
node is advisory metadata only; dispatch continues to pass the model to the
existing NVIDIA Proxy facade, whose own scheduler snapshot, local reservations,
and retry logic choose and fail over between that model's owners.

`auto-fast` and `auto-balanced` give fresh pending and GPU pressure a strong
influence so a smaller model on a less loaded node can beat a larger model on a
busy node. `auto-best` preserves quality/context as the dominant factors and
uses resources only as a tie-breaking influence. Existing aliases and
deterministic tie breaks remain.

## Boundaries and edge cases

- Same model on multiple nodes remains multiple runtime candidates.
- A model score uses the best eligible node for that model; there is no
  cross-node average.
- Each request reads a fresh locked copy of current Proxy state, so later
  scheduler snapshots affect subsequent model rankings.
- The snapshot accessor copies maps while holding a read lock; callers cannot
  mutate shared Proxy state.
- Unknown or stale resource data does not receive a zero-load advantage.
- GPU pressure is distinct from memory pressure and memory capacity checks.
- Explicit local IDs and provider IDs continue through their current resolver
  paths without invoking automatic scoring.
- No reservation is created during model selection. Resource state can change
  between selection and dispatch; this is an optimization, not a guarantee.

## Validation scope

Regression coverage will include: the 32B busy PC-A versus 8B on busy PC-A and
idle PC-B versus 4B low-pressure Android acceptance case; same-model multi-node
best-node aggregation; changed snapshots changing subsequent selections;
unknown and expired resource values; GPU-pressure semantic separation; safe
concurrent snapshot reads and writes; explicit local and cloud routing; and
unchanged official same-model failover behavior.
