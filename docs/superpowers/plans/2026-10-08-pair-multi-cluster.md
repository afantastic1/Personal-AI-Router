<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# PAIR Multi-Cluster Membership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let one PAIR installation join and participate in multiple isolated clusters, with both cluster invitations and approval-based join requests.

**Architecture:** Keep one node UUID and certificate per installation. Move membership, admission, and peer authorization to records keyed by cluster and node; make every cluster-sensitive service request carry an explicit cluster context. The Cluster Manager remains the security authority, the Broker relays typed state, data-plane services enforce the same scope, and desktop, TUI, and Android remain clients.

**Tech Stack:** Go services and JSON-RPC 2.0; shared Go packages; Electron/React/TypeScript; Bubble Tea TUI; Kotlin/Jetpack Compose Android; existing Gradle, npm, and Go test tooling.

**Spec:** `docs/superpowers/specs/2026-10-08-pair-multi-cluster-design.md`

## Global Constraints

- One installation keeps one stable `nodeUuid`, keypair, and certificate.
- Membership identity is `(clusterId, nodeUuid)`; a node may hold multiple memberships concurrently.
- Membership, admission epochs, member lists, removal proofs, and workload distribution are scoped to one cluster.
- A valid device certificate does not authorize a node in every cluster; backends validate the requested cluster membership.
- Join requests require local approval and successful PIN pairing before membership or trust is committed.
- Never publish the complete membership list to unpaired LAN peers.
- Go services own membership, pairing, cryptography, and authorization; clients only relay commands and render state.
- Do not add a runtime worker. Do not change cloud budgets, credentials, or paid-node authorization.
- Migrate an existing single-cluster install without changing its IDs or keypair; do not fall back to old single-cluster behavior after migration.
- Every changed JSON-RPC producer/payload must be propagated through the broker, Go consumers, desktop bridge/types, Android callers, TUI callers, docs, and generated service-contract checks.
- Preserve repository rules: two-line SPDX headers on new files, no casts or `any` in TypeScript, no renderer imports from Electron, and signed commits (`git commit -s`).

## Review Focus

- A peer already in another cluster must be addable to the selected cluster while both prior memberships remain: cover in Tasks 2 and 3.
- A shared device must not leak one cluster's roster, node inventory, or workload to the other: cover in Tasks 3–5 and 9.
- Untrusted join requests must not create trust/membership or spoof the identity confirmed by PIN pairing: cover in Task 2.
- Leave/removal racing with pairing or restart must not resurrect only part of a membership: cover in Tasks 1–3 and 9.
- Legacy migration must preserve the existing identity and must not restore a deliberately removed membership from stale settings: cover in Tasks 1 and 9.

---

### Task 1: Durable Multi-Membership Model and Legacy Migration

**Files:**
- Modify: `services/nvpair-cluster-manager/manager.go`
- Modify: `services/nvpair-cluster-manager/membership.go`
- Modify: `services/nvpair-cluster-manager/membership_store.go`
- Modify: `services/nvpair-cluster-manager/admission_store.go`
- Modify: `services/nvpair-cluster-manager/truststore.go`
- Modify: `services/nvpair-cluster-manager/removal_proof_store.go`
- Create: `services/nvpair-cluster-manager/memberships_store_test.go`
- Create: `services/nvpair-cluster-manager/join_request_store.go`
- Create: `services/nvpair-cluster-manager/join_request_store_test.go`
- Modify: `services/nvpair-cluster-manager/admission_migration_test.go`
- Modify: `services/nvpair-cluster-manager/spec.md`

**Interfaces:**
- Add the persisted membership record `Membership{ClusterID, FriendlyName, LocalAdmissionEpoch, Members, Invites, RemovalProofs}` and key it by `ClusterID`.
- Persist unassigned inbound join requests in a device-level inbox, deduplicated by `(receivingNodeUuid, claimedRequesterNodeUuid)` until approval selects a cluster.
- Replace manager-wide `clusterId`, single roster, single active admission, and node-only trust membership with a membership collection keyed by `clusterId` and peer grants keyed by `(clusterId, nodeUuid)`.
- Keep the existing device identity and certificate store node-wide.
- Preserve the old persisted cluster ID as a one-time migration input; after a durable migration marker is committed, settings cannot recreate membership.

- [ ] **Step 1: Write failing store and migration tests**

Add tests proving: two memberships survive write/reopen; their member/admission/removal state stays separate; legacy single-cluster files import to exactly one membership retaining IDs and key material; repeating migration is a no-op; stale legacy settings cannot restore a removed membership; and a partial write leaves the legacy source recoverable.

- [ ] **Step 2: Run the focused tests and confirm they fail**

Run: `go test ./... -run 'TestMembershipStore|TestLegacyMembershipMigration' -count=1` from `services/nvpair-cluster-manager`.
Expected: the new tests fail because the durable store and idempotent migration are not implemented.

- [ ] **Step 3: Implement the membership store and migration**

Use atomic writes and restrictive file permissions consistent with the existing stores. Keep device identity persistence separate. Store per-membership epochs and removal state without resetting another cluster's generation.

- [ ] **Step 4: Run focused tests**

Run: `go test ./... -run 'TestMembershipStore|TestLegacyMembershipMigration|TestAdmission' -count=1` from `services/nvpair-cluster-manager`.
Expected: PASS, with both migrated and natively-created memberships reopening correctly.

- [ ] **Step 5: Commit**

```bash
git add services/nvpair-cluster-manager
git commit -s -m "feat(cluster): persist multi-cluster memberships"
```

### Task 2: Cluster-Scoped Pairing, Invites, and Join Requests

**Files:**
- Modify: `services/nvpair-cluster-manager/invite.go`
- Modify: `services/nvpair-cluster-manager/respond.go`
- Modify: `services/nvpair-cluster-manager/httpserver.go`
- Modify: `services/nvpair-cluster-manager/leave.go`
- Modify: `services/nvpair-cluster-manager/remove.go`
- Modify: `services/nvpair-cluster-manager/roster.go`
- Modify: `services/nvpair-cluster-manager/rpc_identity.go`
- Modify: `services/nvpair-cluster-manager/ipc.go`
- Modify: `services/nvpair-cluster-manager/README.md`
- Modify: `services/nvpair-cluster-manager/spec.md`
- Modify: `services/tests/cluster_manager_test.go`
- Modify: `services/tests/cluster_data_plane_test.go`

**Interfaces:**
- `cluster:list-memberships({}) -> {memberships: MembershipSummary[]}` where each summary has `clusterId`, `clusterFriendlyName`, and `state`.
- `cluster:request-join({address, port?, nodeId?}) -> JoinRequestReceipt`; it POSTs to `POST /v1/cluster/join-requests` with `{requestId, requesterNodeUuid, requesterNodeId, requesterName}`. Those identity fields are claims until PIN pairing confirms them. Cancellation sends `DELETE /v1/cluster/join-requests/{requestId}`.
- `cluster:list-join-requests({}) -> {requests: JoinRequest[]}`; each request has `requestId`, claimed requester ID/UUID/name, observed source address, `createdAt`, `expiresAt`, `state`, and optional selected `clusterId`.
- `cluster:approve-join-request({requestId, clusterId}) -> Invite`; `cluster:reject-join-request({requestId}) -> JoinRequest`; `cluster:cancel-join-request({requestId}) -> JoinRequest`. Approval starts the existing inviter-side PIN pairing for exactly that cluster.
- `nodes:get-initial({clusterId}) -> {nodes: ClusterNode[]}`; `cluster:invite-node({clusterId, address, port?, nodeId?}) -> Invite`; `cluster:leave({clusterId}) -> MembershipSummary`; and `nodes:remove({clusterId, nodeId}) -> RemovalResult`.
- Include `clusterId` in membership, invite, join-request, and removal pushes. `cluster:get-node-id` returns device identity only; replace scalar `cluster:identity-changed` with a membership-list change notification.
- Approving an inbound PIN invite adds a membership without changing existing memberships. A target's other clusters do not trigger `already-clustered` rejection.

- [ ] **Step 1: Add failing JSON-RPC and cross-process flow tests**

Add tests named `TestClusterManager_InviteAlreadyClusteredPeerAddsSecondMembership`, `TestClusterManager_RequestJoinRequiresApprovalAndAddsOnlySelectedMembership`, `TestClusterManager_JoinRequestIdentityMustMatchPINPeer`, `TestClusterManager_RejectJoinRequestCreatesNoMembership`, and `TestClusterManager_LeaveOneMembershipPreservesAnother`. Assert the membership list and each roster after each transition, not only invite state.

- [ ] **Step 2: Run those tests and confirm they fail**

Run: `go test ./... -run 'TestClusterManager_(InviteAlreadyClusteredPeerAddsSecondMembership|RequestJoinRequiresApprovalAndAddsOnlySelectedMembership|JoinRequestIdentityMustMatchPINPeer|RejectJoinRequestCreatesNoMembership|LeaveOneMembershipPreservesAnother)' -count=1` from `services/tests`.
Expected: FAIL because the scoped RPCs and multi-membership transitions are absent.

- [ ] **Step 3: Implement manager state transitions and request lifecycle**

Requests from unpaired peers are advisory. Bind a request to its claimed node UUID and request ID; after approval, require the EAP-NOOB peer identity to match that claim. Apply source-address rate limits, bounded expiry using the existing pairing invite TTL, cancellation, and duplicate suppression by receiving-node/requester-node until approval selects a cluster. Keep invitation deduplication scoped to `(clusterId, nodeUuid)`. Re-check membership generation before committing pairing/removal.

- [ ] **Step 4: Run focused service and cross-process coverage**

Run: `go test ./... -run 'TestClusterManager_(InviteAlreadyClusteredPeerAddsSecondMembership|RequestJoinRequiresApprovalAndAddsOnlySelectedMembership|JoinRequestIdentityMustMatchPINPeer|RejectJoinRequestCreatesNoMembership|LeaveOneMembershipPreservesAnother)' -count=1` from `services/tests`, then `go test ./...` from `services/nvpair-cluster-manager`.
Expected: PASS; declined, wrong-PIN, expired, duplicate, and concurrent leave paths do not add or resurrect any membership.

- [ ] **Step 5: Commit**

```bash
git add services/nvpair-cluster-manager services/tests
git commit -s -m "feat(cluster): add scoped invites and join requests"
```

### Task 3: Membership-Scoped Cluster Trust

**Files:**
- Modify: `services/shared/clustertrust/clustertrust.go`
- Modify: `services/shared/clustertrust/membership.go`
- Modify: `services/shared/clustertrust/mesh.go`
- Modify: `services/shared/clustertrust/peerclient.go`
- Create: `services/shared/clustertrust/multimembership_test.go`
- Modify: `services/shared/clustertrust/clustertrust_test.go`
- Modify: `services/shared/clustertrust/membership_test.go`
- Modify: `services/shared/clustertrust/peerclient_test.go`
- Modify: `services/nvpair-cluster-manager/mtls.go`
- Modify: `services/nvpair-cluster-manager/truststore.go`
- Modify: `services/nvpair-cluster-manager/endorsement.go`

**Interfaces:**
- Add a cluster-scoped authorization lookup taking `clusterId` and authenticated peer UUID; no call site may use a globally pinned certificate as sufficient membership proof.
- Scope peer-client acquisition and cache invalidation by cluster membership while reusing the one node certificate.
- Scope admission epochs, removal tombstones, and endorsements to the exact membership they protect.

- [ ] **Step 1: Add failing isolation and shared-certificate tests**

Add `TestMesh_AuthorizesSamePeerOnlyInGrantedClusters`, `TestMesh_RemovalRevokesOnlySelectedCluster`, and `TestPeerClientPool_RequiresMembershipScope`. Use one certificate pinned for two memberships, remove one membership, and assert that the other remains usable while the removed scope is rejected.

- [ ] **Step 2: Run focused tests and confirm they fail**

Run: `go test ./... -run 'TestMesh_AuthorizesSamePeerOnlyInGrantedClusters|TestMesh_RemovalRevokesOnlySelectedCluster|TestPeerClientPool_RequiresMembershipScope' -count=1` from `services/shared/clustertrust`.
Expected: FAIL because trust and admission lookups are currently node-wide/single-admission.

- [ ] **Step 3: Implement scoped authorization and peer clients**

Separate certificate identity validation from per-cluster membership authorization. Retain a certificate pin while any membership references it, but reject every operation lacking the exact cluster grant.

- [ ] **Step 4: Run clustertrust and cluster-manager tests**

Run: `go test ./...` from `services/shared/clustertrust` and `services/nvpair-cluster-manager`.
Expected: PASS, including the existing TLS pinning, rotation, revocation, and removal-proof tests.

- [ ] **Step 5: Commit**

```bash
git add services/shared/clustertrust services/nvpair-cluster-manager
git commit -s -m "feat(cluster): scope trust grants by membership"
```

### Task 4: Cluster Context Through Discovery, Settings, and Broker

**Files:**
- Modify: `services/shared/noderec/noderec.go`
- Modify: `services/nvpair-node-info/main.go`
- Modify: `services/nvpair-node-scanner/registry.go`
- Modify: `services/nvpair-node-scanner/directory.go`
- Modify: `services/nvpair-node-scanner/daemon.go`
- Modify: `services/nvpair-node-scanner/scanner.go`
- Modify: `services/nvpair-manual-nodes/manager.go`
- Modify: `services/nvpair-node-settings/manager.go`
- Modify: `services/nvpair-ui-broker/broker.go`
- Modify: `services/nvpair-ui-broker/clustermanager.go`
- Modify: `services/nvpair-ui-broker/nodeinfo.go`
- Modify: `services/tests/cluster_restore_test.go`
- Modify: `services/tests/broker_supervision_test.go`

**Interfaces:**
- Remove the singular public `clusterUuid` from mDNS and node-info identity records; retain stable device UUID/name and endpoint data.
- Keep cluster relationship state in backend membership snapshots scoped by `clusterId`, not in unpaired discovery metadata.
- Replace Broker restoration of a singular cluster identity with membership enumeration and a selected-cluster UI preference that cannot create membership.
- The Broker performs the one-time legacy import even when no legacy cluster ID exists, so an empty first-run marker prevents a stale setting from resurrecting a later-removed membership.
- Keep mTLS and membership authorization in backend services. Existing public device telemetry remains display-only and carries no membership list or admission claim.

- [ ] **Step 1: Add failing discovery, telemetry, and restart tests**

Cover: public identity records contain no membership list or singular cluster claim; local peer relationship views are derived from a selected membership; restart does not restore a removed membership from stale `cluster_id` settings.

- [ ] **Step 2: Run focused tests and confirm they fail**

Run the new tests in `services/shared/noderec`, `services/nvpair-node-info`, `services/nvpair-node-scanner`, `services/nvpair-ui-broker`, and `services/tests`.
Expected: FAIL because the current record, node-info payload, and broker restore path use one `clusterUuid`/`clusterId`.

- [ ] **Step 3: Implement the identity and Broker propagation changes**

Keep mDNS as identity/reachability discovery only. Ensure cluster manager snapshots, not public node records, supply selected-cluster relationship data. Retire the legacy settings value only after the one-time migration marker is durable.

- [ ] **Step 4: Run affected module tests**

Run: `go test ./...` from `services/shared/noderec`, `services/nvpair-node-info`, `services/nvpair-node-scanner`, `services/nvpair-manual-nodes`, `services/nvpair-node-settings`, `services/nvpair-ui-broker`, and `services/tests`.
Expected: PASS, including existing multi-address discovery and identity refresh behavior.

- [ ] **Step 5: Commit**

```bash
git add services/shared/noderec services/nvpair-node-info services/nvpair-node-scanner services/nvpair-manual-nodes services/nvpair-node-settings services/nvpair-ui-broker services/tests
git commit -s -m "feat(cluster): propagate membership context through broker"
```

### Task 5: Enforce Cluster Scope Across Data-Plane Services

**Files:**
- Modify: `services/nvpair-proxy/proxy.go`
- Modify: `services/nvpair-engine-manager/controlserver.go`
- Modify: `services/nvpair-engine-manager/httpserver.go`
- Modify: `services/nvpair-errors/httpserver.go`
- Modify: `services/nvpair-errors/peersync.go`
- Modify: `services/nvpair-workload-manager/broadcast.go`
- Modify: `services/nvpair-workload-manager/server.go`
- Modify: `services/nvpair-job-scheduler/manager.go`
- Modify: `services/nvpair-job-scheduler/schedule.go`
- Modify: `services/nvpair-job-scheduler/state.go`
- Modify: `services/nvpair-job-scheduler/transport.go`
- Modify: `services/tests/cluster_data_plane_test.go`
- Modify: `services/tests/secure_inference_test.go`
- Modify: `services/tests/workload_interop_test.go`

**Interfaces:**
- Every cluster-scoped remote request and event carries `clusterId` and is checked against the authenticated node's matching membership.
- Peer selection, pinned-client resolution, node inventory, engine controls, error synchronization, workload broadcasts, and inference candidates are computed for one cluster context.
- A node in A and B may independently serve or consume within both, but no roster or event is relayed from A to B.

- [ ] **Step 1: Add failing data-plane isolation tests**

Add cross-process cases with clusters A and B and one shared node. Assert A's peer receives A inventory/events only; B's peer receives B inventory/events only; a node removed from A loses A access immediately but retains B access; requests with a valid cert and wrong `clusterId` are rejected.

- [ ] **Step 2: Run the new tests and confirm they fail**

Run: `go test ./... -run 'TestClusterDataPlane_(IsolatesTwoClusters|RemovalIsMembershipScoped|RejectsWrongClusterContext)' -count=1` from `services/tests`.
Expected: FAIL because data-plane consumers currently use a shared node-only pin/roster view.

- [ ] **Step 3: Update each consumer to use the scoped trust API**

Trace each request/event from cluster ID through Broker, shared transport, receiver authorization, scheduler target selection, and response. Do not infer scope from the globally shared certificate or current UI selection.

- [ ] **Step 4: Run focused module and cross-process coverage**

Run `go test ./...` from `services/nvpair-proxy`, `services/nvpair-engine-manager`, `services/nvpair-errors`, `services/nvpair-workload-manager`, `services/nvpair-job-scheduler`, then run `go test ./...` from `services/tests`.
Expected: PASS for the isolation tests and existing secure-inference/workload interop tests.

- [ ] **Step 5: Commit**

```bash
git add services/nvpair-proxy services/nvpair-engine-manager services/nvpair-errors services/nvpair-workload-manager services/nvpair-job-scheduler services/tests
git commit -s -m "feat(cluster): isolate data plane by membership"
```

### Task 6: Broker, Desktop Contract, and Generated Service API

**Files:**
- Modify: `services/nvpair-ui-broker/broker.go`
- Modify: `services/nvpair-ui-broker/clustermanager.go`
- Modify: `desktop/src/shared/types/cluster.ts`
- Modify: `desktop/src/shared/types/ws-channels.ts`
- Modify: `desktop/src/ui/api/pair-api.ts`
- Modify: `desktop/src/electron/service-bridge/cluster-json.ts`
- Modify: `desktop/src/electron/service-bridge/empty-handlers.ts`
- Modify: `desktop/src/electron/service-bridge/modular-state.ts`
- Modify: `desktop/src/electron/service-bridge/modular-supervisor.ts`
- Modify: `desktop/src/ui/stores/cluster-invitations.store.ts`
- Regenerate: `desktop/docs/services-api.md` with the contract tool only

**Interfaces:**
- Expose typed membership enumeration/selection, cluster-scoped roster operations, and join-request operations through the existing broker and `window.pairApi` path.
- Maintain separate pending invite and join-request collections, each keyed by stable request ID and cluster context.
- Consume and redact pairing secrets using the existing established secret-handling paths.

- [ ] **Step 1: Add failing contract parser and bridge/store tests**

Test membership arrays, missing/unknown cluster IDs, multiple pending requests, cluster-scoped `nodes:changed`, join-request pushes, and log redaction. Keep JSON-RPC payload assertions exact.

- [ ] **Step 2: Run focused desktop tests and confirm they fail**

Run the new targeted Vitest files with `npm run test:unit -- <test-file>` from `desktop`.
Expected: FAIL because the bridge currently models one identity/roster and only invite pushes.

- [ ] **Step 3: Implement typed bridge and Broker relay**

Use the generated Go contract surface; keep service invocation and state ownership behind the broker/preload bridge. Do not import Electron modules from renderer code or use casts/`any`.

- [ ] **Step 4: Regenerate and verify contracts**

Run: `npm run service-contracts:write`, `npm run service-contracts:check`, and the targeted unit tests from `desktop`.
Expected: generated API contains the new methods/payloads and the contract checker reports no drift.

- [ ] **Step 5: Commit**

```bash
git add services/nvpair-ui-broker desktop/src desktop/docs/services-api.md
git commit -s -m "feat(desktop): relay multi-cluster state and requests"
```

### Task 7: Desktop and TUI Membership Workflows

**Files:**
- Modify: `desktop/src/ui/components/ClusterSettings/ClusterSettings.tsx`
- Modify: `desktop/src/ui/components/ClusterSettings/AvailableNode.tsx`
- Modify: `desktop/src/ui/components/ClusterSettings/PendingInviteCard.tsx`
- Create: `desktop/src/ui/components/ClusterSettings/PendingJoinRequestCard.tsx`
- Modify: `desktop/src/ui/components/InviteApprovalModal.tsx`
- Modify: `desktop/src/ui/hooks/useInvitePairing.ts`
- Modify: `services/nvpair-tui/ui/cluster.go`
- Modify: `services/nvpair-tui/ui/invite.go`
- Modify: `services/nvpair-tui/ui/nodes.go`
- Create or modify focused tests under the existing desktop unit-test project and TUI test package

**Interfaces:**
- Render membership list and selected cluster; scope roster, invite, leave, and remove actions to it.
- Render join requests separately; approval selects a local membership, then starts the existing PIN invite flow.
- The TUI consumes the same backend contracts and maintains no independent membership authority.

- [ ] **Step 1: Add failing UI tests**

Cover switching the selected cluster, adding a device already in another cluster, refusing duplicate membership in the selected cluster, approving into an explicitly chosen membership, request rejection, PIN redaction, and empty/loading/error states. Add TUI reducer/model tests for selecting a membership and handling a join request.

- [ ] **Step 2: Run targeted desktop and TUI tests and confirm they fail**

Run the targeted Vitest files from `desktop`; run `go test ./...` from `services/nvpair-tui`.
Expected: FAIL because the current views assume one identity/roster and no join-request collection.

- [ ] **Step 3: Implement the desktop and TUI flows**

Use the repository's existing component/state patterns. Ensure every action carries the selected `clusterId`; never infer it from a remote peer's public discovery record.

- [ ] **Step 4: Run focused UI checks**

Run targeted Vitest files, `npm run typecheck`, `npm run lint`, and `go test ./...` from `services/nvpair-tui`.
Expected: PASS with no casts, `any`, dead exports, or renderer-to-Electron imports.

- [ ] **Step 5: Commit**

```bash
git add desktop/src/ui services/nvpair-tui
git commit -s -m "feat(cluster): add desktop and TUI multi-cluster flows"
```

### Task 8: Android Membership Workflows

**Files:**
- Modify: `android/app/src/main/java/com/nv/pair/data/ClusterModels.kt`
- Modify: `android/app/src/main/java/com/nv/pair/data/ClusterRepository.kt`
- Modify: `android/app/src/main/java/com/nv/pair/data/PeerRelationshipResolver.kt`
- Modify: `android/app/src/main/java/com/nv/pair/rpc/ClusterApi.kt`
- Modify: `android/app/src/main/java/com/nv/pair/runtime/PairRuntimeService.kt`
- Modify: `android/app/src/main/java/com/nv/pair/ClusterManagement.kt`
- Modify: `android/app/src/main/java/com/nv/pair/MainActivity.kt`
- Create: focused cluster repository/resolver/UI tests under `android/app/src/test`
- Modify: `android/app/src/androidTest/java/com/nv/pair/BrokerSessionSmokeInstrumentedTest.kt`

**Interfaces:**
- Model a list of memberships and selected-cluster state instead of a scalar `ClusterIdentity.clusterId`.
- Add typed Android RPC calls and push handling for requests, membership lists, and cluster-scoped roster changes.
- Show request-to-join, join-request approval state, selected cluster, and cross-cluster-safe member actions.

- [ ] **Step 1: Add failing resolver, repository, and UI tests**

Assert that one discovered peer can have independent relationships in clusters A and B; joining/leaving A preserves B; a peer already in another cluster remains actionable; pending requests and invites stay distinct; wrong/missing cluster IDs fail closed.

- [ ] **Step 2: Run focused Android tests and confirm they fail**

Run: `gradlew.bat :app:testDebugUnitTest` from `android`.
Expected: FAIL on missing multi-membership models and scoped relationship behavior.

- [ ] **Step 3: Implement Android repository, RPC, and Compose state**

Keep all pairing/trust decisions in the backend. Render membership selection and approval state from repository flows; send each selected cluster ID explicitly.

- [ ] **Step 4: Run Android unit and instrumentation checks**

Run: `gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest` from `android` on the connected tablet.
Expected: PASS, including broker session contract coverage and on-device membership switching.

- [ ] **Step 5: Commit**

```bash
git add android/app/src
git commit -s -m "feat(android): support multi-cluster membership"
```

### Task 9: Full Contract Audit, Migration Acceptance, and Documentation

**Files:**
- Modify: `services/nvpair-cluster-manager/README.md`
- Modify: `services/nvpair-cluster-manager/spec.md`
- Modify: `services/readme.md` or other owned architecture docs where the single-cluster contract is stated
- Modify: `docs/architecture.mdx` and `docs/developing.mdx` where membership lifecycle is described
- Modify: cross-process tests in `services/tests/`
- Regenerate: `desktop/docs/services-api.md` only through the contract generator

- [ ] **Step 1: Add end-to-end migration and isolation acceptance tests**

Test a legacy single-cluster disk image through upgrade/restart, create/join a second cluster, then leave/remove one membership and restart again. Include two isolated clusters with one shared physical node and prove that roster, node-info, model inventory, remote control, error sync, workload delivery, and inference routing do not cross cluster boundaries.

- [ ] **Step 2: Run the complete affected Go and desktop checks**

Run `go test ./...` from each affected service module and from `services/tests`; from `desktop`, run `npm run service-contracts:check`, `npm run typecheck`, `npm run lint`, `npm run test:unit`, and `npm run dead-code:check`.
Expected: all checks pass; skips are reported as untested, not counted as passing.

- [ ] **Step 3: Run Android acceptance checks**

Run `gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest` from `android`, then perform the tablet-to-PC request/approval/PIN flow and the tablet invitation of a PC already in another cluster.
Expected: both physical workflows succeed and the two devices retain all pre-existing memberships.

- [ ] **Step 4: Update owned docs and review release impact**

Remove remaining claims that a device must leave its cluster before joining another. Document membership-scoped authorization, request approval, migration, and mixed-version incompatibility. Declare every changed service binary and user-visible change in the PR `pair-release-intent:v1` block; do not hand-edit `services/versions.json` or `CHANGELOG.md`.

- [ ] **Step 5: Run final repository checks and commit documentation**

Run `node scripts/spdx-headers.mjs`, `git diff --check`, and the affected contract/test commands above. Commit only owned source/docs with `git commit -s`.
Expected: no SPDX or whitespace failures; untracked user-provided plans and `third_party/` remain untouched.

## Execution Handoff

This is a single cross-service feature with a strict dependency chain: the
membership and authorization contracts must stabilize before data-plane and UI
consumers can ship. Recommended execution is native/in-line because the tasks
share evolving interfaces and must land in order. Each task ends with its own
verification and signed commit, then the entire change receives a cross-layer
review.
