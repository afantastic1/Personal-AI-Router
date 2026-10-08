// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/url"
	"regexp"
	"strings"
)

const cloudConfigSchemaVersion = 1

var cloudIdentifierPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`)
var cloudModelPartPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$`)

type cloudConfigOptions struct {
	allowLoopbackURL bool
}

type cloudConfigDocument struct {
	SchemaVersion int                  `json:"schema_version"`
	Providers     []cloudProviderEntry `json:"providers"`
}

type cloudProviderEntry struct {
	ID       string            `json:"id"`
	Protocol string            `json:"protocol"`
	BaseURL  string            `json:"base_url"`
	AuthRef  string            `json:"auth_ref"`
	Enabled  bool              `json:"enabled"`
	Models   []cloudModelEntry `json:"models"`
}

type cloudModelEntry struct {
	PublicID     string   `json:"public_id"`
	UpstreamID   string   `json:"upstream_id"`
	Capabilities []string `json:"capabilities"`
}

type cloudProviderRuntime struct {
	ID       string
	Protocol string
	BaseURL  string
	AuthRef  string
	Enabled  bool
}

type cloudModelRuntime struct {
	PublicID     string
	ProviderID   string
	UpstreamID   string
	Capabilities map[string]bool
	Enabled      bool
}

func parseCloudConfig(data []byte, options cloudConfigOptions) (*cloudRegistrySnapshot, error) {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	var document cloudConfigDocument
	if err := decoder.Decode(&document); err != nil {
		return nil, fmt.Errorf("decode cloud provider configuration: %w", err)
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return nil, fmt.Errorf("cloud provider configuration must contain one JSON value")
	}
	if document.SchemaVersion != cloudConfigSchemaVersion {
		return nil, fmt.Errorf("unsupported cloud configuration schema_version %d", document.SchemaVersion)
	}

	snapshot := &cloudRegistrySnapshot{
		providers: make(map[string]cloudProviderRuntime, len(document.Providers)),
		models:    make(map[string]cloudModelRuntime),
	}
	for _, entry := range document.Providers {
		if !cloudIdentifierPattern.MatchString(entry.ID) {
			return nil, fmt.Errorf("invalid provider ID %q", entry.ID)
		}
		if _, exists := snapshot.providers[entry.ID]; exists {
			return nil, fmt.Errorf("duplicate provider ID %q", entry.ID)
		}
		if entry.Protocol != "openai_chat_completions" {
			return nil, fmt.Errorf("provider %q has unsupported protocol %q", entry.ID, entry.Protocol)
		}
		if strings.TrimSpace(entry.AuthRef) == "" {
			return nil, fmt.Errorf("provider %q requires a non-empty auth_ref", entry.ID)
		}
		baseURL, err := validateCloudBaseURL(entry.BaseURL, options)
		if err != nil {
			return nil, fmt.Errorf("provider %q has invalid base_url: %w", entry.ID, err)
		}
		if len(entry.Models) == 0 {
			return nil, fmt.Errorf("provider %q must configure at least one model", entry.ID)
		}
		snapshot.providers[entry.ID] = cloudProviderRuntime{
			ID: entry.ID, Protocol: entry.Protocol, BaseURL: baseURL,
			AuthRef: entry.AuthRef, Enabled: entry.Enabled,
		}
		for _, model := range entry.Models {
			if !validCloudPublicID(model.PublicID) {
				return nil, fmt.Errorf("provider %q has invalid public model ID %q", entry.ID, model.PublicID)
			}
			if strings.TrimSpace(model.UpstreamID) == "" || len(model.UpstreamID) > 512 {
				return nil, fmt.Errorf("provider %q model %q requires a valid upstream_id", entry.ID, model.PublicID)
			}
			if _, exists := snapshot.models[model.PublicID]; exists {
				return nil, fmt.Errorf("duplicate public model ID %q", model.PublicID)
			}
			capabilities, err := parseCloudCapabilities(model.Capabilities)
			if err != nil {
				return nil, fmt.Errorf("provider %q model %q: %w", entry.ID, model.PublicID, err)
			}
			snapshot.models[model.PublicID] = cloudModelRuntime{
				PublicID: model.PublicID, ProviderID: entry.ID,
				UpstreamID: model.UpstreamID, Capabilities: capabilities,
				Enabled: entry.Enabled,
			}
		}
	}
	return snapshot, nil
}

func validateCloudBaseURL(raw string, options cloudConfigOptions) (string, error) {
	parsed, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return "", fmt.Errorf("parse URL")
	}
	if parsed.Hostname() == "" || parsed.User != nil || parsed.RawQuery != "" || parsed.ForceQuery || parsed.Fragment != "" {
		return "", fmt.Errorf("URL must have a host and must not include credentials, query, or fragment")
	}
	if parsed.Opaque != "" || parsed.RawPath != "" {
		return "", fmt.Errorf("URL path must be a normalized API base path")
	}
	if address := net.ParseIP(parsed.Hostname()); address != nil && isForbiddenCloudIP(address) {
		if !(options.allowLoopbackURL && address.IsLoopback()) {
			return "", fmt.Errorf("provider URL must not use a private or special-purpose IP address")
		}
	}
	if strings.EqualFold(strings.TrimSuffix(parsed.Hostname(), "."), "localhost") && !options.allowLoopbackURL {
		return "", fmt.Errorf("provider URL must not use a loopback hostname")
	}
	for _, part := range strings.Split(parsed.Path, "/") {
		if part == "." || part == ".." {
			return "", fmt.Errorf("URL path must not contain dot segments")
		}
	}
	if strings.HasSuffix(strings.TrimRight(parsed.Path, "/"), "/chat/completions") {
		return "", fmt.Errorf("URL must be an API base URL, not a chat completion endpoint")
	}
	if options.allowLoopbackURL && isLoopbackHost(parsed.Hostname()) {
		if parsed.Scheme != "http" && parsed.Scheme != "https" {
			return "", fmt.Errorf("loopback test URL must use HTTP or HTTPS")
		}
	} else if parsed.Scheme != "https" {
		return "", fmt.Errorf("provider URL must use HTTPS")
	}
	parsed.Path = strings.TrimRight(parsed.Path, "/")
	parsed.RawPath = ""
	return parsed.String(), nil
}

func validCloudPublicID(id string) bool {
	parts := strings.Split(id, "/")
	if len(parts) < 3 || parts[0] != "cloud" {
		return false
	}
	for _, part := range parts[1:] {
		if !cloudModelPartPattern.MatchString(part) || part == "." || part == ".." {
			return false
		}
	}
	return true
}

func parseCloudCapabilities(values []string) (map[string]bool, error) {
	const (
		capabilityChat       = "chat"
		capabilityTools      = "tools"
		capabilityVision     = "vision"
		capabilityJSONObject = "json_object"
		capabilityJSONSchema = "json_schema"
		capabilityStreaming  = "streaming"
	)
	known := map[string]struct{}{
		capabilityChat: {}, capabilityTools: {}, capabilityVision: {},
		capabilityJSONObject: {}, capabilityJSONSchema: {}, capabilityStreaming: {},
	}
	capabilities := make(map[string]bool, len(values))
	for _, value := range values {
		if _, ok := known[value]; !ok {
			return nil, fmt.Errorf("unknown capability %q", value)
		}
		if capabilities[value] {
			return nil, fmt.Errorf("duplicate capability %q", value)
		}
		capabilities[value] = true
	}
	if !capabilities[capabilityChat] {
		return nil, fmt.Errorf("capability %q is required", capabilityChat)
	}
	return capabilities, nil
}
