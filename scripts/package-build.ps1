# turns a publish directory into a SharpEmu *build*: a directory and a zip, with an identity.
#
#   .\scripts\package-build.ps1                          # whatever branch is checked out
#   .\scripts\package-build.ps1 -Branch perf/render-pass-batching
#   .\scripts\package-build.ps1 -Branch android            # packagedAt is stamped for you
#   .\scripts\package-build.ps1 -NoPublish               # repackage what is already in artifacts
#
# **it does not touch a device.** producing a build and putting one on a phone are two jobs, and
# scripts\stage-build.ps1 does the second - which is what lets you stage a build packaged last week,
# or one somebody else packaged, without republishing anything.
#
# **and without a fork checkout at all**, from a published linux-x64 archive by path or URL:
#
#   .\scripts\package-build.ps1 -FromArchive .\sharpemu-0.0.3-hotfix-2-linux-x64.tar.gz -Id android
#   .\scripts\package-build.ps1 -FromArchive https://.../sharpemu-0.0.3-linux-x64.tar.gz -Id android
#
# that mode needs no fork, no .NET SDK and no git -- which is the point: it is the path a third
# party takes, and the one any CI job would take too. what it cannot do is record a commit, so
# meta.json's `commit` is empty and `source` names the archive instead.
#
# **a build is a directory, not a file.** the publish output is the payload *plus* `plugins/`, which
# SharpEmu resolves relative to its own executable in two places - managed plugins in
# `SharpEmu.CLI/Program.cs` and `ffmpeg.RootPath` in `FfmpegNativeBinkFrameSource.cs`. staging only
# the payload, which is what `stage.ps1` has always done, is why there has never been audio.
#
# identity lives in `meta.json` and never in a filename. the on-device folder is derived from it -
# `<id>-<sharpemuVersion>-<packagedAt>`, slugged - so two builds of the same source coexist and
# the app never has to guess which is which.
#
# **the zip is the distribution format and the directory is what runs.** android's shell has no
# unzip worth relying on, so this script unpacks on the PC and pushes; the app-side extractor is
# written once, later, against the same zip. both are produced here so the format is exercised.
#
# staging lands on *external* storage, which is where adb can write and the app can read. a staged
# build runs from there: nothing is copied, because the volume costs nothing measurable and the one
# build that does live on internal storage is the one that shipped inside the APK.

param(
    # the fork branch this build is cut from, and also its `id`. defaults to whatever is checked
    # out; naming a different one is an error rather than a checkout, because switching branches
    # under someone is not something a packaging script should do.
    [string]$Branch = "",
    # when this build was packaged, yyyyMMddHHmmss, and the key everything is ordered by.
    #
    # **empty means now**, which is the whole point: it assigns itself, so there is nothing to
    # remember to bump and no way for two packages of one source to claim the same number. pass one
    # only to reproduce an existing folder name. a [long] because a timestamp overflows an int.
    [long]$PackagedAt = 0,
    # the launcher<->payload interface generation. see docs/build-format.md.
    # 2 means the payload is expected to understand SHARPEMU_HOST_AUDIO. see
    # SharpEmuBuild.CONTRACT_MIN for why the app's range does not include 1.
    [int]$HostContract = 2,
    # guest environment this build wants defaulted on, NAME=VALUE. the lowest-precedence source
    # there is: build < app settings < intent extras < explicit --env. not $Env: that is close
    # enough to powershell's $env: provider to confuse a reader, and this script sets $env:PATH.
    [string[]]$GuestEnv = @(),
    [string]$Name = "",
    [string]$Notes = "",
    # who produced this build. **not who wrote the emulator** - `sharpemuVersion` and `commit`
    # already say what the code is, and the question somebody holding two zips has is whose zip this
    # is. it is typed, and it is deliberately never derived from `git log`: that names whoever wrote
    # the last commit, so after an upstream merge it would credit an upstream contributor for a
    # package they never made.
    [string]$Author = "",
    [switch]$NoPublish,
    # --- packaging without a fork checkout -----------------------------------------------------
    # a published linux-x64 tree as a .tar.gz or .zip, by path or URL, instead of building one. this
    # is the path a third party takes: no fork clone, no .NET SDK, no git. the archive has to contain
    # the payload and its plugins/ somewhere inside it.
    [string]$FromArchive = "",
    # the build's id. defaults to the fork branch, which does not exist in -FromArchive mode, so it
    # is required there.
    [string]$Id = "",
    # likewise: normally read from the fork's Directory.Build.props, so it must be given for an
    # archive unless the file name carries it.
    [string]$SharpEmuVersion = ""
)

$ErrorActionPreference = "Stop"

$here = $PSScriptRoot
. (Join-Path $here "toolchain.ps1")

$fromArchiveMode = [bool]$FromArchive

if ($fromArchiveMode) {
    # no fork, no dotnet, no device: the archive already is the publish output.
    $tc = Resolve-Toolchain -Need @()
    $fork = $null
} else {
    # the SharpEmu fork is its own repository and is checked out beside this one, not inside it. see
    # docs/repo-structure.md for why it is not a submodule; SHARPEMU_FORK points somewhere else.
    $tc = Resolve-Toolchain -Need Dotnet, Fork
    $fork = $tc.Fork
}
$repoRoot = $tc.RepoRoot

if ($fromArchiveMode) {
    if (-not $Id) { throw "-FromArchive needs -Id: there is no branch to take the build's id from." }
    $Branch = $Id
    if ($SharpEmuVersion) {
        $sharpemuVersion = $SharpEmuVersion
    } else {
        # upstream names its release assets sharpemu-<version>-linux-x64.tar.gz, so the version is
        # usually right there. guessing is fine; guessing *silently* is not.
        $leaf = Split-Path -Leaf $FromArchive
        if ($leaf -match 'sharpemu-(.+?)-(?:linux|osx|win)-') {
            $sharpemuVersion = $Matches[1]
            Write-Host "sharpemuVersion $sharpemuVersion, read off the archive name"
        } else {
            throw "cannot tell the SharpEmu version from '$leaf'. pass -SharpEmuVersion."
        }
    }
} else {
    # what is actually checked out. the device gets whichever branch this is, so it is the one thing
    # worth refusing over: a build labelled `perf/render-pass-batching` cut from `android` is a
    # plausible artefact attributed to the wrong source, which nothing downstream can detect.
    $checkedOut = (& git -C $fork rev-parse --abbrev-ref HEAD).Trim()
    if ($checkedOut -eq "HEAD") {
        # **a detached HEAD is the ordinary state, not a broken one.** `git submodule update` leaves
        # the submodule detached at the recorded commit, so this is what everyone building from a
        # clone rather than from a checkout of their own gets -- and `rev-parse --abbrev-ref` answers
        # the literal string "HEAD" for it, which would otherwise become this build's id.
        #
        # the branch is recovered from the remote refs that contain the commit. **the preference is
        # explicit** because a commit is commonly on several branches at once: every `perf/` branch
        # is cut from `android`, so `android`'s own tip is contained by all of them, and taking
        # whatever `--contains` listed first would be a coin toss deciding what a build calls itself.
        $onBranches = @(& git -C $fork branch -r --contains HEAD --format="%(refname:short)") |
            ForEach-Object { ($_ -replace '^origin/', '').Trim() } |
            Where-Object { $_ -and $_ -ne "HEAD" }
        $onBranches = @($onBranches)
        if ($Branch) {
            # naming one is allowed here, unlike the attached case below, because there is no branch
            # to switch to. **but containment is not enough to accept a topic branch**: `--contains`
            # means "is an ancestor of", and since every topic branch is cut from `android`, every
            # `android` commit is an ancestor of all of them. so a bare containment test accepts
            # `-Branch perf/flip-snapshot-pool` at a commit carrying none of that branch's changes,
            # which is a build labelled as something it is not.
            #
            # the trunk and a topic branch are therefore judged differently, and the fork's own
            # naming convention is what separates them. `android` is the trunk every branch descends
            # from, so being on its history *is* being android. a `<type>/` branch is defined by the
            # commits it adds on top, so only its tip carries them.
            if ($onBranches -notcontains $Branch) {
                throw "asked for '$Branch', but the commit checked out in $fork is not on it. it is on: $($onBranches -join ', ')"
            }
            if ($Branch -ne "android") {
                $tip = (& git -C $fork rev-parse "refs/remotes/origin/$Branch").Trim()
                $head = (& git -C $fork rev-parse HEAD).Trim()
                if ($tip -ne $head) {
                    throw ("asked for '$Branch', and $fork is at $($head.Substring(0, 7)) which is an ancestor of it " +
                           "rather than its tip $($tip.Substring(0, 7)). a build named after a topic branch that does " +
                           "not carry that branch's commits is attributed to the wrong source. check the tip out, or " +
                           "omit -Branch and let it be inferred.")
                }
            }
        } elseif ($onBranches -contains "android") {
            $Branch = "android"
        } elseif ($onBranches.Count -eq 0) {
            throw "$fork is on a detached HEAD whose commit is on no branch at origin. either origin has not been fetched, or the recorded submodule pointer names a commit that was rewritten away."
        } elseif ($onBranches.Count -eq 1) {
            $Branch = $onBranches[0]
        } else {
            throw "$fork is on a detached HEAD and its commit is on $($onBranches.Count) branches ($($onBranches -join ', ')). pass -Branch to say which one this build is."
        }
        $checkedOut = $Branch
    }
    if (-not $Branch) { $Branch = $checkedOut }
    if ($Branch -ne $checkedOut) {
        throw "asked for '$Branch' and '$checkedOut' is checked out. run: git -C `"$fork`" checkout $Branch"
    }
    $dirty = & git -C $fork status --porcelain
    # the path is named because two checkouts of this fork exist on a development machine by design.
    if ($dirty) { Write-Host "warning: $fork has a dirty working tree, so this build is not a clean checkout of $Branch" }

    # the version the fork declares, never one hardcoded here.
    $props = Get-Content (Join-Path $fork "Directory.Build.props") -Raw
    if ($props -notmatch "<SharpEmuVersion>([^<]+)</SharpEmuVersion>") { throw "no <SharpEmuVersion> in Directory.Build.props" }
    $sharpemuVersion = $Matches[1]
}

# the first-party ids and what each of them *is*, so `-Branch perf/render-pass-batching` produces a
# build that behaves as advertised rather than a build plus a knob the caller had to remember. this
# table is the stand-in for a release pipeline; a third-party build passes -GuestEnv and -Notes instead.
#
# **one of these is a tier and the rest are topic branches.** `android` is the only maintained branch
# and the only one that absorbs upstream; anything with a `<type>/` prefix is archived at the commit
# it was cut from and is merged nowhere. that is the fork's naming convention, not a quirk of this
# script, and it is what keeps an upstream release something you merge once.
$known = @{
    "android" = @{
        name   = "SharpEmu for Android"
        author = "mircowuffwuff and claude"
        env    = @()
        notes  = "SharpEmu expanded by Android platform support."
    }
    "perf/flip-snapshot-pool" = @{
        name   = "Flip snapshot pool"
        author = "mircowuffwuff and claude"
        env    = @()
        notes  = "android plus a pool for the per-frame guest flip snapshot. a topic branch, open upstream - import it to try the change before it lands."
    }
    "perf/host-cached-memory" = @{
        name   = "Host-cached memory"
        author = "mircowuffwuff and claude"
        env    = @()
        notes  = "android plus a host-cached memory preference for CPU-written allocations on integrated GPUs. it is what turnip needs and does little for the stock driver."
    }
    "perf/render-pass-batching" = @{
        name   = "Render pass batching"
        author = "mircowuffwuff and claude"
        env    = @("SHARPEMU_BATCH_RENDER_PASSES=1")
        notes  = "android plus render pass batching. a parked topic branch that joins nothing: a per-draw global-memory barrier refuses every join, so the change is measured and merged nowhere."
    }
}
if ($known.ContainsKey($Branch)) {
    if (-not $Name) { $Name = $known[$Branch].name }
    if (-not $Notes) { $Notes = $known[$Branch].notes }
    if (-not $Author) { $Author = $known[$Branch].author }
    if (-not $GuestEnv -or $GuestEnv.Count -eq 0) { $GuestEnv = $known[$Branch].env }
}
if (-not $Name) { $Name = $Branch }

# lowercase, spaces to -, restricted to [a-z0-9._-]. this project has already paid once for a path
# with a space in it: stage.ps1 has to write its script to a file on the device because windows
# powershell and /system/bin/sh disagree about quoting badly enough that a game directory's space
# does not survive the trip. game directories we do not control; build directories we do.
function Get-Slug([string]$s) {
    $s = $s.ToLowerInvariant() -replace "\s+", "-"
    $s = $s -replace "[^a-z0-9._-]", "-"
    return ($s -replace "-+", "-").Trim("-")
}

if ($PackagedAt -le 0) { $PackagedAt = [long](Get-Date -Format "yyyyMMddHHmmss") }
$folder = Get-Slug "$Branch-$sharpemuVersion-$PackagedAt"

Write-Host ""
Write-Host ("packaging {0} - {1} {2} {3} -> {4}" -f $Name, $Branch, $sharpemuVersion, $PackagedAt, $folder)

if ($fromArchiveMode) {
    $work = Join-Path $repoRoot "build\from-archive"
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $work | Out-Null

    # a directory is accepted too, and skips straight to the payload search: a work-in-progress
    # publish tree is the same shape as an unpacked archive, and this is what gives one an identity
    # without a fork checkout or a repackage.
    if ((Test-Path -LiteralPath $FromArchive) -and (Get-Item -LiteralPath $FromArchive).PSIsContainer) {
        $extract = (Resolve-Path -LiteralPath $FromArchive).Path
        Write-Host "using the directory as-is: $extract"
    } else {

    $archive = $FromArchive
    if ($archive -match '^https?://') {
        $archive = Join-Path $work (Split-Path -Leaf ([uri]$FromArchive).AbsolutePath)
        Write-Host "downloading $FromArchive"
        $prevProgress = $ProgressPreference
        $ProgressPreference = "SilentlyContinue"
        try { Invoke-WebRequest -Uri $FromArchive -OutFile $archive -UseBasicParsing }
        finally { $ProgressPreference = $prevProgress }
        Write-Host ("  {0:N0} bytes" -f (Get-Item $archive).Length)
    }
    if (-not (Test-Path $archive)) { throw "archive not found: $archive" }

    $extract = Join-Path $work "extract"
    New-Item -ItemType Directory -Force -Path $extract | Out-Null
    if ($archive -match '\.zip$') {
        Expand-Archive -Path $archive -DestinationPath $extract
    } else {
        # bsdtar, shipped in System32 since windows 10: it reads .tar.gz directly. it also preserves
        # nothing about unix permissions, which does not matter -- the payload is never executed as a
        # file, it is read into guest memory by the ELF loader.
        $tar = Join-Path $env:SystemRoot "System32\tar.exe"
        if (-not (Test-Path $tar)) { throw "bsdtar not found at $tar" }
        & $tar -xf $archive -C $extract
        if ($LASTEXITCODE -ne 0) { throw "extracting $archive failed" }
    }
    }

    # the payload can be at the root or one wrapper directory down, depending on who packed it.
    # found rather than assumed, and named when found so the log says which layout it was.
    $found = @(Get-ChildItem $extract -Recurse -File -Filter "SharpEmu" -ErrorAction SilentlyContinue |
               Where-Object { Test-Path (Join-Path $_.DirectoryName "plugins") })
    if ($found.Count -eq 0) {
        throw "no SharpEmu payload with a plugins/ beside it anywhere in $archive - a build is a directory, and that is half of it"
    }
    if ($found.Count -gt 1) { throw "found $($found.Count) candidate payloads in $archive; cannot tell which is meant" }
    $publish = $found[0].DirectoryName
    $where = $publish.Substring($extract.Length).TrimStart('\', '/')
    if (-not $where) { $where = "the archive root" }
    Write-Host "payload found at $where"
} else {
    $publish = Join-Path $fork "artifacts\publish\SharpEmu.CLI\Release\net10.0\linux-x64"

    # **what the publish tree was last built from, recorded beside it.** without this, -NoPublish
    # stamps the identity of whatever branch happens to be checked out onto whatever payload happens
    # to be in artifacts/ - a perf branch's payload packaged as `android` at android's commit, say.
    # that is a plausible artefact attributed to the wrong source, which is this project's most
    # expensive failure shape, and it is silent by construction because both halves are individually
    # valid: the payload is real and so is the identity.
    $stamp = Join-Path $publish ".packaged-from"
    # the branch *and* the commit, because a branch name alone would not notice a rebuild after a
    # commit on the same branch - which is the ordinary case in a dev loop.
    $identity = "$Branch $((& git -C $fork rev-parse --short HEAD).Trim())"

    if (-not $NoPublish) {
        $env:DOTNET_ROOT = $tc.DotnetRoot
        $env:PATH = "$($env:DOTNET_ROOT);$($env:PATH)"
        # the publish tree is not cleaned between branches, so a file a branch stopped producing would
        # survive into the next package. cheap to remove and expensive to debug.
        Remove-Item -Recurse -Force $publish -ErrorAction SilentlyContinue
        & dotnet publish (Join-Path $fork "src\SharpEmu.CLI\SharpEmu.CLI.csproj") -c Release -r linux-x64
        if ($LASTEXITCODE -ne 0) { throw "publish failed" }
        Set-Content -LiteralPath $stamp -Value $identity -Encoding ascii
    }
    if (-not (Test-Path (Join-Path $publish "SharpEmu"))) { throw "no payload at $publish - publish it first, or drop -NoPublish" }
    if ($NoPublish) {
        # refuse rather than warn. a warning on a package that then succeeds is a build somebody
        # keeps, and its meta.json is the only place the mistake would ever show.
        $was = if (Test-Path -LiteralPath $stamp) { (Get-Content -LiteralPath $stamp -Raw).Trim() } else { "" }
        if (-not $was) {
            throw ("the publish tree at $publish has no record of what it was built from, so -NoPublish " +
                   "cannot confirm it is $identity. drop -NoPublish to publish it again.")
        }
        if ($was -ne $identity) {
            throw ("the publish tree at $publish was built from '$was' and this would label it '$identity'. " +
                   "drop -NoPublish to publish the checked-out branch, or check out the branch it came from.")
        }
    }
}

$staging = Join-Path $repoRoot "build\builds\$folder"
Remove-Item -Recurse -Force $staging -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $staging | Out-Null

# the whole publish directory, not the payload. `plugins/` is 15 MB of linux ffmpeg plus
# SharpEmu.LibAtrac9.dll, and it has never once been on the device.
Copy-Item -Recurse -Force (Join-Path $publish "*") $staging
# except the marker, which is a note this script left for itself about the *publish* tree and is not
# part of any build. it was riding along into every build directory and zip, which is a file a third
# party would have to wonder about - and android's asset packer drops dot-files, so a bundled build
# whose file listing named one described a tree the APK did not contain.
Remove-Item -Force -LiteralPath (Join-Path $staging ".packaged-from") -ErrorAction SilentlyContinue
if (-not (Test-Path (Join-Path $staging "plugins"))) { throw "the publish output has no plugins/ - a build is a directory, and that is half of it" }

$envMap = [ordered]@{}
foreach ($assignment in $GuestEnv) {
    if (-not $assignment) { continue }
    $i = $assignment.IndexOf("=")
    if ($i -lt 1) { throw "-GuestEnv wants NAME=VALUE, got '$assignment'" }
    $envMap[$assignment.Substring(0, $i)] = $assignment.Substring($i + 1)
}

$meta = [ordered]@{
    id              = $Branch
    name            = $Name
    sharpemuVersion = $sharpemuVersion
    packagedAt      = $PackagedAt
    hostContract    = $HostContract
    payload         = "SharpEmu"
    env             = $envMap
    notes           = $Notes
    author          = $Author
    # neither of these is part of the format the app reads, and both are recorded deliberately: a
    # build that renders differently from another has to be traceable to where it came from without
    # a changelog. `commit` is empty when packaged from an archive, because there is no checkout to
    # ask -- **empty rather than absent, and `source` says what it was instead.** a build whose
    # provenance is unknown should say so rather than leave a reader to notice a missing field.
    commit          = $(if ($fromArchiveMode) { "" } else { (& git -C $fork rev-parse --short HEAD).Trim() })
    source          = $(if ($fromArchiveMode) { $FromArchive } else { "fork $Branch" })
}
$json = $meta | ConvertTo-Json -Depth 4
# no BOM. java.util.zip and org.json both cope, and every other tool that has to read this one day
# may not.
[System.IO.File]::WriteAllText((Join-Path $staging "meta.json"), $json, (New-Object System.Text.UTF8Encoding $false))

$payloadSize = (Get-Item (Join-Path $staging "SharpEmu")).Length
$dirSize = (Get-ChildItem -Recurse -File $staging | Measure-Object -Property Length -Sum).Sum

# meta.json at the zip *root*, not inside a wrapper folder - the single thing most likely to differ
# between two hand-made packages.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = Join-Path $repoRoot "build\builds\$folder.zip"
Remove-Item -Force $zip -ErrorAction SilentlyContinue
[System.IO.Compression.ZipFile]::CreateFromDirectory($staging, $zip, [System.IO.Compression.CompressionLevel]::Optimal, $false)
$zipSize = (Get-Item $zip).Length

Write-Host ""
Write-Host ("  payload   {0:N0} bytes" -f $payloadSize)
Write-Host ("  directory {0:N0} bytes" -f $dirSize)
Write-Host ("  zip       {0:N0} bytes  {1}" -f $zipSize, $zip)
Write-Host ("  commit    {0}" -f $meta.commit)
if ($envMap.Count) { Write-Host ("  env       {0}" -f (($envMap.Keys | ForEach-Object { "$_=$($envMap[$_])" }) -join " ")) }

Write-Host ""
Write-Host "put it on a device with:"
Write-Host ("  .\scripts\stage-build.ps1 -Build `"{0}`"" -f $staging)

# **a script's last command is its exit status, whether or not it meant it to be.** an `& $adb shell
# "ls -l '$dest'"` at the end of this file names two variables it does not set, so it runs `ls -l ''`
# against whatever adb its *caller* has in scope, fails, and leaves $? false as the script's last act
# -- which every caller reads as "packaging failed". it surfaces as run.ps1 -BuildSharpEmu throwing
# "packaging the fork failed" on a package that succeeded, printing the payload size and the commit
# hash one line above the error. so nothing decorative goes after the last real step here.
