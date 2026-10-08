// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestMembershipStore_ReopensIndependentMemberships(t *testing.T) {
	store := OpenMembershipStore(t.TempDir())
	want := map[string]Membership{
		"cluster-a": {
			ClusterID: "cluster-a", FriendlyName: "Alpha", LocalAdmissionEpoch: 3,
			Members:       map[string]ClusterNode{"peer": {ID: "peer", NodeUUID: "peer", ClusterID: "cluster-a", AdmissionEpoch: 7, State: stateMember}},
			RemovalProofs: map[string]RemovalProof{"old-peer": {Tombstone: Tombstone{NodeUUID: "old-peer", ClusterID: "cluster-a", AdmissionEpoch: 4}}},
		},
		"cluster-b": {
			ClusterID: "cluster-b", FriendlyName: "Beta", LocalAdmissionEpoch: 11,
			Members: map[string]ClusterNode{"peer": {ID: "peer", NodeUUID: "peer", ClusterID: "cluster-b", AdmissionEpoch: 2, State: stateMember}},
		},
	}
	if err := store.Save(want); err != nil {
		t.Fatalf("Save() error = %v", err)
	}

	got, err := OpenMembershipStore(store.dir).Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if got["cluster-a"].FriendlyName != "Alpha" || got["cluster-a"].LocalAdmissionEpoch != 3 {
		t.Fatalf("cluster-a identity/admission = %#v", got["cluster-a"])
	}
	if got["cluster-a"].Members["peer"].AdmissionEpoch != 7 || got["cluster-b"].Members["peer"].AdmissionEpoch != 2 {
		t.Fatalf("peer admissions crossed memberships: A=%d B=%d", got["cluster-a"].Members["peer"].AdmissionEpoch, got["cluster-b"].Members["peer"].AdmissionEpoch)
	}
	if got["cluster-a"].RemovalProofs["old-peer"].Tombstone.ClusterID != "cluster-a" || len(got["cluster-b"].RemovalProofs) != 0 {
		t.Fatalf("removal proofs crossed memberships: A=%#v B=%#v", got["cluster-a"].RemovalProofs, got["cluster-b"].RemovalProofs)
	}
}

func TestLegacyMembershipMigration_ImportsOnceAndPreservesIdentityFiles(t *testing.T) {
	dir := t.TempDir()
	legacyPath := filepath.Join(dir, membersFile)
	legacy := []ClusterNode{
		{ID: "self", NodeUUID: "self-uuid", ClusterID: "old-cluster", State: stateMember},
		{ID: "peer", NodeUUID: "peer-uuid", ClusterID: "old-cluster", State: stateMember},
	}
	legacyBytes, err := json.Marshal(legacy)
	if err != nil {
		t.Fatalf("marshal legacy members: %v", err)
	}
	if err := os.WriteFile(legacyPath, legacyBytes, 0o600); err != nil {
		t.Fatalf("write legacy members: %v", err)
	}
	identityPath := filepath.Join(dir, "identity.json")
	if err := os.WriteFile(identityPath, []byte("stable-device-key"), 0o600); err != nil {
		t.Fatalf("write device identity: %v", err)
	}
	store := OpenMembershipStore(dir)
	if migrated, err := store.MigrateLegacy("old-cluster", "Old", "self-uuid", 9, legacy); err != nil || !migrated {
		t.Fatalf("MigrateLegacy() = (%v, %v), want (true, nil)", migrated, err)
	}
	got, err := store.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if len(got) != 1 || got["old-cluster"].Members["self-uuid"].AdmissionEpoch != 9 || got["old-cluster"].Members["peer-uuid"].AdmissionEpoch != legacyAdmissionEpoch {
		t.Fatalf("migrated membership = %#v", got)
	}
	if migrated, err := store.MigrateLegacy("old-cluster", "Old", "self-uuid", 9, legacy); err != nil || migrated {
		t.Fatalf("repeated MigrateLegacy() = (%v, %v), want (false, nil)", migrated, err)
	}
	identity, err := os.ReadFile(identityPath)
	if err != nil {
		t.Fatalf("read device identity: %v", err)
	}
	if string(identity) != "stable-device-key" {
		t.Fatalf("device identity changed during migration: %q", identity)
	}
}

func TestLegacyMembershipMigration_DoesNotRestoreAfterMarker(t *testing.T) {
	dir := t.TempDir()
	store := OpenMembershipStore(dir)
	legacy := []ClusterNode{{ID: "self", NodeUUID: "self-uuid", State: stateMember}}
	if migrated, err := store.MigrateLegacy("old-cluster", "Old", "self-uuid", 1, legacy); err != nil || !migrated {
		t.Fatalf("initial migration = (%v, %v), want (true, nil)", migrated, err)
	}
	if err := store.Save(map[string]Membership{}); err != nil {
		t.Fatalf("clear memberships: %v", err)
	}
	if migrated, err := store.MigrateLegacy("old-cluster", "Old", "self-uuid", 1, legacy); err != nil || migrated {
		t.Fatalf("stale migration = (%v, %v), want (false, nil)", migrated, err)
	}
	got, err := store.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if len(got) != 0 {
		t.Fatalf("stale legacy settings restored memberships: %#v", got)
	}
}

func TestLegacyMembershipMigration_LeavesSourceRecoverableWhenStoreWriteFails(t *testing.T) {
	dir := t.TempDir()
	legacyPath := filepath.Join(dir, membersFile)
	legacy := []ClusterNode{{ID: "self", NodeUUID: "self-uuid", State: stateMember}}
	legacyBytes, err := json.Marshal(legacy)
	if err != nil {
		t.Fatalf("marshal legacy members: %v", err)
	}
	if err := os.WriteFile(legacyPath, legacyBytes, 0o600); err != nil {
		t.Fatalf("write legacy members: %v", err)
	}
	if err := os.Mkdir(filepath.Join(dir, membershipsFile), 0o700); err != nil {
		t.Fatalf("block membership store path: %v", err)
	}
	store := OpenMembershipStore(dir)
	if migrated, err := store.MigrateLegacy("old-cluster", "Old", "self-uuid", 1, legacy); err == nil || migrated {
		t.Fatalf("MigrateLegacy() = (%v, %v), want write failure", migrated, err)
	}
	if _, err := os.Stat(legacyPath); err != nil {
		t.Fatalf("legacy source was not recoverable: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, membershipMigrationMarkerFile)); !os.IsNotExist(err) {
		t.Fatalf("migration marker exists after failed write, stat error = %v", err)
	}
	if err := os.Remove(filepath.Join(dir, membershipsFile)); err != nil {
		t.Fatalf("remove blocked store path: %v", err)
	}
	if migrated, err := store.MigrateLegacy("old-cluster", "Old", "self-uuid", 1, legacy); err != nil || !migrated {
		t.Fatalf("retry MigrateLegacy() = (%v, %v), want (true, nil)", migrated, err)
	}
}
