// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

type gatewayConfigureParams struct {
	Config                        json.RawMessage `json:"config"`
	CloudEnabled                  bool            `json:"cloudEnabled"`
	Policy                        gatewayPolicy   `json:"policy"`
	AllowPaidFallback             bool            `json:"allowPaidFallback"`
	MonthlyBudgetUSD              float64         `json:"monthlyBudgetUSD"`
	PerRequestMaxEstimatedCostUSD float64         `json:"perRequestMaxEstimatedCostUSD"`
}

func (d *gatewayDispatcher) configure(params gatewayConfigureParams) error {
	d.cloudControlMu.Lock()
	defer d.cloudControlMu.Unlock()
	if len(params.Config) == 0 {
		return fmt.Errorf("cloud provider config is required")
	}
	if params.Policy == "" {
		params.Policy = gatewayPolicyLocalOnly
	}
	switch params.Policy {
	case gatewayPolicyLocalOnly, gatewayPolicyCloudOnly, gatewayPolicyPreferLocal, gatewayPolicyPreferCloud:
	default:
		return fmt.Errorf("unsupported cloud routing policy")
	}
	if params.MonthlyBudgetUSD < 0 || params.PerRequestMaxEstimatedCostUSD < 0 {
		return fmt.Errorf("cloud budget limits cannot be negative")
	}
	snapshot, err := parseCloudConfig(params.Config, cloudConfigOptions{})
	if err != nil {
		return err
	}
	d.registry.snapshot.Store(snapshot)
	d.pruneProviderCredentials(snapshot)
	d.settings.Store(&gatewayRoutingSettings{
		policy: params.Policy, cloudEnabled: params.CloudEnabled,
		allowPaidFallback:             params.AllowPaidFallback,
		monthlyBudgetUSD:              params.MonthlyBudgetUSD,
		perRequestMaxEstimatedCostUSD: params.PerRequestMaxEstimatedCostUSD,
	})
	return nil
}

func (d *gatewayDispatcher) testProvider(providerID string) error {
	provider, ok := d.registry.Snapshot().providerByID(providerID)
	if !ok {
		return fmt.Errorf("provider is not configured")
	}
	d.credentialMu.RLock()
	resolver := d.resolveCredential
	d.credentialMu.RUnlock()
	if resolver == nil {
		return fmt.Errorf("provider credential is not configured")
	}
	credential, err := resolver(provider.AuthRef)
	if err != nil || credential == "" {
		return fmt.Errorf("provider credential is not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	return d.client.TestProvider(ctx, provider, credential)
}

type gatewayCredentialParams struct {
	AuthRef    string `json:"authRef"`
	Credential string `json:"credential"`
}

func (p *Proxy) setGatewayCredential(params gatewayCredentialParams) error {
	p.gatewayDispatcher.cloudControlMu.Lock()
	defer p.gatewayDispatcher.cloudControlMu.Unlock()
	if params.AuthRef == "" {
		return fmt.Errorf("authRef is required")
	}
	if len(params.Credential) > 16<<10 || strings.ContainsAny(params.Credential, "\r\n") {
		return fmt.Errorf("provider credential is invalid")
	}
	if !p.gatewayDispatcher.registry.Snapshot().providerByAuthRef(params.AuthRef) {
		return fmt.Errorf("credential reference is not configured")
	}
	p.gatewayDispatcher.setProviderCredential(params.AuthRef, params.Credential)
	return nil
}

type gatewayTestProviderParams struct {
	ProviderID string `json:"providerId"`
}
