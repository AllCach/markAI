param([int]$WaitForProcessId = 0)
$ErrorActionPreference = 'Stop'
$installLog = Join-Path $PSScriptRoot 'dist\install-walk.log'
New-Item -ItemType Directory -Path (Split-Path -Parent $installLog) -Force | Out-Null
try {
    if ($WaitForProcessId -gt 0) {
        "Waiting for Minecraft process $WaitForProcessId to exit." | Add-Content -LiteralPath $installLog -Encoding UTF8
        Wait-Process -Id $WaitForProcessId -ErrorAction SilentlyContinue
    }
    $sourceJar = Join-Path $PSScriptRoot 'build\libs\markai-0.8.2.jar'
    if (!(Test-Path -LiteralPath $sourceJar)) { $sourceJar = Join-Path $PSScriptRoot 'markai-0.8.2.jar' }
    $modsDir = Join-Path $env:APPDATA '.minecraft\mods'
    $older = @(Get-ChildItem -LiteralPath $modsDir -Filter 'markai-*.jar' -File | Where-Object { $_.Name -in @('markai-0.3.0.jar', 'markai-0.4.0.jar', 'markai-0.5.0.jar', 'markai-0.6.0.jar', 'markai-0.7.0.jar', 'markai-0.7.1.jar', 'markai-0.8.0.jar', 'markai-0.8.1.jar') })
    if ($older.Count -gt 1) { throw 'Multiple previous versions; installation stopped.' }
    $previousJar = if ($older.Count) { $older[0].FullName } else { Join-Path $modsDir 'markai-0.7.0.jar' }
    $disabledJar = $previousJar + '.disabled'
    if (Test-Path -LiteralPath $disabledJar) { $disabledJar += '-' + [guid]::NewGuid().ToString('N') }
    $installedJar = Join-Path $modsDir 'markai-0.8.2.jar'
    $pendingJar = "$installedJar.pending"
    $unexpectedMods = @(Get-ChildItem -LiteralPath $modsDir -Filter 'markai-*.jar' -File |
        Where-Object { $_.Name -notin @('markai-0.3.0.jar', 'markai-0.4.0.jar', 'markai-0.5.0.jar', 'markai-0.6.0.jar', 'markai-0.7.0.jar', 'markai-0.7.1.jar', 'markai-0.8.0.jar', 'markai-0.8.1.jar', 'markai-0.8.2.jar') })
    if ($unexpectedMods.Count) { throw 'Unexpected active MarkAI version; installation stopped.' }
    if ((Test-Path -LiteralPath $previousJar) -and (Test-Path -LiteralPath $disabledJar)) {
        throw 'Previous backup already exists; installation stopped.'
    }
    Copy-Item -LiteralPath $sourceJar -Destination $pendingJar -Force
    if ((Get-FileHash -LiteralPath $sourceJar).Hash -ne (Get-FileHash -LiteralPath $pendingJar).Hash) {
        throw 'Staged JAR hash mismatch.'
    }
    if (Test-Path -LiteralPath $previousJar) { Move-Item -LiteralPath $previousJar -Destination $disabledJar }
    try { Move-Item -LiteralPath $pendingJar -Destination $installedJar -Force }
    catch {
        if (!(Test-Path -LiteralPath $previousJar) -and (Test-Path -LiteralPath $disabledJar)) {
            Move-Item -LiteralPath $disabledJar -Destination $previousJar
        }
        throw
    }
    if ((Get-FileHash -LiteralPath $sourceJar).Hash -ne (Get-FileHash -LiteralPath $installedJar).Hash) {
        throw 'Installed JAR hash mismatch.'
    }
    'Installed MarkAI 0.8.2: ' + $installedJar | Add-Content -LiteralPath $installLog -Encoding UTF8
} catch {
    $_.Exception.Message | Add-Content -LiteralPath $installLog -Encoding UTF8
    throw
}
