// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"fmt"
	"strings"
	"sync"
	"testing"
)

const validCloudConfig = `{"schema_version":1,"providers":[{"id":"deepseek-primary","protocol":"openai_chat_completions","base_url":"https://api.deepseek.com","auth_ref":"user-vault:deepseek-primary","enabled":true,"models":[{"public_id":"cloud/deepseek/deepseek-chat","upstream_id":"deepseek-chat","capabilities":["chat","streaming","tools","json_object"]}]}]}`

func TestCloudRegistryLoadsConfiguredModelWithoutSecrets(t *testing.T) {
	registry := newCloudRegistry()
	if err := registry.ReplaceJSON([]byte(validCloudConfig), cloudConfigOptions{}); err != nil {
		t.Fatalf("load config: %v", err)
	}
	target, ok := registry.Snapshot().Resolve("cloud/deepseek/deepseek-chat")
	if !ok {
		t.Fatal("configured public model was not registered")
	}
	if target.ProviderID != "deepseek-primary" || target.UpstreamID != "deepseek-chat" {
		t.Fatalf("target = %+v", target)
	}
	if !target.Enabled || !target.Capabilities["tools"] {
		t.Fatalf("target = %+v, expected enabled tools-capable target", target)
	}
	if strings.Contains(fmt.Sprintf("%+v", target), "user-vault") || strings.Contains(fmt.Sprintf("%+v", target), "api.deepseek.com") {
		t.Fatalf("public execution target contains provider configuration: %+v", target)
	}
}

func TestCloudConfigRejectsDuplicatePublicIDAndUnknownCapability(t *testing.T) {
	duplicate := strings.Replace(validCloudConfig,
		`"models":[{"public_id":"cloud/deepseek/deepseek-chat","upstream_id":"deepseek-chat","capabilities":["chat","streaming","tools","json_object"]}]`,
		`"models":[{"public_id":"cloud/deepseek/deepseek-chat","upstream_id":"deepseek-chat","capabilities":["chat"]},{"public_id":"cloud/deepseek/deepseek-chat","upstream_id":"other","capabilities":["chat"]}]`, 1)
	if _, err := parseCloudConfig([]byte(duplicate), cloudConfigOptions{}); err == nil || !strings.Contains(err.Error(), "duplicate public model ID") {
		t.Fatalf("duplicate public ID error = %v", err)
	}
	unknownCapability := strings.Replace(validCloudConfig, `"tools"`, `"mystery"`, 1)
	if _, err := parseCloudConfig([]byte(unknownCapability), cloudConfigOptions{}); err == nil || !strings.Contains(err.Error(), "unknown capability") {
		t.Fatalf("unknown capability error = %v", err)
	}
}

func TestCloudConfigRejectsUnsafeURLsAndMissingAuthReference(t *testing.T) {
	for _, test := range []struct {
		name string
		body string
	}{
		{name: "plain HTTP", body: strings.Replace(validCloudConfig, "https://", "http://", 1)},
		{name: "private HTTPS address", body: strings.Replace(validCloudConfig, "api.deepseek.com", "10.20.30.40", 1)},
		{name: "URL credentials", body: strings.Replace(validCloudConfig, "https://api.deepseek.com", "https://user:pass@api.deepseek.com", 1)},
		{name: "URL query", body: strings.Replace(validCloudConfig, "https://api.deepseek.com", "https://api.deepseek.com?token=secret", 1)},
		{name: "empty URL query", body: strings.Replace(validCloudConfig, "https://api.deepseek.com", "https://api.deepseek.com?", 1)},
		{name: "empty auth reference", body: strings.Replace(validCloudConfig, `"auth_ref":"user-vault:deepseek-primary"`, `"auth_ref":""`, 1)},
		{name: "unknown security field", body: strings.Replace(validCloudConfig, `"auth_ref":"user-vault:deepseek-primary"`, `"auth_ref":"user-vault:deepseek-primary","api_key":"secret"`, 1)},
	} {
		t.Run(test.name, func(t *testing.T) {
			if _, err := parseCloudConfig([]byte(test.body), cloudConfigOptions{}); err == nil {
				t.Fatal("invalid provider configuration was accepted")
			}
		})
	}
}

func TestCloudConfigAllowsSameUpstreamIDForDifferentProviders(t *testing.T) {
	secondProvider := `,{"id":"openai-secondary","protocol":"openai_chat_completions","base_url":"https://api.openai.com/v1","auth_ref":"user-vault:openai-secondary","enabled":true,"models":[{"public_id":"cloud/openai/example","upstream_id":"deepseek-chat","capabilities":["chat"]}]}`
	config := strings.TrimSuffix(validCloudConfig, `]}`) + secondProvider + `]}`
	snapshot, err := parseCloudConfig([]byte(config), cloudConfigOptions{})
	if err != nil {
		t.Fatalf("same upstream ID on separate providers was rejected: %v", err)
	}
	if _, ok := snapshot.Resolve("cloud/deepseek/deepseek-chat"); !ok {
		t.Fatal("first provider model is missing")
	}
	if target, ok := snapshot.Resolve("cloud/openai/example"); !ok || target.ProviderID != "openai-secondary" {
		t.Fatalf("second provider model = %+v, found=%v", target, ok)
	}
}

func TestCloudRegistryKeepsLastValidSnapshotOnRejectedUpdate(t *testing.T) {
	registry := newCloudRegistry()
	if err := registry.ReplaceJSON([]byte(validCloudConfig), cloudConfigOptions{}); err != nil {
		t.Fatalf("load initial config: %v", err)
	}
	invalid := strings.Replace(validCloudConfig, `"schema_version":1`, `"schema_version":2`, 1)
	if err := registry.ReplaceJSON([]byte(invalid), cloudConfigOptions{}); err == nil {
		t.Fatal("unsupported schema version was accepted")
	}
	if _, ok := registry.Snapshot().Resolve("cloud/deepseek/deepseek-chat"); !ok {
		t.Fatal("rejected update replaced the last valid snapshot")
	}
}

func TestCloudRegistryDisabledProviderHasNoVisibleModels(t *testing.T) {
	registry := newCloudRegistry()
	disabled := strings.Replace(validCloudConfig, `"enabled":true`, `"enabled":false`, 1)
	if err := registry.ReplaceJSON([]byte(disabled), cloudConfigOptions{}); err != nil {
		t.Fatalf("load disabled provider: %v", err)
	}
	if _, ok := registry.Snapshot().Resolve("cloud/deepseek/deepseek-chat"); ok {
		t.Fatal("disabled provider model is visible to routing")
	}
	if got := registry.Snapshot().Models(); len(got) != 0 {
		t.Fatalf("disabled provider model list = %+v, want empty", got)
	}
}

func TestCloudRegistryReturnsIndependentCapabilityMaps(t *testing.T) {
	registry := newCloudRegistry()
	if err := registry.ReplaceJSON([]byte(validCloudConfig), cloudConfigOptions{}); err != nil {
		t.Fatalf("load config: %v", err)
	}
	target, ok := registry.Snapshot().Resolve("cloud/deepseek/deepseek-chat")
	if !ok {
		t.Fatal("configured model was not registered")
	}
	target.Capabilities["tools"] = false
	second, ok := registry.Snapshot().Resolve("cloud/deepseek/deepseek-chat")
	if !ok || !second.Capabilities["tools"] {
		t.Fatalf("caller mutation changed registry snapshot: target=%+v found=%v", second, ok)
	}
}

func TestCloudRegistryConcurrentReadsSeeWholeSnapshots(t *testing.T) {
	registry := newCloudRegistry()
	if err := registry.ReplaceJSON([]byte(validCloudConfig), cloudConfigOptions{}); err != nil {
		t.Fatalf("load initial config: %v", err)
	}
	secondConfig := strings.Replace(validCloudConfig, `"id":"deepseek-primary"`, `"id":"openai-secondary"`, 1)
	secondConfig = strings.Replace(secondConfig, `"auth_ref":"user-vault:deepseek-primary"`, `"auth_ref":"user-vault:openai-secondary"`, 1)
	secondConfig = strings.Replace(secondConfig, `"upstream_id":"deepseek-chat"`, `"upstream_id":"gpt-example"`, 1)
	const readers = 8
	const writes = 50
	var wg sync.WaitGroup
	for i := 0; i < readers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < writes*4; j++ {
				snapshot := registry.Snapshot()
				target, ok := snapshot.Resolve("cloud/deepseek/deepseek-chat")
				legacyPair := target.ProviderID == "deepseek-primary" && target.UpstreamID == "deepseek-chat"
				updatedPair := target.ProviderID == "openai-secondary" && target.UpstreamID == "gpt-example"
				if !ok || (!legacyPair && !updatedPair) {
					t.Errorf("read partial model snapshot: target=%+v found=%v", target, ok)
					return
				}
			}
		}()
	}
	for i := 0; i < writes; i++ {
		config := validCloudConfig
		if i%2 == 1 {
			config = secondConfig
		}
		if err := registry.ReplaceJSON([]byte(config), cloudConfigOptions{}); err != nil {
			t.Fatalf("replace config %d: %v", i, err)
		}
	}
	wg.Wait()
}
