# stages the shell binary and runs the host layer's regression set on the device.
#
#   .\scripts\regression.ps1
#   .\scripts\regression.ps1 -NoStage      # the device is already up to date
#
# the modes themselves are host\regression.sh, which runs on the device. this is the PC-side half:
# it puts the freshly built binary and guests there first, so a pass always describes what was just
# built rather than whatever happened to be on the device.
#
# **it exits non-zero when any mode fails**, so it can gate anything.
#
# note for anyone editing this file: keep quoted strings ASCII. windows powershell reads this as
# cp1252, where a UTF-8 em-dash ends in the byte 0x94 -- a right double quote -- and silently
# terminates whatever string it sits in. comments are unaffected.

param(
    [switch]$NoStage,
    [string]$Dest = "/data/local/tmp/sharpemu"
)

$ErrorActionPreference = "Continue"

$here = $PSScriptRoot
. (Join-Path $here "toolchain.ps1")
$tc = Resolve-Toolchain -Need Adb
$adb = $tc.Adb

if (-not $NoStage) {
    & (Join-Path $here "stage-shell.ps1") -Dest $Dest
    if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) { throw "staging failed" }
    Write-Host ""
}

$out = & $adb shell "sh '$Dest/regression.sh'"
$out | ForEach-Object { Write-Host $_ }

# the exit code of `adb shell` is the device command's on modern platform-tools, but the output is
# what this asserts on: a mode that failed prints FAIL, and a run that produced no PASS lines at all
# did not run rather than passed. **a green result has to be a positive statement, not the absence
# of a red one** -- an empty capture must never read as success.
$pass = @($out | Where-Object { $_ -match '^PASS' }).Count
$fail = @($out | Where-Object { $_ -match '^FAIL' }).Count

Write-Host ""
Write-Host ("regression: {0} passed, {1} failed" -f $pass, $fail)

if ($fail -gt 0) { exit 1 }
if ($pass -eq 0) { Write-Host "no modes ran at all - is the device connected and staged?"; exit 1 }
exit 0
