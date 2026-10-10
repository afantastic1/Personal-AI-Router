<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# Resource Aware Gateway Model Selection

## Goal

Change automatic model selection so the Gateway ranks available deployed
models using official Scheduler resource snapshots and current Proxy
reservations. NVIDIA Proxy keeps node placement, pending-work balancing,
GPU-pressure balancing, reservations, and same-model failover. No task
classification or cross-model retry is included. Explicit local model routing
and Cloud Provider routing retain their current behavior.

## Data flow

For each enabled engine, Gateway inventory retains one candidate per advertised
`(engine, model, node)` deployment. Each candidate receives the node ID and
whether that model is loaded there. Gateway obtains an immutable value snapshot
of Proxy's per-node pending, GPU-pressure freshness, and in-flight reservation
maps through a method guarded by `priorityMu`; it never reads those maps
directly. Scheduler's `gpuPressureKnown` flag applies the existing 10-second
telemetry freshness rule. Gateway does not age the event-driven rank snapshot
itself.

Resource fields stay semantically separate: scheduler `GPUPressure` affects a
GPU-pressure score only and is never interpreted as memory pressure or free
memory. Missing pending data and pressure marked stale by Scheduler use the
selector's neutral unknown score. They are not synthesized as zero. Current
Proxy reservations contribute to pending load even if the latest Scheduler
snapshot has no pending count. Per-node scores are never averaged together.

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
- A telemetry freshness transition changes `gpuPressureKnown`, so Scheduler
  emits it even when the neutral pressure stays numerically `1`.
- The snapshot accessor copies maps while holding a read lock; callers cannot
  mutate shared Proxy state.
- Unknown or stale resource data does not receive a zero-load advantage.
- GPU pressure is distinct from memory pressure and memory capacity checks.
- Explicit local IDs and provider IDs continue through their current resolver
  paths without invoking automatic scoring.
- No reservation is created during model selection. Resource state can change
  between selection and dispatch; this is an optimization, not a guarantee.
- The model-ranked node remains advisory. Proxy can choose another owner using
  newer pending work, reservations, or an explicit node selection.
- Runtime inventory currently has no TTFT, token-rate, or network-cost source;
  those score inputs retain their neutral defaults.

## Validation scope

Regression coverage will include: the 32B busy PC-A versus 8B on busy PC-A and
idle PC-B versus 4B low-pressure Android acceptance case; same-model multi-node
best-node aggregation; changed snapshots changing subsequent selections;
unknown values and stale pressure transitions; GPU-pressure semantic separation;
safe concurrent snapshot reads and writes; overlapping requests that shift
subsequent model choices; advisory model-ranked nodes versus Proxy's actual
node selection; explicit local and cloud routing; and unchanged official
same-model failover behavior.
