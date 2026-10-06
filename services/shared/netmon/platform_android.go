//go:build android

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package netmon

func isAndroidBuild() bool { return true }

func platformOS() string { return "android" }
