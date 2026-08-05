# runs the game once per SharpEmu build and reports what actually differed between them.
#
#   .\scripts\compare-builds.ps1                                     # every build on the device
#   .\scripts\compare-builds.ps1 -Builds .\build\builds\parity-0.0.3-hotfix-2-b1 -Seconds 90
#   .\scripts\compare-builds.ps1 -Game "Y:\games\Dead Cells [PPSA15552]"
#
# **-Builds, -Game and -Driver are paths on this machine**, each staged if the device does not
# already have those bytes. omitting -Builds compares every build that is on the device, which is
# better than a literal default: it compares what is actually there rather than two names that may
# resolve to something else or to nothing.
#
# the sibling of compare-drivers.ps1, along the other axis. a build is selected per launch through
# an intent extra, so this is one APK and N runs - an APK rebuild between candidates would be a
# second variable.
#
# **the payload's byte count is the control here**, the way the device identity is in
# compare-drivers.ps1. two builds that report the same size are the same payload, whatever their
# meta.json claimed, and a run attributed to the wrong artefact is the mrpurple-t29 trap: the
# number looks fine and nothing errors.
#
# what is reported per run:
#   payload   the size the host layer read off the file it loaded
#   fps       steady state from frame 300, from the presented-frame log's own timestamps
#   passes/f  vkCmdBeginRenderPass per frame, which is the acceptance test item 18 established.
#             fps is not: the batching attempt did not move fps and did not move this either, and
#             it was this that caught it.
#   env       what the build's own meta.json defaulted on, so a build is visibly what it claims

param(
    # paths to build directories or zips. omitted: every build staged on the device, sorted.
    [string[]]$Builds = @(),
    [int]$Seconds = 90,
    # a path to a game directory. omitted: a game already on the device, Dreaming Sarah first.
    [string]$Game = "",
    # a path to a driver package, or the name of one already on the device. omitted: the platform's
    # own Adreno driver.
    [string]$Driver = "",
    # push -Builds, -Game and -Driver over what the device has regardless of size.
    [switch]$Restage,
    [switch]$Screenshots,
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
$repoRoot = $tc.RepoRoot
$adb = $tc.Adb
$out = Join-Path $repoRoot "build\build-compare"
New-Item -ItemType Directory -Force -Path $out | Out-Null

# resolved once, before the first run: staging between two arms of a comparison would land in the
# measurement window of the one it interrupted.
$gameName = Resolve-StagedGame $adb $Package $Game -Restage:$Restage
$driverName = Resolve-StagedDriver $adb $Package $Driver -Restage:$Restage

$buildList = @()
if ($Builds.Count -eq 0) {
    # **every build on the device, sorted** -- the point of this script is what differs between the
    # things you have, and a literal default cannot know what those are.
    $filesDir = Get-AppFilesDir $Package
    $staged = @(Get-DeviceListing $adb "$filesDir/builds") | Sort-Object
    if ($staged.Count -eq 0) { throw "no build is staged for $Package. stage one with .\scripts\stage-build.ps1 -Build <path>." }
    foreach ($s in $staged) { $buildList += [pscustomobject]@{ Path = "$filesDir/builds/$s"; Label = $s } }
    Write-Host ("comparing the {0} build(s) on the device: {1}" -f $staged.Count, ($staged -join ", "))
} else {
    foreach ($b in $Builds) {
        $p = Resolve-StagedBuild $adb $Package $b -Restage:$Restage
        if ($p -notmatch "^/storage/.+/builds/[^/\r\n]+$") { throw "Resolve-StagedBuild returned '$p', which is not one build directory" }
        $buildList += [pscustomobject]@{ Path = $p; Label = (Split-Path -Leaf $p) }
    }
}

$results = @()

foreach ($entry in $buildList) {
    $build = $entry.Label
    Write-Host ""
    Write-Host ("=== {0} ===" -f $build)

    & $adb shell am force-stop $Package | Out-Null
    & $adb logcat -c
    # --ez profile true is not optional here: passes/f only exists under --vulkan-profile.
    $start = "am start -n $(Resolve-AppActivity $Package) --es sharpemu '$($entry.Path)' --ez profile true"
    if ($gameName) { $start += " --es game '$gameName'" }
    if ($driverName) { $start += " --es driver '$driverName'" }
    & $adb shell $start | Out-Null

    Start-Sleep -Seconds $Seconds

    if ($Screenshots) {
        & $adb shell screencap -p /sdcard/cmp.png | Out-Null
        & $adb pull /sdcard/cmp.png (Join-Path $out "$build.png") | Out-Null
    }
    $log = Join-Path $out "$build.log"
    & $adb logcat -d -s sharpemu:* | Set-Content -Encoding utf8 $log
    $lines = Get-Content $log

    # what the launcher resolved, and what the host layer actually opened. the second is the one to
    # believe: the first is meta.json's opinion of itself.
    $identity = ($lines | Select-String "\[app\] build: (.+?) at " | Select-Object -First 1)
    $loaded = ($lines | Select-String "\[host-layer\] loaded .*/([^/]+) \((\d+) bytes\)" | Select-Object -First 1)
    $refused = ($lines | Select-String "no build called|refusing to launch it" | Select-Object -First 1)
    $buildEnv = ($lines | Select-String "\[app\] starting: (.+)$" | Select-Object -First 1)

    $samples = @()
    foreach ($m in ($lines | Select-String "^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d).*presented frame (\d+)")) {
        $g = $m.Matches[0].Groups
        $t = [double]$g[3].Value * 3600 + [double]$g[4].Value * 60 + [double]$g[5].Value + [double]$g[6].Value / 1000
        $samples += [pscustomobject]@{ Frame = [int]$g[7].Value; Time = $t }
    }
    $steady = $samples | Where-Object { $_.Frame -ge 300 }
    $fps = $null
    if ($steady.Count -ge 2) {
        $span = $steady[-1].Time - $steady[0].Time
        if ($span -gt 0) { $fps = ($steady[-1].Frame - $steady[0].Frame) / $span }
    }

    # the per-command table only prints the worst twelve, so vkCmdBeginRenderPass is not always in
    # it. the framebuffer-run line is unconditional under --vulkan-profile and counts every pass,
    # so passes/f is derived from it and the frame count when the table does not carry it.
    $passes = $null
    $row = ($lines | Select-String "vkCmdBeginRenderPass\s+[0-9.]+ ms/f wall\s+[0-9.]+ ms/f cpu\s+([0-9.]+) calls/f" | Select-Object -Last 3)
    if ($row.Count) {
        $passes = ($row | ForEach-Object { [double]$_.Matches[0].Groups[1].Value } | Measure-Object -Average).Average
    }
    $runs = ($lines | Select-String "render passes (\d+) in (\d+) framebuffer runs: mean run ([0-9.]+), longest (\d+)" | Select-Object -Last 1)
    $meanRun = if ($runs) { [double]$runs.Matches[0].Groups[3].Value } else { $null }

    # the batching branch's own accounting, and it only exists when the build's meta.json defaulted
    # SHARPEMU_BATCH_RENDER_PASSES on. its absence on `performance` is half the result. that branch
    # is `perf/render-pass-batching` now, archived rather than maintained.
    $batching = ($lines | Select-String "render pass batching: (.+)$" | Select-Object -Last 1)

    $results += [pscustomobject]@{
        build     = $build
        resolved  = if ($identity) { $identity.Matches[0].Groups[1].Value } else { "" }
        payload   = if ($loaded) { [int64]$loaded.Matches[0].Groups[2].Value } else { $null }
        fps       = if ($fps) { [math]::Round($fps, 1) } else { $null }
        passes_f  = if ($passes) { [math]::Round($passes, 1) } else { $null }
        mean_run  = $meanRun
        frames    = if ($samples.Count) { $samples[-1].Frame } else { 0 }
        batching  = if ($batching) { $batching.Matches[0].Groups[1].Value } else { "" }
        note      = if ($refused) { $refused.Matches[0].Value } else { "" }
    }
    $results[-1] | Format-List | Out-String | Write-Host
    if ($buildEnv) { Write-Host ("  argv: " + $buildEnv.Matches[0].Groups[1].Value) }
}

& $adb shell am force-stop $Package | Out-Null

Write-Host ""
Write-Host "=== summary ==="
$results | Format-Table build, payload, fps, passes_f, mean_run, frames -AutoSize | Out-String -Width 200 | Write-Host

# two builds that loaded the same number of bytes are one build measured twice, whatever their
# names were. this is the only check in here that can fail a run outright.
$sizes = $results | Where-Object { $_.payload } | Select-Object -ExpandProperty payload
if ($sizes.Count -gt 1 -and (($sizes | Select-Object -Unique).Count -eq 1)) {
    Write-Host "IDENTICAL PAYLOADS - every run above loaded the same bytes, so these are not different builds"
}

$results | ConvertTo-Json -Depth 3 | Set-Content -Encoding utf8 (Join-Path $out "results.json")
Write-Host ("logs and results in {0}" -f $out)
