# puts a packaged SharpEmu build on the device, where the app can install or run it.
#
#   .\scripts\stage-build.ps1 -Build .\build\builds\performance-0.0.3-hotfix-2-b1
#   .\scripts\stage-build.ps1 -Build .\build\builds\parity-0.0.3-hotfix-2-b1.zip
#   .\scripts\stage-build.ps1 -Build <dir>            # into the debug app
#
# **producing a build and staging it are two jobs**, so scripts\package-build.ps1 produces the
# directory and the zip and stops there, and this pushes one. that split is why you can stage a build
# you packaged last week, or one somebody else packaged, without re-publishing anything.
#
# a directory or a zip both work: the zip is the distribution format and the directory is what runs,
# so a zip is unpacked here rather than on the device -- android's shell has no unzip worth relying
# on.
#
# the on-device folder name comes from meta.json, never from the file name: `<id>-<version>-b<n>`,
# slugged. two builds of the same source coexist and the app never has to guess which is which.
#
# note for anyone editing this file: keep quoted strings ASCII. windows powershell reads this as
# cp1252, where a UTF-8 em-dash ends in the byte 0x94 -- a right double quote -- and silently
# terminates whatever string it sits in. comments are unaffected.

param(
    [Parameter(Mandatory = $true)]
    [string]$Build,
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

# **reading a build source is Read-BuildSource in device.ps1**, not code here, because the resolver
# run.ps1 and the measurement scripts go through has to agree with this script about what a build is
# called on the device -- and two implementations of one naming rule is one too many. it handles the
# zip, the meta.json checks and the slug.
#
# **$src, not $build, and that is not a style choice.** powershell variable names are
# case-insensitive, so `$build` *is* the `[string]$Build` parameter above -- and assigning an object
# to a string-typed variable does not fail, it silently stores "@{Dir=...; Meta=...}". Every field
# below then read as empty and this script staged a build called nothing to a path ending in a
# slash. the same shape as the `$history`/`$History` bug that made an analyser count everything as 0.
$src = Read-BuildSource $Build $tc.RepoRoot
if (-not $src.Dir) { throw "Read-BuildSource returned '$src', which has no Dir - see the note above about `$build" }
$source = $src.Dir
$meta = $src.Meta
$unpacked = $src.Unpacked
$dest = "$files/builds/$($src.Folder)"
$payloadSize = $src.PayloadSize

Write-Host ""
Write-Host ("staging {0} ({1} {2} b{3}, contract {4})" -f $meta.name, $meta.id, $meta.sharpemuVersion, $meta.buildVersion, $meta.hostContract)
Write-Host ("  payload {0:N0} bytes" -f $payloadSize)
Write-Host ("  -> {0}" -f $dest)

# **pushed into a scratch directory and then renamed into place, rather than pushed at $dest.**
# `adb push <dir> <existing dir>/` lands at `<existing dir>/<leaf of the source>` -- the source's own
# name on this machine, which has nothing to do with the identity in its meta.json. those two
# coincide for anything package-build.ps1 wrote, which is why this held for as long as it did, and
# they part company the moment a build directory or a zip is renamed: a build called `wip` staged to
# `builds/wip`, the assertion below looked at `builds/parity-0.0.3-hotfix-2-b3`, and the header of
# this very file promises the on-device name comes from meta.json. found on 2026-08-05 by staging a
# rebuilt build out of a scratch directory.
#
# the rename is also what makes a re-stage safe: the previous contents are dropped only once the new
# ones are completely there, so an interrupted push cannot leave a half-build under a name that says
# it is whole. same reasoning as SharpEmuBuild.install's .partial.
$leaf = Split-Path -Leaf $source
$scratch = "$files/builds/.staging"
& $adb shell "rm -rf '$scratch' && mkdir -p '$scratch'"
if ($LASTEXITCODE -ne 0) { throw "could not create $scratch on the device" }
Push-Quiet $adb $source "$scratch/"
& $adb shell "rm -rf '$dest' && mv '$scratch/$leaf' '$dest' && rm -rf '$scratch'"
if ($LASTEXITCODE -ne 0) { throw "could not move the staged build into place at $dest" }
& $adb shell sync

# **assert the payload landed, and assert its size.** the payload byte count is this project's
# control for artefact identity - it has caught a build attributed to the wrong source more than
# once - so it is checked here rather than left for a log line at launch to reveal.
$onDevice = Get-DeviceFileSize $adb "$dest/$($meta.payload)"
if ($onDevice -lt 0) { throw "nothing landed at $dest/$($meta.payload)" }
if ($onDevice -ne $payloadSize) {
    throw "payload on the device is $onDevice bytes, expected $payloadSize - the push did not complete"
}
if (-not (Test-DevicePath $adb "$dest/plugins")) { throw "plugins/ did not land at $dest" }

if ($unpacked -and (Test-Path -LiteralPath $unpacked)) { [System.IO.Directory]::Delete($unpacked, $true) }

Write-Host ""
Write-Host ("staged: {0} bytes verified on the device" -f $onDevice)
Write-Host ("  {0}" -f $dest)
# **and no id form is offered, because the app no longer accepts one.** it used to resolve an id to
# the highest installed buildVersion, so staging this very build as b1 and then selecting it by name
# would have run an existing b3 instead. the path is the only way to name a build now, and it is what
# every script in here passes.
Write-Host "  run it with .\scripts\run.ps1 -SharpEmu $Build"
