# runs the game once per GPU driver and reports steady-state fps for each.
#
#   .\scripts\compare-drivers.ps1                                    # stock alone
#   .\scripts\compare-drivers.ps1 -Drivers ..\Turnip_Gen8_V33.zip -Seconds 120
#   .\scripts\compare-drivers.ps1 -Drivers turnip -DriverEnvs TU_DEBUG=sysmem
#   .\scripts\compare-drivers.ps1 -Game "Y:\games\Dead Cells [PPSA15552]" -Profiled -Screenshots
#
# **the platform's own driver is the implicit control and is always run.** it is not a file, so it
# cannot be a path, so it is not a name either -- each -Drivers entry *adds* a driver to compare
# against it. `-Drivers <path to turnip.zip>` gives the same two runs `-Drivers stock,turnip` used
# to. that also matches -DriverEnvs, where "" has always been the control the rest are read against.
#
# each -Drivers entry is a path to an adrenotools package on this machine, staged if the device does
# not have it, or the name of one already staged there.
#
# **the number is taken from frame 300 onwards, never from frame 1.** the first few hundred frames
# include shader compilation and CoreCLR still tiering up, and they are worth 2-3 fps of noise on a
# 57 fps run and far more on a slow one. what is reported is the slope across the presented-frame
# log lines, which agrees with SharpEmu's own overlay to within a few tenths.
#
# the driver is selected per launch through an intent extra rather than by rebuilding, so this is
# one APK and N runs. an APK rebuild between candidates would be a second variable.

# the default set is deliberately empty, which means the control alone. five turnip builds were
# compared once, at 1b, and four of them landed within 0.2 fps of each other - so a wide sweep buys
# nothing until something changes that could plausibly affect drivers differently. name
# ..\Turnip_Gen8_V33.zip to add the package the project has carried from the start.
param(
    # paths to adrenotools packages, or names of ones already on the device. the platform's own
    # driver is always run as the control and is never named here.
    [string[]]$Drivers = @(),
    [int]$Seconds = 110,
    # one run per driver per entry here, so a TU_DEBUG sweep is one invocation. "" is the driver's
    # own defaults and is the control the rest are read against.
    [string[]]$DriverEnvs = @(""),
    # a path to a game directory. `Dreaming Sarah` sits on its own 60 fps target on both drivers and
    # so cannot separate them; `Dead Cells` does not, and is the one to compare a driver on.
    # omitted: a game already on the device, Dreaming Sarah first.
    [string]$Game = "",
    # a path to a build directory or zip. **stated rather than left to the app**: this compares
    # drivers, so the build has to be a constant that is written down, and passing nothing at all
    # made it whatever the app happened to default to -- an unstated variable inside a comparison
    # harness. omitted: whatever is staged on the device, named in the output.
    [string]$Build = "",
    # push -Drivers, -Game and -Build over what the device has regardless of size.
    [switch]$Restage,
    # not $Profile: that collides with powershell's automatic $PROFILE variable.
    [switch]$Profiled,
    # pins the GPU clocks through KGSL for the run. the governor leaves this workload at the
    # *minimum* 660 MHz of 1100 available - it is latency-bound, so ~40% busy reads as headroom -
    # which is the same reason Eden ships a turbo mode.
    [switch]$Turbo,
    # extra guest environment, comma-separated. reaches SharpEmu, not the GPU driver.
    [string]$GuestEnv = "",
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
$out = Join-Path $repoRoot "build\driver-compare"
New-Item -ItemType Directory -Force -Path $out | Out-Null

# everything that is *not* the variable under test, resolved once and printed, so the run says what
# it held constant instead of leaving it to be reconstructed from a log.
$gameName = Resolve-StagedGame $adb $Package $Game -Restage:$Restage
$buildPath = Resolve-StagedBuild $adb $Package $Build -Restage:$Restage
if ($buildPath -notmatch "^/storage/.+/builds/[^/\r\n]+$") { throw "Resolve-StagedBuild returned '$buildPath', which is not one build directory" }

# **the empty string is the control and always runs first**: the platform's own Adreno driver, which
# is not a file and so has no path. every -Drivers entry is staged or checked here rather than in
# the loop, so a typo fails before the first 110 second run instead of after it.
$driverList = @("")
foreach ($d in $Drivers) {
    $name = Resolve-StagedDriver $adb $Package $d -Restage:$Restage
    if (-not $name) { throw "'$d' resolved to no driver at all, which is the control and is already in the set" }
    $driverList += $name
}

$results = @()

foreach ($driver in $driverList) {
  foreach ($driverEnv in $DriverEnvs) {
    $driverLabel = if ($driver) { $driver } else { "stock" }
    $label = if ($driverEnv) { "$driverLabel [$driverEnv]" } else { $driverLabel }
    if ($Turbo) { $label += " +turbo" }
    if ($GuestEnv) { $label += " {$GuestEnv}" }
    Write-Host ""
    Write-Host ("=== {0} ===" -f $label)

    & $adb shell am force-stop $Package | Out-Null
    & $adb logcat -c
    # built as one string and quoted for the *device* shell, not for powershell: adb hands the
    # command to sh, which re-splits it, and every game directory has a space in its name.
    # **no --es driver at all is the control**, rather than a sentinel meaning the same thing. that
    # is the same shape as run.ps1 -Driver, where omitting it is the platform's own driver.
    $start = "am start -n $(Resolve-AppActivity $Package) --es sharpemu '$buildPath'"
    if ($driver) { $start += " --es driver '$driver'" }
    if ($driverEnv) { $start += " --es driverenv '$driverEnv'" }
    if ($gameName) { $start += " --es game '$gameName'" }
    if ($Profiled) { $start += " --ez profile true" }
    if ($Turbo) { $start += " --ez turbo true" }
    if ($GuestEnv) { $start += " --es guestenv '$GuestEnv'" }
    & $adb shell $start | Out-Null

    Start-Sleep -Seconds $Seconds

    if ($Screenshots) {
        & $adb shell screencap -p /sdcard/cmp.png | Out-Null
        & $adb pull /sdcard/cmp.png (Join-Path $out ((($label -replace '[^A-Za-z0-9]+', '-').Trim('-')) + ".png")) | Out-Null
    }
    $slug = ($label -replace '[^A-Za-z0-9]+', '-').Trim('-')
    $log = Join-Path $out "$slug.log"
    # three tags, and each earned its place. sharpemu is ours. hook_impl is adrenotools' own, and
    # the only thing that says why a hook that returned a valid pointer did not actually substitute
    # the driver - mrpurple-t29 reported success and the stock driver came back. MESA is the
    # driver's, and its absence is how a TU_DEBUG sweep was run against a variable nothing was
    # reading.
    & $adb logcat -d -s sharpemu:* hook_impl:* MESA:* | Set-Content -Encoding utf8 $log

    $lines = Get-Content $log

    # what actually answered. a failed injection falls back to the stock driver and renders
    # perfectly, so this is not decoration - it is the only thing separating "slow driver" from
    # "the driver never loaded".
    $injected = ($lines | Select-String "adrenotools: (.+) injected" | Select-Object -First 1)
    $identity = ($lines | Select-String "\[vulkan\] driver: (.+)$" | Select-Object -First 1)
    $fellback = ($lines | Select-String "this is NOT the custom driver" | Select-Object -First 1)
    $failed   = ($lines | Select-String "adrenotools could not load|MISSING:|presenter failed" | Select-Object -First 1)

    # frame N at time T, from the log's own timestamps. the slope from the first sample at or after
    # frame 300 to the last is the steady-state rate.
    $samples = @()
    foreach ($m in ($lines | Select-String "^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d).*presented frame (\d+)")) {
        $g = $m.Matches[0].Groups
        $t = [double]$g[3].Value * 3600 + [double]$g[4].Value * 60 + [double]$g[5].Value + [double]$g[6].Value / 1000
        $samples += [pscustomobject]@{ Frame = [int]$g[7].Value; Time = $t }
    }
    $steady = $samples | Where-Object { $_.Frame -ge 300 }

    $fps = $null
    if ($steady.Count -ge 2) {
        $first = $steady[0]
        $last = $steady[-1]
        $span = $last.Time - $first.Time
        if ($span -gt 0) { $fps = ($last.Frame - $first.Frame) / $span }
    }

    # the last few turnaround lines, averaged. this is the number to read on a GPU-bound scene:
    # fps there is the queue's opinion, and turnaround is the GPU's.
    $turn = $null
    $turns = @($lines | Select-String "gpu turnaround: ([0-9.]+) ms mean" | Select-Object -Last 3)
    if ($turns.Count) {
        $turn = ($turns | ForEach-Object { [double]$_.Matches[0].Groups[1].Value } | Measure-Object -Average).Average
    }

    $reports = if ($identity) { $identity.Matches[0].Groups[1].Value.Trim() } else { "" }
    # the control is the empty name, and it runs first, so its identity is on record before any
    # candidate is read against it.
    if (-not $driver) { $stockIdentity = $reports }

    $note = ""
    if ($failed) { $note = $failed.Matches[0].Value }
    if ($fellback) { $note = "FELL BACK to the platform loader" }
    if ($driver -and -not $injected -and -not $fellback) { $note = "no injection attempted" }

    # **the silent one.** adrenotools can return a valid handle and still not substitute the
    # driver - its own header says so - and what comes back is the stock driver rendering
    # perfectly at the stock frame rate. that is indistinguishable from "this turnip build is
    # excellent" unless the device identity is compared, so it is compared. mrpurple-t29 is why
    # this check exists: 55.9 fps, and not one frame of it was turnip.
    if ($driver -and $stockIdentity -and $reports -eq $stockIdentity) {
        $note = "DID NOT LOAD - reports the stock driver's identity, so this number is not this driver"
        $hookWhy = ($lines | Select-String "hook failed: (.+)$" | Select-Object -First 1)
        if ($hookWhy) { $note += " (" + $hookWhy.Matches[0].Groups[1].Value.Trim() + ")" }
    }

    $results += [pscustomobject]@{
        run      = $label
        fps      = if ($fps) { [math]::Round($fps, 1) } else { $null }
        ms       = if ($fps) { [math]::Round(1000 / $fps, 1) } else { $null }
        gpu_ms   = if ($turn) { [math]::Round($turn, 2) } else { $null }
        frames   = if ($samples.Count) { $samples[-1].Frame } else { 0 }
        note     = $note
    }
    $results[-1] | Format-List | Out-String | Write-Host
  }
}

& $adb shell am force-stop $Package | Out-Null

Write-Host ""
Write-Host "=== summary ==="
$results | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
$results | ConvertTo-Json -Depth 3 | Set-Content -Encoding utf8 (Join-Path $out "results.json")
Write-Host ("logs and results in {0}" -f $out)
