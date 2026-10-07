# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

[CmdletBinding()]
param(
    [string] $SourceDir,
    [string] $SdkRoot,
    [string] $LinuxNdkDir = '/opt/android-ndk-r27c/android-ndk-r27c',
    [switch] $IncludeOpenCL,
    [int] $Jobs = 4,
    [switch] $Force
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$pinnedRevision = 'd407447ed56c4121a11ccbd266dc184ca1ead0c2'
$androidRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $androidRoot
$cacheRoot = Join-Path $androidRoot 'build/cache/mnn'
$artifactRoot = Join-Path $androidRoot 'build/generated/mnn/arm64-v8a'
$requestSamplerPatch = Join-Path $androidRoot 'patches/mnn-request-sampler.patch'
if (-not (Test-Path -LiteralPath $requestSamplerPatch -PathType Leaf)) {
    throw "Required MNN request sampler patch is missing: $requestSamplerPatch"
}
$requestSamplerPatchId = (Get-FileHash -LiteralPath $requestSamplerPatch -Algorithm SHA256).Hash.Substring(0, 12).ToLowerInvariant()

function ConvertTo-BashArgument([string] $Value) {
    return "'" + $Value.Replace("'", "'\''") + "'"
}

function ConvertTo-WslPath([string] $WindowsPath) {
    $fullPath = [System.IO.Path]::GetFullPath($WindowsPath)
    if ($fullPath -notmatch '^([A-Za-z]):\\(.*)$') {
        throw "Expected a drive-qualified Windows path for WSL conversion: $fullPath"
    }
    $drive = $Matches[1].ToLowerInvariant()
    $relative = $Matches[2] -replace '\\', '/'
    return "/mnt/$drive/$relative"
}

function Invoke-Wsl([string] $Command, [string] $FailureMessage) {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & wsl.exe -d Ubuntu-24.04 -- /bin/bash -lc $Command
    $ErrorActionPreference = $previousErrorAction
    if ($LASTEXITCODE -ne 0) {
        throw $FailureMessage
    }
}

function Get-AndroidSdkRoot {
    if (-not [string]::IsNullOrWhiteSpace($SdkRoot)) {
        return [System.IO.Path]::GetFullPath($SdkRoot)
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
        return [System.IO.Path]::GetFullPath($env:ANDROID_SDK_ROOT)
    }
    $localProperties = Join-Path $androidRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
        $line = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($null -ne $line) {
            $value = $line -replace '^sdk\.dir=', ''
            $value = $value.Replace('\:', ':').Replace('\\', '\')
            return [System.IO.Path]::GetFullPath($value)
        }
    }
    throw "Android SDK root is unknown. Pass -SdkRoot or set ANDROID_SDK_ROOT; install NDK $pinnedRevision's required version 27.2.12479018, not NDK 30."
}

function Get-ExactGitSource([string] $RequestedSourceDir) {
    $source = $RequestedSourceDir
    if ([string]::IsNullOrWhiteSpace($source)) {
        $source = Join-Path $repoRoot 'third_party/MNN'
    }
    $resolved = [System.IO.Path]::GetFullPath($source)
    if (-not (Test-Path -LiteralPath (Join-Path $resolved '.git'))) {
        if ([string]::IsNullOrWhiteSpace($RequestedSourceDir)) {
            $resolved = Join-Path $cacheRoot 'MNN-fetch'
            if (-not (Test-Path -LiteralPath (Join-Path $resolved '.git'))) {
                New-Item -ItemType Directory -Path $resolved -Force | Out-Null
                & git -C $resolved init
                if ($LASTEXITCODE -ne 0) { throw "Unable to initialize MNN source cache: $resolved" }
                & git -C $resolved remote add origin https://github.com/alibaba/MNN.git
                if ($LASTEXITCODE -ne 0) { throw 'Unable to configure the pinned MNN source remote.' }
            }
            & git -C $resolved fetch --depth 1 origin $pinnedRevision
            if ($LASTEXITCODE -ne 0) { throw "Unable to fetch pinned MNN revision $pinnedRevision." }
        } else {
            throw "Local MNN source directory is not a git checkout: $resolved"
        }
    }
    & git -C $resolved cat-file -e "$pinnedRevision^{commit}"
    if ($LASTEXITCODE -ne 0) {
        throw "MNN source cache does not contain required commit ${pinnedRevision}: $resolved"
    }
    return $resolved
}

function Invoke-MnnBuild([string] $SourceWsl, [string] $BuildWsl, [string] $LibraryWsl, [string] $NdkWsl, [bool] $UseOpenCL) {
    $options = @(
        '-S', $SourceWsl,
        '-B', $BuildWsl,
        '-G', 'Ninja',
        "-DCMAKE_TOOLCHAIN_FILE=$NdkWsl/build/cmake/android.toolchain.cmake",
        '-DCMAKE_BUILD_TYPE=Release',
        '-DANDROID_ABI=arm64-v8a',
        '-DANDROID_PLATFORM=android-29',
        '-DANDROID_STL=c++_shared',
        '-DMNN_BUILD_SHARED_LIBS=ON',
        '-DMNN_SEP_BUILD=ON',
        '-DMNN_BUILD_LLM=ON',
        '-DMNN_BUILD_LLM_OMNI=OFF',
        '-DMNN_BUILD_FOR_ANDROID_COMMAND=ON',
        '-DMNN_USE_LOGCAT=OFF',
        '-DMNN_SUPPORT_TRANSFORMER_FUSE=ON',
        '-DMNN_LOW_MEMORY=ON',
        '-DMNN_BUILD_TEST=OFF',
        '-DMNN_BUILD_BENCHMARK=OFF',
        '-DMNN_BUILD_DIFFUSION=OFF',
        '-DMNN_BUILD_OPENCV=OFF',
        '-DMNN_IMGCODECS=OFF',
        "-DMNN_OPENCL=$(if ($UseOpenCL) { 'ON' } else { 'OFF' })",
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=$LibraryWsl",
        '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'
    )
    $configure = 'cmake ' + (($options | ForEach-Object { ConvertTo-BashArgument $_ }) -join ' ')
    Invoke-Wsl $configure "MNN $(if ($UseOpenCL) { 'OpenCL' } else { 'CPU' }) CMake configure failed."
    $build = 'cmake --build ' + (ConvertTo-BashArgument $BuildWsl) + ' --target MNN llm --parallel ' + $Jobs
    Invoke-Wsl $build "MNN $(if ($UseOpenCL) { 'OpenCL' } else { 'CPU' }) build failed."
}

if ($Jobs -lt 1) {
    throw '-Jobs must be a positive integer.'
}

$sdk = Get-AndroidSdkRoot
$windowsNdk = Join-Path $sdk 'ndk/27.2.12479018'
if (-not (Test-Path -LiteralPath (Join-Path $windowsNdk 'source.properties') -PathType Leaf)) {
    $androidCli = Join-Path $sdk 'cmdline-tools/latest/bin/android.exe'
    throw "Pinned Android NDK 27.2.12479018 is required; NDK 30 will not be selected. Install it with: `"$androidCli`" --sdk `"$sdk`" sdk install ndk/27.2.12479018"
}
$windowsRevision = Get-Content -LiteralPath (Join-Path $windowsNdk 'source.properties') | Where-Object { $_ -match '^Pkg\.Revision\s*=' } | Select-Object -First 1
if ($windowsRevision -notmatch '27\.2\.12479018') {
    throw "Android SDK NDK revision mismatch at $windowsNdk; expected 27.2.12479018."
}

$packageCheck = "command -v cmake >/dev/null && command -v ninja >/dev/null && command -v g++ >/dev/null && command -v patch >/dev/null && command -v python3 >/dev/null"
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& wsl.exe -d Ubuntu-24.04 -- /bin/bash -lc $packageCheck
$ErrorActionPreference = $previousErrorAction
if ($LASTEXITCODE -ne 0) {
    throw 'Ubuntu-24.04 WSL is missing build prerequisites. Install with: sudo apt-get update && sudo apt-get install -y build-essential cmake ninja-build python3'
}

$ndkWsl = $LinuxNdkDir
$ndkCheck = 'test -f ' + (ConvertTo-BashArgument "$ndkWsl/source.properties") + ' && tr -d "\r" < ' + (ConvertTo-BashArgument "$ndkWsl/source.properties") + ' | grep -q "^Pkg.Revision = 27.2.12479018$" && test -x ' + (ConvertTo-BashArgument "$ndkWsl/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++")
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& wsl.exe -d Ubuntu-24.04 -- /bin/bash -lc $ndkCheck
$ErrorActionPreference = $previousErrorAction
if ($LASTEXITCODE -ne 0) {
    throw "Ubuntu-24.04 Linux-host NDK 27.2.12479018 is missing or invalid at $ndkWsl. Install the official android-ndk-r27c-linux.zip into the WSL filesystem and verify source.properties; do not use the Windows-host NDK or NDK 30."
}

$sourceRepo = Get-ExactGitSource $SourceDir
New-Item -ItemType Directory -Path $cacheRoot -Force | Out-Null
New-Item -ItemType Directory -Path $artifactRoot -Force | Out-Null
$sourceArchive = Join-Path $cacheRoot "$pinnedRevision.tar"
$sourceExtract = Join-Path $cacheRoot "source-$pinnedRevision-$requestSamplerPatchId"
if ($Force -or -not (Test-Path -LiteralPath (Join-Path $sourceExtract 'CMakeLists.txt') -PathType Leaf)) {
    & git -C $sourceRepo archive --format=tar --output=$sourceArchive $pinnedRevision
    if ($LASTEXITCODE -ne 0) { throw "Unable to archive pinned MNN source revision $pinnedRevision." }
    $sourceWsl = ConvertTo-WslPath $sourceExtract
    $archiveWsl = ConvertTo-WslPath $sourceArchive
    Invoke-Wsl "mkdir -p $(ConvertTo-BashArgument $sourceWsl) && tar -xf $(ConvertTo-BashArgument $archiveWsl) -C $(ConvertTo-BashArgument $sourceWsl)" 'Unable to materialize pinned MNN source into the ignored Android build cache.'
}

if (-not (Select-String -LiteralPath (Join-Path $sourceExtract 'transformers/llm/engine/include/llm/llm.hpp') -SimpleMatch 'reset_sampler' -Quiet)) {
    $sourcePatchWsl = ConvertTo-WslPath $sourceExtract
    $requestSamplerPatchWsl = ConvertTo-WslPath $requestSamplerPatch
    $patchCommand = 'cd ' + (ConvertTo-BashArgument $sourcePatchWsl) +
        ' && patch --dry-run -p1 < ' + (ConvertTo-BashArgument $requestSamplerPatchWsl) +
        ' && patch -p1 < ' + (ConvertTo-BashArgument $requestSamplerPatchWsl)
    Invoke-Wsl $patchCommand 'Pinned MNN request sampler patch no longer applies cleanly.'
}

$artifactWsl = ConvertTo-WslPath $artifactRoot
$sourceWsl = ConvertTo-WslPath $sourceExtract
$cpuBuildDirectory = Join-Path $cacheRoot "build-cpu-$requestSamplerPatchId"
$cpuBuild = ConvertTo-WslPath $cpuBuildDirectory
$cpuLibraryDir = $artifactWsl
Invoke-MnnBuild $sourceWsl $cpuBuild $cpuLibraryDir $ndkWsl $false

$cpuExpressLibrary = Join-Path $cpuBuildDirectory 'libMNN_Express.so'
if (-not (Test-Path -LiteralPath $cpuExpressLibrary -PathType Leaf)) {
    throw "Required MNN dependency was not produced: $cpuExpressLibrary"
}
Copy-Item -LiteralPath $cpuExpressLibrary -Destination (Join-Path $artifactRoot 'libMNN_Express.so') -Force

$libcxxWsl = "$LinuxNdkDir/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
$artifactLibcxxWsl = "$artifactWsl/libc++_shared.so"
$copyCpuLibcxx = 'test -f ' + (ConvertTo-BashArgument $libcxxWsl) + ' && cp ' + (ConvertTo-BashArgument $libcxxWsl) + ' ' + (ConvertTo-BashArgument $artifactLibcxxWsl)
Invoke-Wsl $copyCpuLibcxx 'Pinned Linux NDK is missing its ARM64 C++ runtime.'

$includeRoot = Join-Path $artifactRoot 'include'
$mnnInclude = Join-Path $includeRoot 'MNN'
$llmInclude = Join-Path $includeRoot 'llm'
New-Item -ItemType Directory -Path $mnnInclude,$llmInclude -Force | Out-Null
Copy-Item -Path (Join-Path $sourceExtract 'include/MNN/*') -Destination $mnnInclude -Recurse -Force
Copy-Item -Path (Join-Path $sourceExtract 'transformers/llm/engine/include/llm/*') -Destination $llmInclude -Recurse -Force
Set-Content -LiteralPath (Join-Path $artifactRoot 'source-revision.txt') -Value $pinnedRevision -NoNewline -Encoding ascii

if ($IncludeOpenCL) {
    $openclRoot = Join-Path $artifactRoot 'opencl'
    New-Item -ItemType Directory -Path $openclRoot -Force | Out-Null
    $openclWsl = ConvertTo-WslPath $openclRoot
    $openclBuild = ConvertTo-WslPath (Join-Path $cacheRoot "build-opencl-$requestSamplerPatchId")
    Invoke-MnnBuild $sourceWsl $openclBuild $openclWsl $ndkWsl $true
    foreach ($libraryName in @('libMNN_CL.so', 'libMNN_Express.so')) {
        $builtLibrary = Join-Path (Join-Path $cacheRoot "build-opencl-$requestSamplerPatchId") $libraryName
        if (-not (Test-Path -LiteralPath $builtLibrary -PathType Leaf)) {
            throw "Required OpenCL MNN dependency was not produced: $builtLibrary"
        }
        Copy-Item -LiteralPath $builtLibrary -Destination (Join-Path $openclRoot $libraryName) -Force
    }
    $copyOpenClLibcxx = 'cp ' + (ConvertTo-BashArgument $libcxxWsl) + ' ' + (ConvertTo-BashArgument "$openclWsl/libc++_shared.so")
    Invoke-Wsl $copyOpenClLibcxx 'Pinned Linux NDK ARM64 C++ runtime could not be staged for OpenCL.'
    Set-Content -LiteralPath (Join-Path $openclRoot 'source-revision.txt') -Value $pinnedRevision -NoNewline -Encoding ascii
}

Write-Output "Built pinned MNN $pinnedRevision for arm64-v8a (CPU)."
if ($IncludeOpenCL) { Write-Output "Built optional OpenCL variant at $artifactRoot/opencl." }
