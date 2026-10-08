// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

const (
	joinRequestsPath      = "/v1/cluster/join-requests"
	joinRequestMaxBody    = 8 << 10
	joinRequestMaxPending = 256
	joinRequestRateLimit  = 5
)

type JoinRequestReceipt struct {
	RequestID string           `json:"requestId"`
	State     JoinRequestState `json:"state"`
}

type joinRequestSubmission struct {
	RequestID         string `json:"requestId"`
	RequesterNodeUUID string `json:"requesterNodeUuid"`
	RequesterNodeID   string `json:"requesterNodeId"`
	RequesterName     string `json:"requesterName"`
}

func (m *Manager) allowJoinRequestFrom(source string, now time.Time) bool {
	m.requestRateMu.Lock()
	defer m.requestRateMu.Unlock()
	cutoff := now.Add(-time.Minute).UnixMilli()
	recent := m.requestRate[source][:0]
	for _, at := range m.requestRate[source] {
		if at > cutoff {
			recent = append(recent, at)
		}
	}
	if len(recent) >= joinRequestRateLimit {
		m.requestRate[source] = recent
		return false
	}
	m.requestRate[source] = append(recent, now.UnixMilli())
	return true
}

func (m *Manager) handleJoinRequests(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != joinRequestsPath || r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	remote := hostOnly(r.RemoteAddr)
	if remote == "" || net.ParseIP(remote) == nil {
		http.Error(w, "invalid source address", http.StatusBadRequest)
		return
	}
	now := time.Now()
	if !m.allowJoinRequestFrom(remote, now) {
		http.Error(w, "too many join requests", http.StatusTooManyRequests)
		return
	}
	decoder := json.NewDecoder(io.LimitReader(r.Body, joinRequestMaxBody))
	decoder.DisallowUnknownFields()
	var submission joinRequestSubmission
	if err := decoder.Decode(&submission); err != nil {
		http.Error(w, "invalid join request", http.StatusBadRequest)
		return
	}
	if submission.RequestID == "" || len(submission.RequestID) > 128 || !isNodeUUID(submission.RequesterNodeUUID) || submission.RequesterNodeUUID == m.identity.NodeUUID {
		http.Error(w, "invalid join request identity", http.StatusBadRequest)
		return
	}
	if len(submission.RequesterNodeID) > 256 || len(submission.RequesterName) > 256 {
		http.Error(w, "join request identity is too long", http.StatusBadRequest)
		return
	}
	m.requestMu.Lock()
	defer m.requestMu.Unlock()
	requests, err := m.joinRequests.List()
	if err != nil {
		http.Error(w, "join request store unavailable", http.StatusInternalServerError)
		return
	}
	pending := 0
	for _, existing := range requests {
		if existing.State == joinRequestPending && existing.ExpiresAt > now.UnixMilli() {
			pending++
			if existing.RequesterNodeUUID == submission.RequesterNodeUUID {
				writeJoinRequestReceipt(w, http.StatusOK, JoinRequestReceipt{RequestID: existing.RequestID, State: existing.State})
				return
			}
		}
	}
	if pending >= joinRequestMaxPending {
		http.Error(w, "join request inbox is full", http.StatusTooManyRequests)
		return
	}
	request := JoinRequest{
		RequestID: submission.RequestID, RequesterNodeUUID: submission.RequesterNodeUUID,
		RequesterNodeID: submission.RequesterNodeID, RequesterName: submission.RequesterName,
		SourceAddress: remote, CreatedAt: now.UnixMilli(), ExpiresAt: now.Add(m.effectiveInviteTTL()).UnixMilli(),
		State: joinRequestPending,
	}
	created, err := m.joinRequests.Create(request)
	if err != nil {
		http.Error(w, "save join request", http.StatusInternalServerError)
		return
	}
	if !created {
		requests, err = m.joinRequests.List()
		if err != nil {
			http.Error(w, "join request store unavailable", http.StatusInternalServerError)
			return
		}
		for _, existing := range requests {
			if existing.State == joinRequestPending && existing.RequesterNodeUUID == submission.RequesterNodeUUID {
				writeJoinRequestReceipt(w, http.StatusOK, JoinRequestReceipt{RequestID: existing.RequestID, State: existing.State})
				return
			}
		}
		http.Error(w, "duplicate request id", http.StatusConflict)
		return
	}
	if err := m.codec.Notify("cluster:join-request-received", request); err != nil {
		// Durable inbox state is authoritative; a missed push is recovered by list.
	}
	writeJoinRequestReceipt(w, http.StatusCreated, JoinRequestReceipt{RequestID: request.RequestID, State: request.State})
}

func writeJoinRequestReceipt(w http.ResponseWriter, status int, receipt JoinRequestReceipt) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(receipt)
}

func joinRequestIDFromPath(path string) string {
	return strings.TrimPrefix(path, joinRequestsPath+"/")
}
