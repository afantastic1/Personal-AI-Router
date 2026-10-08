// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"fmt"
	"sort"
	"time"
)

func (m *Manager) handleListJoinRequests(msg *Message) {
	m.requestMu.Lock()
	defer m.requestMu.Unlock()
	requests, err := m.joinRequests.List()
	if err != nil {
		m.codec.RespondErrorData(msg.ID, codeInternalError, "read join requests: "+err.Error(), nil)
		return
	}
	now := time.Now().UnixMilli()
	for i := range requests {
		if requests[i].State == joinRequestPending && requests[i].ExpiresAt <= now {
			requests[i].State = joinRequestExpired
			if err := m.joinRequests.Update(requests[i]); err != nil {
				m.codec.RespondErrorData(msg.ID, codeInternalError, "expire join request: "+err.Error(), nil)
				return
			}
		}
	}
	m.codec.Respond(msg.ID, map[string]any{"requests": requests})
}

func (m *Manager) handleListMemberships(msg *Message) {
	ids := make([]string, 0, len(m.memberships))
	for id := range m.memberships {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	summaries := make([]MembershipSummary, 0, len(ids))
	for _, id := range ids {
		membership := m.memberships[id]
		summaries = append(summaries, MembershipSummary{
			ClusterID: id, ClusterFriendlyName: membership.FriendlyName, State: membershipStateJoined,
		})
	}
	m.codec.Respond(msg.ID, map[string]any{"memberships": summaries})
}

type MembershipSummary struct {
	ClusterID           string `json:"clusterId"`
	ClusterFriendlyName string `json:"clusterFriendlyName"`
	State               string `json:"state"`
}

const membershipStateJoined = "joined"

func (m *Manager) updateMembership(membership Membership) error {
	if membership.ClusterID == "" {
		return fmt.Errorf("membership requires clusterId")
	}
	updated := make(map[string]Membership, len(m.memberships)+1)
	for id, existing := range m.memberships {
		updated[id] = existing
	}
	updated[membership.ClusterID] = membership
	if err := m.membershipStore.Save(updated); err != nil {
		return err
	}
	m.memberships = updated
	return nil
}
