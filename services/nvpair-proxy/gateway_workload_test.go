// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"bytes"
	"encoding/json"
	"io"
	"reflect"
	"sync"
	"testing"
)

type workloadRecordingTransport struct {
	mu     sync.Mutex
	writes bytes.Buffer
}

func (*workloadRecordingTransport) Read([]byte) (int, error) { return 0, io.EOF }
func (t *workloadRecordingTransport) Write(data []byte) (int, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.writes.Write(data)
}
func (*workloadRecordingTransport) Close() error { return nil }

func TestGatewayCloudWorkloadEmitsOneTerminalWithoutEngineAttribution(t *testing.T) {
	transport := &workloadRecordingTransport{}
	proxy := NewProxy(NewCodec(transport))
	route := gatewayRoute{
		requestModel: "auto", provider: cloudProviderRuntime{ID: "deepseek"},
		cloudTarget: cloudModelTarget{PublicID: "cloud/deepseek/chat"},
	}
	workload := newGatewayCloudWorkload(proxy, route, "")
	workload.started()
	usage := &CloudUsage{InputTokens: 11, OutputTokens: 4}
	workload.complete(usage, nil)
	workload.finish("failed", "late duplicate", nil, nil)

	transport.mu.Lock()
	lines, err := io.ReadAll(bytes.NewReader(transport.writes.Bytes()))
	transport.mu.Unlock()
	if err != nil {
		t.Fatalf("read notifications: %v", err)
	}
	var methods []string
	for _, line := range bytes.Split(bytes.TrimSpace(lines), []byte{'\n'}) {
		var envelope struct {
			Method string `json:"method"`
			Params struct {
				WorkloadInfo Workload `json:"workloadInfo"`
			} `json:"params"`
		}
		if err := json.Unmarshal(line, &envelope); err != nil {
			t.Fatalf("decode notification: %v", err)
		}
		methods = append(methods, envelope.Method)
		if envelope.Method == workloadCompletedMethod {
			if envelope.Params.WorkloadInfo.Kind != "cloud" || envelope.Params.WorkloadInfo.Engine != "" ||
				envelope.Params.WorkloadInfo.ProviderID != "deepseek" || envelope.Params.WorkloadInfo.Usage == nil {
				t.Errorf("Cloud workload attribution = %+v", envelope.Params.WorkloadInfo)
			}
		}
	}
	want := []string{workloadSubmittedMethod, workloadStartedMethod, workloadCompletedMethod}
	if !reflect.DeepEqual(methods, want) {
		t.Fatalf("lifecycle methods = %v, want %v", methods, want)
	}
}

func TestPairedCloudWorkloadRecordsCallerNode(t *testing.T) {
	transport := &workloadRecordingTransport{}
	proxy := NewProxy(NewCodec(transport))
	route := gatewayRoute{
		requestModel: "cloud/deepseek/chat", provider: cloudProviderRuntime{ID: "deepseek"},
		cloudTarget: cloudModelTarget{PublicID: "cloud/deepseek/chat"},
	}
	workload := newGatewayCloudWorkload(proxy, route, "10000000-0000-0000-0000-000000000001")
	if workload.workload.RequesterID == nil || *workload.workload.RequesterID != "10000000-0000-0000-0000-000000000001" {
		t.Fatalf("Cloud workload requester=%v", workload.workload.RequesterID)
	}
}
