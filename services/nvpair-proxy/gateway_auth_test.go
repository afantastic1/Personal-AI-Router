// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import "testing"

func TestGatewayAuthenticatorRequiresConfiguredBearerToken(t *testing.T) {
	auth := &gatewayAuthenticator{}
	if auth.authorized("Bearer local-client-token-12345678901234567890") {
		t.Fatal("unconfigured token was authorized")
	}
	auth.setToken("short")
	if auth.configured() {
		t.Fatal("short token was accepted")
	}
	const token = "local-client-token-12345678901234567890"
	auth.setToken(token)
	for _, header := range []string{"", "Basic " + token, "Bearer wrong-token", "Bearer " + token + " extra"} {
		if auth.authorized(header) {
			t.Errorf("authorization header %q was accepted", header)
		}
	}
	if !auth.authorized("Bearer " + token) {
		t.Fatal("valid bearer token was rejected")
	}
}
