// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package modelselection

import "testing"

func TestAutoModelSelectorPolicies(t *testing.T) {
	selector := AutoModelSelector{}
	fast := runtimeModel("phone-small", "mnn", 1_700_000_000, true, 700, 12, nil)
	large := runtimeModel("pc-large", "ollama", 8_000_000_000, false, 90, 50, nil)
	vision := runtimeModel("vision-large", "lmstudio", 14_000_000_000, false, 500, 16, map[string]bool{"chat": true, "vision": true})

	t.Run("auto alias uses balanced policy", func(t *testing.T) {
		selection := selector.Select(AutoAlias, Requirements{}, []RuntimeModel{fast, large})
		if selection == nil {
			t.Fatal("auto selected no available model")
		}
		if selection.PolicyAlias != AutoBalancedAlias {
			t.Fatalf("auto policy = %q, want %q", selection.PolicyAlias, AutoBalancedAlias)
		}
	})

	t.Run("fast policy favors loaded model", func(t *testing.T) {
		selection := selector.Select(AutoFastAlias, Requirements{}, []RuntimeModel{fast, large})
		if selection == nil || selection.Model.EngineModelID != fast.Model.EngineModelID {
			t.Fatalf("fast selection = %+v, want loaded phone model", selection)
		}
	})

	t.Run("best policy filters capabilities before scoring size", func(t *testing.T) {
		selection := selector.Select(AutoBestAlias, Requirements{Capabilities: map[string]bool{"vision": true}}, []RuntimeModel{large, vision})
		if selection == nil || selection.Model.EngineModelID != vision.Model.EngineModelID {
			t.Fatalf("best selection = %+v, want capability-compatible vision model", selection)
		}
	})

	t.Run("catalog entries cannot add runtime candidates", func(t *testing.T) {
		selection := selector.Select(AutoBestAlias, Requirements{}, nil)
		if selection != nil {
			t.Fatalf("selection = %+v, want no model from an empty runtime inventory", selection)
		}
	})
}

func runtimeModel(id, engine string, parameters int64, loaded bool, ttfbMillis int64, tokensPerSecond float64, capabilities map[string]bool) RuntimeModel {
	if capabilities == nil {
		capabilities = map[string]bool{"chat": true}
	}
	return RuntimeModel{
		Model: ModelDescriptor{
			LogicalID:            engine + ":" + id,
			EngineModelID:        id,
			ParameterCount:       parameters,
			ContextLength:        8192,
			Capabilities:         capabilities,
			EstimatedMemoryBytes: parameters * 2,
			Compatible:           true,
		},
		Engine:                 engine,
		NodeID:                 "node-" + id,
		Available:              true,
		Loaded:                 loaded,
		TimeToFirstTokenMillis: ttfbMillis,
		TokensPerSecond:        tokensPerSecond,
		AvailableMemoryBytes:   parameters * 4,
	}
}
