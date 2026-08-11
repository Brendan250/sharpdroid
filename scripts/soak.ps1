# the soak harness: run a game N times per build, in interleaved arms, and classify every run.
#
# the harness is general -- N runs, several builds, several arms, one classification per run. its
# *classifiers* are currently audio-shaped, because the audio stall is the failure this project has
# had to chase; adding another one means adding another column, not another script.
#
# this exists because the failure it hunts is **intermittent and silent**. audio stops partway into
# a run, the picture and the frame rate carry on perfectly, and nothing anywhere returns an error.
# at a rate of a few percent a handful of clean runs proves nothing at all, which is a mistake this
# project made twice. so: many runs, both arms, interleaved, classified.
#
#   .\scripts\soak.ps1                                     # 10 runs of whatever is staged
#   .\scripts\soak.ps1 -Builds .\build\builds\blocking-0.0.3-hotfix-2-b1,.\build\builds\android-0.0.3-hotfix-2-b1 -Runs 6
#   .\scripts\soak.ps1 -Game "Y:\games\Dead Cells [PPSA15552]" -Runs 5
#
# **-Builds and -Game are paths on this machine**, and each is staged if the device does not already
# have those bytes. omitting -Builds soaks what is already there. the labels in the table are the
# builds' on-device directory names, which are unique per build -- a full path would wreck the
# column.
#
# **`blocking` is the pre-fix control build** and it may not be on the device. it is the shipping branch
# b2 verbatim -- the AAudio path that parks the guest thread in a blocking write -- with its
# meta.json `id` changed. recreate it by checking out the commit before
# "fix(audio): never park the submitting thread inside AAudio", packaging it, and editing its
# meta.json's `id` and `name`. keeping the pre-fix build selectable is the whole point: an
# arm only means something when the unmodified arm failed in the same sitting.
#
# **a lost run is one of two unrelated failures and they are reported separately.** pooling them is
# how a rate stops meaning anything, and this script did pool them until a traced run separated them:
#
#   AUDIO STALL   the audio thread is ALIVE, parked in a futex on a guest address, and the game
#                 carries on rendering at its full frame rate at ~100% of a core. **this is the
#                 audio stall.**
#   AUDIO CRASH   the process DIED and took the audio with it: presented frames stop too and process
#                 CPU falls to a few percent. that is a *different* failure -- a signal-delivery
#                 correctness bug -- and not the stall. it counts as a crash whether the fault
#                 killed the guest audio thread or the runtime raised a fatal error on some other
#                 thread. **the second shape is the one that scores as a stall if you let it**, since
#                 the watchdog never gets to say the audio thread is gone.
#
# quote the stall rate when you mean the stall. the crash rate belongs to a different investigation.
#
# what each column means:
#   stalls      [audio-wd] STALL episodes -- the guest stopped submitting for 2 s or more
#   recovered   how many of those started again. a stall with no recovery is the bug
#   audio-to    how far into the stream the last frames-read report got, against the run length
#   cpu         process CPU over the measurement window, as a percentage of one core. ~100 on a
#               stall, a few percent on a crash -- an independent check on the classification
#   writes/s    AAudioStream_write calls a second. ~187 blocking, ~1035 non-blocking + our pacing

param(
    # paths to build directories or zips, one soak arm each. omitted: whatever is staged on the
    # device -- **not** a literal default like a branch name, which would resolve to the newest
    # packagedAt of that id and so could measure a build nobody staged.
    [string[]]$Builds = @(),
    [int]$Runs = 10,
    # seconds of measurement, after 25 s of settling. the guest opens its stream at about +6 s.
    [int]$Seconds = 70,
    # a path to a game directory. omitted: a game already on the device, Dreaming Sarah first.
    [string]$Game = "",
    # push -Builds and -Game over what the device has regardless of size.
    [switch]$Restage,
    [string]$Extra = "",
    # a second axis, for when the two things being compared are one build launched two ways rather
    # than two builds -- "label=<extra intent args>", e.g.
    #   -Arms "reuse=","leak=--es guestenv SHARPEMU_LEAK_CONTINUATION_STUBS=1"
    # every arm is run once per iteration so the arms interleave, which is what makes a rate
    # comparison survive the device warming up or drifting mid-soak. empty means one unnamed arm,
    # and the output is then identical to what it has always been.
    [string[]]$Arms = @(),
    [string]$OutDir = "",
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
if (-not $OutDir) { $OutDir = Join-Path $repoRoot "build\soak" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# **resolve every path once, before the first run.** a soak is an hour, and staging a build in the
# middle of one would put a device-side copy in the measurement window of the run it interrupted.
# it also fails now rather than after 25 minutes if a path is wrong.
$gameName = Resolve-StagedGame $adb $Package $Game -Restage:$Restage
if (-not $gameName -or $gameName -match "[\r\n]") { throw "Resolve-StagedGame returned '$gameName', which is not one game name" }

# one entry per build: the device path to launch, and the leaf that labels its row. **objects rather
# than nested arrays**, because powershell unrolls an array of arrays at the first pipeline that
# touches it and the result is a list of strings that still indexes without erroring.
$buildPaths = @()
if ($Builds.Count -eq 0) {
    # **nothing named means what is already there**, and a soak says which it took rather than
    # guessing quietly: an hour of runs attributed to the wrong build is the most expensive version
    # of this project's oldest failure. several staged and none named takes the most recently staged
    # -- run.ps1's rule -- and prints the rest so a wrong one is visible in the first two seconds.
    $staged = @(Get-DeviceListing $adb "$(Get-AppFilesDir $Package)/builds") | Sort-Object
    if ($staged.Count -gt 1) {
        Write-Host ("{0} builds are staged: {1}" -f $staged.Count, ($staged -join ", "))
        Write-Host "  -Builds <path> soaks a particular one"
    }
    $buildPaths += (Resolve-StagedBuild $adb $Package "")
} else {
    foreach ($b in $Builds) { $buildPaths += (Resolve-StagedBuild $adb $Package $b -Restage:$Restage) }
}
# the label is the on-device directory name, which is `<id>-<version>-b<n>` and unique per build. a
# full device path would be one column of the table.
$buildList = @()
foreach ($p in $buildPaths) {
    if ($p -notmatch "^/storage/.+/builds/[^/\r\n]+$") { throw "Resolve-StagedBuild returned '$p', which is not one build directory" }
    $buildList += [pscustomobject]@{ Path = $p; Label = (Split-Path -Leaf $p) }
}

$results = @()

function Invoke-Run($buildPath, $build, $arm, $armExtra, $n) {
    $label = if ($arm) { "$build/$arm" } else { $build }
    & $adb shell am force-stop $Package | Out-Null
    Start-Sleep -Milliseconds 800
    & $adb logcat -c

    # the watchdog runs whether or not this is passed; --ez audiowatchdog true makes it report every
    # second and dump every thread at a stall, which is what the writes/s column reads.
    # Resolve-AppActivity, not "$Package/.MainActivity" -- see toolchain.ps1 for why that shorthand
    # resolves to nothing under a renamed application id, which every script here uses.
    # single-quoted for the *device* shell: adb hands the command to sh, and a build directory can
    # carry anything meta.json's id did.
    $start = "am start -n $(Resolve-AppActivity $Package) --es sharpemu '$buildPath' --ez audiowatchdog true"
    if ($gameName) { $start += " --es game '$gameName'" }
    if ($Extra) { $start += " $Extra" }
    if ($armExtra) { $start += " $armExtra" }
    & $adb shell $start | Out-Null

    Start-Sleep -Seconds 25
    $procId = ((& $adb shell pidof $Package) -join "").Trim()
    if (-not $procId) {
        Write-Host ("{0,-34}{1,-3}process died before the window opened" -f $label, $n)
        return
    }

    $before = (& $adb shell "cat /proc/$procId/stat") -split ' '
    $t0 = Get-Date
    Start-Sleep -Seconds $Seconds
    $alive = ((& $adb shell pidof $Package) -join "").Trim()
    $after = if ($alive) { (& $adb shell "cat /proc/$procId/stat") -split ' ' } else { $null }
    $wall = ((Get-Date) - $t0).TotalSeconds

    $log = & $adb shell "logcat -d -s sharpemu:*"
    $log | Set-Content (Join-Path $OutDir ("{0}-{1:D2}.log" -f ($label -replace "[^a-zA-Z0-9._-]", "-"), $n))
    & $adb shell am force-stop $Package | Out-Null

    # USER_HZ is 100 on this device; fields 14 and 15 of /proc/<pid>/stat are utime and stime.
    $cpu = if ($after) {
        (([double]$after[13] + [double]$after[14]) - ([double]$before[13] + [double]$before[14])) / 100.0 / $wall * 100.0
    } else { 0 }

    $wd = @($log | Select-String -Pattern "\[audio-wd\] (\d+) writes")
    $writes = 0.0
    if ($wd.Count -ge 2) {
        $f = [regex]::Match($wd[0].Line, "\[audio-wd\] (\d+) writes").Groups[1].Value
        $l = [regex]::Match($wd[-1].Line, "\[audio-wd\] (\d+) writes").Groups[1].Value
        $writes = ([double]$l - [double]$f) / ($wd.Count - 1)
    }

    $stalls = @($log | Select-String -Pattern "STALL: writes started").Count
    $recovered = @($log | Select-String -Pattern "recovered: the guest").Count
    $la = @($log | Select-String -Pattern "frames read in")
    $audioTo = if ($la.Count) { [double][regex]::Match($la[-1].Line, "in ([\d.]+) s").Groups[1].Value } else { 0 }
    $size = ($log | Select-String -Pattern "host-layer. loaded .* \((\d+) bytes\)")
    $bytes = if ($size) { [regex]::Match($size[0].Line, "\((\d+) bytes\)").Groups[1].Value } else { "?" }

    # a run is bad if audio stopped and never came back, whether or not the watchdog caught the
    # gap -- the frames-read report stopping well short of the run is the same evidence.
    #
    # **unless the run simply ended early.** the host layer prints an end-of-run summary when the
    # guest exits, and a run that does that with audio healthy to the last report has not lost
    # anything -- it just stopped before the window did. without this a clean exit scores as a stall,
    # which is a false positive that inflates the very rate this script exists to measure.
    $exited = @($log | Select-String -Pattern "\[host-layer\] \d+ frame\(s\) presented").Count -gt 0
    $lost = ($stalls -gt $recovered) -or (($audioTo -lt ($Seconds + 25 - 20)) -and -not $exited)

    # **and a lost run is then one of two completely different failures, which this pooled under one
    # heading until a traced run separated them.** they must not share a rate:
    #
    #   STALL  the audio thread is alive and parked in a futex, and the game carries on rendering at
    #          its full frame rate. this is the audio stall.
    #   CRASH  the audio thread *died* on an unhandled guest fault and took the emulator with it --
    #          frames stop too, and process CPU falls to a few percent instead of ~100. that is the
    #          signal-delivery family, not this one.
    #
    # **the discriminator is the audio thread's own fate, and nothing else.** "a thread died on an
    # unhandled fault" appears in both -- a run has been seen where some *other* guest thread faulted
    # while the audio thread sat in the usual futex and the game presented another 300 frames -- so
    # keying on any fault anywhere over-reports crashes. the watchdog names the one thread that
    # matters: "tid N is GONE (the guest audio thread died)". process CPU is the independent check,
    # ~100% on a stall and a few percent on a crash.
    $died = @($log | Select-String -Pattern "is GONE \(the guest audio thread died\)").Count

    # **and a fourth false positive: a fatal error on a thread that is not the audio one.** the
    # runtime can raise `Invalid Program: attempted to call a UnmanagedCallersOnly method from
    # managed code` on the *presenter* thread, which takes the whole process down -- so the watchdog
    # never gets to say the audio thread is gone and the run would score as a stall. the runtime's
    # own fatal-error path plus a dead process is a crash whatever thread raised it, and process CPU
    # says so independently: a stall holds ~100% of a core to the end of the window, a dead process
    # reads 0.
    $fatal = @($log | Select-String -Pattern "Fatal error\.|Invalid Program:|took signal \d+ with no handler").Count
    $kind = if (-not $lost) { "ok" } elseif ($died -or ($fatal -and $cpu -lt 10)) { "CRASH" } else { "STALL" }
    $script:results += [pscustomobject]@{ Build = $label; Lost = $lost; Kind = $kind }

    Write-Host ("{0,-34}{1,-3}{2,-11}  stalls {3} recovered {4}  audio to {5,6:N1}s  cpu {6,6:N1}%  writes/s {7,7:N1}  payload {8}" -f `
        $label, $n, $(if ($lost) { "AUDIO $kind" } else { "ok" }), $stalls, $recovered, $audioTo, $cpu, $writes, $bytes)
}

$armList = @()
if ($Arms.Count -eq 0) {
    $armList += ,@("", "")
} else {
    foreach ($a in $Arms) {
        $i = $a.IndexOf("=")
        if ($i -lt 1) { throw "-Arms wants label=<extra intent args>, got '$a'" }
        $armList += ,@($a.Substring(0, $i), $a.Substring($i + 1))
    }
}

$labels = @()
foreach ($b in $buildList) {
    foreach ($a in $armList) { $labels += $(if ($a[0]) { "$($b.Label)/$($a[0])" } else { $b.Label }) }
}

for ($i = 1; $i -le $Runs; $i++) {
    foreach ($b in $buildList) {
        foreach ($a in $armList) { Invoke-Run $b.Path $b.Label $a[0] $a[1] $i }
    }
}

Write-Host ""
foreach ($b in $labels) {
    $mine = @($results | Where-Object { $_.Build -eq $b })
    $bad = @($mine | Where-Object { $_.Lost }).Count
    $st = @($mine | Where-Object { $_.Kind -eq "STALL" }).Count
    $cr = @($mine | Where-Object { $_.Kind -eq "CRASH" }).Count
    if ($mine.Count) {
        # the stall rate is the one the audio stall is about, and the only one worth quoting for it.
        Write-Host ("{0,-34} audio lost in {1} of {2} runs  ({3:N0}%)  =  {4} stall ({5:N0}%) + {6} crash ({7:N0}%)" -f `
            $b, $bad, $mine.Count, (100.0 * $bad / $mine.Count), `
            $st, (100.0 * $st / $mine.Count), $cr, (100.0 * $cr / $mine.Count))
    }
}
Write-Host ""
Write-Host "logs: $OutDir"
