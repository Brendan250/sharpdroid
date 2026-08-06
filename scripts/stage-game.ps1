# puts a game on the device, where the app can read it.
#
#   .\scripts\stage-game.ps1 -Game "Y:\games\Dreaming Sarah [PPSA02929]"
#   .\scripts\stage-game.ps1 -Game "..\Dead Cells [PPSA15552]"
#
# **-Game is a path, and the on-device name is its last component.** that is the whole mapping: a
# directory called `Dreaming Sarah [PPSA02929]` becomes `<files>/games/Dreaming Sarah [PPSA02929]`,
# which is what `--es game` names at launch. scripts that only *launch* take that name instead,
# because by then it is the identity of something already on the device; scripts that *stage* take a
# path, because a name would mean guessing which directory on your disk was meant.
#
# note for anyone editing this file: keep quoted strings ASCII. windows powershell reads this as
# cp1252, where a UTF-8 em-dash ends in the byte 0x94 -- a right double quote -- and silently
# terminates whatever string it sits in. comments are unaffected.

param(
    [Parameter(Mandatory = $true)]
    [string]$Game,
    # which app to work against. **empty means the debug app**, com.mircowuffwuff.sharpemu.debug,
    # which is a separate app to android with its own storage and save data -- all development
    # testing happens there and never on a personal install of the release build. resolved by
    # Resolve-AppPackage in toolchain.ps1, because a param default cannot see it yet.
    [string]$Package = "",
    # push even when the device already has this game. worth having: two different dumps can end in
    # the same folder name with the same eboot.bin size, and silently reusing the wrong one is a
    # plausible artefact attributed to the wrong source.
    #
    # **-Restage is the word, everywhere.** run.ps1 uses it for exactly this, and
    # -Force already means "rebuild what is up to date" on build-all.ps1 -- two meanings for one flag
    # across scripts that get run in the same breath. kept as an alias because -Force is what this
    # one was called until 2026-08-05.
    [Alias("Force")]
    [switch]$Restage
)

$ErrorActionPreference = "Continue"

$here = $PSScriptRoot
. (Join-Path $here "toolchain.ps1")
. (Join-Path $here "device.ps1")
$Package = Resolve-AppPackage $Package
$tc = Resolve-Toolchain -Need Adb
$adb = $tc.Adb
$files = Get-AppFilesDir $Package

# **-LiteralPath, everywhere, and it is not optional here.** every PS5 game directory is named
# `Title [PPSAxxxxx]`, and square brackets are powershell wildcard characters: Test-Path on such a
# path reads [PPSA02929] as a character class and reports the directory missing. the same trap has
# already made Get-ChildItem measure these folders as zero bytes.
if (-not (Test-Path -LiteralPath $Game)) { throw "no game directory at $Game" }
$source = (Resolve-Path -LiteralPath $Game).Path
$name = Split-Path -Leaf $source
$eboot = Join-Path $source "eboot.bin"
if (-not (Test-Path -LiteralPath $eboot)) { throw "$source has no eboot.bin - that is not a game directory" }

$dest = "$files/games/$name"
& $adb shell "mkdir -p '$files/games'"
if ($LASTEXITCODE -ne 0) { throw "could not create $files/games on the device" }

# **existence is not sameness.** testing only that eboot.bin is there reuses whichever dump got to
# that folder name first, which is the wrong-artefact failure this project keeps meeting -- so the
# byte count decides, the way it does for a build's payload, and a mismatch restages by itself.
$localSize = (Get-Item -LiteralPath $eboot).Length
$onDevice = Get-DeviceFileSize $adb "$dest/eboot.bin"
if ($onDevice -eq $localSize -and -not $Restage) {
    Write-Host "already on the device: $name"
    Write-Host ("  {0}, eboot.bin {1:N0} bytes" -f $dest, $onDevice)
    Write-Host "  (pass -Restage to push $source over it)"
    return
}
if ($onDevice -ge 0 -and $onDevice -ne $localSize) {
    Write-Host ("{0} is on the device with a {1:N0} byte eboot.bin and this one is {2:N0} - restaging" -f `
        $name, $onDevice, $localSize)
}

$size = (Get-ChildItem -LiteralPath $source -Recurse -File | Measure-Object -Property Length -Sum).Sum
Write-Host ""
Write-Host ("staging {0}" -f $name)
Write-Host ("  from {0}" -f $source)
Write-Host ("  {0:N0} bytes" -f $size)

& $adb shell "rm -rf '$dest'"
Push-Quiet $adb $source "$files/games/"
& $adb shell sync

# assert the one file the app actually opens, rather than trusting the push returned quietly.
if (-not (Test-DevicePath $adb "$dest/eboot.bin")) { throw "no eboot.bin at $dest after staging" }

Write-Host ""
Write-Host "staged: $dest"
Write-Host "  the app launches it with --es game `"$name`""
