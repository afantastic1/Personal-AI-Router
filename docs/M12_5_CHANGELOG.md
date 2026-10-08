<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# M12.5 Engineering Changes

This report records the changes implemented in this workspace. It does not represent a merged or released change.

## Runtime and pairing

- Stopping PAIR now preserves durable cluster membership. Leaving remains an explicit cluster action.
- Peer cards derive their status from discovery, stable node identity, membership, trust and pending invites. Paired offline members remain visible, duplicate discovery identities are marked inconsistent, and unknown/foreign peers cannot be invited.
- The runtime validates invite eligibility again, prevents concurrent duplicate requests for a peer, and addresses peers by stable UUID.
- The cluster manager rejects already-paired targets and returns a typed `invite-in-progress` error for duplicate pending requests, without issuing another PIN. Browser identity lookup now resolves mDNS UUID and cluster metadata.
- Cluster snapshots refresh after invitation terminal states and explicit membership actions. JSON-RPC structured error data is retained by the Android client.

## Model Hub

- ModelScope search uses its OpenAPI owner filter and snake-case pagination, and no longer fetches every search result's file tree. Inspection validates branch/tag metadata, then pins the selected ref through the provider's Git smart-HTTP advertisement before reading files and config. File-tree `Revision` values are per-file last-change commits and are not treated as one repository snapshot.
- Hugging Face search is restricted to `taobao-mnn`; repository detail and checksum inspection happen after explicit Inspect.
- Provider DTOs now describe provider IDs, model summaries/details, artifacts, pages and typed HTTP/network/response failures. Hugging Face paging follows its HTTP `Link` next relation; ModelScope paging uses OpenAPI's snake-case page parameters. Model Hub now exposes previous/next controls and retryable typed catalog errors through its ViewModel state.
- Inspect reads provider file metadata, pins Hugging Face downloads to the reported commit, limits config inspection to 1 MiB, validates configured artifact paths, and supports nested files and `.mtok` tokenizers.
- Installation requires an immutable 40- or 64-hex source revision. When provider SHA-256 metadata is missing, the UI requires explicit confirmation, downloads over the approved HTTPS path, computes local SHA-256 values and marks the manifest `UNVERIFIED_SOURCE_DIGEST`.
- The installer checks the runtime's model resolver, downloads into a private staging directory, writes `.pair-model.json` provenance, and atomically publishes the completed directory.
- Model inspection, installation, local import, and runtime inventory now require the MNN `llm_config` artifact declared by `config.json` (default `llm_config.json`). Incomplete packages are rejected before native model loading.
- Model Hub catalog, inspect, install, import, and installed inventory state now live in an `AndroidViewModel`; source changes cancel and invalidate stale searches.
- When PAIR is running, model deletion is routed through the Service-owned MNN container. It rejects deletion during generation, unloads a matching idle model, confirms the unloaded state, and only then removes the directory. Hidden staging folders are excluded from runtime inventory.
- Downloads report byte progress in Model Hub and can be cancelled; the ViewModel runs installer IO interruptibly and the downloader closes its active connection on interruption.
- The downloader enforces HTTPS and approved provider host suffixes, limits redirects, avoids sending bearer tokens to another origin, validates resumed ranges, handles 416 partials, and restarts a partial file when the server answers a range request with 200. An atomic task manifest binds resumable `.part` files and completed artifact digests to the pinned provider, repository, revision, model, and source checksums, so a later installer instance can safely continue the same installation.

## Android runtime and acceptance

- Model Hub live ModelScope inspection and installation passed on the Galaxy Tab SM-T733 / Android 14 using `MNN/Qwen3-0.6B-MNN`; all five required MNN runtime files were published in app-private storage with provider SHA-256 verification.
- A denied notification permission no longer cancels a user-requested runtime start.
- M65 has an explicit Gradle instrumentation selection and PowerShell runner for an isolated PC broker and LM Studio. The runner requires PC-side invite acceptance before reporting success.
- JNI model-load failures leave cleanup to their owning `unique_ptr`, avoiding double destruction when MNN rejects a model. Failed loads emit a prompt-free diagnostic.
- M10 PC-to-Android acceptance passed on a Galaxy Tab SM-T733 / Android 14 with `taobao-mnn/Qwen3-0.6B-MNN`: trusted pairing, Android-only model routing, streamed token and `[DONE]`, Android workload attribution, cancellation, and a successful follow-up generation.
- M65 Android-to-PC acceptance passed on a Galaxy Tab SM-T733 / Android 14 with local LM Studio Qwen 3 0.6B; SSE completion and PC-side workload attribution were verified.
- Notification permission denial passed on the Galaxy Tab SM-T733 / Android 14: after selecting “Don't allow”, the runtime reached Running; Stop returned it to Stopped and the permission remained denied.
- Two-device pairing persistence now has a dedicated Android instrumentation case and a PowerShell runner that accepts the invite on an isolated PC broker before checking Android Stop/Start state; this acceptance passed on a Galaxy Tab SM-T733.
- Service cancellation is rethrown through cluster actions and proxy monitoring.
- Engine-manager shutdown now runs a command-mode stop CLI when shutdown cancels a start CLI that may already have detached its daemon; a deterministic regression test covers that race.

## Scope still open

Durable resume is covered by a local installer-reconstruction test. M10, M65, notification-denial, two-device pairing persistence, and live Model Hub inspection/download/install plus runtime inference passed on physical Android hardware. Android process-kill recovery remains unverified. These limits are recorded in `M12_5_KNOWN_LIMITATIONS.md` and mean the engineering plan is not fully complete.
