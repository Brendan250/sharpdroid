# stages the shell binary and everything it needs into /data/local/tmp/sharpemu.
#
#   .\scripts\stage-shell.ps1
#   .\scripts\stage-shell.ps1 -NoGuestLibs      # skip the 14 MB of glibc when only the host layer moved
#
# **the shell binary is not a legacy path.** the host layer builds twice from one set of objects: a
# JNI library the APK loads, and `sharpemu-host-layer`, an executable. every milestone before the app was
# measured through the executable, regression.sh runs it, and it is a far better place to bisect a
# JIT problem from than an activity is. this is what puts it on the device.
#
# it is a *push* from the build tree, not a copy from somewhere else on the device: what lands is
# always what was just built.
#
# note for anyone editing this file: keep quoted strings ASCII. windows powershell reads this as
# cp1252, where a UTF-8 em-dash ends in the byte 0x94 -- a right double quote -- and silently
# terminates whatever string it sits in. comments are unaffected.

param(
    [switch]$NoGuestLibs,
    [switch]$NoGuests,
    [string]$Dest = "/data/local/tmp/sharpemu"
)

$ErrorActionPreference = "Continue"

$here = $PSScriptRoot
. (Join-Path $here "toolchain.ps1")
# the NDK supplies libc++_shared.so, which the shell binary needs beside it: it links c++_shared and
# there is no app lib directory out here to find it in.
$tc = Resolve-Toolchain -Need Adb, Ndk
$repoRoot = $tc.RepoRoot
$adb = $tc.Adb

$hostLayer = Join-Path $repoRoot "build\host\sharpemu-host-layer"
if (-not (Test-Path $hostLayer)) { throw "no shell binary at $hostLayer. run .\host\build.ps1 first." }

$stl = Join-Path $tc.NdkSysroot "usr\lib\aarch64-linux-android\libc++_shared.so"
if (-not (Test-Path $stl)) { throw "libc++_shared.so not found at $stl" }

& $adb shell "mkdir -p '$Dest/guest-libs'"
if ($LASTEXITCODE -ne 0) { throw "could not create $Dest on the device" }

$pushed = 0
# adb reports transfer progress on *stderr*, and windows powershell wraps every one of those lines
# in a NativeCommandError record and prints it -- 29 files of that buried the regression results
# under a page of noise. silenced here and the exit code checked instead, which is the only thing
# that ever said whether a push worked.
function Push-One([string]$local, [string]$remote) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    try { & $adb push $local $remote | Out-Null } finally { $ErrorActionPreference = $prev }
    if ($LASTEXITCODE -ne 0) { throw "push failed: $local" }
    $script:pushed += (Get-Item $local).Length
}

Write-Host "host layer"
Push-One $hostLayer "$Dest/"
Push-One $stl "$Dest/"
Push-One (Join-Path $repoRoot "host\regression.sh") "$Dest/"

if (-not $NoGuests) {
    $guests = Join-Path $repoRoot "build\guests"
    if (-not (Test-Path $guests)) { throw "no guests at $guests. run .\guests\build-guests.ps1 first." }
    $n = 0
    Get-ChildItem $guests -File | ForEach-Object { Push-One $_.FullName "$Dest/"; $n++ }
    Write-Host "guests: $n"
}

if (-not $NoGuestLibs) {
    $libs = Join-Path $repoRoot "guest-libs\x86_64"
    if (-not (Test-Path $libs)) { throw "no guest libs at $libs. run .\guest-libs\fetch-guest-libs.ps1 first." }
    $n = 0
    Get-ChildItem $libs -File | ForEach-Object { Push-One $_.FullName "$Dest/guest-libs/"; $n++ }
    # regression.sh's `getent` mode runs the staged binary, which lives beside the libraries.
    $bin = Join-Path $repoRoot "guest-libs\bin"
    if (Test-Path $bin) { Get-ChildItem $bin -File | ForEach-Object { Push-One $_.FullName "$Dest/"; $n++ } }
    Write-Host "guest libs: $n"
}

& $adb shell "chmod 755 '$Dest/sharpemu-host-layer' '$Dest/regression.sh'"
& $adb shell sync

Write-Host ""
Write-Host ("staged {0:N0} bytes to {1}" -f $pushed, $Dest)
