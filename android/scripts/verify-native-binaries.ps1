# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $BinaryPath
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $BinaryPath -PathType Leaf)) {
    throw "Native binary does not exist: $BinaryPath"
}

$header = [IO.File]::ReadAllBytes($BinaryPath)
if ($header.Length -lt 20 -or $header[0] -ne 0x7f -or $header[1] -ne 0x45 -or $header[2] -ne 0x4c -or $header[3] -ne 0x46) {
    throw "Not an ELF binary: $BinaryPath"
}

$machine = [BitConverter]::ToUInt16($header, 18)
if ($machine -ne 183) {
    throw "Expected AArch64 ELF machine 183, got ${machine}: $BinaryPath"
}

$type = [BitConverter]::ToUInt16($header, 16)
if ($type -ne 3) {
    throw "Expected PIE ELF type 3 (ET_DYN), got ${type}: $BinaryPath"
}

Write-Output "Verified AArch64 PIE ELF: $BinaryPath"
