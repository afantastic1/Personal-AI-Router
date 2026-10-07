# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ArtifactDir,
    [Parameter(Mandatory = $true)]
    [ValidateSet('cpu', 'opencl')]
    [string] $Variant,
    [Parameter(Mandatory = $true)]
    [string] $ReadelfPath
)

$ErrorActionPreference = 'Stop'
$resolvedDir = [System.IO.Path]::GetFullPath($ArtifactDir)
if (-not (Test-Path -LiteralPath $ReadelfPath -PathType Leaf)) {
    throw "ELF verifier is missing: $ReadelfPath"
}

foreach ($name in @('libMNN.so', 'libMNN_Express.so', 'libllm.so', 'libc++_shared.so')) {
    $path = Join-Path $resolvedDir $name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required $Variant runtime library is missing: $path"
    }
}

$llmPath = Join-Path $resolvedDir 'libllm.so'
$dynamic = (& $ReadelfPath -d $llmPath 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) {
    throw "Unable to read dynamic dependencies for $llmPath`: $dynamic"
}
$dependsOnOpenCl = $dynamic -match '(?m)NEEDED.*\[libMNN_CL\.so\]'
if ($Variant -eq 'opencl') {
    $openClPath = Join-Path $resolvedDir 'libMNN_CL.so'
    if (-not (Test-Path -LiteralPath $openClPath -PathType Leaf)) {
        throw "Required OpenCL runtime library is missing: $openClPath"
    }
    if (-not $dependsOnOpenCl) {
        throw "OpenCL libllm.so is not linked against libMNN_CL.so: $llmPath"
    }
} elseif ($dependsOnOpenCl) {
    throw "CPU libllm.so unexpectedly depends on libMNN_CL.so: $llmPath"
}

Write-Output "Verified MNN runtime variant '$Variant' in $resolvedDir."
