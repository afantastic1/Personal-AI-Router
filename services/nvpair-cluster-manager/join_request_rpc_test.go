// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"bytes"
	"encoding/json"
	"io"
	"strings"
	"testing"
	"time"
)

func TestClusterManager_ListJoinRequestsExpiresStaleRequests(t *testing.T) {
	var output bytes.Buffer
	codec := NewCodec(struct {
		io.Reader
		io.Writer
	}{strings.NewReader(""), &output})
	m, err := NewManager(codec, t.TempDir(), 15131)
	if err != nil {
		t.Fatalf("NewManager() error = %v", err)
	}
	request := JoinRequest{
		RequestID: "expired-request", RequesterNodeUUID: "e7699f25-3a72-4675-a756-c5190428f028",
		CreatedAt: time.Now().Add(-time.Hour).UnixMilli(), ExpiresAt: time.Now().Add(-time.Minute).UnixMilli(),
		State: joinRequestPending,
	}
	if created, err := m.joinRequests.Create(request); err != nil || !created {
		t.Fatalf("Create() = (%v, %v), want (true, nil)", created, err)
	}
	id := json.RawMessage(`1`)
	m.handleListJoinRequests(&Message{ID: &id})
	requests, err := m.joinRequests.List()
	if err != nil {
		t.Fatalf("List() error = %v", err)
	}
	if len(requests) != 1 || requests[0].State != joinRequestExpired {
		t.Fatalf("request after list = %+v, want expired", requests)
	}
	var response map[string]any
	if err := json.Unmarshal(output.Bytes(), &response); err != nil {
		t.Fatalf("decode JSON-RPC response: %v", err)
	}
	result, ok := response["result"].(map[string]any)
	if !ok || result["requests"] == nil {
		t.Fatalf("JSON-RPC result = %#v, want requests array", response["result"])
	}
}
