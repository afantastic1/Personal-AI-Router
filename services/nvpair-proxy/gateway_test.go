// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestGatewayRequestCapabilitiesInspectStructuredImageParts(t *testing.T) {
	tests := []struct {
		name string
		body string
		want bool
	}{
		{name: "image content part", body: `{"messages":[{"content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,abc"}}]}]}`, want: true},
		{name: "ordinary text mentioning image", body: `{"messages":[{"content":"describe the image field in this JSON"}]}`},
		{name: "unrelated object field", body: `{"messages":[{"content":[{"type":"text","text":"image"}]}]}`},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got := requiredGatewayCapabilities([]byte(test.body))["vision"]
			if got != test.want {
				t.Fatalf("vision requirement = %v, want %v", got, test.want)
			}
		})
	}
}

func TestGatewayModelCapabilitiesDoNotInferFromNames(t *testing.T) {
	capabilities := modelCapabilities()
	if !capabilities["chat"] || capabilities["vision"] || capabilities["tools"] || capabilities["embeddings"] {
		t.Errorf("capabilities for unknown models = %v, want chat only", capabilities)
	}
}

func TestGatewayModelDirectoryUnionsEngineInventoriesWithoutNodeDetails(t *testing.T) {
	ollama, _ := profileFor("ollama")
	lmstudio, _ := profileFor("lmstudio")
	mnn, _ := profileFor("mnn")
	proxy := NewProxy(nil)
	proxy.facades = map[string]*facade{
		"ollama":   newFacade(proxy, ollama, NewDiscovery(), 11435),
		"lmstudio": newFacade(proxy, lmstudio, NewDiscovery(), 1234),
		"mnn":      newFacade(proxy, mnn, NewDiscovery(), 14325),
	}
	proxy.facades["ollama"].discovery.AddManual(Node{ID: "pc-secret", Models: []string{"qwen3-8b", "shared"}})
	proxy.facades["lmstudio"].discovery.AddManual(Node{ID: "peer-secret", Models: []string{"shared", "llama"}})
	proxy.facades["mnn"].discovery.AddManual(Node{ID: "phone-secret", Models: []string{"qwen3-1.7b"}})

	models := proxy.gatewayModels()
	want := []string{
		"llama", "local/lmstudio/shared", "local/mnn/qwen3-1.7b",
		"local/ollama/qwen3-8b", "local/ollama/shared", "qwen3-1.7b", "qwen3-8b",
	}
	for _, id := range want {
		found := false
		for _, model := range models {
			if model.ID == id && model.OwnedBy == "pair" {
				found = true
				break
			}
		}
		if !found {
			t.Errorf("model list = %+v, missing id=%q owned_by=pair", models, id)
		}
	}
}

func TestGatewayModelDirectoryAdvertisesAutoAliases(t *testing.T) {
	proxy := NewProxy(nil)
	models := proxy.gatewayModels()
	for _, alias := range []string{"auto", "auto-fast", "auto-balanced", "auto-best"} {
		found := false
		for _, model := range models {
			if model.ID == alias {
				found = true
				break
			}
		}
		if !found {
			t.Errorf("model list does not advertise %q", alias)
		}
	}
}

func TestGatewayAutoPoliciesSelectOnlyAdvertisedModels(t *testing.T) {
	proxy := NewProxy(nil)
	ollama, _ := profileFor("ollama")
	mnn, _ := profileFor("mnn")
	proxy.facades = map[string]*facade{
		"ollama": newFacade(proxy, ollama, NewDiscovery(), 11435),
		"mnn":    newFacade(proxy, mnn, NewDiscovery(), 14325),
	}
	proxy.facades["ollama"].discovery.AddManual(Node{ID: "pc", Models: []string{"qwen3-8b"}})
	proxy.facades["mnn"].discovery.AddManual(Node{ID: "phone", Models: []string{"qwen3-1.7b"}, LoadedModels: []string{"qwen3-1.7b"}})

	if got := proxy.resolveGatewayModel(AUTO_FAST_ALIAS, nil); got == nil || got.Model.EngineModelID != "qwen3-1.7b" || got.Engine != "mnn" {
		t.Fatalf("fast selection = %+v, want loaded phone model", got)
	}
	if got := proxy.resolveGatewayModel(AUTO_BEST_ALIAS, nil); got == nil || got.Model.EngineModelID != "qwen3-8b" || got.Engine != "ollama" {
		t.Fatalf("best selection = %+v, want larger PC model", got)
	}
	if got := proxy.resolveGatewayModel(AUTO_ALIAS, nil); got == nil || got.Model.EngineModelID != "qwen3-1.7b" {
		t.Fatalf("default auto selection = %+v, want balanced alias selection", got)
	}
}

func TestGatewayRejectsAmbiguousBareModelID(t *testing.T) {
	proxy := NewProxy(NewCodec(rwNop{}))
	ollama, _ := profileFor("ollama")
	lmstudio, _ := profileFor("lmstudio")
	proxy.facades = map[string]*facade{
		"ollama":   newFacade(proxy, ollama, NewDiscovery(), 11435),
		"lmstudio": newFacade(proxy, lmstudio, NewDiscovery(), 1234),
	}
	proxy.facades["ollama"].discovery.AddManual(Node{ID: "pc", Models: []string{"shared"}})
	proxy.facades["lmstudio"].discovery.AddManual(Node{ID: "workstation", Models: []string{"shared"}})
	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(
		`{"model":"shared","messages":[{"role":"user","content":"hello"}]}`,
	))
	response := httptest.NewRecorder()
	proxy.serveGateway(response, request)
	if response.Code != http.StatusConflict || !strings.Contains(response.Body.String(), "model_conflict") {
		t.Fatalf("ambiguous model response=%d %s", response.Code, response.Body.String())
	}
}

func TestGatewayAutoToolsRequestDoesNotRouteToMNN(t *testing.T) {
	var hits atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		hits.Add(1)
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()
	proxy := NewProxy(NewCodec(rwNop{}))
	mnn, _ := profileFor("mnn")
	discovery := NewDiscovery()
	discovery.AddManual(nodeForModel(t, "phone", upstream.URL, "qwen-tool-0.6b"))
	proxy.facades = map[string]*facade{"mnn": newFacade(proxy, mnn, discovery, 14325)}
	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(
		`{"model":"auto-balanced","messages":[{"role":"user","content":"hello"}],"tools":[{"type":"function"}]}`,
	))
	response := httptest.NewRecorder()
	proxy.serveGateway(response, request)

	if response.Code != http.StatusUnprocessableEntity {
		t.Fatalf("gateway status = %d body=%s, want no compatible model", response.Code, response.Body.String())
	}
	if hits.Load() != 0 {
		t.Fatalf("MNN upstream hits = %d, want 0", hits.Load())
	}
}

func TestGatewayAutoChatRequestCanRouteToMNNAndRewritesOnlyModel(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Errorf("read upstream request: %v", err)
		}
		var payload map[string]json.RawMessage
		if err := json.Unmarshal(body, &payload); err != nil {
			t.Errorf("decode upstream request: %v", err)
		}
		var payloadModel string
		if err := json.Unmarshal(payload["model"], &payloadModel); err != nil || payloadModel != "qwen3-1.7b" {
			t.Errorf("resolved model = %q, err = %v", payloadModel, err)
		}
		var stream bool
		if err := json.Unmarshal(payload["stream"], &stream); err != nil || !stream {
			t.Errorf("stream = %v, err = %v; rewriting the alias must preserve other request fields", stream, err)
		}
		if !strings.Contains(string(body), `"messages"`) {
			t.Errorf("rewritten body lost messages: %s", body)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, `{"choices":[]}`)
	}))
	defer upstream.Close()
	proxy := NewProxy(NewCodec(rwNop{}))
	mnn, _ := profileFor("mnn")
	discovery := NewDiscovery()
	discovery.AddManual(nodeForModel(t, "phone", upstream.URL, "qwen3-1.7b"))
	proxy.facades = map[string]*facade{"mnn": newFacade(proxy, mnn, discovery, 14325)}
	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(`{"model":"auto-fast","messages":[{"role":"user","content":"hello"}],"stream":true}`))
	response := httptest.NewRecorder()
	proxy.serveGateway(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("gateway status = %d body=%s", response.Code, response.Body.String())
	}
}

func TestGatewayModelListUsesOpenAIShapeAndHidesRoutingDetails(t *testing.T) {
	ollama, _ := profileFor("ollama")
	discovery := NewDiscovery()
	node := nodeForModel(t, "private-node-id", "http://192.0.2.44:11434", "qwen3-8b")
	discovery.AddManual(node)
	p := newTestProxy(ollama, NewCodec(rwNop{}), discovery, 11435)
	response := httptest.NewRecorder()
	p.serveGateway(response, httptest.NewRequest(http.MethodGet, "http://127.0.0.1:14326/v1/models", nil))
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}
	var payload struct {
		Data   []gatewayModel `json:"data"`
		Object string         `json:"object"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if payload.Object != "list" {
		t.Fatalf("model list = %+v", payload)
	}
	foundModel := false
	foundAliases := map[string]bool{}
	for _, model := range payload.Data {
		if model.ID == "qwen3-8b" && model.OwnedBy == "pair" {
			foundModel = true
		}
		if isGatewayAutoAlias(model.ID) {
			foundAliases[model.ID] = true
		}
	}
	if !foundModel || len(foundAliases) != len(autoAliases) {
		t.Fatalf("model list missing explicit model or auto aliases: %+v", payload.Data)
	}
	for _, private := range []string{"private-node-id", "192.0.2.44", "11434"} {
		if strings.Contains(response.Body.String(), private) {
			t.Errorf("model list disclosed %q: %s", private, response.Body.String())
		}
	}
}

func TestGatewayResolvesDuplicateModelByCentralEnginePreference(t *testing.T) {
	proxy := NewProxy(nil)
	ollama, _ := profileFor("ollama")
	lmstudio, _ := profileFor("lmstudio")
	mnn, _ := profileFor("mnn")
	proxy.facades = map[string]*facade{
		"lmstudio": newFacade(proxy, lmstudio, NewDiscovery(), 1234),
		"ollama":   newFacade(proxy, ollama, NewDiscovery(), 11435),
		"mnn":      newFacade(proxy, mnn, NewDiscovery(), 14325),
	}
	for _, engine := range []string{"lmstudio", "ollama", "mnn"} {
		proxy.facades[engine].discovery.AddManual(Node{ID: engine, Models: []string{"shared"}})
	}
	got := proxy.resolveGatewayFacade("shared")
	if got == nil || got.profile.Name != "mnn" {
		t.Fatalf("resolved facade = %v, want mnn by configured default preference", got)
	}
}

func TestGatewayPrefersEngineWithLoadedModelBeforeEnginePreference(t *testing.T) {
	proxy := NewProxy(nil)
	ollama, _ := profileFor("ollama")
	lmstudio, _ := profileFor("lmstudio")
	mnn, _ := profileFor("mnn")
	proxy.facades = map[string]*facade{
		"lmstudio": newFacade(proxy, lmstudio, NewDiscovery(), 1234),
		"ollama":   newFacade(proxy, ollama, NewDiscovery(), 11435),
		"mnn":      newFacade(proxy, mnn, NewDiscovery(), 14325),
	}
	for _, engine := range []string{"lmstudio", "ollama", "mnn"} {
		loaded := []string(nil)
		if engine == "lmstudio" {
			loaded = []string{"shared"}
		}
		proxy.facades[engine].discovery.AddManual(Node{ID: engine, Models: []string{"shared"}, LoadedModels: loaded})
	}
	if got := proxy.resolveGatewayFacade("shared"); got == nil || got.profile.Name != "lmstudio" {
		t.Fatalf("resolved facade = %v, want loaded lmstudio model", got)
	}
}

func TestGatewayChatRoutesExplicitModelThroughEngineFacade(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/chat/completions" {
			t.Errorf("path = %q", r.URL.Path)
		}
		body, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(body), `"model":"qwen3-8b"`) {
			t.Errorf("body = %s", body)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"choices":[{"message":{"content":"routed"}}]}`)
	}))
	defer upstream.Close()
	ollama, _ := profileFor("ollama")
	discovery := NewDiscovery()
	discovery.AddManual(nodeForModel(t, "pc-private", upstream.URL, "qwen3-8b"))
	p := newTestProxy(ollama, NewCodec(rwNop{}), discovery, 11435)
	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(`{"model":"qwen3-8b","messages":[]}`))
	request.Header.Set("Authorization", "Bearer arbitrary-placeholder-or-client-key")
	response := httptest.NewRecorder()
	p.serveGateway(response, request)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), "routed") {
		t.Fatalf("gateway response = %d %s", response.Code, response.Body.String())
	}
}

func TestGatewayMissingModelReturnsOpenAIInvalidRequestError(t *testing.T) {
	p := NewProxy(NewCodec(rwNop{}))
	response := httptest.NewRecorder()
	p.serveGateway(response, httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(`{"messages":[]}`)))
	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}
	if response.Header().Get("Content-Type") != "application/json" {
		t.Errorf("content type = %q, want application/json", response.Header().Get("Content-Type"))
	}
	if !strings.Contains(response.Body.String(), "invalid_request_error") {
		t.Errorf("body = %s", response.Body.String())
	}
}

func TestGatewayRejectsOversizedRequestBody(t *testing.T) {
	request := httptest.NewRequest(
		http.MethodPost,
		"http://127.0.0.1:14326/v1/chat/completions",
		strings.NewReader(strings.Repeat("x", (64<<20)+1)),
	)
	response := httptest.NewRecorder()
	NewProxy(NewCodec(rwNop{})).serveGateway(response, request)

	if response.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("gateway status = %d, want %d", response.Code, http.StatusRequestEntityTooLarge)
	}
}

func TestGatewayRoutesAuthorizedCloudModelAndMapsPublicModelID(t *testing.T) {
	var upstreamHits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		upstreamHits.Add(1)
		if r.URL.Path != "/v1/chat/completions" {
			t.Errorf("upstream path=%q", r.URL.Path)
		}
		if got := r.Header.Get("Authorization"); got != "Bearer provider-test-key" {
			t.Errorf("upstream authorization=%q", got)
		}
		var payload map[string]json.RawMessage
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Errorf("decode upstream request: %v", err)
		}
		var model string
		if err := json.Unmarshal(payload["model"], &model); err != nil || model != "deepseek-chat" {
			t.Errorf("upstream model=%q err=%v", model, err)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"chatcmpl-1","model":"deepseek-chat","choices":[],"provider_extra":{"kept":true}}`)
	}))
	defer server.Close()

	proxy := NewProxy(nil)
	config := strings.Replace(validCloudConfig, "https://api.deepseek.com", server.URL, 1)
	if err := proxy.gatewayDispatcher.registry.ReplaceJSON([]byte(config), cloudConfigOptions{allowLoopbackURL: true}); err != nil {
		t.Fatalf("load provider config: %v", err)
	}
	proxy.gatewayDispatcher.client = newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{cloudEnabled: true, policy: gatewayPolicyCloudOnly})
	proxy.gatewayDispatcher.resolveCredential = func(string) (string, error) { return "provider-test-key", nil }
	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(
		`{"model":"cloud/deepseek/deepseek-chat","messages":[{"role":"user","content":"hello"}]}`,
	))
	response := httptest.NewRecorder()
	proxy.serveGateway(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("gateway status=%d body=%s", response.Code, response.Body.String())
	}
	var payload struct {
		Model         string          `json:"model"`
		ProviderExtra json.RawMessage `json:"provider_extra"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode Gateway response: %v", err)
	}
	if payload.Model != "cloud/deepseek/deepseek-chat" || string(payload.ProviderExtra) != `{"kept":true}` {
		t.Fatalf("public response model=%q provider_extra=%s", payload.Model, payload.ProviderExtra)
	}
	if got := upstreamHits.Load(); got != 1 {
		t.Fatalf("cloud upstream hit count=%d, want 1", got)
	}
}

func TestGatewayCloudRoutingIsDisabledByDefault(t *testing.T) {
	var upstreamHits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		upstreamHits.Add(1)
	}))
	defer server.Close()
	proxy := NewProxy(nil)
	if err := proxy.gatewayDispatcher.registry.ReplaceJSON([]byte(strings.Replace(validCloudConfig, "https://api.deepseek.com", server.URL, 1)), cloudConfigOptions{allowLoopbackURL: true}); err != nil {
		t.Fatalf("load provider config: %v", err)
	}
	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(
		`{"model":"cloud/deepseek/deepseek-chat","messages":[]}`,
	))
	response := httptest.NewRecorder()
	proxy.serveGateway(response, request)
	if response.Code != http.StatusForbidden || !strings.Contains(response.Body.String(), "cloud_not_allowed") {
		t.Fatalf("disabled Cloud response=%d %s", response.Code, response.Body.String())
	}
	if got := upstreamHits.Load(); got != 0 {
		t.Fatalf("disabled cloud route reached upstream %d times", got)
	}
}
