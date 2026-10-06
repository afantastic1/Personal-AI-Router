# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ModelDir,
    [string] $ModelSwitchDir,
    [string] $Serial
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$androidRoot = Split-Path -Parent $PSScriptRoot
$sdkDir = (Get-Content -LiteralPath (Join-Path $androidRoot 'local.properties') |
    Where-Object { $_ -match '^sdk\.dir=' } |
    Select-Object -First 1) -replace '^sdk\.dir=', ''
$sdkDir = $sdkDir.Replace('\:', ':').Replace('\\', '\')
$adb = Join-Path $sdkDir 'platform-tools/adb.exe'
$fixture = (Resolve-Path -LiteralPath $ModelDir).Path
$switchFixture = $null

foreach ($requiredFile in @('config.json', 'llm.mnn', 'llm.mnn.weight')) {
    if (-not (Test-Path -LiteralPath (Join-Path $fixture $requiredFile) -PathType Leaf)) {
        throw "Required MNN fixture file is missing: $requiredFile"
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $fixture 'tokenizer.mtok') -PathType Leaf) -and
    -not (Test-Path -LiteralPath (Join-Path $fixture 'tokenizer.txt') -PathType Leaf)) {
    throw 'Required MNN fixture tokenizer is missing (expected tokenizer.mtok or tokenizer.txt).'
}
if (-not [string]::IsNullOrWhiteSpace($ModelSwitchDir)) {
    $switchFixture = (Resolve-Path -LiteralPath $ModelSwitchDir).Path
    foreach ($requiredFile in @('config.json', 'llm.mnn', 'llm.mnn.weight')) {
        if (-not (Test-Path -LiteralPath (Join-Path $switchFixture $requiredFile) -PathType Leaf)) {
            throw "Required model-switch MNN fixture file is missing: $requiredFile"
        }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $switchFixture 'tokenizer.mtok') -PathType Leaf) -and
        -not (Test-Path -LiteralPath (Join-Path $switchFixture 'tokenizer.txt') -PathType Leaf)) {
        throw 'Required model-switch tokenizer is missing (expected tokenizer.mtok or tokenizer.txt).'
    }
    if ($switchFixture -eq $fixture) {
        throw 'Model-switch acceptance requires two separate local model directories.'
    }
}
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw "Android adb was not found at $adb; install Android SDK platform-tools or correct android/local.properties."
}

$deviceList = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S+\s+device$' })
if ($LASTEXITCODE -ne 0) { throw 'Unable to query Android devices with adb.' }
if ([string]::IsNullOrWhiteSpace($Serial)) {
    if ($deviceList.Count -ne 1) {
        throw "Expected exactly one authorized Android device; found $($deviceList.Count). Pass -Serial to select one."
    }
    $Serial = ($deviceList[0] -split '\s+')[0]
}
if (-not ($deviceList | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device$" })) {
    throw "Android device '$Serial' is not connected and authorized. Check USB debugging or adb pair/connect."
}
$deviceAbis = (& $adb -s $Serial shell getprop ro.product.cpu.abilist).Trim()
if ($LASTEXITCODE -ne 0 -or $deviceAbis -notmatch '(^|,)arm64-v8a(,|$)') {
    throw "Selected Android device must support arm64-v8a; reported ABI list: $deviceAbis"
}

$runId = [guid]::NewGuid().ToString('N')
$remoteStage = "/data/local/tmp/pair-mnn-$runId"
$privateModel = "files/mnn-acceptance-$runId"
$privateSwitchModel = "files/mnn-model-switch-$runId"
$deviceModelArgument = "/data/user/0/com.nv.pair/$privateModel"
$adbArgs = @('-s', $Serial)

function Invoke-Adb([string[]] $Arguments, [string] $FailureMessage) {
    & $adb @adbArgs @Arguments
    if ($LASTEXITCODE -ne 0) { throw $FailureMessage }
}

function Get-PushedFixtureRoot([string] $PushedPath, [string] $LocalFixturePath) {
    & $adb @adbArgs shell test -f "$PushedPath/config.json" | Out-Null
    if ($LASTEXITCODE -eq 0) { return $PushedPath }

    $nestedPath = "$PushedPath/$(Split-Path -Leaf $LocalFixturePath)"
    & $adb @adbArgs shell test -f "$nestedPath/config.json" | Out-Null
    if ($LASTEXITCODE -eq 0) { return $nestedPath }
    throw 'Transferred model fixture did not contain config.json at the expected device staging paths.'
}

function Install-AcceptanceFixtures([string] $Variant) {
    $installArgs = @(':app:installDebug')
    if ($Variant -eq 'opencl') { $installArgs += '-PpairMnnOpenCL=true' }
    Push-Location $androidRoot
    try {
        & .\gradlew.bat @installArgs
        if ($LASTEXITCODE -ne 0) { throw "Unable to build/install the $Variant Android debug app." }
    } finally {
        Pop-Location
    }

    Invoke-Adb @('shell', 'run-as', 'com.nv.pair', 'mkdir', '-p', $privateModel) 'Unable to create isolated app-private fixture directory; build/install the debug APK first.'
    if ($null -ne $switchFixture) {
        Invoke-Adb @('shell', 'run-as', 'com.nv.pair', 'mkdir', '-p', $privateSwitchModel) 'Unable to create isolated app-private model-switch fixture directory.'
    }
    Invoke-Adb @('shell', 'mkdir', '-p', $remoteStage) 'Unable to create isolated device staging directory.'
    Invoke-Adb @('push', $fixture, "$remoteStage/primary") 'Unable to transfer the external MNN fixture to the device.'
    $remotePrimaryRoot = Get-PushedFixtureRoot "$remoteStage/primary" $fixture
    Invoke-Adb @('shell', 'run-as', 'com.nv.pair', 'cp', '-R', "$remotePrimaryRoot/.", $privateModel) 'Unable to copy the MNN fixture into app-private storage.'
    if ($null -ne $switchFixture) {
        Invoke-Adb @('push', $switchFixture, "$remoteStage/secondary") 'Unable to transfer the model-switch fixture to the device.'
        $remoteSecondaryRoot = Get-PushedFixtureRoot "$remoteStage/secondary" $switchFixture
        Invoke-Adb @('shell', 'run-as', 'com.nv.pair', 'cp', '-R', "$remoteSecondaryRoot/.", $privateSwitchModel) 'Unable to copy the model-switch fixture into app-private storage.'
    }
    Invoke-Adb @('shell', 'rm', '-rf', $remoteStage) 'Unable to remove the temporary device staging directory.'
}

try {
    foreach ($variant in @('cpu', 'opencl')) {
        Install-AcceptanceFixtures $variant
        $gradleArgs = @('.\gradlew.bat', ':app:connectedDebugAndroidTest', "-PpairMnnDeviceModelDir=$deviceModelArgument")
        if ($null -ne $switchFixture) {
            $gradleArgs += "-PpairMnnModelSwitchDeviceDir=/data/user/0/com.nv.pair/$privateSwitchModel"
        }
        if ($variant -eq 'opencl') { $gradleArgs += '-PpairMnnOpenCL=true' }
        $gradleTaskArgs = @($gradleArgs | Select-Object -Skip 1)
        Push-Location $androidRoot
        try {
            & $gradleArgs[0] @gradleTaskArgs
            if ($LASTEXITCODE -ne 0) { throw "Android MNN $variant acceptance failed with exit code $LASTEXITCODE." }
        } finally {
            Pop-Location
        }
    }
} finally {
    & $adb @adbArgs shell rm -rf $remoteStage | Out-Null
    & $adb @adbArgs shell pm path com.nv.pair | Out-Null
    if ($LASTEXITCODE -eq 0) {
        & $adb @adbArgs shell run-as com.nv.pair rm -rf $privateModel | Out-Null
        if ($null -ne $switchFixture) {
            & $adb @adbArgs shell run-as com.nv.pair rm -rf $privateSwitchModel | Out-Null
        }
    }
}
