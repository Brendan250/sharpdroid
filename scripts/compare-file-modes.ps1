# runs one game both ways -- staged as a path, and reached through a directory grant -- and says
# whether the two are the same run.
#
#   .\scripts\compare-file-modes.ps1
#   .\scripts\compare-file-modes.ps1 -Pairs 3 -Seconds 60
#   .\scripts\compare-file-modes.ps1 -Game "Y:\games\Dead Cells [PPSA15552]"
#   .\scripts\compare-file-modes.ps1 -SafGame "Dead Cells [PPSA15552]"
#
# a game behind a grant is not reachable by path, so the host layer answers the guest's file
# syscalls itself out of a content provider. docs/guest-files.md is the design. **the question this
# script exists to answer is whether that changes the run**, and it answers it two ways:
#
#   1. **the counts.** with the file probe on, both arms print what the guest asked of its own game
#      directory. the path-taking counts freeze at the end of boot and are a property of the game
#      rather than of the run -- so they must match **exactly**, and a mismatch means the layer is
#      answering something differently from the filesystem rather than merely more slowly.
#   2. **the cost.** boot to first frame, and steady-state fps.
#
# **the arms alternate AND the order reverses on alternate pairs, which is not the same thing.**
# alternating alone still puts the same arm first in every pair, and this device drifts downward run
# over run as it warms -- so "which arm" and "which position" become perfectly confounded and the
# arm that goes first wins every time. that is not a hypothetical: measured control-then-granted
# four pairs running, the control won 4 of 4 by ~0.6 fps, and reversing the order collapsed it to
# ~0.1. one pair each way is the minimum that can tell the two apart.
#
# **the staged arm is the control and nothing about it changes.** no mount is passed, so the
# interception is never registered and the run is byte-for-byte the one every other script produces.
# that is the whole point: a number measured through it cannot have been moved by a file layer that
# was not in it.
#
# the granted arm needs a directory grant the app already holds, taken by hand through the picker.
# there is no picker in the app yet, so a run without one stops and says so rather than failing
# somewhere further on.

param(
    # a path to a game directory to stage, or the name of one already on the device. omitted: a game
    # already there. **this is the staged arm's game**, and -SafGame is the granted arm's.
    [string]$Game = "",
    # the directory name inside the granted tree. omitted: the same name as the staged arm's, which
    # is what comparing one game both ways means -- the two have to be the same dump for the counts
    # to be comparable at all.
    [string]$SafGame = "",
    # one staged run and one granted run per pair, with the order reversed on odd-numbered pairs.
    # two is the minimum that says anything; three is worth it if the answer is close.
    [int]$Pairs = 2,
    # long enough for the boot and a steady-state window past frame 300. the path-taking counts are
    # complete well before this, so a shorter run compares the counts and not the frame rate.
    [int]$Seconds = 45,
    # a path to a build directory or zip. **stated rather than left to the app**, for the same reason
    # compare-drivers.ps1 states it: this compares file modes, so the build is a constant that has to
    # be written down. omitted: whatever is staged, named in the output.
    [string]$Build = "",
    [switch]$Restage,
    # which app to work against. empty means the debug app, resolved by Resolve-AppPackage.
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
$out = Join-Path $repoRoot "build\file-modes"
New-Item -ItemType Directory -Force -Path $out | Out-Null

$gameName = Resolve-StagedGame $adb $Package $Game -Restage:$Restage
$buildPath = Resolve-StagedBuild $adb $Package $Build -Restage:$Restage
if ($buildPath -notmatch "^/storage/.+/builds/[^/\r\n]+$") { throw "Resolve-StagedBuild returned '$buildPath', which is not one build directory" }
if (-not $SafGame) { $SafGame = $gameName }

Write-Host ""
Write-Host ("game   {0}" -f $gameName)
Write-Host ("grant  {0}" -f $SafGame)
Write-Host ("build  {0}" -f $buildPath)
Write-Host ("{0} pair(s), {1} s each, order reversed on alternate pairs" -f $Pairs, $Seconds)

# the ten path-taking counts out of the probe's own line. **read= and fstat= are deliberately not
# among them**: those run on descriptors and keep ticking for as long as the guest plays, so they
# depend on when the run was cut and not on how the file came to be open. everything below freezes
# at the end of boot, which is what makes it a property of the game.
$countFields = @("paths", "opens", "dir", "write_opens", "failed", "stat", "access", "readlink", "statfs", "getdents")
$countPattern = 'paths=(\d+) opens=(\d+) \(dir (\d+), write (\d+), failed (\d+)\) stat=(\d+) access=(\d+) readlink=(\d+) statfs=(\d+) \| getdents=(\d+) read=(\d+) \((\d+) KB\) write=(\d+)'

function Invoke-Arm {
    param([bool]$Granted, [int]$Pair)

    $label = if ($Granted) { "granted" } else { "staged " }
    $slug = "pair$Pair-" + $(if ($Granted) { "granted" } else { "staged" })

    $start = "am start -n $(Resolve-AppActivity $Package) --es sharpemu '$buildPath' --es game '$gameName' --ez tracefiles true"
    if ($Granted) { $start += " --es safgame '$SafGame'" }

    & $adb shell am force-stop $Package | Out-Null
    & $adb logcat -c
    & $adb shell $start | Out-Null

    Start-Sleep -Seconds $Seconds

    $log = Join-Path $out "$slug.log"
    & $adb logcat -d -s sharpemu:* | Set-Content -Encoding utf8 $log
    & $adb shell am force-stop $Package | Out-Null
    $lines = Get-Content $log

    # a granted run with no grant behind it is a setup problem rather than a result, and it has to be
    # said in those words -- the app refuses before the guest starts, so every number below would be
    # empty and the comparison would report a difference that is not one.
    if ($Granted -and ($lines | Select-String "needs a granted directory and this app holds none")) {
        throw "the app holds no directory grant, so the granted arm cannot run. grant one by hand through the picker first."
    }
    if ($Granted -and ($lines | Select-String "is that the name of a game directory in the grant")) {
        throw "'$SafGame' is not a game directory inside the grant. pass -SafGame with the name as it appears there."
    }

    # frame N at time T, from the log's own timestamps, exactly as compare-drivers.ps1 reads them.
    $samples = @()
    foreach ($m in ($lines | Select-String "^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d).*presented frame (\d+)")) {
        $g = $m.Matches[0].Groups
        $t = [double]$g[3].Value * 3600 + [double]$g[4].Value * 60 + [double]$g[5].Value + [double]$g[6].Value / 1000
        $samples += [pscustomobject]@{ Frame = [int]$g[7].Value; Time = $t }
    }

    $fps = $null
    $steady = @($samples | Where-Object { $_.Frame -ge 300 })
    if ($steady.Count -ge 2) {
        $span = $steady[-1].Time - $steady[0].Time
        if ($span -gt 0) { $fps = ($steady[-1].Frame - $steady[0].Frame) / $span }
    }

    # boot: the argument vector going down against the first frame coming back. both lines carry the
    # log's own timestamp, so this needs nothing switched on.
    $boot = $null
    $launch = ($lines | Select-String "^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d).*\[app\] starting:" | Select-Object -First 1)
    if ($launch -and $samples.Count) {
        $g = $launch.Matches[0].Groups
        $t0 = [double]$g[3].Value * 3600 + [double]$g[4].Value * 60 + [double]$g[5].Value + [double]$g[6].Value / 1000
        $boot = $samples[0].Time - $t0
    }

    $counts = $null
    $readKB = $null
    $writes = $null
    $probe = ($lines | Select-String $countPattern | Select-Object -Last 1)
    if ($probe) {
        $g = $probe.Matches[0].Groups
        $counts = [ordered]@{}
        for ($i = 0; $i -lt $countFields.Count; $i++) { $counts[$countFields[$i]] = [int]$g[$i + 1].Value }
        $readKB = [int]$g[12].Value
        $writes = [int]$g[13].Value
    }

    Write-Host ("  {0}  boot {1,6}  {2,6} fps  {3}" -f $label,
        $(if ($boot) { "{0:N2}s" -f $boot } else { "-" }),
        $(if ($fps) { "{0:N2}" -f $fps } else { "-" }),
        $(if ($counts) { ($countFields | ForEach-Object { "$_=$($counts[$_])" }) -join " " } else { "no probe line" }))

    return [pscustomobject]@{
        Arm = $(if ($Granted) { "granted" } else { "staged" })
        Pair = $Pair
        Boot = $boot
        Fps = $fps
        Counts = $counts
        ReadKB = $readKB
        Writes = $writes
        Log = $log
    }
}

$runs = @()
for ($p = 1; $p -le $Pairs; $p++) {
    # the reversal. an odd pair runs staged first, an even pair granted first, so neither arm holds
    # the first position in every pair and thermal drift cannot pose as a result.
    $grantedFirst = ($p % 2) -eq 0
    Write-Host ""
    Write-Host ("=== pair {0} of {1}, {2} first ===" -f $p, $Pairs, $(if ($grantedFirst) { "granted" } else { "staged" }))
    if ($grantedFirst) {
        $runs += Invoke-Arm -Granted $true -Pair $p
        $runs += Invoke-Arm -Granted $false -Pair $p
    } else {
        $runs += Invoke-Arm -Granted $false -Pair $p
        $runs += Invoke-Arm -Granted $true -Pair $p
    }
}

# --- the verdict --------------------------------------------------------------------------------

Write-Host ""
Write-Host ("=" * 78)

$staged = @($runs | Where-Object { $_.Arm -eq "staged" })
$granted = @($runs | Where-Object { $_.Arm -eq "granted" })

# **the counts first, because they are the falsifiable half.** the cost is a measurement with a
# spread; this is an equality, and it either holds or the layer is answering the guest differently
# from the filesystem.
$countsOk = $true
$reference = ($runs | Where-Object { $_.Counts } | Select-Object -First 1)
if (-not $reference) {
    Write-Host "counts     NO PROBE LINE in any run. --ez tracefiles true produced nothing, so nothing was compared."
    $countsOk = $false
} else {
    foreach ($r in $runs) {
        if (-not $r.Counts) {
            Write-Host ("counts     {0} pair {1}: no probe line" -f $r.Arm, $r.Pair)
            $countsOk = $false
            continue
        }
        foreach ($f in $countFields) {
            if ($r.Counts[$f] -ne $reference.Counts[$f]) {
                Write-Host ("counts     MISMATCH {0} pair {1}: {2}={3}, against {4} in {5} pair {6}" -f
                    $r.Arm, $r.Pair, $f, $r.Counts[$f], $reference.Counts[$f], $reference.Arm, $reference.Pair)
                $countsOk = $false
            }
        }
    }
    if ($countsOk) {
        Write-Host ("counts     IDENTICAL across all {0} runs: {1}" -f $runs.Count,
            (($countFields | ForEach-Object { "$_=$($reference.Counts[$_])" }) -join " "))
    }
}

# the read-only claim, which is a measurement rather than an assumption and stops being true the day
# a title writes into its own dump.
$wrote = @($runs | Where-Object { $_.Writes -ne $null -and $_.Writes -gt 0 })
if ($wrote.Count) {
    Write-Host ("read-only  BROKEN: {0} run(s) wrote to a descriptor from the game directory. the layer refuses writes, so this title cannot work through a grant." -f $wrote.Count)
    $countsOk = $false
} elseif ($reference) {
    Write-Host ("read-only  holds: 0 writes in every run, and {0} open(s) of which {1} were for writing" -f $reference.Counts["opens"], $reference.Counts["write_opens"])
}

function Show-Spread {
    param([string]$What, [double[]]$A, [double[]]$B, [string]$Unit, [int]$Digits)
    if (-not $A.Count -or -not $B.Count) { return }
    $ma = ($A | Measure-Object -Average).Average
    $mb = ($B | Measure-Object -Average).Average

    # **one run per arm has no spread, so it cannot be compared against one.** said rather than
    # computed, because the arithmetic below would happily report a 0.7 fps difference as being
    # above a spread of zero -- which is a confident answer to a question this sample cannot answer,
    # and the exact shape of mistake the reversal above exists to prevent. a single pair also never
    # reverses, so both runs sit in a fixed order on a device that drifts.
    if ($A.Count -lt 2 -or $B.Count -lt 2) {
        Write-Host ("{0,-10} staged {1} {4}   granted {2} {4}   difference {3} {4} -- **one run each, so there is no spread to read this against, and this pair never reversed. -Pairs 2 or more.**" -f $What,
            [math]::Round($ma, $Digits), [math]::Round($mb, $Digits), [math]::Round($mb - $ma, $Digits), $Unit)
        return
    }
    # the spread *within* one arm is the yardstick the difference *between* them has to beat. a gap
    # smaller than the noise is not a result, and printing both is what makes that visible instead of
    # leaving two means to be compared as though they were exact.
    $spread = [math]::Max((($A | Measure-Object -Maximum).Maximum - ($A | Measure-Object -Minimum).Minimum),
                          (($B | Measure-Object -Maximum).Maximum - ($B | Measure-Object -Minimum).Minimum))
    $delta = $mb - $ma
    $verdict = if ([math]::Abs($delta) -lt $spread) { "below the run-to-run spread" } else { "ABOVE the spread, so look at it" }
    Write-Host ("{0,-10} staged {1} {5}   granted {2} {5}   difference {3} {5}, {4}" -f $What,
        [math]::Round($ma, $Digits), [math]::Round($mb, $Digits), [math]::Round($delta, $Digits), $verdict, $Unit)
    Write-Host ("           staged  {0}" -f (($A | ForEach-Object { [math]::Round($_, $Digits) }) -join "  "))
    Write-Host ("           granted {0}" -f (($B | ForEach-Object { [math]::Round($_, $Digits) }) -join "  "))
}

Show-Spread -What "boot" -A @($staged | Where-Object { $_.Boot } | ForEach-Object { $_.Boot }) -B @($granted | Where-Object { $_.Boot } | ForEach-Object { $_.Boot }) -Unit "s" -Digits 2
Show-Spread -What "fps" -A @($staged | Where-Object { $_.Fps } | ForEach-Object { $_.Fps }) -B @($granted | Where-Object { $_.Fps } | ForEach-Object { $_.Fps }) -Unit "fps" -Digits 2

Write-Host ""
Write-Host ("logs in {0}" -f $out)
Write-Host ""
if (-not $countsOk) {
    Write-Host "VERDICT    the two ways of reaching this game are NOT the same run. the counts above say where."
    exit 1
}
Write-Host "VERDICT    the guest asked the grant exactly what it asked the filesystem, and got the same answers."
exit 0
