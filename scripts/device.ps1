# the handful of device operations every staging script needs, in one place.
#
# dot-source it after toolchain.ps1:
#
#   . (Join-Path $PSScriptRoot "toolchain.ps1")
#   . (Join-Path $PSScriptRoot "device.ps1")
#   $tc = Resolve-Toolchain -Need Adb
#   $files = Get-AppFilesDir $Package
#
# it exists because five scripts push files to the same two places and each had grown its own copy
# of the same three lines. **this file sets no $ErrorActionPreference**, for the reason toolchain.ps1
# gives: it is dot-sourced, and would be changing its caller's setting out from under it.
#
# note for anyone editing this file: keep quoted strings ASCII. windows powershell reads this as
# cp1252, where a UTF-8 em-dash ends in the byte 0x94 -- a right double quote -- and silently
# terminates whatever string it sits in. comments are unaffected.

# the one directory both `adb` and the app can use without a permission or a picker.
#
# **there is no neutral shared location and that is not an oversight.** /data/local/tmp is
# shell_data_file and SELinux denies an app's domain access to it, and scoped storage stops an app
# reading arbitrary external paths without All-files access. an app's own external files directory is
# what is left -- which also means a different application id gets a completely separate one, so a
# debug build cannot disturb a personal install.
function Get-AppFilesDir([string]$Package) {
    return "/storage/emulated/0/Android/data/$Package/files"
}

# adb reports transfer progress on *stderr*, and windows powershell wraps every one of those lines in
# a NativeCommandError record and prints it -- 29 files of that once buried a set of regression
# results under a page of noise. silenced here, and the exit code checked instead, which is the only
# thing that ever said whether a push worked.
#
# -LiteralPath on the existence check: game directories are named `Title [PPSAxxxxx]` and square
# brackets are powershell wildcards, so a plain Test-Path reports every one of them missing.
function Push-Quiet([string]$Adb, [string]$Local, [string]$Remote) {
    if (-not (Test-Path -LiteralPath $Local)) { throw "nothing to push at $Local" }
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    try { & $Adb push $Local $Remote | Out-Null } finally { $ErrorActionPreference = $prev }
    if ($LASTEXITCODE -ne 0) { throw "push failed: $Local" }
}

# a device-side `ls`, trimmed, empty when the path is not there. the caller decides what that means.
function Get-DeviceListing([string]$Adb, [string]$Path) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    try { $out = & $Adb shell "ls '$Path' 2>/dev/null" } finally { $ErrorActionPreference = $prev }
    if (-not $out) { return @() }
    return @($out | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

# **the absent case is the one that has to work.** the first version called .Trim() on the result,
# and `[ -e ... ] && echo yes` prints *nothing* when the path is missing -- so the check threw on a
# null instead of returning false, which is a verification step that cannot report the failure it
# exists to catch.
function Test-DevicePath([string]$Adb, [string]$Path) {
    $out = & $Adb shell "[ -e '$Path' ] && echo yes"
    if (-not $out) { return $false }
    return ((($out | Select-Object -First 1) -as [string]).Trim() -eq "yes")
}

# one line of device output, as a string, empty when there was none. `adb shell` returns an *array*
# when the command printed more than one line, and .Trim() on an array throws -- which is how a
# verification step ends up failing on the success path rather than on the one it was written for.
function Get-DeviceLine([string]$Adb, [string]$Command) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    try { $out = & $Adb shell $Command } finally { $ErrorActionPreference = $prev }
    if (-not $out) { return "" }
    return (($out | Select-Object -First 1) -as [string]).Trim()
}

# the size of a file on the device, or -1 if it is not there. the payload byte count is this
# project's control for artefact identity, so asking for it is one function rather than five
# spellings of the same `stat`.
function Get-DeviceFileSize([string]$Adb, [string]$Path) {
    $out = Get-DeviceLine $Adb "stat -c %s '$Path' 2>/dev/null"
    if (-not $out) { return -1 }
    $n = 0
    if (-not [long]::TryParse($out, [ref]$n)) { return -1 }
    return [long]$n
}

# ------------------------------------------------------------------------------------------------
# selecting things by PC path
# ------------------------------------------------------------------------------------------------
#
# **you type a PC path. device paths are computed, never typed.** that is the whole user-facing rule,
# and these three functions are the only implementation of it:
#
#   - naming something means a path on this machine, to a build directory or zip, a game directory,
#     or a driver package
#   - it is **staged if the device does not already have it, reused if it does** -- where "has it"
#     compares the payload's byte count and not the name -- and `-Restage` pushes over it regardless
#   - **omitting it means "whatever the device already has"**, per build, game and driver alike
#
# the second bullet is the one that matters. a locally rebuilt build keeps its directory name, so
# "the leaf is already there" silently runs yesterday's bytes: exactly the wrong-artefact failure
# that selecting by id was removed for, moved rather than fixed. the byte count is what this project
# has always used to tell two artefacts apart, and it costs one `stat`.
#
# **each of these returns exactly one string**, and a caller should sanity-check it. a powershell
# function returns everything written to its output stream, so a stray native command inside one of
# these appends its chatter to the answer -- which this project has already been bitten by once, with
# the whole of `dotnet publish`'s log arriving as a "path". every native call below is therefore
# assigned or sent to Out-Null, and run.ps1 asserts the shape of what comes back.

# a source that cannot be a PC path, explained rather than reported as a missing file. the two
# mistakes worth naming are the ones this change creates: a build id, which used to work, and a
# device path, which never did.
function Deny-NotAPcPath([string]$Kind, [string]$Source, [string]$Hint) {
    if ($Source -match '^/(storage|data|sdcard)/') {
        throw ("'$Source' is a path on the device, and device paths are computed rather than typed.`n" +
               "  name the $Kind by its path on this machine and it gets staged for you.")
    }
    if ($Source -notmatch '[\\/]' -and -not [System.IO.Path]::IsPathRooted($Source)) {
        throw ("'$Source' is a name, not a path. selecting by name was removed on 2026-08-05 because it`n" +
               "  answered with the highest installed buildVersion, so a freshly staged b1 lost to an`n" +
               "  existing b3 and the wrong artefact ran with nothing erroring.`n" +
               "  $Hint")
    }
    throw "no $Kind at $Source"
}

# reads a build source -- a packaged directory or its zip -- into the four things anything needs to
# know about it: where it is, what meta.json says, what it is called on the device, and how big its
# payload is. a zip is unpacked here rather than on the device, because android's shell has no unzip
# worth relying on.
#
# **the on-device name comes from meta.json, never from the source's own name.** a build may sit at
# C:\wip\publish\linux-x64 or parity-b1.zip; its identity is `<id>-<version>-b<n>`, which is what the
# app lists and what the staleness check reads.
function Read-BuildSource([string]$Source, [string]$RepoRoot) {
    if (-not (Test-Path -LiteralPath $Source)) {
        Deny-NotAPcPath "build" $Source ("pass the build directory or zip that scripts/package-build.ps1 wrote, " +
                                         "under build/builds/, or omit it to run what is already on the device.")
    }
    $dir = (Resolve-Path -LiteralPath $Source).Path

    $unpacked = $null
    if ($dir -match '\.zip$') {
        $unpacked = Join-Path $RepoRoot ("build\stage-unpack\" + [System.IO.Path]::GetFileNameWithoutExtension($dir))
        if (Test-Path -LiteralPath $unpacked) { [System.IO.Directory]::Delete($unpacked, $true) }
        New-Item -ItemType Directory -Force -Path $unpacked | Out-Null
        Write-Host "unpacking $(Split-Path -Leaf $dir)"
        Expand-Archive -Path $dir -DestinationPath $unpacked
        $dir = $unpacked
    }

    $metaPath = Join-Path $dir "meta.json"
    if (-not (Test-Path -LiteralPath $metaPath)) {
        throw "no meta.json in $dir - that is what gives a build its identity. package it with .\scripts\package-build.ps1."
    }
    $meta = Get-Content -LiteralPath $metaPath -Raw | ConvertFrom-Json
    $payloadPath = Join-Path $dir $meta.payload
    if (-not (Test-Path -LiteralPath $payloadPath)) {
        throw "meta.json names payload '$($meta.payload)' and it is not in $dir"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $dir "plugins"))) {
        throw "$dir has no plugins/ - a build is a directory, and that is half of it"
    }

    $slug = "$($meta.id)-$($meta.sharpemuVersion)-b$($meta.buildVersion)".ToLowerInvariant()
    $slug = $slug -replace "\s+", "-"
    $slug = $slug -replace "[^a-z0-9._-]", "-"
    $slug = ($slug -replace "-+", "-").Trim("-")

    return [pscustomobject]@{
        Dir         = $dir
        Meta        = $meta
        Folder      = $slug
        Payload     = $meta.payload
        PayloadPath = $payloadPath
        PayloadSize = (Get-Item -LiteralPath $payloadPath).Length
        Unpacked    = $unpacked
    }
}

# a PC path to a build, or "", resolved to one absolute directory on the device. staging it if that
# is what the situation calls for.
function Resolve-StagedBuild([string]$Adb, [string]$Package, [string]$Source, [switch]$Restage) {
    $files = Get-AppFilesDir $Package
    $buildsDir = "$files/builds"

    if (-not $Source) {
        # **omitted means whatever the device already has.** in a debug loop "the one I last put
        # there" is what is meant, and the alternative silently prefers an older-but-higher-numbered
        # build -- which is the failure this whole change exists to remove.
        $staged = @(Get-DeviceListing $Adb $buildsDir)
        if ($staged.Count -eq 0) {
            throw ("no SharpEmu build is staged for $Package and none was named.`n" +
                   "  stage one with .\scripts\stage-build.ps1 -Build <build directory or zip>,`n" +
                   "  or package one first with .\scripts\package-build.ps1.")
        }
        if ($staged.Count -eq 1) {
            Write-Host ("build: {0} (the only one staged)" -f $staged[0])
            return "$buildsDir/$($staged[0])"
        }
        $chosen = Get-DeviceLine $Adb "ls -t '$buildsDir' | head -1"
        if (-not $chosen) { throw "could not list $buildsDir even though it has $($staged.Count) entries" }
        Write-Host ("build: {0} of {1} staged, most recently staged wins" -f $chosen, $staged.Count)
        return "$buildsDir/$chosen"
    }

    $tc = Resolve-Toolchain -Need Adb -Quiet
    $build = Read-BuildSource $Source $tc.RepoRoot
    $dest = "$buildsDir/$($build.Folder)"

    # **existence is not sameness.** rebuild a build locally and its directory name does not change,
    # so a check on the name alone runs yesterday's bytes and says nothing. the payload's size is
    # what this project tells artefacts apart by, and on a mismatch this restages by itself and says
    # so rather than leaving it to be remembered.
    $onDevice = Get-DeviceFileSize $Adb "$dest/$($build.Payload)"
    $why = ""
    if ($Restage)                            { $why = "-Restage" }
    elseif ($onDevice -lt 0)                 { $why = "not on the device" }
    elseif ($onDevice -ne $build.PayloadSize) { $why = "payload is $onDevice bytes there and $($build.PayloadSize) here" }

    if (-not $why) {
        Write-Host ("build: {0} (already on the device, payload {1:N0} bytes; -Restage to push over it)" -f `
            $build.Folder, $build.PayloadSize)
        if ($build.Unpacked) { [System.IO.Directory]::Delete($build.Unpacked, $true) }
        return $dest
    }

    Write-Host ("staging {0} ({1})" -f $build.Folder, $why)
    & (Join-Path $PSScriptRoot "stage-build.ps1") -Build $build.Dir -Package $Package
    if (-not $?) { throw "staging $($build.Dir) failed" }
    if ($build.Unpacked) { [System.IO.Directory]::Delete($build.Unpacked, $true) }
    return $dest
}

# a PC path to a game directory, or "", resolved to the **name** it has on the device -- which is
# what `--es game` takes, because by launch time it is the identity of something already there.
function Resolve-StagedGame([string]$Adb, [string]$Package, [string]$Source, [switch]$Restage) {
    $files = Get-AppFilesDir $Package
    $gamesDir = "$files/games"

    if (-not $Source) {
        # Dreaming Sarah first, because it is this project's primary test title and every recorded
        # measurement is against it -- matched loosely, since the directory carries a title id
        # (`Dreaming Sarah [PPSA02929]`) that is the user's own dump's and not ours to spell.
        $staged = @(Get-DeviceListing $Adb $gamesDir) | Sort-Object
        if ($staged.Count -eq 0) {
            throw ("no game is staged for $Package and none was named.`n" +
                   "  pass -Game <path to a game directory> -- its last component becomes the name on the device.")
        }
        $preferred = @($staged | Where-Object { $_ -like "*Dreaming Sarah*" })
        if ($preferred.Count -gt 0) {
            $name = $preferred[0]
            $why = if ($staged.Count -gt 1) { "the default, of $($staged.Count) staged; -Game to pick another" }
                   else                     { "the default, and the only one staged" }
        } elseif ($staged.Count -eq 1) {
            $name = $staged[0]
            $why = "the only one staged"
        } else {
            # sorted, so this is the same choice every time rather than whatever ls happened to say first.
            $name = $staged[0]
            $why = "no Dreaming Sarah staged, so the first of $($staged.Count); -Game to pick another"
        }
        Write-Host ("game: {0} ({1})" -f $name, $why)
        return $name
    }

    # -LiteralPath, everywhere, and it is not optional here: every PS5 game directory is named
    # `Title [PPSAxxxxx]`, and square brackets are powershell wildcard characters.
    if (-not (Test-Path -LiteralPath $Source)) {
        Deny-NotAPcPath "game directory" $Source ("pass the directory holding eboot.bin, or omit it to run a game " +
                                                  "already on the device.")
    }
    $dir = (Resolve-Path -LiteralPath $Source).Path
    $name = Split-Path -Leaf $dir
    $eboot = Join-Path $dir "eboot.bin"
    if (-not (Test-Path -LiteralPath $eboot)) { throw "$dir has no eboot.bin - that is not a game directory" }
    $localSize = (Get-Item -LiteralPath $eboot).Length

    # **the same weakness the build side had.** two different dumps can end in the same folder name,
    # and testing only that eboot.bin *exists* reuses whichever one got there first. comparing its
    # size costs one stat and catches the case that matters.
    $onDevice = Get-DeviceFileSize $Adb "$gamesDir/$name/eboot.bin"
    $why = ""
    if ($Restage)                        { $why = "-Restage" }
    elseif ($onDevice -lt 0)             { $why = "not on the device" }
    elseif ($onDevice -ne $localSize)    { $why = "eboot.bin is $onDevice bytes there and $localSize here" }

    if (-not $why) {
        Write-Host ("game: {0} (already on the device; -Restage to push {1} over it)" -f $name, $dir)
        return $name
    }

    Write-Host ("staging {0} ({1})" -f $name, $why)
    & (Join-Path $PSScriptRoot "stage-game.ps1") -Game $dir -Package $Package -Restage
    if (-not $?) { throw "staging $dir failed" }
    return $name
}

# a PC path to a driver package, the name of one already on the device, or "" for the platform's own
# Adreno driver. resolved to the **name** the app selects it by, "" meaning stock.
#
# **stock is the absence of a name rather than a name**, which is what lets this rule be one rule: a
# driver you name is a file you have, and the platform's own driver is not a file at all.
function Resolve-StagedDriver([string]$Adb, [string]$Package, [string]$Source, [switch]$Restage) {
    if (-not $Source) { return "" }

    $files = Get-AppFilesDir $Package
    if (Test-Path -LiteralPath $Source) {
        $name = [System.IO.Path]::GetFileNameWithoutExtension((Resolve-Path -LiteralPath $Source).Path)
        if ((Test-DevicePath $Adb "$files/gpu-drivers/$name") -and -not $Restage) {
            Write-Host ("driver: {0} (already on the device; -Restage to push {1} over it)" -f $name, $Source)
            return $name
        }
        & (Join-Path $PSScriptRoot "stage-driver.ps1") -Driver $Source -Name $name -Package $Package
        if (-not $?) { throw "staging the driver $Source failed" }
        return $name
    }

    # **a driver is the one thing that may also be named rather than pathed**, and that is not an
    # exception to the rule so much as the rule's other half: what is on the device is selected by
    # what it is called there. run.ps1 has read this way since M6. a name that is not there is an
    # error and never a fallback to stock, because a comparison silently run against the platform
    # driver is the mrpurple-t29 trap.
    if (-not (Test-DevicePath $Adb "$files/gpu-drivers/$Source")) {
        throw ("no driver called '$Source' on the device, and no file at that path either.`n" +
               "  stage one with .\scripts\stage-driver.ps1 -Driver <package.zip>, or omit it for the`n" +
               "  platform's own Adreno driver.")
    }
    return $Source
}
