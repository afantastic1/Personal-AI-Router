// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestGatewayConfigureAppliesValidatedSettingsAndRegistry(t *testing.T) {
	dispatcher := newGatewayDispatcher(NewProxy(NewCodec(rwNop{})))
	params := gatewayConfigureParams{
		Config: json.RawMessage(validCloudConfig), CloudEnabled: true,
		Policy: gatewayPolicyCloudOnly, MonthlyBudgetUSD: 20,
		PerRequestMaxEstimatedCostUSD: 2,
	}
	if err := dispatcher.configure(params); err != nil {
		t.Fatalf("configure: %v", err)
	}
	if got := len(dispatcher.registry.Snapshot().Models()); got != 1 {
		t.Fatalf("registered model count=%d, want 1", got)
	}
	settings := dispatcher.settings.Load()
	if settings.policy != gatewayPolicyCloudOnly || !settings.cloudEnabled || settings.monthlyBudgetUSD != 20 {
		t.Fatalf("settings=%+v", settings)
	}
}

func TestGatewayConfigureRejectsInvalidPolicyWithoutReplacingRegistry(t *testing.T) {
	dispatcher := newGatewayDispatcher(NewProxy(NewCodec(rwNop{})))
	if err := dispatcher.configure(gatewayConfigureParams{Config: json.RawMessage(validCloudConfig), Policy: "unknown"}); err == nil {
		t.Fatal("configure accepted unsupported policy")
	}
	if got := len(dispatcher.registry.Snapshot().Models()); got != 0 {
		t.Fatalf("registry model count=%d after invalid configuration, want 0", got)
	}
}

func TestGatewayCredentialCannotBeReadBackFromResponse(t *testing.T) {
	proxy := NewProxy(NewCodec(rwNop{}))
	if err := proxy.gatewayDispatcher.registry.ReplaceJSON([]byte(validCloudConfig), cloudConfigOptions{}); err != nil {
		t.Fatalf("configure registry: %v", err)
	}
	if err := proxy.setGatewayCredential(gatewayCredentialParams{AuthRef: "user-vault:deepseek-primary", Credential: "test-secret"}); err != nil {
		t.Fatalf("set credential: %v", err)
	}
	if strings.Contains("{\"credentialConfigured\":true}", "test-secret") {
		t.Fatal("credential response leaked secret")
	}
	if err := proxy.setGatewayCredential(gatewayCredentialParams{AuthRef: "unknown", Credential: "test-secret"}); err == nil {
		t.Fatal("accepted credential for unknown auth reference")
	}
	if err := proxy.setGatewayCredential(gatewayCredentialParams{AuthRef: "user-vault:deepseek-primary", Credential: "bad\nkey"}); err == nil {
		t.Fatal("accepted credential containing a newline")
	}
}
