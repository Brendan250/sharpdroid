# builds everything, in the order the pieces actually depend on each other.
#
#   .\scripts\build-all.ps1                # build. the APK is SharpEmu Debug
#   .\scripts\build-all.ps1 -Install       # build, then adb install -r the APK
#   .\scripts\build-all.ps1 -Clean         # wipe the native build directories first
#   .\scripts\build-all.ps1 -List          # print the steps and what each one is waiting on
#   .\scripts\build-all.ps1 -Release       # the APK under the manifest's own id and label
#
# **the ordering is real, not editorial.** host\CMakeLists.txt refuses to configure without
# build\adrenotools\libadrenotools.a, app\build-app.ps1 refuses without libsharpemu-host-layer.so,
# and guests\build-guests.ps1 refuses without the generated guest-side libvulkan.so.1 and
# libaaudio.so. encoding that here beats writing it down and hoping.
#
# steps whose output already exists are skipped unless -Force, so a rebuild after editing one host
# layer source does not re-fetch 14 MB of glibc or re-link the adrenotools hooks.
#
# note for anyone editing this file: keep quoted strings ASCII. windows powershell reads this as
# cp1252, where a UTF-8 em-dash ends in the byte 0x94 -- a right double quote -- and silently
# terminates whatever string it sits in. comments are unaffected.

param(
    [switch]$Install,
    [switch]$Clean,
    [switch]$Force,
    [switch]$List,
    # build the APK under a different application id, and under a different name in the launcher.
    # see app\build-app.ps1 for why that is a separate app to android, and scripts\run.ps1 for what
    # uses it. **passing neither gets the debug identity**, which is the default.
    [string]$Package = "",
    [string]$Name = "",
    # build the APK under the manifest's own id and label. ignored if -Package or -Name is passed.
    [switch]$Release
)

$ErrorActionPreference = "Stop"

$here = $PSScriptRoot
. (Join-Path $here "toolchain.ps1")
$repoRoot = Get-SharpEmuRepoRoot

# each step: what it is, the script, and the artefact whose absence means it has to run.
$steps = @(
    [ordered]@{
        name    = "guest libraries"
        script  = "guest-libs\fetch-guest-libs.ps1"
        args    = @()
        produces = "guest-libs\x86_64\libc.so.6"
        about   = "downloads the debian x86-64 glibc set the guest's ld.so searches"
    },
    [ordered]@{
        name    = "libadrenotools"
        script  = "scripts\build-adrenotools.ps1"
        args    = @()
        produces = "build\adrenotools\libadrenotools.a"
        about   = "the custom GPU driver loader. host\CMakeLists.txt will not configure without it"
    },
    [ordered]@{
        name    = "vulkan thunk, guest half"
        script  = "host\thunks\vulkan\build-guest-vulkan.ps1"
        args    = @()
        produces = "guest-libs\x86_64\libvulkan.so.1"
        about   = "623 x86-64 stubs assembled into the libvulkan.so.1 the guest links against"
    },
    [ordered]@{
        name    = "audio thunk, guest half"
        script  = "host\thunks\audio\build-guest-aaudio.ps1"
        args    = @()
        produces = "guest-libs\x86_64\libaaudio.so"
        about   = "72 x86-64 stubs assembled into the libaaudio.so the fork's audio backend calls"
    },
    [ordered]@{
        name    = "host layer"
        script  = "host\build.ps1"
        args    = @()
        produces = "build\host\libsharpemu-host-layer.so"
        about   = "FEXCore for bionic plus the host layer, as a JNI library and a shell binary"
        always  = $true
    },
    [ordered]@{
        name    = "test guests"
        script  = "guests\build-guests.ps1"
        args    = @()
        produces = "build\guests\hello-libc"
        about   = "the x86-64 guests regression.sh exercises the host layer with"
    },
    [ordered]@{
        name    = "APK"
        script  = "app\build-app.ps1"
        # **a hashtable, not an array.** array splatting binds *positionally* -- @("-Package", $x)
        # passes the literal string "-Package" as the first positional parameter and silently drops
        # the rest, which is how a build once ran with the application id set to "-Package" and
        # -Install quietly discarded. only hashtable splatting binds by name.
        args    = $(
                    $a = @{}
                    if ($Package) { $a["Package"] = $Package }
                    if ($Name)    { $a["Name"] = $Name }
                    if ($Release) { $a["Release"] = $true }
                    $a
                  )
        # a renamed application id writes to its own directory, so the artefact asserted below has to
        # follow it rather than staying at the default path. **the identity is resolved here with the
        # same function build-app.ps1 uses**, because this step asserts the file that one produces and
        # two copies of the rule would eventually disagree -- silently, as a missing artefact on a
        # build that worked.
        produces = (Get-ApkArtefact -Package (Resolve-AppIdentity -Package $Package -Name $Name -Release:$Release).Package)
        about   = "aapt2, javac, d8, zipalign, apksigner. no gradle"
        always  = $true
    }
)

if ($List) {
    Write-Host ""
    foreach ($s in $steps) {
        $have = Test-Path (Join-Path $repoRoot $s.produces)
        $mark = if ($s.always) { "[always]" } elseif ($have) { "[have]  " } else { "[build] " }
        Write-Host ("  {0} {1}" -f $mark, $s.name)
        Write-Host ("           {0}" -f $s.about)
        Write-Host ("           -> {0}" -f $s.produces)
    }
    Write-Host ""
    Write-Host "[always] steps run every time; the rest are skipped when their output exists. -Force runs all."
    return
}

# fail early and in one place if the toolchain is not there, rather than three steps in.
try {
    $null = Resolve-Toolchain -Need Ndk, Cmake, BuildTools, Jdk -Quiet
} catch {
    Write-Host ""
    Write-Host $_.Exception.Message
    throw "toolchain check failed - run .\scripts\fetch-toolchain.ps1 to see what is missing."
}

if ($Clean) {
    foreach ($d in @("build\host", "build\adrenotools")) {
        $p = Join-Path $repoRoot $d
        if (Test-Path $p) { Write-Host "wiping $d"; Remove-Item -Recurse -Force $p }
    }
}

$ran = 0
$skipped = 0
foreach ($s in $steps) {
    $target = Join-Path $repoRoot $s.produces
    $always = [bool]$s.always
    if (-not $Force -and -not $always -and (Test-Path $target)) {
        Write-Host ("skip  {0}  (have {1})" -f $s.name, $s.produces)
        $skipped++
        continue
    }

    Write-Host ""
    Write-Host ("==== {0} " -f $s.name).PadRight(78, "=")
    $script = Join-Path $repoRoot $s.script
    if (-not (Test-Path $script)) { throw "missing build script: $script" }

    $stepArgs = @{}
    foreach ($k in $s.args.Keys) { $stepArgs[$k] = $s.args[$k] }
    # -Install belongs to the APK step alone; -Clean is handled above, since only two steps have one.
    if ($Install -and $s.name -eq "APK") { $stepArgs["Install"] = $true }

    & $script @stepArgs
    $ran++

    # **assert the artefact, not the absence of an exception.** a step that returned quietly having
    # produced nothing is the failure mode this project keeps meeting -- sdkmanager exited 0 having
    # installed nothing at all.
    if (-not (Test-Path $target)) {
        throw "$($s.name) finished but did not produce $($s.produces)"
    }
}

Write-Host ""
Write-Host ("built: {0} step(s) run, {1} skipped" -f $ran, $skipped)
$apk = Join-Path $repoRoot ($steps | Where-Object { $_.name -eq "APK" }).produces
if (Test-Path $apk) { Write-Host ("  {0:N0} bytes  {1}" -f (Get-Item $apk).Length, $apk) }
Write-Host ""
Write-Host "next: .\scripts\run.ps1              build, stage and launch on the device"
Write-Host "      .\scripts\regression.ps1       the host layer's 15 modes, on the device"
Write-Host "      .\scripts\package-build.ps1    cut a SharpEmu build"
Write-Host "      .\scripts\stage-build.ps1      put one on a device"
Write-Host "      .\scripts\stage-game.ps1       and a game, and stage-guest-libs.ps1"
