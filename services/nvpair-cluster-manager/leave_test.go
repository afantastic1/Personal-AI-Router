// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"io"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestManagerRunPreservesClusterWhenBrokerClosesInput(t *testing.T) {
	portListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("reserve peer port: %v", err)
	}
	peerPort := portListener.Addr().(*net.TCPAddr).Port
	if err := portListener.Close(); err != nil {
		t.Fatalf("release peer port: %v", err)
	}

	peer := newTestManagerPort(t, peerPort)
	peerContext, stopPeer := context.WithCancel(context.Background())
	defer stopPeer()
	go func() { _ = peer.runHTTP(peerContext) }()
	waitForTCPListener(t, peerPort)

	leaving := newTestManagerPort(t, 0)
	pinTrusted(t, leaving, peer.identity.NodeUUID, string(peer.identity.CertPEM), peer.identity.CertFingerprint)
	pinTrusted(t, peer, leaving.identity.NodeUUID, string(leaving.identity.CertPEM), leaving.identity.CertFingerprint)
	leaving.upsertMember(&ClusterNode{
		NodeUUID: peer.identity.NodeUUID, ID: "peer", IPAddress: "127.0.0.1", Port: peerPort,
		AdmissionEpoch: 1, State: stateMember,
	})
	peer.upsertMember(&ClusterNode{
		NodeUUID: leaving.identity.NodeUUID, ID: "leaving", IPAddress: "127.0.0.1", Port: 1,
		AdmissionEpoch: 1, State: stateMember,
	})

	leaving.codec = NewCodec(struct {
		io.Reader
		io.Writer
	}{strings.NewReader(""), io.Discard})
	if err := leaving.Run(context.Background()); err != nil {
		t.Fatalf("manager run after broker EOF: %v", err)
	}

	if clusterID, _ := leaving.clusterIdentity(); clusterID != "cluster-1" {
		t.Fatalf("manager cluster id after broker EOF = %q, want cluster-1 for restart recovery", clusterID)
	}
	if _, trusted := peer.trust.Get(leaving.identity.NodeUUID); !trusted {
		t.Fatal("online peer no longer trusts the manager after a broker-only restart")
	}
	foundMember := false
	for _, member := range peer.snapshotNodes() {
		if member.NodeUUID == leaving.identity.NodeUUID {
			foundMember = true
			break
		}
	}
	if !foundMember {
		t.Fatal("online peer no longer lists the manager after a broker-only restart")
	}
}

func waitForTCPListener(t *testing.T, port int) {
	t.Helper()
	address := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		connection, err := net.DialTimeout("tcp", address, 50*time.Millisecond)
		if err == nil {
			if closeErr := connection.Close(); closeErr != nil {
				t.Fatalf("close readiness connection: %v", closeErr)
			}
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("peer cluster manager did not listen on %s before timeout", address)
}

// TestHandleLeave_NotBlockedByUnreachableMember verifies that a member reachable
// at the TCP layer but never completing a request must
// not stall the whole leave for the full pairingHTTPTimeout (10s). The
// departure-tombstone pushes run concurrently under leaveNotifyTimeout, then
// teardown proceeds; the stuck peer converges later from the gossiped tombstone.
func TestHandleLeave_NotBlockedByUnreachableMember(t *testing.T) {
	m := newTestManager(t) // clustered as "cluster-1"

	// A listener that accepts but never responds, so the mTLS reconcile POST
	// hangs in the TLS handshake until the client's 10s timeout — exactly the
	// offline/wedged-peer case that used to hang the leave.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) { _, _ = io.Copy(io.Discard, c) }(c) // read and drop; never reply
		}
	}()
	addr := ln.Addr().(*net.TCPAddr)

	uuid, cert, fp, _ := makeNode(t, "ghost")
	if err := m.trust.Pin(&TrustedPin{
		NodeUUID: uuid, NodeID: "ghost", Name: "ghost", ClusterID: "cluster-1",
		CertPem: cert, CertFingerprint: fp, PinnedAt: time.Now().UnixMilli(),
	}); err != nil {
		t.Fatalf("pin ghost: %v", err)
	}
	m.upsertMember(&ClusterNode{
		NodeUUID: uuid, ID: "ghost",
		IPAddress: "127.0.0.1", Port: addr.Port, State: stateMember,
	})

	start := time.Now()
	m.handleLeave(&Message{})
	elapsed := time.Since(start)

	if elapsed > leaveNotifyTimeout+3*time.Second {
		t.Fatalf("handleLeave took %v; the bounded notify phase must not wait the full %v for a wedged member", elapsed, pairingHTTPTimeout)
	}
	if id, _ := m.clusterIdentity(); id != "" {
		t.Fatalf("after leave the node must be unclustered, got cluster id %q", id)
	}
}
