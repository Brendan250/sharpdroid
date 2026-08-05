# puts the x86-64 guest libraries on the device, where the guest's own ld.so will search them.
#
#   .\scripts\stage-guest-libs.ps1
#   .\scripts\stage-guest-libs.ps1                    # into the debug app
#
# **this is not a rootfs.** it is a directory of shared objects the guest's dynamic linker finds on
# its search path: still one process, one address space, no proot and no socket hop. they run under
# FEXCore exactly like SharpEmu does, and their syscalls arrive at our dispatch layer exactly like
# SharpEmu's do. guest-libs\fetch-guest-libs.ps1 builds the set; this puts it on a device.
#
# it includes the two generated ones -- libvulkan.so.1 and libaaudio.so, the guest halves of the
# thunks -- because they live in the same directory and are found the same way.
#
# note for anyone editing this file: keep quoted strings ASCII. windows powershell reads this as
# cp1252, where a UTF-8 em-dash ends in the byte 0x94 -- a right double quote -- and silently
# terminates whatever string it sits in. comments are unaffected.

param(
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
$tc = Resolve-Toolchain -Need Adb
$adb = $tc.Adb
$files = Get-AppFilesDir $Package

$libs = Join-Path $tc.RepoRoot "guest-libs\x86_64"
if (-not (Test-Path $libs)) {
    throw "no guest libraries at $libs. run .\guest-libs\fetch-guest-libs.ps1 first."
}
$local = @(Get-ChildItem $libs -File)
if ($local.Count -eq 0) { throw "$libs is empty" }

& $adb shell "mkdir -p '$files/guest-libs'"
if ($LASTEXITCODE -ne 0) { throw "could not create $files/guest-libs on the device" }

Write-Host ("staging {0} libraries" -f $local.Count)
foreach ($f in $local) { Push-Quiet $adb $f.FullName "$files/guest-libs/" }
& $adb shell sync

# assert the count, and assert the two the guest cannot start without: ld.so is the interpreter and
# libc.so.6 is what everything else needs.
$onDevice = @(Get-DeviceListing $adb "$files/guest-libs")
if ($onDevice.Count -lt $local.Count) {
    throw "$($onDevice.Count) of $($local.Count) libraries landed at $files/guest-libs"
}
foreach ($needed in @("ld-linux-x86-64.so.2", "libc.so.6")) {
    if ($onDevice -notcontains $needed) { throw "$needed is not on the device" }
}

Write-Host ""
Write-Host ("staged: {0} libraries verified at {1}/guest-libs" -f $onDevice.Count, $files)
