// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestCloudProvidersSettingsRoundTripStoresOnlyPublicConfiguration(t *testing.T) {
	t.Setenv("LOCALAPPDATA", t.TempDir())
	settings := cloudProvidersSettings{
		SchemaVersion: 1,
		Config:        json.RawMessage(`{"schema_version":1,"providers":[{"id":"p","auth_ref":"user-vault:p"}]}`),
		CloudEnabled:  true, Policy: "cloud_only", MonthlyBudgetUSD: 10,
		PerRequestMaxEstimatedCostUSD: 1,
		AuthorizedNodes: []cloudNodeAuthorization{{
			NodeUUID: "10000000-0000-0000-0000-000000000001", CertFingerprint: "sha256:" + strings.Repeat("a", 64),
		}},
	}
	if err := saveCloudProvidersSettings(settings); err != nil {
		t.Fatalf("save settings: %v", err)
	}
	got, err := loadCloudProvidersSettings()
	if err != nil {
		t.Fatalf("load settings: %v", err)
	}
	if got.CloudEnabled != settings.CloudEnabled || got.Policy != settings.Policy || string(got.Config) != string(settings.Config) ||
		!reflect.DeepEqual(got.AuthorizedNodes, settings.AuthorizedNodes) {
		t.Fatalf("round trip=%+v, want %+v", got, settings)
	}
	path := filepath.Join(os.Getenv("LOCALAPPDATA"), "Nvidia Corporation", "Personal AI Router", cloudProvidersStoreName)
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read persisted config: %v", err)
	}
	if strings.Contains(string(data), "credential") || strings.Contains(string(data), "api_key") {
		t.Fatalf("persisted cloud config contains a secret field: %s", data)
	}
}

func TestCloudProvidersSettingsRejectsUnsupportedSchema(t *testing.T) {
	t.Setenv("LOCALAPPDATA", t.TempDir())
	if err := saveCloudProvidersSettings(cloudProvidersSettings{SchemaVersion: 2, Config: json.RawMessage(`{}`)}); err != nil {
		t.Fatalf("seed future settings: %v", err)
	}
	if _, err := loadCloudProvidersSettings(); err == nil {
		t.Fatal("load accepted unsupported schema version")
	}
}

func TestCloudProvidersSettingsRejectsUnknownSecretFields(t *testing.T) {
	_, err := parseCloudProvidersSettings([]byte(`{"schema_version":1,"config":{"schema_version":1,"providers":[]},"cloudEnabled":false,"policy":"local_only","allowPaidFallback":false,"monthlyBudgetUSD":0,"perRequestMaxEstimatedCostUSD":0,"api_key":"secret"}`))
	if err == nil {
		t.Fatal("accepted an unknown api_key field")
	}
}
