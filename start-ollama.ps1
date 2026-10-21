param([string]$Model = 'qwen3:8b', [switch]$Pull)
$ErrorActionPreference = 'Stop'
if ($Model -notin @('qwen3:8b', 'qwen3:4b')) { throw 'Use qwen3:8b or qwen3:4b.' }
$ollamaExe = Join-Path $PSScriptRoot '.tools\ollama\ollama.exe'
if (-not (Test-Path -LiteralPath $ollamaExe)) {
    throw 'Download https://ollama.com/download/ollama-windows-amd64.zip and extract into .tools/ollama, or install Ollama normally.'
}
$endpoint = 'http://127.0.0.1:11434'
$taskTools = Join-Path $PSScriptRoot '.tools'
$savedHost = $env:OLLAMA_HOST
$savedModels = $env:OLLAMA_MODELS
$savedCloud = $env:OLLAMA_NO_CLOUD
try {
    $env:OLLAMA_HOST = '127.0.0.1:11434'
    $env:OLLAMA_MODELS = Join-Path $taskTools 'ollama-models'
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
} finally {
    $env:OLLAMA_HOST = $savedHost
    $env:OLLAMA_MODELS = $savedModels
    $env:OLLAMA_NO_CLOUD = $savedCloud
}
