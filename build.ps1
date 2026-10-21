param([string]$Task = 'build')
$ErrorActionPreference = 'Stop'
$localJdk = Get-ChildItem -LiteralPath "$PSScriptRoot\.tools\jdk" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 25 installation.' }
Push-Location $PSScriptRoot
try {
    & .\gradlew.bat $Task
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally { Pop-Location }
