// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
)

const membershipsFile = "memberships.json"
const membershipMigrationMarkerFile = "memberships.migrated"

// Membership is the durable, cluster-scoped authorization and roster record.
// Device identity and certificate material remain installation-scoped.
type Membership struct {
	ClusterID           string                  `json:"clusterId"`
	FriendlyName        string                  `json:"friendlyName"`
	LocalAdmissionEpoch uint64                  `json:"localAdmissionEpoch"`
	Members             map[string]ClusterNode  `json:"members"`
	Invites             map[string]Invite       `json:"invites"`
	RemovalProofs       map[string]RemovalProof `json:"removalProofs"`
}

// MembershipStore persists memberships independently by cluster ID.
type MembershipStore struct {
	dir string
}

func OpenMembershipStore(clusterDir string) *MembershipStore {
	return &MembershipStore{dir: clusterDir}
}

func (s *MembershipStore) path() string {
	return filepath.Join(s.dir, membershipsFile)
}

func (m *Manager) loadMemberships() error {
	memberships, err := m.membershipStore.Load()
	if err != nil {
		return err
	}
	m.memberships = memberships
	return nil
}

func (m *Manager) migrateLegacyMemberships() error {
	clusterID, epoch := m.currentAdmission()
	if clusterID == "" || epoch == 0 {
		clusterID = ""
		epoch = 0
	}
	m.memMu.Lock()
	nodes := make([]ClusterNode, 0, len(m.members))
	for _, node := range m.members {
		nodes = append(nodes, *cloneClusterNode(node))
	}
	m.memMu.Unlock()
	if _, err := m.membershipStore.MigrateLegacy(clusterID, m.clusterFriendlyName, m.identity.NodeUUID, epoch, nodes); err != nil {
		return err
	}
	return m.loadMemberships()
}

func (s *MembershipStore) Load() (map[string]Membership, error) {
	data, err := os.ReadFile(s.path())
	if os.IsNotExist(err) {
		return map[string]Membership{}, nil
	}
	if err != nil {
		return nil, err
	}
	var memberships map[string]Membership
	if err := json.Unmarshal(data, &memberships); err != nil {
		return nil, err
	}
	if memberships == nil {
		return nil, fmt.Errorf("membership store must be a JSON object")
	}
	for clusterID, membership := range memberships {
		if clusterID == "" || membership.ClusterID != clusterID {
			return nil, fmt.Errorf("membership key %q does not match clusterId %q", clusterID, membership.ClusterID)
		}
	}
	return memberships, nil
}

func (s *MembershipStore) Save(memberships map[string]Membership) error {
	for clusterID, membership := range memberships {
		if clusterID == "" || membership.ClusterID != clusterID {
			return fmt.Errorf("membership key %q does not match clusterId %q", clusterID, membership.ClusterID)
		}
	}
	ids := make([]string, 0, len(memberships))
	for clusterID := range memberships {
		ids = append(ids, clusterID)
	}
	sort.Strings(ids)
	ordered := make(map[string]Membership, len(memberships))
	for _, clusterID := range ids {
		ordered[clusterID] = memberships[clusterID]
	}
	data, err := json.MarshalIndent(ordered, "", "  ")
	if err != nil {
		return err
	}
	return atomicWrite(s.path(), data, 0o600)
}

// MigrateLegacyImports the existing single-cluster record once. The source
// files remain untouched; the marker is committed only after the new store is
// durable, so an interrupted migration can safely be retried.
func (s *MembershipStore) MigrateLegacy(clusterID, friendlyName, selfUUID string, epoch uint64, nodes []ClusterNode) (bool, error) {
	if clusterID == "" || epoch == 0 {
		return false, nil
	}
	if _, err := os.Stat(filepath.Join(s.dir, membershipMigrationMarkerFile)); err == nil {
		return false, nil
	} else if !os.IsNotExist(err) {
		return false, err
	}
	memberships, err := s.Load()
	if err != nil {
		return false, err
	}
	if len(memberships) == 0 && clusterID != "" && epoch != 0 {
		membership := Membership{
			ClusterID: clusterID, FriendlyName: friendlyName, LocalAdmissionEpoch: epoch,
			Members: make(map[string]ClusterNode), Invites: make(map[string]Invite),
			RemovalProofs: make(map[string]RemovalProof),
		}
		for _, node := range nodes {
			if node.NodeUUID == "" {
				return false, fmt.Errorf("legacy member has no nodeUuid")
			}
			node.ClusterID = clusterID
			if node.NodeUUID == selfUUID {
				if node.AdmissionEpoch == 0 {
					node.AdmissionEpoch = epoch
				}
			} else if node.AdmissionEpoch == 0 {
				node.AdmissionEpoch = legacyAdmissionEpoch
			}
			membership.Members[node.NodeUUID] = node
		}
		memberships[clusterID] = membership
		if err := s.Save(memberships); err != nil {
			return false, err
		}
	}
	if err := atomicWrite(filepath.Join(s.dir, membershipMigrationMarkerFile), []byte("v1\n"), 0o600); err != nil {
		return false, err
	}
	return true, nil
}
