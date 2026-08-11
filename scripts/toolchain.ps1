# finds the toolchains this repository builds against, and checks their versions.
#
# dot-source it and ask for what you need:
#
#   . "$PSScriptRoot\..\scripts\toolchain.ps1"
#   $tc = Resolve-Toolchain -Need AndroidSdk, Ndk, Cmake
#   & "$($tc.CmakeBin)\cmake.exe" ...
#
# ask only for what the script uses. a missing JDK must not break the native build, so
# host\build.ps1 asks for Ndk and Cmake and never learns whether a JDK exists.
#
# **why this is not just $env:ANDROID_HOME and friends.** those are one variable per machine. a
# contributor with a jre8 and a jdk21 installed has JAVA_HOME pointed at whatever their other work
# needs, and repointing it to build this project breaks that other work. so the override layer is
# project-scoped -- SHARPEMU_JDK and friends -- and the standard variables are consulted after the
# vendored copy rather than instead of it.
#
# **and the order is vendored-before-standard-before-PATH on purpose.** the versions here are not
# incidental: NDK r29 is required because FEXCore's SpinWaitLock.h uses std::atomic_ref, which libc++
# did not implement until LLVM 19, and the .NET SDK has to satisfy the fork's global.json. on the
# maintainer's machine none of the standard variables are set, yet `dotnet` on PATH resolves to a
# *different* install from the vendored one. a PATH-first design would have silently built the
# payload with the wrong SDK.
#
# every hit is version-checked, which is what makes falling back safe rather than reckless: a wrong
# version is named and refused here instead of failing four steps later with a confusing error.
#
# **two conventions every script in this repository follows, written down here because this is the
# file they all include.**
#
# `$ErrorActionPreference` is "Continue" in any script that invokes a native executable, and "Stop"
# in the ones that do not. that is not carelessness: windows powershell wraps every stderr line from
# a native tool in an error record, so "Stop" aborts on any tool that merely warns -- cmake, adb,
# javac and sdkmanager all do, routinely. those scripts check `$LASTEXITCODE` explicitly instead,
# and that is what makes them correct. **this file sets neither**, because it is dot-sourced and
# would be changing its caller's setting out from under it.
#
# paths come from `$PSScriptRoot`, never `$MyInvocation.MyCommand.Path`: the two agree when a script
# is run and disagree when it is dot-sourced, and one of them is right in both cases.
#
# note for anyone editing this file: keep quoted strings ASCII. windows powershell reads this as
# cp1252, where a UTF-8 em-dash ends in the byte 0x94 -- a right double quote -- and silently
# terminates whatever string it sits in. comments are unaffected.

# the repository root, from this file's own location, so it does not matter how deep the caller is.
$script:SharpEmuRepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Get-SharpEmuRepoRoot { return $script:SharpEmuRepoRoot }

function Get-SharpEmuWorkspace {
    # the directory this repository sits in, holding the SDKs, the fork and the test assets. see
    # docs/repo-structure.md. SHARPEMU_WORKSPACE overrides it.
    if ($env:SHARPEMU_WORKSPACE) {
        if (-not (Test-Path $env:SHARPEMU_WORKSPACE)) {
            throw "SHARPEMU_WORKSPACE is set to '$($env:SHARPEMU_WORKSPACE)', which does not exist."
        }
        return (Resolve-Path $env:SHARPEMU_WORKSPACE).Path
    }
    return (Resolve-Path (Join-Path $script:SharpEmuRepoRoot "..")).Path
}

function Get-ToolchainSpec {
    # the single source of truth for every required version. bumping one is one edit here.
    $path = Join-Path $script:SharpEmuRepoRoot "toolchain.json"
    if (-not (Test-Path $path)) { throw "missing $path" }
    return (Get-Content $path -Raw | ConvertFrom-Json)
}

# ---------------------------------------------------------------------------------------------
# the APK's identity
#
# **every script that builds an APK defaults to the debug identity**, because the everyday build is
# a deploy loop and a different application id is a different app to android -- its own internal
# storage, its own external files directory, its own save data. so the default build cannot disturb
# a personal install of SharpEmu on the same phone, and you have to ask for the release identity
# rather than remember to ask for the debug one. see app\build-app.ps1 for the mechanism.
$script:SharpEmuDebugPackage = "com.mircowuffwuff.sharpemu.debug"
$script:SharpEmuDebugName    = "SharpEmu Debug"

function Resolve-AppIdentity {
    # the application id and launcher label an APK build should use. it lives here rather than in
    # build-app.ps1 so that build-all.ps1 can predict the artefact path build-app.ps1 will write to,
    # instead of the two reimplementing the same rule and drifting.
    #
    #   nothing passed      the debug identity. the default
    #   -Release            the manifest's own id and label, rewritten nowhere
    #   -Package or -Name   exactly what was passed, and the other comes from the manifest
    #
    # **naming a build replaces the whole default pair, not half of it**, which is what makes
    # -Release beside -Package or -Name genuinely do nothing rather than something surprising.
    # the alternative -- treating -Release as merely ignored -- would give "-Release -Name X" the
    # *debug* application id, which is the one outcome nobody asking for a release build wants.
    param([string]$Package = "", [string]$Name = "", [switch]$Release)

    if ($Package -or $Name) { return [PSCustomObject]@{ Package = $Package; Name = $Name } }
    if ($Release)           { return [PSCustomObject]@{ Package = ""; Name = "" } }
    return [PSCustomObject]@{ Package = $script:SharpEmuDebugPackage; Name = $script:SharpEmuDebugName }
}

function Resolve-AppPackage {
    # the application id a development script works against. **every script here defaults to the
    # debug app**, not just the ones that build an APK: staging, soaking and comparing all happen on
    # com.mircowuffwuff.sharpemu.debug, which android treats as a separate app with its own storage
    # and save data. so no amount of development testing can reach a personal install of the release
    # build on the same device, and the tooling agrees with itself about which app it is driving.
    #
    # it is resolved here rather than written as a parameter default because a param block is
    # evaluated before this file is dot-sourced -- eight literals would drift, and the drift would
    # look like a staging step that quietly wrote to an app nobody was running.
    param([string]$Package = "")

    if ($Package) { return $Package }
    return $script:SharpEmuDebugPackage
}

$script:SharpEmuJavaPackage = "com.mircowuffwuff.sharpemu"

function Resolve-AppActivity {
    # the component name to launch, "<application id>/<java package>.<class>". -Class picks which
    # activity: the default MainActivity is a guest run, and GameListActivity is the app's own list,
    # which is what a launch that runs no game wants.
    #
    # **the two halves are different things and only one of them moves.** aapt2's
    # --rename-manifest-package changes the application id and leaves the java package alone, so the
    # activity of the debug build is
    # com.mircowuffwuff.sharpemu.debug/com.mircowuffwuff.sharpemu.MainActivity. the shorthand
    # "<id>/.MainActivity" expands to <id>.MainActivity and resolves to nothing under any renamed id
    # -- and it works against the unrenamed one, where the two halves coincide, so the shorthand
    # looks correct until something is renamed.
    #
    # it lives here so that no script spells the java package out for itself: that is the same
    # duplication -Package would be, correct everywhere today and one more thing to miss on the day
    # the java package moves. that rename also touches entry_jni.cpp's symbol names and the -Wl,-u in
    # host/CMakeLists.txt, so it is not hypothetical bookkeeping.
    param([string]$Package = "", [string]$Class = "MainActivity")

    $Package = Resolve-AppPackage $Package
    return "$Package/$script:SharpEmuJavaPackage.$Class"
}

function Get-ApkArtefact {
    # where an APK build writes, repository-relative. two application ids must not overwrite each
    # other's output, so a renamed build gets its own directory and its own file name.
    param([string]$Package = "", [string]$OutName = "")

    if (-not $Package) { return "build\app\sharpemu-android.apk" }
    $leaf = if ($OutName) { $OutName } else { ($Package -split '\.')[-1] }
    return "build\app-$leaf\sharpemu-android-$leaf.apk"
}

# ---------------------------------------------------------------------------------------------
# version probes. each returns $null when it cannot tell, which counts as a failed check.

function Get-NdkRevision([string]$ndkPath) {
    $props = Join-Path $ndkPath "source.properties"
    if (-not (Test-Path $props)) { return $null }
    foreach ($line in (Get-Content $props)) {
        if ($line -match '^\s*Pkg\.Revision\s*=\s*(\S+)') { return $Matches[1] }
    }
    return $null
}

function Get-JdkVersion([string]$jdkPath) {
    # the `release` file rather than running java: it is faster, and java writes -version to stderr,
    # which windows powershell turns into an error record.
    $release = Join-Path $jdkPath "release"
    if (Test-Path $release) {
        foreach ($line in (Get-Content $release)) {
            if ($line -match '^\s*JAVA_VERSION\s*=\s*"?([0-9]+)') { return [int]$Matches[1] }
        }
    }
    return $null
}

function Get-DotnetSdkVersions([string]$dotnetRoot) {
    $exe = Join-Path $dotnetRoot "dotnet.exe"
    if (-not (Test-Path $exe)) { return @() }
    $out = & $exe --list-sdks
    if ($LASTEXITCODE -ne 0) { return @() }
    $versions = @()
    foreach ($line in $out) {
        if ($line -match '^\s*(\d+\.\d+\.\d+)') { $versions += $Matches[1] }
    }
    return $versions
}

function Test-DotnetSatisfies([string[]]$have, [string]$want) {
    # global.json pins 10.0.103 with rollForward latestFeature, so any 10.0.x at or above the
    # required feature band will do. major and minor must match exactly.
    $w = [version]$want
    foreach ($v in $have) {
        $h = [version]$v
        if ($h.Major -eq $w.Major -and $h.Minor -eq $w.Minor -and $h.Build -ge $w.Build) { return $true }
    }
    return $false
}

# ---------------------------------------------------------------------------------------------

# rejecting a candidate. **an explicit SHARPEMU_* override that fails is a hard error, never a
# silent fallback**: if you set it, you meant it, and quietly using something else instead is how a
# build ends up measured against a toolchain nobody chose. everything else warns and moves on, so
# that a rejection is always visible.
function Deny-Candidate([bool]$strict, [string]$source, [string]$path, [string]$reason) {
    if ($strict) {
        throw "$source is set to '$path', but $reason.`n  unset it, or point it at something valid."
    }
    Write-Warning "[toolchain] skipping $path ($source): $reason."
}

# $fix names the way out. it is a parameter because the components fetch-toolchain.ps1 installs and
# the ones git checks out are fixed by different commands, and an error naming the wrong one is worse
# than an error naming none.
function New-ToolchainFailure([string]$what, [string[]]$tried, [string]$needed, [string]$fix = "") {
    $lines = @("could not find $what.", "  needed: $needed", "  looked in:")
    foreach ($t in $tried) { $lines += "    $t" }
    if ($fix) { $lines += "  $fix" }
    else { $lines += "  fix it by running .\scripts\fetch-toolchain.ps1, or point SHARPEMU_* at your own copy." }
    $lines += "  see docs/repo-structure.md."
    return ($lines -join "`n")
}

function Resolve-Toolchain {
    <#
      .SYNOPSIS
        Resolves the toolchain components a script needs, checking every version.
      .PARAMETER Need
        Any of: AndroidSdk, Ndk, Cmake, BuildTools, Jdk, Dotnet, Fork, Adb.
        Adb and BuildTools imply AndroidSdk; Ndk and Cmake imply it too.
      .PARAMETER Quiet
        Suppresses the notes about where a component came from. Warnings still print.
      .PARAMETER Workspace
        Looks in this directory instead of the real workspace. fetch-toolchain.ps1 uses it to ask
        "is it already in the place I am about to install to?" -- without it, installing to a custom
        -Destination would check the default workspace and conclude there was nothing to do.
    #>
    param(
        [string[]]$Need = @(),
        [switch]$Quiet,
        [string]$Workspace = ""
    )

    $spec = Get-ToolchainSpec

    # **$wsRoot, not $workspace.** powershell variable names are case-insensitive, so a local named
    # $workspace IS the $Workspace parameter -- assigning the default to it in the else branch made
    # the parameter look like the caller had supplied one, and every subsequent test took the wrong
    # branch. this is the same trap that once made an analyser in this project print confident zeroes
    # ($history and $History), and it cost a wrong answer here too.
    $overridden = [bool]$Workspace
    # **a supplied -Workspace may not exist yet, and that is not an error.** fetch-toolchain.ps1 asks
    # about its own install destination *before* creating it, to decide what to download. under its
    # $ErrorActionPreference = "Stop" a Resolve-Path failure there became a terminating error, every
    # probe was caught as "not found", and the printed plan said [fetch] for things that were in fact
    # already resolvable -- then the same probe run a few lines later, after the directory existed,
    # answered the opposite way and the download was skipped. a path with nothing in it simply
    # contains nothing.
    if ($overridden) {
        $wsRoot = if (Test-Path $Workspace) { (Resolve-Path $Workspace).Path } else { $Workspace }
    } else { $wsRoot = Get-SharpEmuWorkspace }

    # **two places are searched, in this order, and the repository's own toolchain\ comes first.**
    # scripts\fetch-toolchain.ps1 installs there, so a clone that fetched its own toolchain uses it
    # even on a machine where the parent directory happens to hold something else. the workspace is
    # second because not everyone keeps a clone tidily beside its SDKs -- that layout is a
    # convenience for whoever has it, never an assumption.
    #
    # -Workspace replaces both with the one directory given, which is how fetch-toolchain.ps1 asks
    # "is it already in the place I am about to install to?".
    if ($overridden) {
        $roots = @(@{ p = $wsRoot; src = "the given directory" })
    } else {
        $roots = @(
            @{ p = (Join-Path $script:SharpEmuRepoRoot "toolchain"); src = "the repository's toolchain\" },
            @{ p = $wsRoot; src = "the workspace" }
        )
    }

    $tc = [ordered]@{
        RepoRoot  = $script:SharpEmuRepoRoot
        Workspace = $wsRoot
        Roots     = $roots
        Spec      = $spec
    }

    function Note([string]$msg) { if (-not $Quiet) { Write-Host "[toolchain] $msg" } }

    # everything under the SDK implies the SDK itself.
    $need = @($Need)
    if ($need -contains "Ndk" -or $need -contains "Cmake" -or $need -contains "BuildTools" -or $need -contains "Adb") {
        if ($need -notcontains "AndroidSdk") { $need += "AndroidSdk" }
    }

    # --- the android SDK -----------------------------------------------------------------------
    if ($need -contains "AndroidSdk") {
        $tried = @()
        $sdk = $null
        $candidates = @(@{ p = $env:SHARPEMU_ANDROID_SDK; src = "SHARPEMU_ANDROID_SDK"; strict = $true })
        foreach ($r in $roots) { $candidates += @{ p = (Join-Path $r.p "android-sdk"); src = $r.src; strict = $false } }
        $candidates += @{ p = $env:ANDROID_HOME; src = "ANDROID_HOME"; strict = $false }
        $candidates += @{ p = $env:ANDROID_SDK_ROOT; src = "ANDROID_SDK_ROOT"; strict = $false }
        foreach ($cand in $candidates) {
            if (-not $cand.p) { continue }
            $tried += "$($cand.p)  ($($cand.src))"
            if (-not (Test-Path $cand.p)) {
                Deny-Candidate $cand.strict $cand.src $cand.p "it does not exist"
                continue
            }
            if (-not (Test-Path (Join-Path $cand.p "platform-tools"))) {
                Deny-Candidate $cand.strict $cand.src $cand.p "it has no platform-tools directory, so it is not an android SDK"
                continue
            }
            $sdk = $cand.p; $sdkSource = $cand.src; break
        }
        if (-not $sdk) { throw (New-ToolchainFailure "the android SDK" $tried "a directory containing platform-tools") }
        $tc.AndroidSdk = (Resolve-Path $sdk).Path
        if ($sdkSource -ne "the workspace") { Note "android SDK from $sdkSource : $($tc.AndroidSdk)" }
    }

    # --- adb -----------------------------------------------------------------------------------
    if ($need -contains "Adb") {
        $adb = Join-Path $tc.AndroidSdk "platform-tools\adb.exe"
        if (-not (Test-Path $adb)) { throw (New-ToolchainFailure "adb" @($adb) "platform-tools in the android SDK") }
        $tc.Adb = $adb
    }

    # --- the NDK -------------------------------------------------------------------------------
    if ($need -contains "Ndk") {
        $tried = @()
        $ndk = $null
        foreach ($cand in @(
            @{ p = $env:SHARPEMU_NDK; src = "SHARPEMU_NDK"; strict = $true },
            @{ p = (Join-Path $tc.AndroidSdk "ndk\$($spec.ndk)"); src = "the android SDK"; strict = $false },
            @{ p = $env:ANDROID_NDK_HOME; src = "ANDROID_NDK_HOME"; strict = $false },
            @{ p = $env:ANDROID_NDK_ROOT; src = "ANDROID_NDK_ROOT"; strict = $false })) {
            if (-not $cand.p) { continue }
            $tried += "$($cand.p)  ($($cand.src))"
            $rev = Get-NdkRevision $cand.p
            if (-not $rev) {
                Deny-Candidate $cand.strict $cand.src $cand.p "it has no readable source.properties, so it is not an NDK"
                continue
            }
            $major = 0
            if ($rev -match '^(\d+)') { $major = [int]$Matches[1] }
            if ($major -lt [int]$spec.ndkMinRevision) {
                Deny-Candidate $cand.strict $cand.src $cand.p "it is r$major, and FEXCore needs r$($spec.ndkMinRevision) or newer for std::atomic_ref"
                continue
            }
            $ndk = $cand.p; $ndkSource = $cand.src; $ndkRev = $rev; break
        }
        if (-not $ndk) { throw (New-ToolchainFailure "the NDK" $tried "revision $($spec.ndk), or at least r$($spec.ndkMinRevision)") }
        $tc.Ndk = (Resolve-Path $ndk).Path
        $tc.NdkRevision = $ndkRev
        if ($ndkSource -ne "the android SDK") { Note "NDK from $ndkSource : $($tc.Ndk) (r$ndkRev)" }
        if ($ndkRev -ne $spec.ndk) {
            Write-Warning "[toolchain] NDK is $ndkRev, the pinned build is $($spec.ndk). it should work, but this is not the build the turnip package was compiled with."
        }
        $tc.NdkBin = Join-Path $tc.Ndk "toolchains\llvm\prebuilt\windows-x86_64\bin"
        $tc.NdkSysroot = Join-Path $tc.Ndk "toolchains\llvm\prebuilt\windows-x86_64\sysroot"
    }

    # --- cmake and ninja -----------------------------------------------------------------------
    if ($need -contains "Cmake") {
        $tried = @()
        $bin = $null
        $sdkCmake = Join-Path $tc.AndroidSdk "cmake\$($spec.cmake)\bin"
        $tried += "$sdkCmake  (the android SDK)"
        if ((Test-Path (Join-Path $sdkCmake "cmake.exe")) -and (Test-Path (Join-Path $sdkCmake "ninja.exe"))) {
            $bin = $sdkCmake
        } else {
            $onPath = (Get-Command cmake.exe -ErrorAction SilentlyContinue)
            $ninjaOnPath = (Get-Command ninja.exe -ErrorAction SilentlyContinue)
            if ($onPath -and $ninjaOnPath) {
                $bin = Split-Path -Parent $onPath.Source
                Write-Warning "[toolchain] using cmake from PATH ($bin) rather than the SDK's $($spec.cmake). ninja is at $($ninjaOnPath.Source)."
            }
            $tried += "PATH"
        }
        if (-not $bin) { throw (New-ToolchainFailure "cmake and ninja" $tried "cmake $($spec.cmake) from the android SDK") }
        $tc.CmakeBin = $bin
    }

    # --- build-tools and the platform jar ------------------------------------------------------
    if ($need -contains "BuildTools") {
        $bt = Join-Path $tc.AndroidSdk "build-tools\$($spec.buildTools)"
        $jar = Join-Path $tc.AndroidSdk "platforms\$($spec.platform)\android.jar"
        if (-not (Test-Path $bt))  { throw (New-ToolchainFailure "android build-tools" @($bt) $spec.buildTools) }
        if (-not (Test-Path $jar)) { throw (New-ToolchainFailure "the android platform jar" @($jar) $spec.platform) }
        $tc.BuildTools = $bt
        $tc.AndroidJar = $jar
    }

    # --- the JDK -------------------------------------------------------------------------------
    if ($need -contains "Jdk") {
        $tried = @()
        $jdk = $null
        $candidates = @()
        if ($env:SHARPEMU_JDK) { $candidates += @{ p = $env:SHARPEMU_JDK; src = "SHARPEMU_JDK"; strict = $true } }
        # globbed, never the exact build string: jdk-21.0.11+10-eclipse-temurin was the least
        # portable path in this repository. newest first, so a root with two JDKs picks up.
        foreach ($r in $roots) {
            foreach ($d in (Get-ChildItem $r.p -Directory -Filter "jdk-*" -ErrorAction SilentlyContinue | Sort-Object Name -Descending)) {
                $candidates += @{ p = $d.FullName; src = $r.src; strict = $false }
            }
        }
        if ($env:JAVA_HOME) { $candidates += @{ p = $env:JAVA_HOME; src = "JAVA_HOME"; strict = $false } }
        $javaOnPath = (Get-Command java.exe -ErrorAction SilentlyContinue)
        if ($javaOnPath) {
            $candidates += @{ p = (Split-Path -Parent (Split-Path -Parent $javaOnPath.Source)); src = "PATH"; strict = $false }
        }
        foreach ($cand in $candidates) {
            $tried += "$($cand.p)  ($($cand.src))"
            $v = Get-JdkVersion $cand.p
            if ($null -eq $v) {
                Deny-Candidate $cand.strict $cand.src $cand.p "it has no readable release file, so it is not a java installation"
                continue
            }
            if ($v -ne [int]$spec.jdk) {
                Deny-Candidate $cand.strict $cand.src $cand.p "it is java $v and this build needs java $($spec.jdk)"
                continue
            }
            if (-not (Test-Path (Join-Path $cand.p "bin\javac.exe"))) {
                Deny-Candidate $cand.strict $cand.src $cand.p "it has no bin\javac.exe, so it is a JRE rather than a JDK"
                continue
            }
            $jdk = $cand.p; $jdkSource = $cand.src; break
        }
        if (-not $jdk) { throw (New-ToolchainFailure "a JDK" $tried "java $($spec.jdk) with javac") }
        $tc.Jdk = (Resolve-Path $jdk).Path
        if ($jdkSource -ne "the workspace") { Note "JDK from $jdkSource : $($tc.Jdk)" }
    }

    # --- the .NET SDK --------------------------------------------------------------------------
    if ($need -contains "Dotnet") {
        $tried = @()
        $dn = $null
        $candidates = @()
        if ($env:SHARPEMU_DOTNET) { $candidates += @{ p = $env:SHARPEMU_DOTNET; src = "SHARPEMU_DOTNET"; strict = $true } }
        foreach ($r in $roots) { $candidates += @{ p = (Join-Path $r.p "dotnet-sdk"); src = $r.src; strict = $false } }
        if ($env:DOTNET_ROOT) { $candidates += @{ p = $env:DOTNET_ROOT; src = "DOTNET_ROOT"; strict = $false } }
        $dotnetOnPath = (Get-Command dotnet.exe -ErrorAction SilentlyContinue)
        if ($dotnetOnPath) { $candidates += @{ p = (Split-Path -Parent $dotnetOnPath.Source); src = "PATH"; strict = $false } }
        foreach ($cand in $candidates) {
            $tried += "$($cand.p)  ($($cand.src))"
            if (-not (Test-Path (Join-Path $cand.p "dotnet.exe"))) {
                Deny-Candidate $cand.strict $cand.src $cand.p "it has no dotnet.exe"
                continue
            }
            $have = Get-DotnetSdkVersions $cand.p
            if ($have.Count -eq 0) {
                # a runtime-only install. common, and it looks exactly like an SDK from the outside.
                Deny-Candidate $cand.strict $cand.src $cand.p "it has no SDKs installed, only a runtime"
                continue
            }
            if (-not (Test-DotnetSatisfies $have $spec.dotnetSdk)) {
                Deny-Candidate $cand.strict $cand.src $cand.p "it has $($have -join ', '), and the fork's global.json needs $($spec.dotnetSdk) or a later 10.0.x"
                continue
            }
            $dn = $cand.p; $dnSource = $cand.src; break
        }
        if (-not $dn) { throw (New-ToolchainFailure "a .NET SDK" $tried "$($spec.dotnetSdk) or a later 10.0.x, per the fork's global.json") }
        $tc.DotnetRoot = (Resolve-Path $dn).Path
        $tc.Dotnet = Join-Path $tc.DotnetRoot "dotnet.exe"
        if ($dnSource -ne "the workspace") { Note "dotnet from $dnSource : $($tc.DotnetRoot)" }
    }

    # --- the SharpEmu fork ---------------------------------------------------------------------
    if ($need -contains "Fork") {
        $tried = @()
        $fork = $null
        # **the submodule is the default and a checkout of your own is the override.** the pin has to
        # be the path an ordinary build takes, or nothing here ever notices it has gone stale and the
        # first person to find out is somebody cloning this repository. this is the shape `go mod
        # replace` and gclient's custom_deps use: the pin builds unless a local checkout is declared,
        # and SHARPEMU_FORK is that declaration. the submodule is not where the fork is developed --
        # point SHARPEMU_FORK at the checkout you commit in.
        foreach ($cand in @(
            @{ p = $env:SHARPEMU_FORK; src = "SHARPEMU_FORK"; strict = $true },
            @{ p = (Join-Path $script:SharpEmuRepoRoot "external\sharpemu"); src = "the submodule"; strict = $false })) {
            if (-not $cand.p) { continue }
            $tried += "$($cand.p)  ($($cand.src))"
            if (-not (Test-Path (Join-Path $cand.p "Directory.Build.props"))) {
                if ($cand.src -eq "the submodule") {
                    $why = "it is not checked out, so run: git submodule update --init --recursive"
                } else {
                    $why = "it has no Directory.Build.props, so it is not a SharpEmu checkout"
                }
                Deny-Candidate $cand.strict $cand.src $cand.p $why
                continue
            }
            $fork = $cand.p; $forkSource = $cand.src; break
        }
        if (-not $fork) {
            $fix = "fix it by running: git submodule update --init --recursive"
            $fix += "`n  or set SHARPEMU_FORK to a checkout of your own."
            throw (New-ToolchainFailure "the SharpEmu fork" $tried "a checkout of https://github.com/sharpemu-android/sharpemu" $fix)
        }
        $tc.Fork = (Resolve-Path $fork).Path
        # **always named, unlike every other component.** two checkouts of one fork exist on a
        # development machine by design, and which one a build was cut from is a question this
        # project has already paid for once.
        Note "fork from $forkSource : $($tc.Fork)"
    }

    return [PSCustomObject]$tc
}
