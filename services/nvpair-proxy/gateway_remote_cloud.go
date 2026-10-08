// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"sort"
	"strings"
	"time"
)

const (
	peerCloudModelsPath      = "/v1/pair/cloud/models"
	peerCloudCompletionsPath = "/v1/pair/cloud/chat/completions"
	peerCloudLookupTimeout   = 2 * time.Second
	peerCloudResponseLimit   = 64 << 10
	peerCloudCacheLifetime   = 10 * time.Second
)

type remoteCloudModel struct {
	ID           string
	Capabilities map[string]bool
	Node         Node
	Facade       *facade
}

type peerCloudModelsResponse struct {
	Enabled bool `json:"enabled"`
	Models  []struct {
		ID           string   `json:"id"`
		Capabilities []string `json:"capabilities"`
	} `json:"models"`
}

func (p *Proxy) remoteCloudModels() []remoteCloudModel {
	catalog := p.remoteCloudCatalog("")
	counts := make(map[string]int, len(catalog))
	for _, model := range catalog {
		counts[model.ID]++
	}
	unique := make([]remoteCloudModel, 0, len(catalog))
	for _, model := range catalog {
		if counts[model.ID] == 1 {
			unique = append(unique, model)
		}
	}
	return unique
}

func (p *Proxy) remoteCloudTarget(publicID string) (remoteCloudModel, bool) {
	matches := p.remoteCloudCatalog(publicID)
	if len(matches) != 1 {
		return remoteCloudModel{}, false
	}
	return matches[0], true
}

func (p *Proxy) remoteCloudCatalog(wantID string) []remoteCloudModel {
	d := p.gatewayDispatcher
	d.remoteCloudMu.Lock()
	defer d.remoteCloudMu.Unlock()
	if time.Now().After(d.remoteCloudExpires) {
		d.remoteCloudCache = p.fetchRemoteCloudCatalog()
		d.remoteCloudExpires = time.Now().Add(peerCloudCacheLifetime)
	}
	if wantID == "" {
		return append([]remoteCloudModel(nil), d.remoteCloudCache...)
	}
	var matches []remoteCloudModel
	for _, model := range d.remoteCloudCache {
		if model.ID == wantID {
			matches = append(matches, model)
		}
	}
	return matches
}

func (p *Proxy) fetchRemoteCloudCatalog() []remoteCloudModel {
	if p.mesh == nil {
		return nil
	}
	p.mesh.Refresh()
	facades := p.enabledFacades()
	nodes := make(map[string]struct {
		node   Node
		facade *facade
	})
	for _, f := range facades {
		for _, node := range f.discovery.Nodes() {
			if node.ClusterUUID == "" || node.ClusterUUID == p.mesh.NodeUUID() || !p.mesh.HasPin(node.ClusterUUID) {
				continue
			}
			if _, exists := nodes[node.ClusterUUID]; !exists {
				nodes[node.ClusterUUID] = struct {
					node   Node
					facade *facade
				}{node: node, facade: f}
			}
		}
	}
	peerIDs := make([]string, 0, len(nodes))
	for peerID := range nodes {
		peerIDs = append(peerIDs, peerID)
	}
	sort.Strings(peerIDs)
	var models []remoteCloudModel
	for _, peerID := range peerIDs {
		entry := nodes[peerID]
		models = append(models, p.fetchPeerCloudModels(entry.node, entry.facade)...)
	}
	return models
}

func (p *Proxy) fetchPeerCloudModels(node Node, f *facade) []remoteCloudModel {
	target := nodeURL(node)
	if target == nil || f == nil {
		return nil
	}
	target.Scheme = "https"
	target.Path = peerCloudModelsPath
	ctx, cancel := context.WithTimeout(context.Background(), peerCloudLookupTimeout)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, target.String(), nil)
	if err != nil {
		return nil
	}
	tlsConfig, ok := p.mesh.ClientTLSConfig(node.ClusterUUID)
	if !ok {
		return nil
	}
	transport := newProxyTransport(tlsConfig)
	defer transport.CloseIdleConnections()
	response, err := (&http.Client{Transport: transport, Timeout: peerCloudLookupTimeout}).Do(request)
	if err != nil {
		return nil
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil
	}
	var snapshot peerCloudModelsResponse
	decoder := json.NewDecoder(io.LimitReader(response.Body, peerCloudResponseLimit))
	if err := decoder.Decode(&snapshot); err != nil || !snapshot.Enabled {
		return nil
	}
	models := make([]remoteCloudModel, 0, len(snapshot.Models))
	for _, model := range snapshot.Models {
		if !strings.HasPrefix(model.ID, "cloud/") || model.ID == "cloud/" {
			continue
		}
		capabilities := make(map[string]bool, len(model.Capabilities))
		for _, capability := range model.Capabilities {
			if capability != "" {
				capabilities[capability] = true
			}
		}
		models = append(models, remoteCloudModel{ID: model.ID, Capabilities: capabilities, Node: node, Facade: f})
	}
	return models
}

func (d *gatewayDispatcher) dispatchRemoteCloud(w http.ResponseWriter, r *http.Request, body []byte, route gatewayRoute) {
	if route.remoteNode == nil || route.remoteFacade == nil || route.remotePeerUUID == "" {
		writeGatewayError(w, http.StatusServiceUnavailable, "cloud execution host is unavailable", "cloud_unavailable")
		return
	}
	d.proxy.mesh.Refresh()
	if !d.proxy.mesh.HasPin(route.remotePeerUUID) {
		writeGatewayError(w, http.StatusServiceUnavailable, "cloud execution host is no longer paired", "cloud_unavailable")
		return
	}
	target := nodeURL(*route.remoteNode)
	if target == nil {
		writeGatewayError(w, http.StatusServiceUnavailable, "cloud execution host is unavailable", "cloud_unavailable")
		return
	}
	target.Scheme = "https"
	target.Path = peerCloudCompletionsPath
	request, err := http.NewRequestWithContext(r.Context(), http.MethodPost, target.String(), bytes.NewReader(body))
	if err != nil {
		writeGatewayError(w, http.StatusBadGateway, "cloud execution host request failed", "cloud_unavailable")
		return
	}
	request.Header.Set("Content-Type", "application/json")
	if r.Header.Get("Accept") != "" {
		request.Header.Set("Accept", r.Header.Get("Accept"))
	}
	tlsConfig, ok := d.proxy.mesh.ClientTLSConfig(route.remotePeerUUID)
	if !ok {
		writeGatewayError(w, http.StatusServiceUnavailable, "cloud execution host is no longer paired", "cloud_unavailable")
		return
	}
	transport := newProxyTransport(tlsConfig)
	defer transport.CloseIdleConnections()
	response, err := (&http.Client{Transport: transport}).Do(request)
	if err != nil {
		writeGatewayError(w, http.StatusBadGateway, "cloud execution host is unavailable", "cloud_unavailable")
		return
	}
	defer response.Body.Close()
	for _, header := range []string{"Content-Type", "Cache-Control", "Retry-After"} {
		if value := response.Header.Get(header); value != "" {
			w.Header().Set(header, value)
		}
	}
	w.WriteHeader(response.StatusCode)
	if _, err := io.Copy(w, response.Body); err != nil {
		return
	}
}
