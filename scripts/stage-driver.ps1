# stages an adrenotools driver package where the app can list it and load it from.
#
#   .\scripts\stage-driver.ps1                          # Turnip_Gen8_V33.zip
#   .\scripts\stage-driver.ps1 -Driver .\other.zip      # any adrenotools package
#   .\scripts\stage-driver.ps1 -Driver .\t.zip -Name turnip
#
# the package format is the one the driver manager imports over SAF: a zip holding meta.json
# and the driver .so. it is unpacked here rather than on the device because android's shell has no
# unzip worth relying on, and the two files are small.
#
# **this is not where the driver is loaded from.** it lands in the app's *external* files
# directory, which is the one place both `adb shell` and the app can see without a picker -- and
# adrenotools will not touch it there. it stats and then dlopens the driver, and the linker
# refuses a library sitting somewhere another app could have written it, which
# /storage/emulated/0 is by definition. MainActivity copies it onto internal storage at launch,
# which is the same two-step the AdrenoToolsTest reference does and for the same reason.

# **-Driver is the zip and -Package is the application id**, matching every other stage-*.ps1. the
# other way round -- the zip on -Package and the app on a flag of its own -- reads backwards against
# its siblings, and that is the kind of thing that quietly stages a driver to the wrong app.
param(
    [string]$Driver = "Turnip_Gen8_V33.zip",
    [string]$Name = "turnip",
    # which app to work against. **empty means the debug app**, com.mircowuffwuff.sharpemu.debug,
    # which is a separate app to android with its own storage and save data -- all development
    # testing happens there and never on a personal install of the release build. resolved by
    # Resolve-AppPackage in toolchain.ps1, because a param default cannot see it yet.
    [string]$Package = ""
)

$ErrorActionPreference = "Continue"

$here = $PSScriptRoot
. (Join-Path $here "toolchain.ps1")
. (Join-Path $here "device.ps1")
$Package = Resolve-AppPackage $Package
# driver packages live in the workspace beside this repository, not in it.
$tc = Resolve-Toolchain -Need Adb
$repoRoot = $tc.RepoRoot
$adb = $tc.Adb
$files = Get-AppFilesDir $Package

$zipPath = $Driver
if (-not [System.IO.Path]::IsPathRooted($zipPath)) { $zipPath = Join-Path $tc.Workspace $Driver }
if (-not (Test-Path -LiteralPath $zipPath)) { throw "driver package not found: $zipPath" }

$staging = Join-Path $repoRoot "build\driver\$Name"
Remove-Item -Recurse -Force $staging -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $staging | Out-Null

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($zipPath, $staging)

# meta.json is what names the library, so a package whose .so is called something else still
# works without this script knowing anything about turnip in particular.
$metaPath = Join-Path $staging "meta.json"
if (-not (Test-Path $metaPath)) { throw "$Package has no meta.json - is it an adrenotools package?" }
$meta = Get-Content $metaPath -Raw | ConvertFrom-Json
$libName = $meta.libraryName
if (-not $libName) { throw "meta.json has no libraryName" }
if (-not (Test-Path (Join-Path $staging $libName))) { throw "meta.json names $libName and the package does not contain it" }

Write-Host ("{0} - {1}, {2}" -f $meta.name, $meta.driverVersion, $libName)

$dest = "$files/gpu-drivers/$Name"
& $adb shell "mkdir -p '$dest'"
if ($LASTEXITCODE -ne 0) { throw "could not create $dest - is $Package installed and has it run once?" }

foreach ($f in @("meta.json", $libName)) {
    Push-Quiet $adb (Join-Path $staging $f) "$dest/$f"
}
& $adb shell sync

# assert the library landed, not just that the pushes returned.
if (-not (Test-DevicePath $adb "$dest/$libName")) { throw "$libName did not land at $dest" }

Write-Host ""
Write-Host "staged to $dest"
& $adb shell "ls -l '$dest'"
