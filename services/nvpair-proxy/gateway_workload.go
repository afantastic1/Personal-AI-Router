// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"fmt"
	"sync"
	"time"
)

type gatewayCloudWorkload struct {
	proxy      *Proxy
	mu         sync.Mutex
	workload   Workload
	sequence   int64
	terminal   bool
	terminalMu sync.Once
}

func newGatewayCloudWorkload(proxy *Proxy, route gatewayRoute, callerNode string) *gatewayCloudWorkload {
	now := time.Now().UnixMilli()
	workload := Workload{
		ID:    "cloud-" + fmt.Sprint(proxy.cloudRequestID.Add(1)),
		Model: route.requestModel, Kind: "cloud", ProviderID: route.provider.ID,
		PublicModelID: route.cloudTarget.PublicID, RunID: proxy.runID,
		State: "queued", CreatedAt: now,
	}
	if callerNode != "" {
		workload.RequesterID = &callerNode
	}
	lifecycle := &gatewayCloudWorkload{proxy: proxy, workload: workload, sequence: 1}
	lifecycle.emit(workloadSubmittedMethod)
	return lifecycle
}

func (w *gatewayCloudWorkload) started() {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.terminal || w.workload.StartedAt != nil {
		return
	}
	now := time.Now().UnixMilli()
	w.workload.StartedAt = &now
	w.workload.State = "running"
	w.sequence++
	w.emitLocked(workloadStartedMethod)
}

func (w *gatewayCloudWorkload) finish(state string, message string, usage *CloudUsage, cost *float64) {
	w.terminalMu.Do(func() {
		w.mu.Lock()
		defer w.mu.Unlock()
		w.terminal = true
		now := time.Now().UnixMilli()
		w.workload.CompletedAt = &now
		w.workload.State = state
		if message != "" {
			w.workload.Error = &message
		}
		if usage != nil {
			copy := *usage
			w.workload.Usage = &copy
		}
		if cost != nil {
			copy := *cost
			w.workload.CostEstimate = &copy
		}
		w.sequence++
		w.emitLocked(workloadErroredMethod)
	})
}

func (w *gatewayCloudWorkload) complete(usage *CloudUsage, cost *float64) {
	w.terminalMu.Do(func() {
		w.mu.Lock()
		defer w.mu.Unlock()
		w.terminal = true
		now := time.Now().UnixMilli()
		w.workload.CompletedAt = &now
		w.workload.State = "completed"
		if usage != nil {
			copy := *usage
			w.workload.Usage = &copy
		}
		if cost != nil {
			copy := *cost
			w.workload.CostEstimate = &copy
		}
		w.sequence++
		w.emitLocked(workloadCompletedMethod)
	})
}

func (w *gatewayCloudWorkload) emit(method string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.emitLocked(method)
}

func (w *gatewayCloudWorkload) emitLocked(method string) {
	w.workload.Seq = w.sequence
	w.proxy.emitWorkload(method, w.workload)
}
