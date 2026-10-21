param([string]$Model = 'qwen3:8b', [switch]$Pull)
$ErrorActionPreference = 'Stop'
if ($Model -notin @('qwen3:8b', 'qwen3:4b')) { throw 'Use qwen3:8b or qwen3:4b.' }
$ollamaExe = Join-Path $PSScriptRoot '.tools\ollama\ollama.exe'
$portable = Test-Path -LiteralPath $ollamaExe -PathType Leaf
if (-not (Test-Path -LiteralPath $ollamaExe)) {
    $installed = Get-Command ollama -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($installed) { $ollamaExe = $installed.Source }
    else {
        $installedPath = Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama.exe'
        if (Test-Path -LiteralPath $installedPath -PathType Leaf) { $ollamaExe = $installedPath }
        else { throw 'Install Ollama and restart the terminal, or extract the portable executable into .tools/ollama.' }
    }
}
$endpoint = 'http://127.0.0.1:11434'
$taskTools = Join-Path $PSScriptRoot '.tools'
New-Item -ItemType Directory -Path $taskTools -Force | Out-Null
$savedHost = $env:OLLAMA_HOST
$savedModels = $env:OLLAMA_MODELS
$savedCloud = $env:OLLAMA_NO_CLOUD
try {
    $env:OLLAMA_HOST = '127.0.0.1:11434'
    if ($portable -and -not $env:OLLAMA_MODELS) { $env:OLLAMA_MODELS = Join-Path $taskTools 'ollama-models' }
    $env:OLLAMA_NO_CLOUD = '1'
    $running = $false
    try { $null = Invoke-RestMethod "$endpoint/api/version" -TimeoutSec 2; $running = $true } catch { }
    if (-not $running) {
        $process = Start-Process -FilePath $ollamaExe -ArgumentList 'serve' -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $taskTools 'ollama-stdout.log') `
            -RedirectStandardError (Join-Path $taskTools 'ollama-stderr.log')
        for ($attempt = 0; $attempt -lt 30; $attempt++) {
            Start-Sleep -Seconds 1
            try { $null = Invoke-RestMethod "$endpoint/api/version" -TimeoutSec 1; $running = $true; break } catch { }
            if ($process.HasExited) { throw 'Ollama exited; see .tools/ollama-stderr.log.' }
        }
        if (-not $running) { throw 'Ollama did not become ready; see .tools/ollama-stderr.log.' }
        Write-Output "Ollama started on $endpoint (PID $($process.Id))."
    } else { Write-Output "Ollama is already running on $endpoint." }
    if ($Pull) {
        & $ollamaExe pull $Model
        if ($LASTEXITCODE -ne 0) { throw "Model download failed: $Model" }
    }
    & $ollamaExe list
    if ($LASTEXITCODE -ne 0) { throw 'Could not list Ollama models.' }
} finally {
    $env:OLLAMA_HOST = $savedHost
    $env:OLLAMA_MODELS = $savedModels
    $env:OLLAMA_NO_CLOUD = $savedCloud
}
