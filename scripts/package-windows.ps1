<#
.SYNOPSIS
    Builds a self-contained Windows package: dist\ad-aggregator\ad-aggregator.exe plus a bundled Java runtime,
    and zips it as dist\ad-aggregator-windows-x64.zip. Users of the package do not need Java installed.

.PARAMETER JavaHome
    A JDK 21+ (must include jlink and jpackage). Defaults to the JAVA_HOME environment variable.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\package-windows.ps1 -JavaHome "C:\path\to\jdk-21"
#>
param([string]$JavaHome = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$target = Join-Path $root 'target'
$dist = Join-Path $root 'dist'
$appName = 'ad-aggregator'

if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\jpackage.exe'))) {
    throw 'Set JAVA_HOME or pass -JavaHome pointing to a JDK 21+ that contains bin\jpackage.exe.'
}
$env:JAVA_HOME = $JavaHome

function Invoke-Tool([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "$exe failed with exit code $LASTEXITCODE" }
}

Write-Host '1/4 Building the jar and running the tests...'
Invoke-Tool (Join-Path $root 'mvnw.cmd') @('-q', '-f', (Join-Path $root 'pom.xml'), 'clean', 'package')

Write-Host '2/4 Creating a minimal Java runtime (only java.base is needed)...'
$runtime = Join-Path $target 'runtime'
Invoke-Tool (Join-Path $JavaHome 'bin\jlink.exe') @(
    '--add-modules', 'java.base',
    '--strip-debug', '--no-header-files', '--no-man-pages', '--compress=zip-6',
    '--output', $runtime)

Write-Host '3/4 Packaging the console executable...'
$packageInput = Join-Path $target 'package-input'
New-Item -ItemType Directory -Force $packageInput | Out-Null
Copy-Item (Join-Path $target "$appName.jar") $packageInput
if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
Invoke-Tool (Join-Path $JavaHome 'bin\jpackage.exe') @(
    '--type', 'app-image',
    '--name', $appName,
    '--app-version', '1.0.0',
    '--input', $packageInput,
    '--main-jar', "$appName.jar",
    '--runtime-image', $runtime,
    '--win-console',
    '--dest', $dist)

Write-Host '4/4 Zipping...'
$zip = Join-Path $dist "$appName-windows-x64.zip"
Compress-Archive -Path (Join-Path $dist $appName) -DestinationPath $zip

$sizeMb = [math]::Round((Get-Item $zip).Length / 1MB, 1)
Write-Host "Done: $(Join-Path $dist "$appName\$appName.exe")"
Write-Host "      $zip ($sizeMb MB)"
