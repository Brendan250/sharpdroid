# acquires the toolchains this repository builds against, into the workspace.
#
#   .\scripts\fetch-toolchain.ps1                 # print the plan. downloads nothing
#   .\scripts\fetch-toolchain.ps1 -Install        # actually do it
#   .\scripts\fetch-toolchain.ps1 -Install -What jdk
#
# **it prints the plan by default and downloads nothing without -Install**, because this pulls close
# to a gigabyte from three different hosts and a build script should never do that as a side effect
# of being run.
#
# it installs into **the repository's own toolchain\**, which is gitignored and is the first place
# scripts\toolchain.ps1 looks, so a fresh clone becomes buildable without touching anything outside
# itself and without any environment variable being set. nothing is installed machine-wide, no PATH
# is modified, and no existing install is touched: a contributor who already has a JDK 21 or the
# right NDK should point SHARPEMU_JDK / SHARPEMU_NDK at it instead of running this.
#
# versions and download URLs both come from toolchain.json, so there is one place to look and one
# place to bump.
#
# note for anyone editing this file: keep quoted strings ASCII. windows powershell reads this as
# cp1252, where a UTF-8 em-dash ends in the byte 0x94 -- a right double quote -- and silently
# terminates whatever string it sits in. comments are unaffected.

param(
    [ValidateSet("all", "jdk", "sdk", "dotnet")]
    [string]$What = "all",
    [switch]$Install,
    # where to install. defaults to the repository's own toolchain\, which is gitignored and is the
    # first place the resolver looks -- so a fresh clone becomes buildable without touching anything
    # outside itself, and without assuming a clone sits tidily beside a set of SDKs.
    [string]$Destination = ""
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "toolchain.ps1")

$spec = Get-ToolchainSpec
if (-not $Destination) { $Destination = Join-Path (Get-SharpEmuRepoRoot) "toolchain" }

$jdkDir    = Join-Path $Destination "jdk-$($spec.jdk)-temurin"
$sdkDir    = Join-Path $Destination "android-sdk"
$dotnetDir = Join-Path $Destination "dotnet-sdk"

# **"have" means "the resolver can find it", asked of the resolver rather than reimplemented.** the
# first version of this script tested for a fixed directory name and reported a JDK as missing that
# scripts\toolchain.ps1 was resolving happily, because the resolver globs jdk-* and this did not. two
# implementations of the same question is one too many, and the wrong answer here costs a needless
# 180 MB download.
#
# it asks about $Destination rather than about the real workspace, so that installing somewhere else
# does not conclude there is nothing to do because the *default* workspace already has everything.
#
# it reports *where* it found something rather than a bare yes, because "present" can legitimately
# mean "on your PATH somewhere else entirely" -- printing [present] beside an empty destination
# directory reads as a lie even when the answer is correct.
function Find-Existing([string]$component, [string]$property) {
    $prevW = $WarningPreference
    $WarningPreference = "SilentlyContinue"
    try { $found = (Resolve-Toolchain -Need $component -Quiet -Workspace $Destination).$property }
    catch { $found = $null }
    finally { $WarningPreference = $prevW }
    return $found
}

function Test-Resolves([string]$component) {
    $prevW = $WarningPreference
    $WarningPreference = "SilentlyContinue"
    try { Resolve-Toolchain -Need $component -Quiet -Workspace $Destination | Out-Null; $ok = $true }
    catch { $ok = $false }
    finally { $WarningPreference = $prevW }
    return $ok
}

$plan = @()

# **the JDK comes first and that ordering is not cosmetic**: sdkmanager is a java program, so the
# android SDK step cannot run without one.
if ($What -eq "all" -or $What -eq "jdk") {
    $plan += [ordered]@{
        step  = "JDK $($spec.jdk)"
        from  = $spec.sources.jdk
        into  = $jdkDir
        about = "~180 MB, Eclipse Temurin, from the Adoptium API"
        found = (Find-Existing "Jdk" "Jdk")
    }
}

if ($What -eq "all" -or $What -eq "sdk") {
    $components = @(
        "platform-tools",
        "ndk;$($spec.ndk)",
        "cmake;$($spec.cmake)",
        "build-tools;$($spec.buildTools)",
        "platforms;$($spec.platform)"
    )
    $plan += [ordered]@{
        step  = "android SDK: " + ($components -join ", ")
        from  = $spec.sources.cmdlineTools + "  (then sdkmanager)"
        into  = $sdkDir
        about = "~2.5 GB unpacked, mostly the NDK. accepts the Android SDK licences"
        found = $(if ((Test-Resolves "Cmake") -and (Test-Resolves "BuildTools")) { Find-Existing "Ndk" "Ndk" } else { $null })
    }
}

if ($What -eq "all" -or $What -eq "dotnet") {
    $plan += [ordered]@{
        step  = ".NET SDK $($spec.dotnetSdk)"
        from  = $spec.sources.dotnetInstall + "  (Microsoft's own installer script)"
        into  = $dotnetDir
        about = "~750 MB. installs one SDK into a directory, not machine-wide"
        found = (Find-Existing "Dotnet" "DotnetRoot")
    }
}

Write-Host ""
Write-Host "toolchain directory: $Destination"
Write-Host ""
foreach ($p in $plan) {
    if ($p.found) {
        Write-Host ("  [have]    {0}" -f $p.step)
        Write-Host ("            already resolvable at {0}" -f $p.found)
    } else {
        Write-Host ("  [fetch]   {0}" -f $p.step)
        Write-Host ("            from {0}" -f $p.from)
        Write-Host ("            into {0}" -f $p.into)
        Write-Host ("            {0}" -f $p.about)
    }
    Write-Host ""
}

if (-not $Install) {
    Write-Host "nothing was downloaded. re-run with -Install to fetch the missing pieces."
    Write-Host "already have your own? point SHARPEMU_JDK / SHARPEMU_ANDROID_SDK / SHARPEMU_NDK /"
    Write-Host "SHARPEMU_DOTNET at them instead - see docs/repo-structure.md."
    return
}

New-Item -ItemType Directory -Force -Path $Destination | Out-Null

function Get-ToWorkspace([string]$url, [string]$outFile, [string]$label) {
    Write-Host "downloading $label"
    Write-Host "  $url"
    # the progress bar makes Invoke-WebRequest an order of magnitude slower on large files.
    $prev = $ProgressPreference
    $ProgressPreference = "SilentlyContinue"
    try { Invoke-WebRequest -Uri $url -OutFile $outFile -UseBasicParsing }
    finally { $ProgressPreference = $prev }
    Write-Host ("  {0:N0} bytes" -f (Get-Item $outFile).Length)
}

$scratch = Join-Path $Destination "_toolchain-download"
New-Item -ItemType Directory -Force -Path $scratch | Out-Null

# --- the JDK ---------------------------------------------------------------------------------
if (($What -eq "all" -or $What -eq "jdk") -and -not (Test-Resolves "Jdk")) {
    $zip = Join-Path $scratch "jdk.zip"
    Get-ToWorkspace $spec.sources.jdk $zip "JDK $($spec.jdk)"
    $stage = Join-Path $scratch "jdk-stage"
    Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
    Expand-Archive -Path $zip -DestinationPath $stage
    # the archive has a single versioned top-level directory whose name we do not want to depend on.
    $inner = Get-ChildItem $stage -Directory | Select-Object -First 1
    Remove-Item -Recurse -Force $jdkDir -ErrorAction SilentlyContinue
    Move-Item $inner.FullName $jdkDir
    Write-Host "JDK installed to $jdkDir"
}

# --- the android SDK -------------------------------------------------------------------------
if ($What -eq "all" -or $What -eq "sdk") {
    $sdkManager = Join-Path $sdkDir "cmdline-tools\latest\bin\sdkmanager.bat"
    if (-not (Test-Path $sdkManager)) {
        $zip = Join-Path $scratch "cmdline-tools.zip"
        Get-ToWorkspace $spec.sources.cmdlineTools $zip "android command line tools"
        $stage = Join-Path $scratch "cmdline-stage"
        Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
        Expand-Archive -Path $zip -DestinationPath $stage
        # sdkmanager insists on living at cmdline-tools\latest\, not cmdline-tools\.
        $target = Join-Path $sdkDir "cmdline-tools\latest"
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
        Remove-Item -Recurse -Force $target -ErrorAction SilentlyContinue
        Move-Item (Join-Path $stage "cmdline-tools") $target
    }

    # sdkmanager is a java program. prefer the JDK in toolchain\, and fall back to whichever one the
    # resolver can see.
    #
    # **the fallback is not belt-and-braces.** the JDK step above is skipped when a JDK is already
    # resolvable *anywhere* -- including on PATH, which the plan reports honestly as
    # "[have] ... already resolvable at C:\..." -- so on any machine with a java outside this
    # repository, toolchain\jdk-21-temurin is legitimately absent at this point. looking only there
    # made -Install die on a clean clone with the message "run this with -What jdk first", which was
    # advice that could not have worked: -What jdk skips for the same reason.
    if (Test-Path (Join-Path $jdkDir "bin\java.exe")) { $env:JAVA_HOME = $jdkDir }
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = Find-Existing "Jdk" "Jdk" }
    if (-not $env:JAVA_HOME) {
        throw ("sdkmanager needs a JDK and none was found, in toolchain\ or anywhere else.`n" +
               "  re-run with -What jdk, or point SHARPEMU_JDK at a JDK $($spec.jdk).")
    }
    Write-Host "sdkmanager will use the JDK at $env:JAVA_HOME"

    $components = @("platform-tools", "ndk;$($spec.ndk)", "cmake;$($spec.cmake)",
                    "build-tools;$($spec.buildTools)", "platforms;$($spec.platform)")
    $ErrorActionPreference = "Continue"

    # **the licences go first, as their own step, and the acceptances arrive by a cmd-level redirect
    # from a file.** sdkmanager prompts "Accept? (y/N):" per licence and reads EOF on a closed stdin
    # as "N" -- it then skips every package and **exits 0**. neither `"y" | & $sdkManager` nor an
    # array pipeline reaches it, because stdin does not survive the .bat -> java hop on windows.
    # redirecting from a file through cmd does. this cost two failed runs to establish.
    #
    # this accepts the Android SDK licence on your behalf, the same thing Android Studio's first run
    # does. it is named in the plan above rather than buried here.
    $acceptFile = Join-Path $scratch "sdk-accept.txt"
    Set-Content -Path $acceptFile -Value (@("y") * 60) -Encoding ascii

    Write-Host "sdkmanager: accepting the SDK licences"
    cmd /c "`"$sdkManager`" --licenses --sdk_root=`"$sdkDir`" < `"$acceptFile`"" | Select-Object -Last 1

    Write-Host "sdkmanager: $($components -join ', ')"
    cmd /c "`"$sdkManager`" --sdk_root=`"$sdkDir`" $($components -join ' ') < `"$acceptFile`""
    $sdkExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"

    # **the exit code is not the check.** sdkmanager returned 0 having installed nothing at all, so
    # what is asserted is the outcome: the components have to be on disk where they were asked for.
    $missing = @()
    foreach ($c in @(@{ n = "ndk;$($spec.ndk)"; p = "ndk\$($spec.ndk)" },
                     @{ n = "cmake;$($spec.cmake)"; p = "cmake\$($spec.cmake)" },
                     @{ n = "build-tools;$($spec.buildTools)"; p = "build-tools\$($spec.buildTools)" },
                     @{ n = "platforms;$($spec.platform)"; p = "platforms\$($spec.platform)" },
                     @{ n = "platform-tools"; p = "platform-tools" })) {
        if (-not (Test-Path (Join-Path $sdkDir $c.p))) { $missing += $c.n }
    }
    if ($missing.Count -gt 0) {
        throw ("sdkmanager exited $sdkExit but did not install: " + ($missing -join ", ") + "`n" +
               "  run it by hand to see why:`n" +
               "    `$env:JAVA_HOME = '$jdkDir'`n" +
               "    & '$sdkManager' --sdk_root='$sdkDir' " + ($components -join " "))
    }
}

# --- the .NET SDK ----------------------------------------------------------------------------
if (($What -eq "all" -or $What -eq "dotnet") -and -not (Test-Resolves "Dotnet")) {
    $installer = Join-Path $scratch "dotnet-install.ps1"
    Get-ToWorkspace $spec.sources.dotnetInstall $installer "the .NET install script"
    Write-Host "installing .NET SDK $($spec.dotnetSdk) into $dotnetDir"
    & $installer -Version $spec.dotnetSdk -InstallDir $dotnetDir
    if (-not (Test-Path (Join-Path $dotnetDir "dotnet.exe"))) { throw "the .NET install script did not produce $dotnetDir\dotnet.exe" }
}

Remove-Item -Recurse -Force $scratch -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "done. checking what the resolver now finds in $Destination :"
# -Workspace, or this verifies a completely different installation. the first version of this script
# omitted it and cheerfully reported the *default* workspace's toolchains after installing nothing.
$tc = Resolve-Toolchain -Need AndroidSdk, Ndk, Cmake, BuildTools, Jdk, Dotnet -Workspace $Destination
Write-Host ("  NDK        {0} (r{1})" -f $tc.Ndk, $tc.NdkRevision)
Write-Host ("  cmake      {0}" -f $tc.CmakeBin)
Write-Host ("  buildtools {0}" -f $tc.BuildTools)
Write-Host ("  JDK        {0}" -f $tc.Jdk)
Write-Host ("  dotnet     {0}" -f $tc.DotnetRoot)
