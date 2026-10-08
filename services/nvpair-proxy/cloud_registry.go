// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"sort"
	"sync/atomic"
)

type cloudModelTarget struct {
	PublicID     string
	ProviderID   string
	UpstreamID   string
	Capabilities map[string]bool
	Enabled      bool
}

type cloudRegistrySnapshot struct {
	providers map[string]cloudProviderRuntime
	models    map[string]cloudModelRuntime
}

type cloudRegistry struct {
	snapshot atomic.Pointer[cloudRegistrySnapshot]
}

func newCloudRegistry() *cloudRegistry {
	registry := &cloudRegistry{}
	registry.snapshot.Store(&cloudRegistrySnapshot{
		providers: make(map[string]cloudProviderRuntime),
		models:    make(map[string]cloudModelRuntime),
	})
	return registry
}

func (r *cloudRegistry) ReplaceJSON(data []byte, options cloudConfigOptions) error {
	snapshot, err := parseCloudConfig(data, options)
	if err != nil {
		return err
	}
	r.snapshot.Store(snapshot)
	return nil
}

func (r *cloudRegistry) Snapshot() *cloudRegistrySnapshot {
	return r.snapshot.Load()
}

func (s *cloudRegistrySnapshot) Resolve(publicID string) (cloudModelTarget, bool) {
	model, ok := s.models[publicID]
	if !ok || !model.Enabled {
		return cloudModelTarget{}, false
	}
	provider, ok := s.providers[model.ProviderID]
	if !ok || !provider.Enabled {
		return cloudModelTarget{}, false
	}
	return cloudModelTarget{
		PublicID: model.PublicID, ProviderID: model.ProviderID,
		UpstreamID: model.UpstreamID, Capabilities: cloneCloudCapabilities(model.Capabilities),
		Enabled: true,
	}, true
}

func (s *cloudRegistrySnapshot) Models() []cloudModelTarget {
	models := make([]cloudModelTarget, 0, len(s.models))
	for id := range s.models {
		if target, ok := s.Resolve(id); ok {
			models = append(models, target)
		}
	}
	sort.Slice(models, func(i, j int) bool { return models[i].PublicID < models[j].PublicID })
	return models
}

func (s *cloudRegistrySnapshot) provider(providerID string) (cloudProviderRuntime, bool) {
	provider, ok := s.providers[providerID]
	return provider, ok && provider.Enabled
}

func cloneCloudCapabilities(capabilities map[string]bool) map[string]bool {
	cloned := make(map[string]bool, len(capabilities))
	for capability, enabled := range capabilities {
		cloned[capability] = enabled
	}
	return cloned
}
