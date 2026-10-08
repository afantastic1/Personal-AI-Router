# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $PcAddress,
    [string] $Serial
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$androidRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $androidRoot
$localProperties = Join-Path $androidRoot 'local.properties'
if (-not (Test-Path -LiteralPath $localProperties -PathType Leaf)) {
    throw 'android/local.properties is required to locate adb.'
}
$sdkLine = Get-Content -LiteralPath $localProperties |
    Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
if ($null -eq $sdkLine) { throw 'android/local.properties does not define sdk.dir.' }
$sdkDir = ($sdkLine -replace '^sdk\.dir=', '').Replace('\:', ':').Replace('\\', '\')
$adb = Join-Path $sdkDir 'platform-tools/adb.exe'
$brokerPath = Join-Path $repoRoot 'desktop/cli-bin/nvpair-ui-broker.exe'
$cliDir = Split-Path -Parent $brokerPath
$pairingPath = 'cache/pairing-persistence.json'
$runId = [guid]::NewGuid().ToString('N')
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) "pair-persistence-$runId"
$stdoutPath = Join-Path ([System.IO.Path]::GetTempPath()) "pair-persistence-gradle-$runId.out"
$stderrPath = Join-Path ([System.IO.Path]::GetTempPath()) "pair-persistence-gradle-$runId.err"
$brokerProcess = $null
$gradleProcess = $null
$script:brokerReadTask = $null
$rpcId = 0

if ([string]::IsNullOrWhiteSpace($PcAddress)) { throw 'PcAddress must be the reachable LAN address of this PC.' }
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) { throw "adb was not found at $adb." }
if (-not (Test-Path -LiteralPath $brokerPath -PathType Leaf)) {
    throw "PC broker binary was not found at $brokerPath. Build desktop modular binaries first."
}

function Test-TcpPort([string] $Address, [int] $Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.BeginConnect($Address, $Port, $null, $null)
        return $pending.AsyncWaitHandle.WaitOne(500) -and $client.Connected
    } finally { $client.Dispose() }
}

function Invoke-BrokerRpc([string] $Method, [hashtable] $Params) {
    $script:rpcId++
    $requestId = $script:rpcId
    $request = @{ jsonrpc = '2.0'; id = $requestId; method = $Method; params = $Params } | ConvertTo-Json -Compress
    $requestBytes = [System.Text.UTF8Encoding]::new($false).GetBytes($request + "`n")
    $inputStream = $script:brokerProcess.StandardInput.BaseStream
    $inputStream.Write($requestBytes, 0, $requestBytes.Length)
    $inputStream.Flush()
    $deadline = [DateTime]::UtcNow.AddSeconds(35)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($null -eq $script:brokerReadTask) {
            $script:brokerReadTask = $script:brokerProcess.StandardOutput.ReadLineAsync()
        }
        if (-not $script:brokerReadTask.Wait(50)) { continue }
        $line = $script:brokerReadTask.Result
        if ($null -eq $line) { throw 'PC broker closed its JSON-RPC output.' }
        $script:brokerReadTask = $script:brokerProcess.StandardOutput.ReadLineAsync()
        try { $message = $line | ConvertFrom-Json } catch { continue }
        if ($message.id -eq $requestId) {
            if ($null -ne $message.error) {
                throw "PC broker RPC '$Method' failed (code $($message.error.code))."
            }
            return $message.result
        }
    }
    throw "PC broker RPC '$Method' timed out."
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
    throw "Android device '$Serial' is not connected and authorized."
}
if ((Test-TcpPort '127.0.0.1' 14321) -or (Test-TcpPort '127.0.0.1' 14324)) {
    throw 'Stop the existing PAIR runtime before starting the isolated pairing acceptance broker (ports 14321/14324 must be free).'
}

try {
    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    $brokerInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $brokerInfo.FileName = $brokerPath
    $brokerInfo.WorkingDirectory = $cliDir
    $brokerInfo.Arguments = '--cluster-dir "' + (Join-Path $tempRoot 'cluster') + '"'
    $brokerInfo.EnvironmentVariables['LOCALAPPDATA'] = Join-Path $tempRoot 'localappdata'
    $brokerInfo.UseShellExecute = $false
    $brokerInfo.CreateNoWindow = $true
    $brokerInfo.RedirectStandardInput = $true
    $brokerInfo.RedirectStandardOutput = $true
    $brokerInfo.RedirectStandardError = $false
    $brokerProcess = [System.Diagnostics.Process]::new()
    $brokerProcess.StartInfo = $brokerInfo
    if (-not $brokerProcess.Start()) { throw 'Unable to start the isolated PC broker.' }
    $script:brokerReadTask = $brokerProcess.StandardOutput.ReadLineAsync()
    # Prime the redirected writer so its UTF-8 preamble cannot prefix a JSON-RPC request.
    $brokerProcess.StandardInput.WriteLine('')
    $null = Invoke-BrokerRpc 'ping' @{}

    $gradleArgs = @(
        ':app:connectedDebugAndroidTest',
        '-PpairPersistenceAcceptance=true',
        "-PpairPersistencePcAddress=$PcAddress"
    )
    $javaCommand = Get-Command java.exe -CommandType Application -ErrorAction Stop
    $wrapperJar = Join-Path $androidRoot 'gradle/wrapper/gradle-wrapper.jar'
    $javaArgs = @(
        '"-Xmx64m"',
        '"-Xms64m"',
        '"-Dorg.gradle.appname=gradlew"',
        '-classpath',
        ('"' + $wrapperJar + '"'),
        'org.gradle.wrapper.GradleWrapperMain'
    ) + $gradleArgs
    $gradleProcess = Start-Process -FilePath $javaCommand.Source `
        -ArgumentList ($javaArgs -join ' ') -WorkingDirectory $androidRoot -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru

    $pairingAccepted = $false
    while (-not $gradleProcess.HasExited) {
        if (-not $pairingAccepted) {
            $installedApp = & $adb -s $Serial shell pm path com.nv.pair
            if ($LASTEXITCODE -eq 0 -and $installedApp) {
                & $adb -s $Serial shell run-as com.nv.pair test -f $pairingPath
            }
            if ($LASTEXITCODE -eq 0 -and $installedApp) {
                $pairingText = & $adb -s $Serial shell run-as com.nv.pair cat $pairingPath
                if ($LASTEXITCODE -eq 0 -and $pairingText) {
                    $invite = ($pairingText -join "`n") | ConvertFrom-Json
                    $null = Invoke-BrokerRpc 'cluster:respond-to-invite' @{
                        inviteId = [string]$invite.inviteId
                        accept = $true
                        pin = [string]$invite.pin
                    }
                    $pairingAccepted = $true
                    Write-Host "PC accepted Android pairing invitation $($invite.inviteId)."
                }
            }
        }
        Start-Sleep -Milliseconds 400
        $gradleProcess.Refresh()
    }
    $gradleProcess.WaitForExit()
    $gradleProcess.Refresh()
    $gradleOutput = Get-Content -LiteralPath $stdoutPath -Raw
    Write-Output $gradleOutput
    if (Test-Path -LiteralPath $stderrPath) { Get-Content -LiteralPath $stderrPath | Write-Output }
    $gradleExitCode = $gradleProcess.ExitCode
    $gradleFailed = $gradleOutput -notmatch '(?m)^BUILD SUCCESSFUL(?: in .*)?$' -or
        $gradleOutput -match '(?m)^BUILD FAILED(?: in .*)?$'
    if ($null -ne $gradleExitCode -and $gradleExitCode -ne 0) { $gradleFailed = $true }
    if ($gradleFailed) {
        throw "Pairing persistence acceptance failed (Gradle exit code $($gradleProcess.ExitCode))."
    }
    if (-not $pairingAccepted) { throw 'The Android test completed without PC-side pairing acceptance.' }
    Write-Host 'Two-device pairing persistence acceptance passed. Review the test output for restart assertions.'
} finally {
    if ($null -ne $gradleProcess -and -not $gradleProcess.HasExited) {
        Stop-Process -Id $gradleProcess.Id -Force -ErrorAction SilentlyContinue
        $gradleProcess.WaitForExit()
    }
    if ($null -ne $brokerProcess -and -not $brokerProcess.HasExited) {
        try { $brokerProcess.StandardInput.Close() } catch { }
        if (-not $brokerProcess.WaitForExit(10000)) {
            Stop-Process -Id $brokerProcess.Id -Force -ErrorAction SilentlyContinue
            $brokerProcess.WaitForExit()
        }
    }
    $resolvedTempRoot = [System.IO.Path]::GetFullPath($tempRoot)
    $tempBaseRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolvedTempRoot.StartsWith($tempBaseRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove temporary path outside the temp directory: $resolvedTempRoot"
    }
    if (Test-Path -LiteralPath $resolvedTempRoot) { Remove-Item -LiteralPath $resolvedTempRoot -Recurse -Force }
    if (Test-Path -LiteralPath $stdoutPath) { Remove-Item -LiteralPath $stdoutPath -Force }
    if (Test-Path -LiteralPath $stderrPath) { Remove-Item -LiteralPath $stderrPath -Force }
}
