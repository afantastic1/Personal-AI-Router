<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# PAIR Multi-Cluster Membership Design

**Status:** Approved conversational design; awaiting written-spec review.
**Date:** 2026-10-08

## 1. Goal

Allow one PAIR device to be a member of multiple independent clusters at the
same time. Either side can start the process: a device can request admission to
a cluster hosted by another device, or invite another device into one of its own
clusters. Existing memberships remain intact when a device joins or leaves a
different cluster.

Membership, discovery, authorization, and workload exchange must remain scoped
to the specific cluster. A device's participation in two clusters must not
merge their rosters or permit one cluster to enumerate the other cluster's
members.

## 2. Current Constraint

The current Cluster Manager has one cluster identity, one roster, one active
admission, and a trusted-node record that carries one cluster admission. Its
pairing protocol refuses an inbound pairing when the target already has a
cluster. Android and desktop project one cluster identity into their settings
views, and the Workload Manager derives one peer set from that roster. These
are linked backend and wire-contract constraints; changing only the Android
button state would leave admission and data-plane authorization incorrect.

The current Cluster Manager specification describes the single-cluster rule as
intentional. This design supersedes that rule for the implementation described
here.

## 3. Product Behavior

### 3.1 Device identity and membership

- A physical PAIR installation keeps one stable `nodeUuid`, node name, keypair,
  and self-signed device certificate across all clusters.
- A membership is identified by `(clusterId, nodeUuid)`. A device may have one
  membership in each of several clusters, but cannot be duplicated within the
  same cluster.
- Each membership has its own cluster name, admission epoch, roster entry,
  invite lifecycle, and permissions.
- Creating or joining a membership never replaces or silently leaves another
  membership. Leaving or removal revokes only the selected membership.

### 3.2 Invite and request flows

These are separate user actions with separate pending-state records.

**Request admission to another device's cluster:**

1. The requester selects a discovered device and submits a join request. The
   request carries the requester's claimed stable device identity and a
   short-lived request ID, but does not create trust or membership. The claim is
   untrusted until the approved PIN pairing confirms the same identity.
2. The receiving device shows the request to its user. If it belongs to several
   clusters, the user selects the cluster for this request, then approves or
   rejects it.
3. Approval starts the existing inviter-side EAP-NOOB pairing for the selected
   cluster. The receiver displays the six-digit PIN; the requester enters it
   and accepts. Only successful PIN confirmation commits trust and membership.
4. Rejection, cancellation, timeout, or incorrect PIN leaves all existing
   memberships unchanged and creates no new trust grant.

**Invite another device into one of this device's clusters:**

1. The inviter selects one of its memberships and a discovered device.
2. The invitee accepts by entering the inviter's six-digit PIN.
3. Successful pairing adds the invitee to the selected cluster while preserving
   every other membership on both devices.

An unclustered device can create a cluster and invite as it can today. A device
that already has memberships must explicitly choose the target cluster. A
peer's membership in another cluster is not a reason to block either flow.

### 3.3 User interface

- Desktop and Android Cluster views show the local membership list and a
  selected-cluster context for roster, invite, leave, and removal actions.
- Both clients show incoming join requests separately from pairing invites.
- Approving a request selects one of the receiving device's memberships before
  starting the normal PIN pairing flow.
- An available peer can be added to the selected cluster even when it belongs
  to other clusters. Duplicate membership in the selected cluster is rejected
  with a clear state.
- Cluster lists, member rosters, and request details are returned by backend
  contracts. Neither UI implements pairing, cryptography, or authorization.

## 4. Architecture and Data Model

### 4.1 Service ownership

`nvpair-cluster-manager` remains the authority for device identity, membership,
pairing, membership requests, and cluster-scoped authorization. No new worker
or frontend-owned security state is introduced. The broker relays typed
cluster-scoped commands and state notifications. Desktop, Android, and TUI
consumers are updated wherever they expose the affected cluster contract.

The Cluster Manager persists a collection of memberships keyed by cluster ID.
Each membership owns its friendly name, local admission epoch, members, pending
invites, and removal proofs. Join requests are held in a device-level inbox
until the receiving user selects a cluster to approve into; the resulting
outbound invite is then scoped to that membership. Membership state changes
identify the cluster explicitly. A single selected-cluster value may remain as
UI preference only; it is never an authorization source.

### 4.2 Device authentication versus cluster authorization

The device certificate authenticates a stable node identity, not blanket access
to every cluster on that device. The backend keeps the device certificate
identity separate from membership grants. Every cluster-scoped inter-node
operation must authenticate the certificate and then authorize the exact
`(clusterId, nodeUuid, admissionEpoch)` membership. A valid certificate alone
does not grant access to another cluster.

Trust data may be shared for the same physical node identity, but a membership
removal revokes only that cluster's grant. Retain the public certificate pin
while another membership still needs it; retire it only when no active
membership references the identity. Pairing continues to use the user's
out-of-band PIN confirmation before granting a new membership.

Admission counters and removal/endorsement proofs are scoped to the membership
they protect. Rejoining one cluster creates a new admission for that cluster
without changing another cluster's epoch. A proof for cluster A must never
remove or authorize membership in cluster B.

### 4.3 Discovery and workload isolation

Discovery continues to identify devices and reachable endpoints. It does not
publish the complete list of a device's cluster memberships to unpaired LAN
peers. The current singular `clusterUuid` advertisement is removed because it
cannot represent membership correctly and would tempt consumers to treat one
publicly advertised value as the device's active security scope. Membership
selection and request approval are exchanged through the explicit cluster
protocol. Discovery and node telemetry are never admission authority.

The Broker, node scanner, scheduler, and inter-node services consume
per-cluster membership snapshots. Cluster-scoped node inventory, model
availability, remote engine control, error sync, workload events, and
inference routing are exposed only within the requesting membership. A
workload, inventory, or cluster event is delivered only to members of its
originating cluster. A device that belongs to clusters A and B may participate
in both independently; it does not relay A's topology or payloads into B.
Removing it from A updates A's peer set without disconnecting its B membership.

The shared cluster-trust package authenticates the device certificate, then
resolves authorization against the cluster ID on each scoped operation. Client
pooling and pin lookup are keyed by device identity; admission checks and peer
sets are keyed by cluster membership. Every inter-node service that currently
uses `clustertrust` must use the same cluster-scoped decision rather than
assuming that a globally pinned certificate is a member of every group.

Cluster membership does not itself grant paid cloud-provider authorization.
Existing explicit cloud provider and node authorization controls remain
separate.

## 5. Contracts and Lifecycle

- Cluster operations that inspect or mutate one membership carry `clusterId`.
  Operations that enumerate local memberships return a stable list.
- Add typed operations for submitting, listing, approving, rejecting, and
  canceling join requests. Approval identifies the target `clusterId` and starts
  the regular outbound invitation flow.
- Member, invite, and request notifications include `clusterId` and enough
  stable identity data for consumers to update only the matching cluster view.
- Removal endpoints, workload/event envelopes, roster reconciliation, and
  admission proofs carry the cluster scope. The receiving backend validates the
  scope against the authenticated node's membership.
- Replace the singular cluster marker in `shared/noderec`, mDNS records,
  `/v1/node-info`, scanner directory records, and UI node snapshots with
  identity/reachability data plus local, backend-derived per-cluster
  relationship state. Do not serialize the full membership list to unpaired
  LAN peers.
- Audit and update all current `shared/clustertrust` consumers: `nvpair-proxy`,
  `nvpair-engine-manager`, `nvpair-errors`, `nvpair-manual-nodes`,
  `nvpair-node-info`, `nvpair-node-scanner`, and `nvpair-workload-manager`.
  Update `nvpair-job-scheduler` and Broker peer/identity projection so their
  candidate sets are scoped to the selected/requesting cluster.
- Move durable membership authority out of the singular cluster-ID mirror in
  `nvpair-node-settings`; retain only a selected-cluster UI preference there if
  needed. Update Broker startup restore and identity-change handling so stale
  preference data cannot recreate a membership.
- Update Go producers, shared service contract generation, broker relay and
  state projection, Electron bridge/types/stores, Android RPC/models/repository,
  TUI if it consumes the changed methods, tests, and owned protocol docs in one
  contract change. Generated `desktop/docs/services-api.md` is regenerated by
  the existing contract tooling, never edited manually.
- Any unsupported old peer receives a clear protocol-version/feature
  incompatibility result. Do not silently fall back to the single-cluster
  behavior.

## 6. Persistence Migration

The existing single-cluster identity, roster, admission, trust pins, and
removal-proof state migrate to one membership with the exact existing cluster
ID and node UUIDs. Migration is idempotent and crash-safe: the source remains
recoverable until the new membership state is durably committed and validated.
Existing device keys and certificates are retained.

After migration, the Cluster Manager owns the authoritative membership set.
Any setting retained by `nvpair-node-settings` for the selected cluster is a UI
preference only; it cannot recreate membership or restore an authorization
grant. Startup must not resurrect a removed membership from a stale selected
cluster setting.

## 7. Failure and Security Requirements

- Requests from unpaired peers are advisory only. They cannot mutate membership
  or trust without explicit local approval and successful PIN pairing.
- Requests and invites have bounded lifetimes, cancellation, and clear terminal
  states. A pending request is deduplicated by `(receivingNodeUuid,
  claimedRequesterNodeUuid)` because the receiving user selects the target
  cluster only at approval time. A pending invite is deduplicated by
  `(clusterId, nodeUuid)`. A request from one peer must not replace a different
  peer's request.
- Wrong PIN, expired request, cancellation, service restart, or partial
  migration cannot leave one-sided membership or stale cross-cluster trust.
- State transitions re-check the target cluster and membership generation
  before commit so leave/removal racing with a handshake cannot resurrect
  membership.
- Logs and UI telemetry must not contain PINs, private keys, prompts, or model
  response bodies.
- Malformed, missing, unknown, or unauthorized cluster scopes fail closed.

## 8. Non-Goals

- Merging clusters, forwarding one cluster's roster into another, or creating a
  transitive trust bridge.
- Per-cluster device identities or separate certificates for one installation.
- Automatic approval or PIN-free membership.
- Changing cloud provider budgets, credentials, or explicit paid-node grants.
- Replacing the existing backend-owned pairing or cryptographic trust model
  with UI-side logic.

## 9. Acceptance Criteria

1. A device can hold at least two independent memberships concurrently and
   restart without losing or conflating either one.
2. A tablet can request admission to a selected PC-owned cluster; PC approval
   and successful PIN entry add only that membership.
3. A tablet can invite a PC already in another cluster into the tablet's
   selected cluster; accepting preserves the PC's original membership.
4. Leaving/removing a device from one cluster does not remove it from another.
5. Roster, discovery, node inventory, workload broadcasts, and authorization
   show no cross-cluster member or payload leakage, including through a device
   that belongs to both clusters.
6. Migration preserves an existing single-cluster installation's node identity,
   cluster ID, membership, and trust after restart.
7. Unsupported peer versions fail with an explicit error and do not mutate
   membership.
8. Focused service, broker cross-process, desktop contract, and Android tests
   cover the new flows, migration, authorization boundaries, and failure paths.

## 10. Implementation Boundaries

The implementation plan must be decomposed around the shared Go membership and
authorization model first, then cross-service data-plane propagation, then
contract consumers and user interfaces, and finally migration and end-to-end
verification. A task may not ship a UI affordance that the backend cannot
authorize or persist correctly.
