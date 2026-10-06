# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $OutputDir,
    [string[]] $Services = @('nvpair-node-settings')
)

$ErrorActionPreference = 'Stop'
$androidRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repositoryRoot = (Resolve-Path (Join-Path $androidRoot '..')).Path
$servicesRoot = Join-Path $repositoryRoot 'services'
$versionsPath = Join-Path $servicesRoot 'versions.json'
$versions = Get-Content -Raw -LiteralPath $versionsPath | ConvertFrom-Json

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

foreach ($service in $Services) {
    $serviceRoot = Join-Path $servicesRoot $service
    if (-not (Test-Path -LiteralPath (Join-Path $serviceRoot 'go.mod') -PathType Leaf)) {
        throw "Unknown Go service module: $service"
    }
    $versionProperty = $versions.components.PSObject.Properties[$service]
    if ($null -eq $versionProperty -or [string]::IsNullOrWhiteSpace([string]$versionProperty.Value)) {
        throw "No component version is declared for $service in services/versions.json"
    }
    $outputPath = Join-Path $OutputDir $service

    Push-Location $serviceRoot
    try {
        $env:GOOS = 'android'
        $env:GOARCH = 'arm64'
        $env:CGO_ENABLED = '0'
        $ldflags = "-s -w -X main.Version=$($versionProperty.Value)"
        & go build -trimpath -buildmode=pie -ldflags $ldflags -o $outputPath '.'
        if ($LASTEXITCODE -ne 0) {
            throw "go build failed for $service with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }

    & (Join-Path $PSScriptRoot 'verify-native-binaries.ps1') -BinaryPath $outputPath
}
