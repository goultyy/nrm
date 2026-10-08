<#
.SYNOPSIS
    Builds the Windows installer for NRM.

.DESCRIPTION
    1. Builds the application and copies its libraries to target\dist (mvnw package).
    2. Uses jpackage to make a self-contained app image with its own trimmed Java runtime
       (target\installer\NRM\NRM.exe). Nothing else needs to be
       installed on the target computer.
    3. If the WiX Toolset (3.x) is installed, wraps the image into an .msi and an .exe installer.
       jpackage needs WiX for that step; without it only the app image is produced.

    Install WiX with:  winget install WiXToolset.WiXToolset
    (or download 3.14 from https://wixtoolset.org/ and make sure candle.exe and light.exe are on PATH.)

.PARAMETER SkipTests
    Skip the unit tests while building.

.PARAMETER AppImageOnly
    Stop after the app image, even if WiX is available.

.EXAMPLE
    .\packaging\build-installer.ps1
#>
[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$AppImageOnly
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# --- version -------------------------------------------------------------------------------
[xml]$pom = Get-Content -Raw (Join-Path $root 'pom.xml')
$version = $pom.project.version
$appVersion = $version -replace '-.*$', ''     # installers want plain numbers, e.g. 1.0.0
Write-Host "Building NRM $version" -ForegroundColor Cyan

# --- build -----------------------------------------------------------------------------------
$dist = Join-Path $root 'target\dist'
if (Test-Path $dist) { Get-ChildItem $dist -File | Remove-Item -Force }   # no stale jars from earlier builds
$mvnArgs = @('package', '-q')
if ($SkipTests) { $mvnArgs += '-DskipTests' }
# Maven and the tests write warnings to stderr; that is not a failure, so judge by the exit code only.
$ErrorActionPreference = 'Continue'
& (Join-Path $root 'mvnw.cmd') @mvnArgs
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -ne 0) { throw "The Maven build failed." }

$mainJar = "nrm-$version.jar"
if (-not (Test-Path (Join-Path $root "target\dist\$mainJar"))) { throw "target\dist\$mainJar was not built." }

# --- jpackage --------------------------------------------------------------------------------
$jpackage = Get-Command jpackage.exe -ErrorAction SilentlyContinue
if (-not $jpackage) { throw "jpackage was not found. Use a JDK 17 or newer (not just a JRE) and put it on PATH." }

# The Java modules the app needs at run time (JavaFX itself ships as jars in target\dist).
$modules = 'java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.sql,java.xml,jdk.crypto.ec,jdk.jfr,jdk.unsupported'

# A module missing from this list only shows up as a crash at run time, in whatever screen first uses it (the Cloudflare
# pages did, for java.net.http). So ask jdeps which JDK modules the app's classes need and stop if any is not listed.
$jdeps = Get-Command jdeps.exe -ErrorAction SilentlyContinue
if ($jdeps) {
    $jars = @(Get-ChildItem (Join-Path $root 'target\dist') -Filter *.jar | ForEach-Object { $_.FullName })
    $needed = & jdeps.exe --multi-release 17 --ignore-missing-deps --print-module-deps --class-path ($jars -join ';') (Join-Path $root "target\dist\$mainJar") 2>$null
    if ($LASTEXITCODE -eq 0 -and $needed) {
        $have = $modules.Split(',')
        $missing = @($needed.Trim().Split(',') | Where-Object { $_ -and ($have -notcontains $_) })
        if ($missing.Count -gt 0) {
            throw "The app uses Java modules that build-installer.ps1 does not bundle: $($missing -join ', '). Add them to `$modules."
        }
    } else {
        Write-Host "jdeps could not check the module list; make sure `$modules covers everything the app uses." -ForegroundColor Yellow
    }
} else {
    Write-Host "jdeps was not found, so the module list was not checked." -ForegroundColor Yellow
}

$dest = Join-Path $root 'target\installer'
if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
New-Item -ItemType Directory -Force $dest | Out-Null

$common = @(
    '--name', 'NRM',
    '--app-version', $appVersion,
    '--vendor', 'Summit',
    '--description', 'NRM',
    '--input', 'target\dist',
    '--main-jar', $mainJar,
    '--main-class', 'mt.su.nrm.app.Launcher',
    '--icon', 'packaging\app.ico',
    '--add-modules', $modules,
    '--java-options', '-Dfile.encoding=UTF-8',
    '--dest', $dest
)

Write-Host "Creating the app image (with a bundled Java runtime)..." -ForegroundColor Cyan
& jpackage.exe --type app-image @common
if ($LASTEXITCODE -ne 0) { throw "jpackage failed to create the app image." }
$exe = Join-Path $dest 'NRM\NRM.exe'
Write-Host "App image: $exe" -ForegroundColor Green

# --- installers (need WiX) ---------------------------------------------------------------------
if ($AppImageOnly) { return }
$wix = (Get-Command candle.exe -ErrorAction SilentlyContinue) -and (Get-Command light.exe -ErrorAction SilentlyContinue)
if (-not $wix) {
    Write-Warning ("The WiX Toolset 3.x (candle.exe / light.exe) was not found, so no .msi or .exe installer was made. " +
        "Install it with 'winget install WiXToolset.WiXToolset', open a new terminal, and run this script again. " +
        "The app image above already runs on its own and can be zipped and shared.")
    return
}

$installerOptions = @(
    '--win-menu', '--win-shortcut', '--win-dir-chooser',
    '--win-per-user-install',                                   # no administrator rights needed
    '--win-upgrade-uuid', 'b6f3a1c2-7d48-4a55-9c3e-2f1d8a90e7b4' # lets a new version replace an old one
)
foreach ($type in 'msi', 'exe') {
    Write-Host "Creating the .$type installer..." -ForegroundColor Cyan
    & jpackage.exe --type $type @common @installerOptions
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed to create the .$type installer." }
}
Get-ChildItem $dest -File | ForEach-Object { Write-Host "Installer: $($_.FullName)" -ForegroundColor Green }
