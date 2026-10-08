// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const (
	defaultCloudMaxConcurrent     = 8
	defaultCloudMaxResponseBytes  = 8 << 20
	defaultCloudMaxSSEEventBytes  = 1 << 20
	cloudDialTimeout              = 10 * time.Second
	cloudTLSHandshakeTimeout      = 10 * time.Second
	cloudResponseHeaderTimeout    = 30 * time.Second
	cloudIdleConnectionTimeout    = 90 * time.Second
	defaultCloudStreamIdleTimeout = 2 * time.Minute
	defaultCloudNonStreamTimeout  = 2 * time.Minute
)

type cloudIPLookup func(context.Context, string, string) ([]net.IPAddr, error)

type cloudHTTPOptions struct {
	maxConcurrent     int
	maxResponseBytes  int64
	maxSSEEventBytes  int
	streamIdleTimeout time.Duration
	nonStreamTimeout  time.Duration
	allowLoopback     bool
	lookupIP          cloudIPLookup
	tlsConfig         *tls.Config
}

type cloudHTTPClient struct {
	client            *http.Client
	slots             chan struct{}
	maxResponseBytes  int64
	maxSSEEventBytes  int
	streamIdleTimeout time.Duration
	nonStreamTimeout  time.Duration
	allowLoopback     bool
	lookupIP          cloudIPLookup
}

func newCloudHTTPClient(options cloudHTTPOptions) *cloudHTTPClient {
	if options.maxConcurrent <= 0 {
		options.maxConcurrent = defaultCloudMaxConcurrent
	}
	if options.maxResponseBytes <= 0 {
		options.maxResponseBytes = defaultCloudMaxResponseBytes
	}
	if options.maxSSEEventBytes <= 0 {
		options.maxSSEEventBytes = defaultCloudMaxSSEEventBytes
	}
	if options.streamIdleTimeout <= 0 {
		options.streamIdleTimeout = defaultCloudStreamIdleTimeout
	}
	if options.nonStreamTimeout <= 0 {
		options.nonStreamTimeout = defaultCloudNonStreamTimeout
	}
	if options.lookupIP == nil {
		options.lookupIP = func(ctx context.Context, _, host string) ([]net.IPAddr, error) {
			return net.DefaultResolver.LookupIPAddr(ctx, host)
		}
	}
	transport := &http.Transport{
		Proxy:                 nil,
		DialContext:           nil,
		ForceAttemptHTTP2:     true,
		TLSHandshakeTimeout:   cloudTLSHandshakeTimeout,
		ResponseHeaderTimeout: cloudResponseHeaderTimeout,
		IdleConnTimeout:       cloudIdleConnectionTimeout,
		MaxIdleConns:          options.maxConcurrent,
		MaxIdleConnsPerHost:   options.maxConcurrent,
		TLSClientConfig:       options.tlsConfig,
	}
	cloudClient := &cloudHTTPClient{
		slots:             make(chan struct{}, options.maxConcurrent),
		maxResponseBytes:  options.maxResponseBytes,
		maxSSEEventBytes:  options.maxSSEEventBytes,
		streamIdleTimeout: options.streamIdleTimeout,
		nonStreamTimeout:  options.nonStreamTimeout,
		allowLoopback:     options.allowLoopback,
		lookupIP:          options.lookupIP,
	}
	transport.DialContext = cloudClient.dialContext
	cloudClient.client = &http.Client{
		Transport: transport,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	return cloudClient
}

func (c *cloudHTTPClient) CloseIdleConnections() {
	c.client.CloseIdleConnections()
}

func (c *cloudHTTPClient) DoChat(
	ctx context.Context,
	provider cloudProviderRuntime,
	target cloudModelTarget,
	credential string,
	rawBody []byte,
) (*http.Response, error) {
	if !provider.Enabled || !target.Enabled || provider.ID == "" || target.ProviderID != provider.ID {
		return nil, newCloudAdapterError(cloudErrUnavailable, nil)
	}
	if strings.TrimSpace(credential) == "" || len(credential) > 16<<10 || strings.ContainsAny(credential, "\r\n") {
		return nil, newCloudAdapterError(cloudErrInvalidCredential, nil)
	}
	requestBody, err := rewriteCloudRequestModel(rawBody, target.UpstreamID)
	if err != nil {
		return nil, newCloudAdapterError(cloudErrInvalidRequest, err)
	}
	endpoint, err := cloudChatEndpoint(provider.BaseURL)
	if err != nil {
		return nil, newCloudAdapterError(cloudErrInvalidEndpoint, err)
	}
	stream := cloudRequestWantsStream(requestBody)
	select {
	case c.slots <- struct{}{}:
	case <-ctx.Done():
		return nil, cloudTransportError(ctx.Err())
	}
	release := sync.OnceFunc(func() { <-c.slots })
	var requestContext context.Context
	var cancel context.CancelFunc
	if stream {
		requestContext, cancel = context.WithCancel(ctx)
	} else {
		requestContext, cancel = context.WithTimeout(ctx, c.nonStreamTimeout)
	}
	request, err := http.NewRequestWithContext(requestContext, http.MethodPost, endpoint, bytes.NewReader(requestBody))
	if err != nil {
		cancel()
		release()
		return nil, newCloudAdapterError(cloudErrInvalidEndpoint, err)
	}
	// POST replay is disabled even if the request constructor supplies GetBody.
	request.GetBody = nil
	request.Header.Set("Authorization", "Bearer "+credential)
	request.Header.Set("Content-Type", "application/json")
	if stream {
		request.Header.Set("Accept", "text/event-stream, application/json")
	} else {
		request.Header.Set("Accept", "application/json")
	}
	response, err := c.client.Do(request)
	if err != nil {
		cancel()
		release()
		return nil, cloudTransportError(err)
	}
	if stream && response.StatusCode >= http.StatusOK && response.StatusCode < http.StatusMultipleChoices &&
		strings.Contains(strings.ToLower(response.Header.Get("Content-Type")), "text/event-stream") {
		response.Body = &cloudSSEBody{
			ReadCloser: response.Body, maxEventBytes: c.maxSSEEventBytes,
			idleTimeout: c.streamIdleTimeout, cancel: cancel, release: release,
		}
		return response, nil
	}
	responseData, readErr := io.ReadAll(io.LimitReader(response.Body, c.maxResponseBytes+1))
	closeErr := response.Body.Close()
	cancel()
	release()
	if readErr != nil {
		if errors.Is(readErr, context.DeadlineExceeded) || errors.Is(readErr, context.Canceled) {
			return nil, cloudTransportError(readErr)
		}
		return nil, newCloudAdapterError(cloudErrResponseRead, readErr)
	}
	if closeErr != nil {
		return nil, newCloudAdapterError(cloudErrResponseClose, closeErr)
	}
	if int64(len(responseData)) > c.maxResponseBytes {
		return nil, newCloudAdapterError(cloudErrResponseTooLarge, nil)
	}
	response.Body = io.NopCloser(bytes.NewReader(responseData))
	response.ContentLength = int64(len(responseData))
	return response, nil
}

func rewriteCloudRequestModel(rawBody []byte, upstreamID string) ([]byte, error) {
	var payload map[string]json.RawMessage
	if err := json.Unmarshal(rawBody, &payload); err != nil || payload == nil {
		return nil, newCloudAdapterError(cloudErrInvalidRequest, err)
	}
	encoded, err := json.Marshal(upstreamID)
	if err != nil {
		return nil, newCloudAdapterError(cloudErrInvalidRequest, err)
	}
	payload["model"] = encoded
	return json.Marshal(payload)
}

func cloudChatEndpoint(baseURL string) (string, error) {
	parsed, err := url.Parse(baseURL)
	if err != nil || parsed.Scheme != "https" && parsed.Scheme != "http" || parsed.Hostname() == "" {
		return "", errors.New("provider API base URL is invalid")
	}
	path := strings.TrimRight(parsed.Path, "/")
	if strings.HasSuffix(path, "/v1") {
		parsed.Path = path + "/chat/completions"
	} else {
		parsed.Path = path + "/v1/chat/completions"
	}
	parsed.RawPath = ""
	return parsed.String(), nil
}

func cloudRequestWantsStream(rawBody []byte) bool {
	var request struct {
		Stream bool `json:"stream"`
	}
	return json.Unmarshal(rawBody, &request) == nil && request.Stream
}

func (c *cloudHTTPClient) dialContext(ctx context.Context, network, address string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return nil, errors.New("provider address is invalid")
	}
	if ip := net.ParseIP(host); ip != nil {
		if isForbiddenCloudIP(ip) && !(c.allowLoopback && ip.IsLoopback()) {
			return nil, errors.New("provider resolved to a disallowed IP address")
		}
		return (&net.Dialer{Timeout: cloudDialTimeout}).DialContext(ctx, network, net.JoinHostPort(ip.String(), port))
	}
	addresses, err := c.lookupIP(ctx, "ip", host)
	if err != nil {
		return nil, errors.New("provider hostname could not be resolved")
	}
	if len(addresses) == 0 {
		return nil, errors.New("provider hostname resolved to no addresses")
	}
	for _, resolved := range addresses {
		ip := resolved.IP
		if isForbiddenCloudIP(ip) && !(c.allowLoopback && ip.IsLoopback()) {
			return nil, errors.New("provider resolved to a disallowed IP address")
		}
		connection, dialErr := (&net.Dialer{Timeout: cloudDialTimeout}).DialContext(ctx, network, net.JoinHostPort(ip.String(), port))
		if dialErr == nil {
			return connection, nil
		}
	}
	return nil, errors.New("provider connection failed")
}

func isForbiddenCloudIP(address net.IP) bool {
	if address == nil || !address.IsGlobalUnicast() || address.IsPrivate() || address.IsLoopback() ||
		address.IsLinkLocalUnicast() || address.IsLinkLocalMulticast() {
		return true
	}
	reservedRanges := []string{
		"100.64.0.0/10", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24",
		"198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "2001::/23",
		"2001:db8::/32", "2002::/16", "64:ff9b::/96",
	}
	if address.To4() == nil {
		_, globalIPv6, _ := net.ParseCIDR("2000::/3")
		if globalIPv6 == nil || !globalIPv6.Contains(address) {
			return true
		}
	}
	for _, value := range reservedRanges {
		_, block, err := net.ParseCIDR(value)
		if err == nil && block.Contains(address) {
			return true
		}
	}
	return false
}

type cloudSSEBody struct {
	io.ReadCloser
	maxEventBytes int
	idleTimeout   time.Duration
	eventBytes    int
	line          []byte
	data          []byte
	sawDone       bool
	cancel        context.CancelFunc
	release       func()
	closeOnce     sync.Once
}

func (b *cloudSSEBody) Read(buffer []byte) (int, error) {
	if len(buffer) == 0 {
		return 0, nil
	}
	type readResult struct {
		data []byte
		n    int
		err  error
	}
	result := make(chan readResult, 1)
	readBuffer := make([]byte, len(buffer))
	go func() {
		n, err := b.ReadCloser.Read(readBuffer)
		result <- readResult{data: readBuffer, n: n, err: err}
	}()
	timer := time.NewTimer(b.idleTimeout)
	defer timer.Stop()
	var read readResult
	select {
	case read = <-result:
	case <-timer.C:
		b.cancel()
		read = <-result
		if read.n > 0 {
			copy(buffer, read.data[:read.n])
		}
		return read.n, newCloudAdapterError(cloudErrProviderTimeout, context.DeadlineExceeded)
	}
	if read.n > 0 {
		copy(buffer, read.data[:read.n])
	}
	n, err := read.n, read.err
	for _, value := range buffer[:n] {
		b.eventBytes++
		if b.eventBytes > b.maxEventBytes {
			return n, newCloudAdapterError(cloudErrSSEEventTooLarge, nil)
		}
		if value == '\n' {
			line := bytes.TrimSuffix(b.line, []byte{'\r'})
			if len(line) == 0 {
				if bytes.Equal(b.data, []byte("[DONE]")) {
					b.sawDone = true
				}
				b.data = b.data[:0]
				b.eventBytes = 0
			} else if bytes.HasPrefix(line, []byte("data:")) {
				value := bytes.TrimPrefix(line, []byte("data:"))
				if len(value) > 0 && value[0] == ' ' {
					value = value[1:]
				}
				if len(b.data) > 0 {
					b.data = append(b.data, '\n')
				}
				b.data = append(b.data, value...)
			}
			b.line = b.line[:0]
		} else if value != '\r' {
			b.line = append(b.line, value)
		}
	}
	if err == io.EOF && !b.sawDone {
		return n, newCloudAdapterError(cloudErrStreamTruncated, io.ErrUnexpectedEOF)
	}
	if errors.Is(err, context.Canceled) {
		return n, cloudTransportError(err)
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return n, cloudTransportError(err)
	}
	return n, err
}

func (b *cloudSSEBody) Close() error {
	var err error
	b.closeOnce.Do(func() {
		b.cancel()
		b.release()
		err = b.ReadCloser.Close()
	})
	return err
}
