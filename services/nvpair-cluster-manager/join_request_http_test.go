// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestJoinRequestHTTP_StoresUntrustedClaimWithoutGrantingMembership(t *testing.T) {
	m := testManagerAt(t, t.TempDir(), 15129)
	body := `{"requestId":"request-1","requesterNodeUuid":"e7699f25-3a72-4675-a756-c5190428f028","requesterNodeId":"tablet","requesterName":"Tablet"}`
	req := httptest.NewRequest(http.MethodPost, joinRequestsPath, strings.NewReader(body))
	req.RemoteAddr = "192.0.2.5:41000"
	response := httptest.NewRecorder()
	m.handleJoinRequests(response, req)
	if response.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want %d: %s", response.Code, http.StatusCreated, response.Body.String())
	}
	var receipt JoinRequestReceipt
	if err := json.Unmarshal(response.Body.Bytes(), &receipt); err != nil {
		t.Fatalf("decode receipt: %v", err)
	}
	if receipt.RequestID != "request-1" || receipt.State != joinRequestPending {
		t.Fatalf("receipt = %+v", receipt)
	}
	requests, err := m.joinRequests.List()
	if err != nil {
		t.Fatalf("List() error = %v", err)
	}
	if len(requests) != 1 || requests[0].SourceAddress != "192.0.2.5" {
		t.Fatalf("stored requests = %+v", requests)
	}
	if _, ok := m.memberByNodeID("e7699f25-3a72-4675-a756-c5190428f028"); ok {
		t.Fatal("untrusted join request created membership")
	}
}

func TestJoinRequestHTTP_RejectsMalformedPayload(t *testing.T) {
	m := testManagerAt(t, t.TempDir(), 15130)
	req := httptest.NewRequest(http.MethodPost, joinRequestsPath, bytes.NewBufferString(`{"requestId":"missing-uuid"}`))
	response := httptest.NewRecorder()
	m.handleJoinRequests(response, req)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("POST status = %d, want %d", response.Code, http.StatusBadRequest)
	}
}
