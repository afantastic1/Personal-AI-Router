// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestCloudBudgetRequiresMonthlyAndPerRequestLimits(t *testing.T) {
	ledger, err := newCloudBudgetLedger(filepath.Join(t.TempDir(), "budget.json"))
	if err != nil {
		t.Fatalf("create ledger: %v", err)
	}
	if _, err := ledger.reserve(0, 0.1); !errors.Is(err, errCloudBudgetUnavailable) {
		t.Fatalf("zero monthly budget error=%v, want budget unavailable", err)
	}
	if _, err := ledger.reserve(1, 0); !errors.Is(err, errCloudBudgetUnavailable) {
		t.Fatalf("zero per-request limit error=%v, want budget unavailable", err)
	}
}

func TestCloudBudgetReservesConcurrentlyAndPersistsSettlement(t *testing.T) {
	path := filepath.Join(t.TempDir(), "budget.json")
	ledger, err := newCloudBudgetLedger(path)
	if err != nil {
		t.Fatalf("create ledger: %v", err)
	}
	first, err := ledger.reserve(1, 0.7)
	if err != nil {
		t.Fatalf("first reserve: %v", err)
	}
	if _, err := ledger.reserve(1, 0.4); !errors.Is(err, errCloudQuotaExceeded) {
		t.Fatalf("second reserve error=%v, want quota exceeded", err)
	}
	actual := 0.2
	if err := first.settle(&actual); err != nil {
		t.Fatalf("settle first reservation: %v", err)
	}
	second, err := ledger.reserve(1, 0.7)
	if err != nil {
		t.Fatalf("reserve remaining budget after settlement: %v", err)
	}
	if err := second.settle(nil); err != nil {
		t.Fatalf("settle unknown-cost reservation: %v", err)
	}
	reloaded, err := newCloudBudgetLedger(path)
	if err != nil {
		t.Fatalf("reload ledger: %v", err)
	}
	if _, err := reloaded.reserve(1, 0.2); !errors.Is(err, errCloudQuotaExceeded) {
		t.Fatalf("reserve after persisted settlement error=%v, want quota exceeded", err)
	}
}

func TestCloudBudgetChargesOutstandingReservationAfterRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "budget.json")
	ledger, err := newCloudBudgetLedger(path)
	if err != nil {
		t.Fatalf("create ledger: %v", err)
	}
	if _, err := ledger.reserve(1, 0.6); err != nil {
		t.Fatalf("reserve: %v", err)
	}
	restarted, err := newCloudBudgetLedger(path)
	if err != nil {
		t.Fatalf("recover ledger: %v", err)
	}
	if _, err := restarted.reserve(1, 0.5); !errors.Is(err, errCloudQuotaExceeded) {
		t.Fatalf("reserve after recovery error=%v, want conservative quota exhaustion", err)
	}
}

func TestCloudBudgetRejectsCorruptLedger(t *testing.T) {
	path := filepath.Join(t.TempDir(), "budget.json")
	if err := os.WriteFile(path, []byte(`{"month":"2026-10","spentUsd":-1}`), 0o600); err != nil {
		t.Fatalf("write fixture: %v", err)
	}
	if _, err := newCloudBudgetLedger(path); err == nil {
		t.Fatal("corrupt ledger unexpectedly loaded")
	}
}
