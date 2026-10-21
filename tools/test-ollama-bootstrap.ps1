$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
$fixtureRoot = Join-Path $repository ('build\bootstrap-fixture-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
$fakeExe = Join-Path $fixtureRoot 'fake-ollama.exe'
Add-Type -OutputAssembly $fakeExe -OutputType ConsoleApplication -TypeDefinition @'
using System;
using System.IO;
public static class FakeOllama {
    public static int Main(string[] args) {
        File.AppendAllText(Environment.GetEnvironmentVariable("MARKAI_BOOTSTRAP_CAPTURE"),
            string.Join(" ", args) + "|" + Environment.GetEnvironmentVariable("OLLAMA_MODELS") + "|"
            + Environment.GetEnvironmentVariable("OLLAMA_HOST") + "|" + Environment.GetEnvironmentVariable("OLLAMA_NO_CLOUD") + "\n");
        return Environment.GetEnvironmentVariable("MARKAI_BOOTSTRAP_FAIL") == "1" ? 7 : 0;
    }
}
'@
$capture = Join-Path $fixtureRoot 'calls.txt'
$savedEnvironment = @{}
foreach ($name in @('OLLAMA_HOST','OLLAMA_MODELS','OLLAMA_NO_CLOUD','LOCALAPPDATA','MARKAI_BOOTSTRAP_CAPTURE','MARKAI_BOOTSTRAP_FAIL','MARKAI_BOOTSTRAP_EXE')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
}
$env:MARKAI_BOOTSTRAP_EXE = $null
function Get-Command { param($Name, $CommandType, $ErrorAction) if ($env:MARKAI_BOOTSTRAP_EXE) { [pscustomobject]@{ Source = $env:MARKAI_BOOTSTRAP_EXE } } }
function Invoke-RestMethod { param($Uri, $TimeoutSec) [pscustomobject]@{ version = 'synthetic-no-network' } }
$checks = 0
function Assert-Bootstrap($Condition, $Message) {
    if (-not $Condition) { throw $Message }
    $script:checks++
}
function New-Fixture([string]$Name, [switch]$Portable) {
    $project = Join-Path $fixtureRoot $Name
    New-Item -ItemType Directory -Path $project -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repository 'start-ollama.ps1') -Destination (Join-Path $project 'start-ollama.ps1')
    if ($Portable) {
        $portableDir = Join-Path $project '.tools\ollama'
        New-Item -ItemType Directory -Path $portableDir -Force | Out-Null
        Copy-Item -LiteralPath $fakeExe -Destination (Join-Path $portableDir 'ollama.exe')
    }
    return $project
}
try {
    $env:MARKAI_BOOTSTRAP_CAPTURE = $capture
    $env:MARKAI_BOOTSTRAP_FAIL = $null
    $env:OLLAMA_HOST = 'original-host'
    $env:OLLAMA_NO_CLOUD = 'original-cloud'
    $env:OLLAMA_MODELS = $null
    $portable = New-Fixture 'portable' -Portable
    & (Join-Path $portable 'start-ollama.ps1') -Pull | Out-Null
    $calls = @(Get-Content -LiteralPath $capture)
    Assert-Bootstrap ($calls.Count -eq 2 -and $calls[0].StartsWith('pull qwen3:8b|') -and $calls[1].StartsWith('list|')) 'Pull and list must run on the selected executable.'
    Assert-Bootstrap ($calls[0].Contains((Join-Path $portable '.tools\ollama-models'))) 'Portable default uses project models.'
    Assert-Bootstrap ($env:OLLAMA_HOST -eq 'original-host' -and $env:OLLAMA_NO_CLOUD -eq 'original-cloud' -and -not $env:OLLAMA_MODELS) 'Environment must be restored after success.'

    $env:OLLAMA_MODELS = 'custom-model-cache'
    & (Join-Path $portable 'start-ollama.ps1') -Model qwen3:4b | Out-Null
    Assert-Bootstrap ((Get-Content -LiteralPath $capture | Select-Object -Last 1).Contains('|custom-model-cache|')) 'Portable mode respects explicit model cache.'
    $installed = New-Fixture 'installed-on-path'
    $env:MARKAI_BOOTSTRAP_EXE = $fakeExe
    $env:OLLAMA_MODELS = $null
    & (Join-Path $installed 'start-ollama.ps1') | Out-Null
    Assert-Bootstrap ((Get-Content -LiteralPath $capture | Select-Object -Last 1) -eq 'list||127.0.0.1:11434|1') 'Normal installation keeps the standard model cache.'
    Assert-Bootstrap (Test-Path -LiteralPath (Join-Path $installed '.tools')) 'Log directory is created for a clean normal installation.'

    $env:MARKAI_BOOTSTRAP_EXE = $null
    $env:LOCALAPPDATA = Join-Path $fixtureRoot 'local-appdata'
    $fallback = Join-Path $env:LOCALAPPDATA 'Programs\Ollama'
    New-Item -ItemType Directory -Path $fallback -Force | Out-Null
    Copy-Item -LiteralPath $fakeExe -Destination (Join-Path $fallback 'ollama.exe')
    $normal = New-Fixture 'installed-without-path'
    & (Join-Path $normal 'start-ollama.ps1') | Out-Null
    Assert-Bootstrap ((Get-Content -LiteralPath $capture | Select-Object -Last 1).StartsWith('list|')) 'Normal installation works before the PATH refresh.'
    $env:MARKAI_BOOTSTRAP_FAIL = '1'
    $failed = $false
    try { & (Join-Path $normal 'start-ollama.ps1') | Out-Null } catch { $failed = $_.Exception.Message.Contains('Could not list') }
    Assert-Bootstrap $failed 'Native list failure must propagate.'
    Assert-Bootstrap ($env:OLLAMA_HOST -eq 'original-host' -and $env:OLLAMA_NO_CLOUD -eq 'original-cloud' -and -not $env:OLLAMA_MODELS) 'Environment must be restored after a native failure.'
    $failed = $false
    try { & (Join-Path $normal 'start-ollama.ps1') -Model invalid-model | Out-Null } catch { $failed = $_.Exception.Message.Contains('Use qwen3') }
    Assert-Bootstrap $failed 'Unsupported model is rejected before any command.'
    $env:LOCALAPPDATA = Join-Path $fixtureRoot 'not-installed'
    $failed = $false
    try { & (Join-Path $normal 'start-ollama.ps1') | Out-Null } catch { $failed = $_.Exception.Message.Contains('Install Ollama') }
    Assert-Bootstrap $failed 'Missing executable gives an installation instruction.'
    [pscustomobject]@{ completed=$true; checks=$checks; network_calls=0; model_downloads=0 } | ConvertTo-Json |
        Set-Content -LiteralPath (Join-Path $repository 'build\bootstrap-test-result.json') -Encoding UTF8
    Write-Output "Ollama bootstrap: $checks checks passed; no network requests or model downloads."
} finally {
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name]) }
}
