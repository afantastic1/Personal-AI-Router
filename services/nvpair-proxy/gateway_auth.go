// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"crypto/subtle"
	"os"
	"strings"
	"sync"
)

const gatewayClientTokenEnvironment = "PAIR_GATEWAY_CLIENT_TOKEN"
const minimumGatewayClientTokenLength = 32

type gatewayAuthenticator struct {
	mu    sync.RWMutex
	token string
}

func newGatewayAuthenticator() *gatewayAuthenticator {
	authenticator := &gatewayAuthenticator{}
	authenticator.setToken(os.Getenv(gatewayClientTokenEnvironment))
	return authenticator
}

func (a *gatewayAuthenticator) setToken(token string) {
	if len(token) < minimumGatewayClientTokenLength || strings.ContainsAny(token, "\r\n ") {
		token = ""
	}
	a.mu.Lock()
	a.token = token
	a.mu.Unlock()
}

func (a *gatewayAuthenticator) configured() bool {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return a.token != ""
}

func (a *gatewayAuthenticator) authorized(authorization string) bool {
	scheme, token, found := strings.Cut(authorization, " ")
	if !found || !strings.EqualFold(scheme, "Bearer") || token == "" || strings.Contains(token, " ") {
		return false
	}
	a.mu.RLock()
	expected := a.token
	a.mu.RUnlock()
	if expected == "" || len(token) != len(expected) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(token), []byte(expected)) == 1
}
