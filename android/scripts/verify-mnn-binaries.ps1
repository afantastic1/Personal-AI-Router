# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES.
# SPDX-License-Identifier: Apache-2.0

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ArtifactDir,

    [string] $ReadelfPath
)

$ErrorActionPreference = 'Stop'
$expectedRevision = 'd407447ed56c4121a11ccbd266dc184ca1ead0c2'
$resolvedArtifactDir = [System.IO.Path]::GetFullPath($ArtifactDir)

$requiredBinaries = @('libMNN.so', 'libMNN_Express.so', 'libllm.so', 'libpair_mnn.so', 'libc++_shared.so')
foreach ($name in $requiredBinaries) {
    $path = Join-Path $resolvedArtifactDir $name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required MNN artifact is missing: $path"
    }
}

$revisionFile = Join-Path $resolvedArtifactDir 'source-revision.txt'
if (-not (Test-Path -LiteralPath $revisionFile -PathType Leaf)) {
    throw "Required MNN source revision manifest is missing: $revisionFile"
}
$actualRevision = (Get-Content -LiteralPath $revisionFile -Raw).Trim()
if ($actualRevision -ne $expectedRevision) {
    throw "MNN source revision mismatch: expected $expectedRevision, found '$actualRevision' in $revisionFile"
}

if ([string]::IsNullOrWhiteSpace($ReadelfPath)) {
    $candidate = Get-Command llvm-readelf.exe -ErrorAction SilentlyContinue
    if ($null -ne $candidate) {
        $ReadelfPath = $candidate.Source
    } else {
        throw 'llvm-readelf.exe is missing; supply -ReadelfPath from the pinned Android NDK toolchain.'
    }
}
if (-not (Test-Path -LiteralPath $ReadelfPath -PathType Leaf)) {
    throw "ELF verifier is missing: $ReadelfPath"
}

$pageSize = [UInt64] 16384
$binaryFiles = @(Get-ChildItem -LiteralPath $resolvedArtifactDir -Filter '*.so' -File -Recurse)
foreach ($name in $requiredBinaries) {
    if (-not ($binaryFiles | Where-Object { $_.FullName -eq (Join-Path $resolvedArtifactDir $name) })) {
        throw "Required MNN artifact is missing: $(Join-Path $resolvedArtifactDir $name)"
    }
}

foreach ($binary in $binaryFiles) {
    $path = $binary.FullName
    $header = (& $ReadelfPath -h $path 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to read ELF header for $path`: $header"
    }
    if ($header -notmatch '(?m)^\s*Machine:\s+AArch64\s*$') {
        throw "ELF machine mismatch for $path`: expected AArch64."
    }

    $programHeaders = (& $ReadelfPath -lW $path 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to read ELF load segments for $path`: $programHeaders"
    }
    $loadLines = @($programHeaders -split "`r?`n" | Where-Object { $_ -match '^\s*LOAD\s+' })
    if ($loadLines.Count -eq 0) {
        throw "ELF has no PT_LOAD segments: $path"
    }
    foreach ($line in $loadLines) {
        $fields = @($line.Trim() -split '\s+')
        if ($fields.Count -lt 8 -or $fields[0] -ne 'LOAD') {
            throw "Unable to parse PT_LOAD alignment for $path`: $line"
        }
        $offset = [Convert]::ToUInt64($fields[1], 16)
        $virtualAddress = [Convert]::ToUInt64($fields[2], 16)
        $alignment = [Convert]::ToUInt64($fields[-1], 16)
        if ($alignment -lt $pageSize -or ($offset % $pageSize) -ne ($virtualAddress % $pageSize)) {
            throw "ELF PT_LOAD is not 16 KB compatible for $path`: $line"
        }
    }
    Write-Output "Verified AArch64 ELF with 16 KB PT_LOAD alignment: $path"
}

$systemLibraries = @('libandroid.so', 'libc.so', 'libdl.so', 'liblog.so', 'libm.so')
foreach ($binary in $binaryFiles) {
    $dynamic = (& $ReadelfPath -d $binary.FullName 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to read ELF dynamic dependencies for $($binary.FullName)`: $dynamic"
    }
    $dependencies = [regex]::Matches($dynamic, '\(NEEDED\).*\[([^\]]+)\]')
    foreach ($dependency in $dependencies) {
        $dependencyName = $dependency.Groups[1].Value
        if ($dependencyName -notin $systemLibraries -and
            -not (Test-Path -LiteralPath (Join-Path $binary.DirectoryName $dependencyName) -PathType Leaf)) {
            throw "ELF dependency is not staged beside $($binary.FullName): $dependencyName"
        }
    }
}

$openClDir = Join-Path $resolvedArtifactDir 'opencl'
if (Test-Path -LiteralPath $openClDir -PathType Container) {
    foreach ($name in @('libMNN.so', 'libMNN_CL.so', 'libMNN_Express.so', 'libllm.so', 'libc++_shared.so')) {
        $path = Join-Path $openClDir $name
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Required OpenCL MNN artifact is missing: $path"
        }
    }
}

$jniLibrary = Join-Path $resolvedArtifactDir 'libpair_mnn.so'
$jniDynamic = (& $ReadelfPath -d $jniLibrary 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) {
    throw "Unable to read JNI shared-library dependencies for $jniLibrary`: $jniDynamic"
}
foreach ($dependency in @('libllm.so', 'libMNN.so')) {
    if ($jniDynamic -notmatch "(?m)NEEDED.*\[$([regex]::Escape($dependency))\]") {
        throw "JNI library $jniLibrary is not linked against required MNN dependency $dependency."
    }
}

Write-Output "Verified pinned MNN source revision: $actualRevision"
