// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"testing"
	"time"
)

func TestJoinRequestStore_DeduplicatesPendingRequesterAndReopens(t *testing.T) {
	store := OpenJoinRequestStore(t.TempDir())
	first := JoinRequest{RequestID: "request-1", RequesterNodeUUID: "requester", ExpiresAt: time.Now().Add(time.Minute).UnixMilli(), State: joinRequestPending}
	created, err := store.Create(first)
	if err != nil {
		t.Fatalf("Create() error = %v", err)
	}
	if !created {
		t.Fatal("Create() reported the first request as a duplicate")
	}
	duplicate, err := store.Create(JoinRequest{RequestID: "request-2", RequesterNodeUUID: "requester", State: joinRequestPending})
	if err != nil {
		t.Fatalf("Create(duplicate) error = %v", err)
	}
	if duplicate {
		t.Fatal("Create() accepted a duplicate pending request from the same requester")
	}

	requests, err := OpenJoinRequestStore(store.dir).List()
	if err != nil {
		t.Fatalf("List() error = %v", err)
	}
	if len(requests) != 1 || requests[0].RequestID != "request-1" {
		t.Fatalf("reopened requests = %#v, want only request-1", requests)
	}
}

func TestJoinRequestStore_AllowsNewRequestAfterApprovalSelectsCluster(t *testing.T) {
	store := OpenJoinRequestStore(t.TempDir())
	first := JoinRequest{RequestID: "request-1", RequesterNodeUUID: "requester", State: joinRequestPending}
	if created, err := store.Create(first); err != nil || !created {
		t.Fatalf("Create() = (%v, %v), want (true, nil)", created, err)
	}
	first.State = joinRequestApproved
	first.ClusterID = "cluster-a"
	if err := store.Update(first); err != nil {
		t.Fatalf("Update(approved) error = %v", err)
	}
	second := JoinRequest{RequestID: "request-2", RequesterNodeUUID: "requester", State: joinRequestPending}
	if created, err := store.Create(second); err != nil || !created {
		t.Fatalf("Create(after approval) = (%v, %v), want (true, nil)", created, err)
	}
}
