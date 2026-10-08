// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"nvpair-shared/modelselection"
)

const gatewayAddress = "127.0.0.1:14326"

const (
	gatewayReadHeaderTimeout = 5 * time.Second
	gatewayIdleTimeout       = 60 * time.Second
	gatewayMaxHeaderBytes    = 32 << 10
)

var gatewayEnginePreference = []string{"mnn", "ollama", "lmstudio"}

const (
	AUTO_ALIAS          = modelselection.AutoAlias
	AUTO_FAST_ALIAS     = modelselection.AutoFastAlias
	AUTO_BALANCED_ALIAS = modelselection.AutoBalancedAlias
	AUTO_BEST_ALIAS     = modelselection.AutoBestAlias
)

var autoAliases = []string{AUTO_ALIAS, AUTO_FAST_ALIAS, AUTO_BALANCED_ALIAS, AUTO_BEST_ALIAS}
var gatewayParameterPattern = regexp.MustCompile(`(?i)([0-9]+(?:\.[0-9]+)?)\s*[- ]?b([^a-z]|$)`)

type gatewayModel struct {
	ID      string `json:"id"`
	Object  string `json:"object"`
	OwnedBy string `json:"owned_by"`
}

type gatewayServer struct {
	srv *http.Server
}

func (p *Proxy) gatewayModels() []gatewayModel {
	ids := make(map[string]struct{})
	for _, f := range p.enabledFacades() {
		for _, node := range f.discovery.Nodes() {
			for _, model := range node.Models {
				if strings.TrimSpace(model) != "" {
					ids[model] = struct{}{}
				}
			}
		}
	}
	keys := make([]string, 0, len(ids))
	for id := range ids {
		keys = append(keys, id)
	}
	sort.Strings(keys)
	models := make([]gatewayModel, 0, len(keys))
	for _, id := range keys {
		models = append(models, gatewayModel{ID: id, Object: "model", OwnedBy: "pair"})
	}
	for _, alias := range autoAliases {
		models = append(models, gatewayModel{ID: alias, Object: "model", OwnedBy: "pair"})
	}
	sort.Slice(models, func(i, j int) bool { return models[i].ID < models[j].ID })
	return models
}

func (p *Proxy) gatewayInventory() []modelselection.RuntimeModel {
	candidates := make(map[string]modelselection.RuntimeModel)
	for _, f := range p.enabledFacades() {
		engine := f.profile.Name
		for _, node := range f.discovery.Nodes() {
			for _, model := range node.Models {
				if strings.TrimSpace(model) == "" {
					continue
				}
				key := engine + "\x00" + model
				candidate := candidates[key]
				candidate.Model = modelselection.ModelDescriptor{
					LogicalID: engine + ":" + model, EngineModelID: model,
					Family: modelFamily(model), ParameterCount: modelParameterCount(model),
					Capabilities: modelCapabilities(), Compatible: true,
				}
				candidate.Engine = engine
				candidate.Available = true
				if candidate.Model.ParameterCount > 0 {
					candidate.Model.EstimatedMemoryBytes = candidate.Model.ParameterCount * 2
				}
				for _, loaded := range node.LoadedModels {
					if f.profile.normalizeModel(loaded) == f.profile.normalizeModel(model) {
						candidate.Loaded = true
						break
					}
				}
				candidates[key] = candidate
			}
		}
	}
	result := make([]modelselection.RuntimeModel, 0, len(candidates))
	for _, candidate := range candidates {
		result = append(result, candidate)
	}
	return result
}

func (p *Proxy) resolveGatewayModel(alias string, requestBody []byte) *modelselection.Selection {
	return (modelselection.AutoModelSelector{}).Select(alias, modelselection.Requirements{
		Capabilities: requiredGatewayCapabilities(requestBody),
	}, p.gatewayInventory())
}

func modelParameterCount(model string) int64 {
	match := gatewayParameterPattern.FindStringSubmatch(model)
	if len(match) < 2 {
		return 0
	}
	value, _ := strconv.ParseFloat(match[1], 64)
	return int64(value * 1_000_000_000)
}

func modelFamily(model string) string {
	if index := strings.IndexAny(model, "-_"); index > 0 {
		return strings.ToLower(model[:index])
	}
	return strings.ToLower(model)
}

func modelCapabilities() map[string]bool {
	// Discovery currently provides model IDs only. Do not infer optional
	// capabilities from names; unknown models are eligible for basic chat only.
	return map[string]bool{"chat": true}
}

func requiredGatewayCapabilities(body []byte) map[string]bool {
	var request struct {
		Tools    json.RawMessage `json:"tools"`
		Messages []struct {
			Content json.RawMessage `json:"content"`
		} `json:"messages"`
	}
	if json.Unmarshal(body, &request) != nil {
		return nil
	}
	capabilities := map[string]bool{"chat": true}
	var tools []json.RawMessage
	if json.Unmarshal(request.Tools, &tools) == nil && len(tools) > 0 {
		capabilities["tools"] = true
	}
	for _, message := range request.Messages {
		var parts []struct {
			Type string `json:"type"`
		}
		if json.Unmarshal(message.Content, &parts) != nil {
			continue
		}
		for _, part := range parts {
			if part.Type == "image_url" || part.Type == "image" {
				capabilities["vision"] = true
				break
			}
		}
	}
	return capabilities
}

func enginePreference(engine string) int {
	for index, preferred := range gatewayEnginePreference {
		if engine == preferred {
			return index
		}
	}
	return len(gatewayEnginePreference)
}

func (p *Proxy) resolveGatewayFacade(model string) *facade {
	var candidates []*facade
	var loaded []*facade
	for _, f := range p.enabledFacades() {
		servesModel := false
		loadsModel := false
		for _, node := range f.discovery.Nodes() {
			if !nodeAdvertisesModel(f.profile, node, model) {
				continue
			}
			servesModel = true
			for _, loadedModel := range node.LoadedModels {
				if f.profile.normalizeModel(loadedModel) == f.profile.normalizeModel(model) {
					loadsModel = true
					break
				}
			}
		}
		if servesModel {
			candidates = append(candidates, f)
		}
		if loadsModel {
			loaded = append(loaded, f)
		}
	}
	if len(loaded) > 0 {
		candidates = loaded
	}
	if len(candidates) == 1 {
		return candidates[0]
	}
	for _, engine := range gatewayEnginePreference {
		for _, f := range candidates {
			if f.profile.Name == engine {
				return f
			}
		}
	}
	return nil
}

func (p *Proxy) enableGateway(port int) (int, error) {
	if port != 14326 {
		return 0, fmt.Errorf("gateway port must be %d", 14326)
	}
	p.gatewayMu.Lock()
	defer p.gatewayMu.Unlock()
	if p.gateway != nil {
		return port, nil
	}
	listener, err := net.Listen("tcp", gatewayAddress)
	if err != nil {
		return 0, err
	}
	server := &gatewayServer{srv: &http.Server{
		Handler:           http.HandlerFunc(p.serveGateway),
		ReadHeaderTimeout: gatewayReadHeaderTimeout,
		IdleTimeout:       gatewayIdleTimeout,
		MaxHeaderBytes:    gatewayMaxHeaderBytes,
	}}
	p.gateway = server
	go func() { _ = server.srv.Serve(listener) }()
	return port, nil
}

func (p *Proxy) serveGateway(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet && r.URL.Path == "/v1/models" {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]any{"object": "list", "data": p.gatewayModels()})
		return
	}
	if r.Method != http.MethodPost || r.URL.Path != "/v1/chat/completions" {
		http.NotFound(w, r)
		return
	}
	body, model, bodyErr := bufferBodyAndModel(w, r)
	if bodyErr != nil {
		writeRequestBodyError(w, bodyErr)
		return
	}
	if model == "" {
		writeGatewayError(w, http.StatusBadRequest, "model is required", "invalid_request_error")
		return
	}
	f := p.resolveGatewayFacade(model)
	if isGatewayAutoAlias(model) {
		candidate := p.resolveGatewayModel(model, body)
		if candidate == nil {
			writeGatewayError(w, http.StatusNotFound, "no available model matches the requested auto policy", "model_not_found")
			return
		}
		model = candidate.Model.EngineModelID
		f = p.facades[candidate.Engine]
		var err error
		body, err = rewriteGatewayModel(body, model)
		if err != nil {
			writeGatewayError(w, http.StatusBadRequest, "request body must be a JSON object", "invalid_request_error")
			return
		}
	}
	if f == nil {
		writeGatewayError(w, http.StatusNotFound, "model is unavailable", "model_not_found")
		return
	}
	r.Body = io.NopCloser(bytes.NewReader(body))
	r.ContentLength = int64(len(body))
	f.handleHTTP(w, r)
}

func isGatewayAutoAlias(model string) bool {
	for _, alias := range autoAliases {
		if model == alias {
			return true
		}
	}
	return false
}

func rewriteGatewayModel(body []byte, model string) ([]byte, error) {
	var request map[string]json.RawMessage
	if err := json.Unmarshal(body, &request); err != nil {
		return nil, fmt.Errorf("decode gateway request: %w", err)
	}
	if request == nil {
		return nil, fmt.Errorf("gateway request must be a JSON object")
	}
	encodedModel, err := json.Marshal(model)
	if err != nil {
		return nil, fmt.Errorf("encode resolved gateway model: %w", err)
	}
	request["model"] = encodedModel
	return json.Marshal(request)
}

func writeGatewayError(w http.ResponseWriter, status int, message, kind string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"error": map[string]string{"message": message, "type": kind}})
}

func (p *Proxy) stopGateway() {
	p.gatewayMu.Lock()
	server := p.gateway
	p.gateway = nil
	p.gatewayMu.Unlock()
	if server != nil {
		_ = server.srv.Close()
	}
}
