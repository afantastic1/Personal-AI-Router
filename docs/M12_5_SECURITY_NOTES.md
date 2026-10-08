<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# M12.5 Security Notes

## Pairing and membership

- Stop preserves the cluster identity and membership; explicit Leave still requests removal.
- Invite eligibility is checked in the Android service and again in the Go cluster manager. Stable UUIDs are used to identify a peer. Pending requests are deduplicated and already-member peers are rejected.
- The Go manager uses local membership and trust records when determining whether a UUID is already paired. It does not log pairing PINs or key material.
- Android preserves structured JSON-RPC error data for machine-readable invite reasons.

## Model downloads

- Production downloads require HTTPS and a host matching the supported ModelScope or Hugging Face provider domains. Redirects are checked manually and bounded; bearer credentials are only attached when the redirect target has the same origin as the original URL.
- ModelScope branch/tag selections are resolved to an immutable object ID through its read-only Git smart-HTTP ref advertisement. The file-tree `Revision` field is per-file history and is not trusted as a repository snapshot ID.
- Repository file paths are checked as safe relative paths before staging writes. The provider config is limited to 1 MiB and paths are checked against the inspected file listing.
- Files with provider SHA-256 metadata are verified before publication. If a pinned source has no provider checksum, the UI requires explicit confirmation and the installer computes a local SHA-256; this is recorded as `UNVERIFIED_SOURCE_DIGEST` and is not described as source verification.
- Successful installations record provider, repository, pinned revision, digest status and per-file provider/local SHA-256 values in `.pair-model.json`; final publication requires an atomic move.
- The downloader has bounded 408/429/5xx retries, Retry-After handling, byte progress, interrupt cancellation and range validation. It does not persist resumable task metadata; cancellation removes the staging tree. It also does not enforce a provider content length against every fresh response beyond SHA-256 verification.

## Runtime boundaries

- The M65 acceptance runner uses an isolated temporary broker state directory and checks that PAIR ports are free before starting it.
- Active model deletion is coordinated with the Service-owned MNN runtime; a busy generation is rejected and an idle loaded session must unload successfully before files are removed. Native calls that do not return still require device soak coverage before release.
