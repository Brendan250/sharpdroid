# build it, put it on the device, start it, and show you the log. one command.
#
#   .\scripts\run.ps1
#   .\scripts\run.ps1 -Game "Y:\games\Dead Cells [PPSA15552]"
#   .\scripts\run.ps1 -SharpEmu C:\wip\publish\linux-x64      # a bare folder: SharpEmu + plugins/
#   .\scripts\run.ps1 -SharpEmu .\build\builds\android-0.0.3-hotfix-2-b1.zip
#   .\scripts\run.ps1 -BuildSharpEmu                          # publish and package the fork first
#   .\scripts\run.ps1 -Driver ..\Turnip_Gen8_V33.zip -Turbo
#   .\scripts\run.ps1 -Seconds 90 -NoLogs                     # a timed run, summarised at the end
#   .\scripts\run.ps1 -NoGame                                 # the app's own game list, no guest
#
# **it installs under its own application id.** com.mircowuffwuff.sharpemu.debug is a different app
# to android: its own internal storage, its own external files directory, its own save data. so a
# deploy loop cannot disturb a personal install of SharpEmu on the same phone. everything it stages
# lives under that id and nothing it does touches the release one.
#
# **the SharpEmu build is held constant unless you say otherwise**, and that is deliberate rather
# than lazy. with no -SharpEmu it reuses whatever is already staged for the debug app. rebuilding the
# emulator every time you change one line of the host layer moves two variables per iteration and
# hands you a different payload from the one your last measurement used, which is the single mistake
# this project has recorded most often. **when nothing at all is staged it asks** rather than telling
# you to run the same command again with one more flag -- Y publishes and packages the fork, n stops.
#
# **with no -Game it takes one off the device**: Dreaming Sarah if it is there, because every
# measurement in this project is against it, and otherwise any staged game. it says which it took.
#
# **-NoGame launches the app's own game list instead of a guest**, which is the run for looking at
# the frontend. it starts GameListActivity rather than MainActivity, so nothing is resolved that only
# a guest run needs: no game, and no build or driver unless you name one to stage. every extra the
# other flags produce is read by a guest launch and by nothing else, so naming one alongside -NoGame
# is refused rather than accepted and dropped.
#
# **everything you can name here is a path on this machine, and nothing is a name.** -Game, -SharpEmu
# and -Driver take a PC path; the device path is computed from it and never typed. each one is staged
# if the device does not already have those bytes and reused if it does -- **compared by size, not by
# name**, because a rebuilt build keeps its directory name and reusing it runs yesterday's payload.
# -Restage pushes over all three regardless. **omitting one means "whatever the device already has"**,
# which is the common case and needs no path because nothing needs staging.
#
# **a path rather than an id, and the app accepts nothing else.** an id names a family, so resolving
# one means answering with the newest of it -- and a work-in-progress build then loses silently to an
# older one still on the device, which is a plausible artefact attributed to the wrong source. one
# form rather than two is also what stops the ambiguous one staying reachable.
#
# note for anyone editing this file: keep quoted strings ASCII. windows powershell reads this as
# cp1252, where a UTF-8 em-dash ends in the byte 0x94 -- a right double quote -- and silently
# terminates whatever string it sits in. comments are unaffected.

param(
    # a path to a game directory. its last component is the name on the device, and one already there
    # with an eboot.bin of the same size is reused unless -Restage. omitted: a game already on the
    # device -- Dreaming Sarah if it is there, otherwise any of them.
    [string]$Game = "",
    # open the app's game list and run no guest. the frontend run: -SharpEmu and -Driver still stage
    # what they name, and everything else a guest launch needs is skipped.
    [switch]$NoGame,
    # a path to a build directory or zip. reused if the device already has that build with a payload
    # of the same size, staged otherwise. omitted: whatever is already staged for the debug app, and
    # if nothing is, it offers to publish and package the fork.
    [string]$SharpEmu = "",
    # publish and package the fork checkout first. explicit, because it is minutes and it moves a
    # variable.
    [switch]$BuildSharpEmu,
    # a driver package (.zip) to stage, or the name of one already on the device. omitted: the
    # platform's own Adreno driver.
    [string]$Driver = "",
    [switch]$Turbo,
    # extra guest environment, comma separated NAME=VALUE.
    [string]$GuestEnv = "",
    [ValidateSet("", "none", "mtrack", "full")]
    [string]$Smc = "",
    # the FEXCore JIT preset. omitted: whatever the settings scene stored, which is nothing on a
    # fresh install -- and FEXCore's own defaults are what a run with no preset gets.
    [ValidateSet("", "stability", "compatibility", "intermediate", "performance", "extreme")]
    [string]$FexPreset = "",
    # run the host layer's 15 regression modes before deploying.
    [switch]$Check,
    # stop after this many seconds and summarise. 0 streams until the process exits or you Ctrl-C.
    [int]$Seconds = 0,
    [switch]$NoLogs,
    [switch]$NoBuild,
    # push -Game, -SharpEmu and -Driver over what the device has, whatever their sizes say. rarely
    # needed, since a size mismatch restages by itself; it is the escape hatch for the one case a
    # byte count cannot see, which is two different dumps or builds of exactly the same length.
    [switch]$Restage,
    # which app to work against. **empty means the debug app**, com.mircowuffwuff.sharpemu.debug,
    # which is a separate app to android with its own storage and save data -- all development
    # testing happens there and never on a personal install of the release build. resolved by
    # Resolve-AppPackage in toolchain.ps1, because a param default cannot see it yet.
    [string]$Package = "",
    # the name in the launcher. it installs beside a release SharpEmu, so telling the two apart at a
    # glance matters more than it sounds.
    [string]$Name = "SharpEmu Debug"
)

$ErrorActionPreference = "Continue"

# **refused rather than dropped.** every one of these is an extra MainActivity reads, and the game
# list neither receives nor honours any of them - so accepting one here would start a screen that
# quietly is not the run that was asked for.
if ($NoGame) {
    $guestOnly = @()
    if ($Game)      { $guestOnly += "-Game" }
    if ($Turbo)     { $guestOnly += "-Turbo" }
    if ($GuestEnv)  { $guestOnly += "-GuestEnv" }
    if ($Smc)       { $guestOnly += "-Smc" }
    if ($FexPreset) { $guestOnly += "-FexPreset" }
    if ($guestOnly.Count) {
        throw ("-NoGame runs no guest, so " + ($guestOnly -join ", ") + " would have no effect. drop it, or drop -NoGame.")
    }
}

$here = $PSScriptRoot
. (Join-Path $here "toolchain.ps1")
. (Join-Path $here "device.ps1")
$Package = Resolve-AppPackage $Package
$tc = Resolve-Toolchain -Need Adb -Quiet
$adb = $tc.Adb
$repoRoot = $tc.RepoRoot
$files = Get-AppFilesDir $Package
# the list is an activity of the app's, a guest run is MainActivity in its own process. **an `if`
# expression would be a parse error here**: windows powershell has no such thing, and this file is
# read by it.
$activityClass = "MainActivity"
if ($NoGame) { $activityClass = "GameListActivity" }
$activity = Resolve-AppActivity $Package -Class $activityClass

function Step([string]$text) {
    Write-Host ""
    Write-Host ("==== {0} " -f $text).PadRight(78, "=")
}

# --- 0. a device -------------------------------------------------------------------------------
$devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' })
if ($devices.Count -eq 0) { throw "no device. plug one in with usb debugging enabled." }
if ($devices.Count -gt 1) { throw "$($devices.Count) devices attached; set `$env:ANDROID_SERIAL to pick one." }

# --- 1. build and install ----------------------------------------------------------------------
if (-not $NoBuild) {
    Step "build"
    & (Join-Path $here "build-all.ps1") -Install -Package $Package -Name $Name
    if (-not $?) { throw "build failed" }
} else {
    Write-Host "skipping the build (-NoBuild)"
}

if (-not (& $adb shell "pm list packages $Package")) {
    throw "$Package is not installed. drop -NoBuild so the APK gets built and installed."
}

# --- 2. optional regression ---------------------------------------------------------------------
if ($Check) {
    Step "regression"
    & (Join-Path $here "regression.ps1")
    if ($LASTEXITCODE -ne 0) { throw "the regression set failed - fix that before looking at a game." }
}

# --- 3. guest libraries -------------------------------------------------------------------------
# the debug app starts with nothing, so this is the common case rather than a repair path.
$haveLibs = @(Get-DeviceListing $adb "$files/guest-libs")
if ($haveLibs.Count -lt 20) {
    Step "guest libraries"
    & (Join-Path $here "stage-guest-libs.ps1") -Package $Package
    if (-not $?) { throw "staging the guest libraries failed" }
}

# --- 4. the game --------------------------------------------------------------------------------
# **one call, and the rule it implements lives in device.ps1** -- named, so it is a path on this
# machine and gets staged if the device does not already have those bytes; omitted, so it is whatever
# is already there, Dreaming Sarah first. every measurement script goes through the same function,
# which is what stops the two halves of this project disagreeing about what "already staged" means.
$gameName = ""
if (-not $NoGame) {
Step "game"
$gameName = Resolve-StagedGame $adb $Package $Game -Restage:$Restage
# **assert the shape of what came back.** a powershell function returns everything written to its
# output stream, so a stray native command inside one of the resolvers would arrive appended to the
# answer -- which has happened here before, with the whole of `dotnet publish`'s log returned as a
# path. a game name is one line and has no newline in it.
if (-not $gameName -or $gameName -match "[\r\n]") { throw "Resolve-StagedGame returned '$gameName', which is not one game name" }
}

# --- 5. the SharpEmu build ----------------------------------------------------------------------
$buildsDir = "$files/builds"

if ($BuildSharpEmu -and $SharpEmu) { throw "-BuildSharpEmu and -SharpEmu both name a build; pick one." }

# **nothing named and nothing staged: ask, rather than erroring out with an instruction to run the
# same command again with one more flag.** the answer is a few minutes of publishing, so it is worth
# a question -- but only a question, because -BuildSharpEmu exists precisely so that rebuilding the
# emulator is something you opt into rather than something that happens to you. answering Y just sets
# the switch, so there is one packaging path below rather than two.
#
# **a list launch is never asked**, because it runs no guest: a device with no build staged at all is
# a perfectly good device to look at the frontend on, and a question whose only honest answers cost
# minutes or abort the run would be one nobody wanted.
if (-not $NoGame -and -not $SharpEmu -and -not $BuildSharpEmu -and @(Get-DeviceListing $adb $buildsDir).Count -eq 0) {
    Write-Host ""
    Write-Host "no SharpEmu build is staged for $Package, and none was given."
    Write-Host "  -SharpEmu <folder|zip>  stages one you already have"
    Write-Host "  -BuildSharpEmu          publishes and packages the fork checkout, which takes a few minutes"
    Write-Host ""
    # **do not prompt into a redirected stdin.** it reads EOF immediately, which a naive prompt takes
    # for an answer -- the exact shape of the sdkmanager licence bug this repository already has on
    # record. soak.ps1 and CI go down this path.
    if ([Console]::IsInputRedirected) {
        throw "stdin is redirected, so there is nobody to ask. pass -SharpEmu <folder|zip> or -BuildSharpEmu."
    }
    $answer = (Read-Host "publish and package the fork now? [Y/n]").Trim()
    if ($answer -and $answer -notmatch '^(y|yes)$') { throw "aborted. no SharpEmu build to run." }
    $BuildSharpEmu = $true
}

# **this stays inline rather than becoming a function, and that is not a style preference.** a
# powershell function returns everything written to its output stream, so wrapping this put the whole
# of `dotnet publish`'s log into the returned "path" -- `no build at   Determining projects to
# restore...`. at script level that output goes to the host, where it belongs.
if ($BuildSharpEmu) {
    Step "package the fork"
    # **its failure has to stop the run.** package-build.ps1 explains its own failures perfectly well
    # -- no fork checkout in the workspace, a .NET SDK that does not satisfy global.json, a branch
    # that will not build -- and without this check run.ps1 would carry on and launch whatever was
    # staged in some earlier session, or nothing at all. both the terminating and the non-terminating
    # case are handled, because a called script can fail either way depending on its own
    # $ErrorActionPreference.
    $packageOk = $true
    try {
        & (Join-Path $here "package-build.ps1")
        if (-not $?) { $packageOk = $false }
    } catch {
        Write-Host ""
        Write-Host $_.Exception.Message
        $packageOk = $false
    }
    if (-not $packageOk) { throw "packaging the fork failed - see the error above. nothing was launched." }

    # package-build prints where it put things; find the newest directory it wrote. **assert it**: a
    # script that returns quietly having produced nothing is the failure this repository has met most
    # often.
    $packaged = Get-ChildItem (Join-Path $repoRoot "build\builds") -Directory -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $packaged) { throw "package-build.ps1 reported success but produced no build directory" }
    $SharpEmu = $packaged.FullName
}

if ($SharpEmu) {
    # a bare publish tree has no identity, so give it one rather than refusing. package-build.ps1
    # owns the meta.json format, so it does the work; duplicating that here is how two definitions of
    # one format start. this has to happen before the resolver, which reads meta.json to work out
    # what the build is called on the device.
    if (-not (Test-Path -LiteralPath $SharpEmu)) {
        Deny-NotAPcPath "build" $SharpEmu ("pass the build directory or zip that scripts/package-build.ps1 wrote, " +
                                           "or drop -SharpEmu to run what is already on the device.")
    }
    $source = (Resolve-Path -LiteralPath $SharpEmu).Path
    $isDir = (Get-Item -LiteralPath $source).PSIsContainer
    if ($isDir -and -not (Test-Path -LiteralPath (Join-Path $source "meta.json"))) {
        Step "give the publish tree an identity"
        Write-Host "$source has no meta.json, so it is being packaged as a dev build"
        & (Join-Path $here "package-build.ps1") -FromArchive $source -Id dev -SharpEmuVersion dev
        if (-not $?) { throw "packaging $source failed" }
        $SharpEmu = Join-Path $repoRoot "build\builds\dev-dev-b1"
    }
}

# **resolving a build is what stages one**, so a list launch still comes through here when -SharpEmu
# or -BuildSharpEmu named one -- it is only the resolution a launch needs that is skipped, and a list
# launch names no build to run.
$buildPath = ""
if (-not $NoGame -or $SharpEmu) {
    Step "the build"
    $buildPath = Resolve-StagedBuild $adb $Package $SharpEmu -Restage:$Restage
    # the same assertion the game gets, and here it is worth more: this string is about to be handed
    # to `am start`, where a device path that has picked up a second line launches something
    # unintended or nothing at all.
    if ($buildPath -notlike "$buildsDir/*" -or $buildPath -match "[\r\n]") {
        throw "Resolve-StagedBuild returned '$buildPath', which is not one build directory under $buildsDir"
    }
}

# --- 6. the driver ------------------------------------------------------------------------------
# named, it is a package on this machine or the name of one already on the device; omitted, it is the
# platform's own Adreno driver. **stock is the absence of a name and not a name**, which is why there
# is no sentinel here to spell.
#
# a list launch stages one it is given and says nothing when it is not: which driver the platform
# would load is a fact about a guest run, and there is no guest run to report it for.
$driverName = ""
if (-not $NoGame -or $Driver) {
    Step "the driver"
    $driverName = Resolve-StagedDriver $adb $Package $Driver -Restage:$Restage
    if ($driverName -match "[\r\n]") { throw "Resolve-StagedDriver returned '$driverName', which is not one driver name" }
    if (-not $driverName) { Write-Host "driver: the platform's own Adreno driver (-Driver to stage another)" }
}

# --- 7. launch ----------------------------------------------------------------------------------
Step "launch"
if ($buildPath) {
    $payloadSize = (& $adb shell "stat -c %s '$buildPath/SharpEmu' 2>/dev/null").Trim()
    Write-Host ("  build   {0}" -f $buildPath)
    Write-Host ("  payload {0} bytes" -f $payloadSize)
}
if ($NoGame)     { Write-Host  "  screen  the game list - no guest runs" }
else             { Write-Host ("  game    {0}" -f $gameName) }
if ($driverName) { Write-Host ("  driver  {0}" -f $driverName) }
if ($Turbo)      { Write-Host  "  turbo   on" }
if ($GuestEnv)   { Write-Host ("  env     {0}" -f $GuestEnv) }
if ($FexPreset)  { Write-Host ("  fex     {0}" -f $FexPreset) }

# **one single-quoted command string, not a list of arguments.** every game directory is named
# `Title [PPSAxxxxx]`, and passing that as its own argument loses the quoting somewhere between
# powershell and /system/bin/sh: the first launch written this way started
# `games/Dreaming/eboot.bin`. adb hands a single argument to the device shell verbatim, and single
# quotes there survive both the space and the brackets. the staging scripts hit this same wall long
# ago and solved it the same way.
#
# **the list is started bare.** it takes no extras at all, and the app resolves a build, a driver and
# a game from what the settings scene stored - which is the whole point of looking at it.
$cmd = "am start -n $activity"
if (-not $NoGame) { $cmd += " --es sharpemu '$buildPath' --es game '$gameName'" }
if ($driverName -and -not $NoGame) { $cmd += " --es driver '$driverName'" }
if ($GuestEnv)   { $cmd += " --es guestenv '$GuestEnv'" }
if ($Smc)        { $cmd += " --es smc $Smc" }
# lowercased on the way out. ValidateSet above accepts any casing and passes through what was
# typed, so -FexPreset "Intermediate" would otherwise reach the app as a spelling its own table does
# not hold -- and an id the app does not know is dropped in favour of the stored setting, which is a
# run that silently is not the one asked for. the app lowercases too; this is the cheaper half.
if ($FexPreset)  { $cmd += " --es fexpreset " + $FexPreset.ToLowerInvariant() }
if ($Turbo)      { $cmd += " --ez turbo true" }

& $adb shell am force-stop $Package
& $adb logcat -c
& $adb shell $cmd | Out-Null
if ($LASTEXITCODE -ne 0) { throw "am start failed" }

# --- 8. the log ---------------------------------------------------------------------------------
$logDir = Join-Path $repoRoot "build\runs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$logPath = Join-Path $logDir ("run-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".log")

function Write-Summary([string]$path) {
    if (-not (Test-Path $path)) { return }
    Write-Host ""
    Write-Host ("==== summary ").PadRight(78, "=")
    $identity = Select-String -Path $path -Pattern "\[app\] build:|\[host-layer\] loaded|backend=" |
                Select-Object -First 3
    $identity | ForEach-Object { "  " + ($_.Line -replace '^.*sharpemu: ', '') } | Write-Host

    $frames = Select-String -Path $path -Pattern "\[vulkan\] presented frame (\d+)" | ForEach-Object {
        [PSCustomObject]@{ t = [datetime]::ParseExact($_.Line.Substring(0, 18), "MM-dd HH:mm:ss.fff", $null)
                           n = [int]$_.Matches[0].Groups[1].Value }
    }
    if ($frames.Count -ge 2) {
        # from frame 300: the first few hundred include shader compilation and CoreCLR tiering up,
        # and they are worth several fps of noise.
        $a = $frames | Where-Object { $_.n -ge 300 } | Select-Object -First 1
        if (-not $a) { $a = $frames[0] }
        $b = $frames[-1]
        $span = ($b.t - $a.t).TotalSeconds
        if ($span -gt 0) {
            Write-Host ("  frames {0} -> {1} in {2:N1} s  =  {3:N2} fps" -f $a.n, $b.n, $span, (($b.n - $a.n) / $span))
        }
    } else {
        Write-Host "  no frames presented"
    }

    # **count episodes, not lines, and always say whether it recovered.** the watchdog prints four
    # diagnostic lines per stall, so matching every "STALL" reported one event as four. and a stall
    # that recovers is a game going quiet, while a stall that does not is the open correctness bug --
    # pooling the two is what made two of this project's published rates meaningless. the episode
    # begins at the "writes started" line, which is emitted exactly once per event.
    $stalls = @(Select-String -Path $path -Pattern "\[audio-wd\] STALL: writes started")
    $recovered = @(Select-String -Path $path -Pattern "\[audio-wd\].*recovered")
    $fatal = @(Select-String -Path $path -Pattern "FATAL EXCEPTION|Invalid Program|UnsatisfiedLinkError")

    if ($stalls.Count) {
        $permanent = $stalls.Count - $recovered.Count
        Write-Host ("  audio: {0} stall episode(s), {1} recovered" -f $stalls.Count, $recovered.Count)
        if ($permanent -gt 0) {
            Write-Host ("  ** {0} did not recover - this is the known audio-stall bug, not something you just broke **" -f $permanent)
        }
    }
    if ($fatal.Count) { $fatal | Select-Object -First 3 | ForEach-Object { Write-Host ("  FATAL  " + $_.Line.Trim()) } }
    if (-not $stalls.Count -and -not $fatal.Count) { Write-Host "  no stall, no fatal error" }
    Write-Host ""
    Write-Host "  full log: $path"
}

if ($NoLogs -and $Seconds -le 0) {
    Write-Host ""
    Write-Host "started. not following the log (-NoLogs)."
    return
}

# **the two modes are genuinely different and each is done the way that is reliable for it.**
#
# a timed run waits and then dumps: `logcat -d` after the fact is deterministic, and it is what every
# measurement in this project has been taken with. following live cannot be timed reliably, because
# a deadline checked inside a pipeline only fires when the next line arrives -- if the guest stops
# emitting, the run never ends.
#
# and the live path writes through one open StreamWriter. the first version called
# `Out-File -Append` per line, which reopens the file for every line: against a guest emitting
# thousands of lines a second the pipeline backed up and the capture stopped growing after 3 KB.
if ($Seconds -gt 0) {
    Write-Host ""
    Write-Host "running for $Seconds s"
    Start-Sleep -Seconds $Seconds
    & $adb shell am force-stop $Package | Out-Null
    & $adb logcat -d -v time -s sharpemu:V AndroidRuntime:E DEBUG:E libc:E |
        Out-File -FilePath $logPath -Encoding utf8
    Write-Summary $logPath
    return
}

Write-Host ""
Write-Host "following the log - Ctrl-C to stop"
Write-Host ""

$writer = New-Object System.IO.StreamWriter($logPath, $false, (New-Object System.Text.UTF8Encoding $false))
try {
    # -s filters to our own tags, so the emulator's output is not buried in the system's. the raw
    # capture is written alongside so the summary can be recomputed later.
    & $adb logcat -v time -s sharpemu:V AndroidRuntime:E DEBUG:E libc:E | ForEach-Object {
        $writer.WriteLine($_)
        if (-not $NoLogs) { Write-Host $_ }
    }
} finally {
    $writer.Flush()
    $writer.Close()
    Write-Summary $logPath
}
