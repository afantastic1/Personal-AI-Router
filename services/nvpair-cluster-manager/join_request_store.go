// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"time"
)

const joinRequestsFile = "join-requests.json"

type JoinRequestState string

const (
	joinRequestPending  JoinRequestState = "pending"
	joinRequestApproved JoinRequestState = "approved"
	joinRequestRejected JoinRequestState = "rejected"
	joinRequestCanceled JoinRequestState = "canceled"
	joinRequestExpired  JoinRequestState = "expired"
)

// JoinRequest contains an untrusted identity claim. It is never an
// authorization grant; the EAP-NOOB identity must match before pairing commits.
type JoinRequest struct {
	RequestID         string           `json:"requestId"`
	RequesterNodeUUID string           `json:"requesterNodeUuid"`
	RequesterNodeID   string           `json:"requesterNodeId"`
	RequesterName     string           `json:"requesterName"`
	SourceAddress     string           `json:"sourceAddress"`
	CreatedAt         int64            `json:"createdAt"`
	ExpiresAt         int64            `json:"expiresAt"`
	State             JoinRequestState `json:"state"`
	ClusterID         string           `json:"clusterId,omitempty"`
}

type JoinRequestStore struct {
	dir string
}

func OpenJoinRequestStore(dir string) *JoinRequestStore {
	return &JoinRequestStore{dir: dir}
}

func (s *JoinRequestStore) path() string {
	return filepath.Join(s.dir, joinRequestsFile)
}

func (s *JoinRequestStore) read() (map[string]JoinRequest, error) {
	data, err := os.ReadFile(s.path())
	if os.IsNotExist(err) {
		return map[string]JoinRequest{}, nil
	}
	if err != nil {
		return nil, err
	}
	var requests map[string]JoinRequest
	if err := json.Unmarshal(data, &requests); err != nil {
		return nil, err
	}
	if requests == nil {
		return nil, fmt.Errorf("join request store must be a JSON object")
	}
	for id, request := range requests {
		if id == "" || request.RequestID != id || request.RequesterNodeUUID == "" {
			return nil, fmt.Errorf("invalid join request record %q", id)
		}
	}
	return requests, nil
}

func (s *JoinRequestStore) write(requests map[string]JoinRequest) error {
	data, err := json.MarshalIndent(requests, "", "  ")
	if err != nil {
		return err
	}
	return atomicWrite(s.path(), data, 0o600)
}

// Create persists a request, returning false when this requester already has
// an unexpired pending request in the inbox.
func (s *JoinRequestStore) Create(request JoinRequest) (bool, error) {
	if request.RequestID == "" || request.RequesterNodeUUID == "" {
		return false, fmt.Errorf("join request requires requestId and requesterNodeUuid")
	}
	if request.State == "" {
		request.State = joinRequestPending
	}
	if request.State != joinRequestPending {
		return false, fmt.Errorf("new join request must be pending")
	}
	if request.CreatedAt == 0 {
		request.CreatedAt = time.Now().UnixMilli()
	}
	requests, err := s.read()
	if err != nil {
		return false, err
	}
	now := time.Now().UnixMilli()
	for _, existing := range requests {
		if existing.State == joinRequestPending && existing.ExpiresAt > now && existing.RequesterNodeUUID == request.RequesterNodeUUID {
			return false, nil
		}
	}
	requests[request.RequestID] = request
	if err := s.write(requests); err != nil {
		return false, err
	}
	return true, nil
}

func (s *JoinRequestStore) Update(request JoinRequest) error {
	if request.RequestID == "" {
		return fmt.Errorf("join request requires requestId")
	}
	requests, err := s.read()
	if err != nil {
		return err
	}
	if _, ok := requests[request.RequestID]; !ok {
		return fmt.Errorf("join request %q does not exist", request.RequestID)
	}
	requests[request.RequestID] = request
	return s.write(requests)
}

func (s *JoinRequestStore) List() ([]JoinRequest, error) {
	requests, err := s.read()
	if err != nil {
		return nil, err
	}
	out := make([]JoinRequest, 0, len(requests))
	for _, request := range requests {
		out = append(out, request)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].CreatedAt == out[j].CreatedAt {
			return out[i].RequestID < out[j].RequestID
		}
		return out[i].CreatedAt < out[j].CreatedAt
	})
	return out, nil
}
