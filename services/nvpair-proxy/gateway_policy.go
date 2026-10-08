// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"errors"
	"math"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"
)

var errCloudBudgetUnavailable = errors.New("cloud budget is not configured")
var errCloudQuotaExceeded = errors.New("cloud monthly budget would be exceeded")

type cloudBudgetState struct {
	Month    string             `json:"month"`
	SpentUSD float64            `json:"spentUsd"`
	Reserved map[string]float64 `json:"reservedUsd"`
}

type cloudBudgetLedger struct {
	mu    sync.Mutex
	path  string
	state cloudBudgetState
	next  uint64
}

type cloudBudgetReservation struct {
	ledger *cloudBudgetLedger
	id     string
	amount float64
	month  string
	once   sync.Once
}

func newCloudBudgetLedger(path string) (*cloudBudgetLedger, error) {
	ledger := &cloudBudgetLedger{
		path:  path,
		state: cloudBudgetState{Month: budgetMonth(time.Now()), Reserved: make(map[string]float64)},
	}
	data, err := os.ReadFile(path)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	if err == nil {
		if err := json.Unmarshal(data, &ledger.state); err != nil {
			return nil, err
		}
		if ledger.state.Month == "" || math.IsNaN(ledger.state.SpentUSD) || math.IsInf(ledger.state.SpentUSD, 0) || ledger.state.SpentUSD < 0 {
			return nil, errors.New("cloud budget ledger is invalid")
		}
		if ledger.state.Reserved == nil {
			ledger.state.Reserved = make(map[string]float64)
		}
		for id, amount := range ledger.state.Reserved {
			if id == "" || math.IsNaN(amount) || math.IsInf(amount, 0) || amount <= 0 {
				return nil, errors.New("cloud budget ledger reservation is invalid")
			}
			// After a process crash, a request may have reached the provider even
			// though its terminal result was never recorded. Charge its full risk
			// reservation instead of making that spend available again.
			ledger.state.SpentUSD += amount
		}
		ledger.state.Reserved = make(map[string]float64)
	}
	if ledger.state.Month != budgetMonth(time.Now()) {
		ledger.state = cloudBudgetState{Month: budgetMonth(time.Now()), Reserved: make(map[string]float64)}
	}
	if err == nil && len(ledger.state.Reserved) == 0 {
		if persistErr := ledger.persistLocked(); persistErr != nil {
			return nil, persistErr
		}
	}
	return ledger, nil
}

func (l *cloudBudgetLedger) reserve(monthlyBudgetUSD, requestLimitUSD float64) (*cloudBudgetReservation, error) {
	if !validPositiveMoney(monthlyBudgetUSD) || !validPositiveMoney(requestLimitUSD) {
		return nil, errCloudBudgetUnavailable
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	l.beginCurrentMonthLocked()
	if requestLimitUSD > monthlyBudgetUSD || l.state.SpentUSD+reservedTotal(l.state.Reserved)+requestLimitUSD > monthlyBudgetUSD+1e-9 {
		return nil, errCloudQuotaExceeded
	}
	l.next++
	id := l.state.Month + "-" + time.Now().UTC().Format("150405.000000000") + "-" + jsonNumber(l.next)
	l.state.Reserved[id] = requestLimitUSD
	if err := l.persistLocked(); err != nil {
		delete(l.state.Reserved, id)
		return nil, err
	}
	return &cloudBudgetReservation{ledger: l, id: id, amount: requestLimitUSD, month: l.state.Month}, nil
}

func (r *cloudBudgetReservation) settle(actualUSD *float64) error {
	var result error
	r.once.Do(func() {
		l := r.ledger
		l.mu.Lock()
		defer l.mu.Unlock()
		if l.state.Month != r.month {
			return
		}
		reserved, ok := l.state.Reserved[r.id]
		if !ok {
			return
		}
		charge := reserved
		if actualUSD != nil && validNonNegativeMoney(*actualUSD) {
			charge = *actualUSD
		}
		delete(l.state.Reserved, r.id)
		l.state.SpentUSD += charge
		result = l.persistLocked()
		if result != nil {
			// Keep the in-memory reservation charged if persistence fails. On
			// restart the persisted reservation is also conservatively charged.
			l.state.Reserved[r.id] = reserved
		}
	})
	return result
}

func (l *cloudBudgetLedger) beginCurrentMonthLocked() {
	month := budgetMonth(time.Now())
	if l.state.Month == month {
		return
	}
	l.state = cloudBudgetState{Month: month, Reserved: make(map[string]float64)}
}

func (l *cloudBudgetLedger) persistLocked() error {
	if err := os.MkdirAll(filepath.Dir(l.path), 0o700); err != nil {
		return err
	}
	data, err := json.Marshal(l.state)
	if err != nil {
		return err
	}
	tempPath := l.path + ".tmp"
	file, err := os.OpenFile(tempPath, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	if _, err := file.Write(data); err != nil {
		_ = file.Close()
		_ = os.Remove(tempPath)
		return err
	}
	if err := file.Sync(); err != nil {
		_ = file.Close()
		_ = os.Remove(tempPath)
		return err
	}
	if err := file.Close(); err != nil {
		_ = os.Remove(tempPath)
		return err
	}
	if err := os.Rename(tempPath, l.path); err != nil {
		_ = os.Remove(tempPath)
		return err
	}
	return nil
}

func reservedTotal(reserved map[string]float64) float64 {
	var total float64
	for _, amount := range reserved {
		total += amount
	}
	return total
}

func validPositiveMoney(value float64) bool {
	return value > 0 && validNonNegativeMoney(value)
}

func validNonNegativeMoney(value float64) bool {
	return value >= 0 && !math.IsNaN(value) && !math.IsInf(value, 0)
}

func budgetMonth(value time.Time) string { return value.UTC().Format("2006-01") }

func jsonNumber(value uint64) string { return strconv.FormatUint(value, 10) }
