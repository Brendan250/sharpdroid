# builds the APK.
#
#   .\app\build-app.ps1              # build, as SharpEmu Debug
#   .\app\build-app.ps1 -Install     # build, then adb install -r
#   .\app\build-app.ps1 -Release     # build under the manifest's own id and label
#   .\app\build-app.ps1 -Release -BundleSharpEmu .\build\builds\android-0.0.3-hotfix-2-20260808015108
#
# **this drives gradle, and it is still the entry point rather than a wrapper you may skip.** it
# resolves the SDK and JDK through scripts\toolchain.ps1 and writes local.properties from what it
# found, so the app step builds against the same SDK the native step did; gradle left alone would
# find its own through ANDROID_HOME, which on a machine with two installs is exactly the silent
# disagreement the resolver exists to prevent. it also passes the application id and label, and
# copies the APK to the path every other script predicts through Get-ApkArtefact.
#
# gradle was not used until the frontend work began, and the reason it is used now is the frontend:
# Material3, RecyclerView, SAF and the rest are a maven dependency graph, and hand-resolving one
# offline is a job with no end. what the raw aapt2/javac/d8 pipeline bought -- an offline, one
# command build with no maven at all -- was real, and it is what was traded away here.
#
# note the native libraries are NOT built here. run host\build.ps1 and
# scripts\build-adrenotools.ps1 first; app\build.gradle.kts collects their output.

param(
    [switch]$Install,
    # build under a different application id. **passing nothing gets the debug id**, not the
    # manifest's -- see -Release below and Resolve-AppIdentity in scripts\toolchain.ps1.
    #
    # **this is how a debug deploy cannot touch a personal install.** a different application id is a
    # different app to android: its own internal storage, its own
    # /storage/emulated/0/Android/data/<id>/files, its own save data, installed side by side. under
    # gradle it is the applicationId in app\build.gradle.kts, set from the property this passes.
    #
    # **the java package does not move, and that is what keeps this cheap.** the JNI entry points are
    # named Java_com_mircowuffwuff_sharpemu_HostLayer_*, which keys on the *java* package; only the
    # application id changes, and the namespace in build.gradle.kts stays put. so the native library
    # needs no rebuild and the symbols still resolve. what does change is the component name: the
    # activity becomes <id>/com.mircowuffwuff.sharpemu.MainActivity rather than <id>/.MainActivity.
    [string]$Package = "",
    # the name under the icon. a renamed application id installs beside the release app, so without
    # this you get two entries in the launcher both called "SharpEmu" and no way to tell which is
    # which.
    [string]$Name = "",
    # where the APK is written, so two application ids do not overwrite each other's output.
    [string]$OutName = "",
    # build the real thing: the application id and label exactly as the defaults have them, with
    # nothing renamed. **ignored if -Package or -Name is passed**, because those already say what the
    # identity should be.
    #
    # **it does not mean a release *build type*.** only the debug type is ever assembled -- see
    # app\build.gradle.kts. the two senses of the word are deliberately not the same thing here.
    [switch]$Release,
    # hand gradle --offline. everything resolves from the gradle cache or the build fails, which is
    # the way to find out whether a dependency was quietly fetched that nobody declared.
    [switch]$Offline,
    # a path to a build directory to ship inside the APK. **exactly one build ships per APK**, and
    # this flag is the whole of how one gets there.
    #
    # **bundling happens because a build was named and never because of a build type.** there is
    # deliberately no coupling to -Release: a release APK with no build in it and a debug APK with
    # one are both things somebody may want, and a rule tying the two together is a rule to get
    # wrong. absent means no asset at all, so the deploy loop keeps its small APK and its staged
    # builds without anyone deciding that it should.
    #
    # **explicit rather than "the newest thing under build\builds".** a release is rare, and a
    # silently stale bundle is not worth the saved keystroke: an APK carrying a build nobody
    # intended is exactly the plausible-artefact-attributed-to-the-wrong-source failure this
    # project keeps paying for.
    [string]$BundleSharpEmu = ""
)

# same reasoning as every other script here: windows powershell turns any stderr line from a native
# tool into an error record, and gradle writes progress to stderr routinely. the explicit
# $LASTEXITCODE checks are what makes this correct.
$ErrorActionPreference = "Continue"

$here = $PSScriptRoot
. (Join-Path $here "..\scripts\toolchain.ps1")

# the NDK is needed for its libc++_shared.so, which build.gradle.kts finds under the SDK. it is
# still version-checked: the STL that ships in the APK has to be the one the host layer was linked
# against.
$tc = Resolve-Toolchain -Need BuildTools, Jdk, Ndk, Adb
$repoRoot = $tc.RepoRoot
$adb = $tc.Adb

# the identity, and where it writes. both come out of scripts\toolchain.ps1 so that build-all.ps1
# predicts the same path this script is about to use rather than reimplementing the rule.
$identity = Resolve-AppIdentity -Package $Package -Name $Name -Release:$Release
$Package = $identity.Package
$Name    = $identity.Name

$artefact = Get-ApkArtefact -Package $Package -OutName $OutName
$out      = Join-Path $repoRoot (Split-Path -Parent $artefact)
$apkName  = Split-Path -Leaf $artefact

# --- the SDK levels, asserted rather than assumed ------------------------------------------
# toolchain.json is the source of truth for the toolchains and gradle\libs.versions.toml for what
# the app compiles against, and they overlap in exactly one place: the compile SDK. a build.gradle
# compiling against 36 while toolchain.json installs only android-35 fails inside gradle with a
# message about a missing platform, a long way from either declaration.
$buildGradle = Join-Path $here "build.gradle.kts"
$compileSdk = $null
if ((Get-Content $buildGradle -Raw) -match 'compileSdk\s*=\s*(\d+)') { $compileSdk = $Matches[1] }
$platformSdk = ($tc.Spec.platform -replace '^android-', '')
if (-not $compileSdk) { throw "no compileSdk found in $buildGradle" }
if ($compileSdk -ne $platformSdk) {
    throw "app\build.gradle.kts compiles against SDK $compileSdk and toolchain.json installs android-$platformSdk. bump one to match the other."
}

# --- the bundled SharpEmu build ---------------------------------------------------------------
# **exactly one build ships per APK, and it is a plain directory tree under assets/ rather than a
# zip.** a zip would be an archive inside an archive that already is one, so the payload would be
# compressed twice and the device would pay to undo both.
#
# the tree is staged under build/ rather than into app/src/main/assets, so "which build is in this
# APK" is answered by this flag and never by what somebody left in the source tree. **it is emptied
# on every build that does not name one**, which is what stops yesterday's -BundleSharpEmu from
# quietly riding along in today's debug APK.
$bundleRoot  = Join-Path $repoRoot "build\bundle"
$bundleAsset = Join-Path $bundleRoot "sharpemu"
Remove-Item -Recurse -Force -LiteralPath $bundleRoot -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $bundleRoot | Out-Null

$bundlePayload = ""
if ($BundleSharpEmu) {
    if (-not (Test-Path -LiteralPath $BundleSharpEmu -PathType Container)) {
        throw ("-BundleSharpEmu wants a build directory and '$BundleSharpEmu' is not one. " +
               "scripts\package-build.ps1 writes both a directory and a zip under build\builds\; " +
               "name the directory.")
    }
    $source = (Resolve-Path -LiteralPath $BundleSharpEmu).Path
    $sourceMeta = Join-Path $source "meta.json"
    if (-not (Test-Path -LiteralPath $sourceMeta)) {
        throw "$source has no meta.json, so it has no identity. package it with scripts\package-build.ps1."
    }
    $meta = Get-Content -LiteralPath $sourceMeta -Raw | ConvertFrom-Json
    if (-not $meta.id) { throw "$sourceMeta has no id." }

    # a build is a directory: the payload *and* its plugins. SharpEmu resolves plugins/ relative to
    # its own executable, so a payload bundled alone is a payload with no audio and no video.
    $bundlePayload = if ($meta.payload) { [string]$meta.payload } else { "SharpEmu" }
    if (-not (Test-Path -LiteralPath (Join-Path $source $bundlePayload) -PathType Leaf)) {
        throw "$source names payload '$bundlePayload' in its meta.json and it is not there."
    }
    if (-not (Test-Path -LiteralPath (Join-Path $source "plugins") -PathType Container)) {
        throw "$source has no plugins\ beside the payload - a build is a directory, and that is half of it."
    }

    # **the contract, asserted here rather than discovered on the device.** the bundled build cannot
    # mismatch, because the two ship together - so if it ever does, it is a build-system bug (a
    # stale asset, the wrong branch) and catching it on the PC beats a launch refusing itself. the
    # range is read out of the app's own source so there is one declaration of it and not two.
    $buildJava = Join-Path $here "src\main\java\com\mircowuffwuff\sharpemu\SharpEmuBuild.java"
    $javaSource = Get-Content -LiteralPath $buildJava -Raw
    if ($javaSource -notmatch 'CONTRACT_MIN\s*=\s*(\d+)') { throw "no CONTRACT_MIN in $buildJava" }
    $contractMin = [int]$Matches[1]
    if ($javaSource -notmatch 'CONTRACT_MAX\s*=\s*(\d+)') { throw "no CONTRACT_MAX in $buildJava" }
    $contractMax = [int]$Matches[1]
    $contract = [int]$meta.hostContract
    if ($contract -lt $contractMin -or $contract -gt $contractMax) {
        throw ("$source declares host contract $contract and this app speaks $contractMin..$contractMax. " +
               "bundling it would ship an APK that refuses its own build.")
    }

    # **the recorded submodule pointer has to name the commit this build was cut from.** that pointer
    # is the only thing that makes an APK reproducible from a clone: meta.json's commit is a receipt
    # written from whatever the packager had checked out, while external\sharpemu is what the
    # repository itself claims shipped. bundling a build the pointer does not name publishes an APK
    # whose contents nothing can reconstruct.
    #
    # **a refusal rather than a warning**, on the .packaged-from precedent: a warning on a package
    # that then succeeds is a build somebody keeps. and it catches a second failure for free -- a
    # submodule can only be moved to a commit it fetched, so a build cut from a checkout of your own
    # whose commit was never pushed cannot match this either.
    if (-not $meta.commit) {
        throw ("$source has no commit in its meta.json, so it was packaged from an archive and cannot " +
               "say what source it came from. an APK bundling it is not reproducible from a clone - " +
               "package the build from the fork instead.")
    }
    # ls-files rather than rev-parse: it reads the index entry without looking the object up in this
    # repository's own database, where a submodule's commits do not live, and it says nothing at all
    # on stderr when the path is not a submodule.
    $lsEntry = (& git -C $repoRoot ls-files -s -- "external/sharpemu") -join "`n"
    if ($lsEntry -match '^160000\s+([0-9a-f]{40})') { $pointer = $Matches[1] } else { $pointer = "" }
    if (-not $pointer) {
        throw ("external\sharpemu is not a submodule of this repository, so there is no recorded " +
               "commit to check '$($meta.commit)' against. run: git submodule update --init --recursive")
    }
    if (-not $pointer.StartsWith([string]$meta.commit)) {
        throw ("$source was cut from $($meta.commit) and external\sharpemu is recorded at " +
               "$($pointer.Substring(0, 7)). an APK bundling this build would ship a commit this " +
               "repository does not name. move the submodule onto it and stage the pointer:" +
               "`n  git -C external\sharpemu fetch origin" +
               "`n  git -C external\sharpemu checkout $($meta.commit)" +
               "`n  git add external/sharpemu")
    }
    Write-Host "bundled build is $($meta.commit), which external\sharpemu is recorded at"

    New-Item -ItemType Directory -Force -Path $bundleAsset | Out-Null
    Copy-Item -Recurse -Force (Join-Path $source "*") $bundleAsset

    # **the names android's asset packer silently drops, dropped here instead.** aapt2 has a default
    # ignore pattern for assets\ - `.*`, `<dir>_*` and `*~` among them - and it applies it without a
    # word, so a file listed in `contents` and absent from the APK is a listing that describes a tree
    # nobody has, and the launch that unpacks it aborts part-way through on a FileNotFoundException.
    # that is not hypothetical: a build directory picks up dotfiles from whatever produced it.
    # removing them here rather than filtering the listing keeps the extracted directory equal to
    # what the APK actually carries, and says which files went.
    $ignored = @(Get-ChildItem -LiteralPath $bundleAsset -Recurse -Force |
        Where-Object { $_.Name.StartsWith(".") -or $_.Name.EndsWith("~") -or
                       ($_.PSIsContainer -and $_.Name.StartsWith("_")) } |
        Sort-Object -Property FullName -Descending)
    foreach ($entry in $ignored) {
        Write-Host ("  dropping {0} - android's asset packer would not have shipped it" -f
            $entry.FullName.Substring($bundleAsset.Length + 1))
        Remove-Item -Recurse -Force -LiteralPath $entry.FullName -ErrorAction SilentlyContinue
    }

    # **the identity is regenerated rather than copied, and packagedAt is what it drops.** exactly
    # one of this build exists and nothing orders it against anything, so the field was provably
    # doing no work for it - and its version, to a person, is its commit. every value is given a
    # default rather than allowed through as JSON null, because org.json reads a null back as the
    # four-character string "null".
    #
    # **the name and the author are the other two the bundle decides for itself.** this build is the
    # one the app came with, which is what a person needs to know about it and is not what it shares
    # with a staged copy of the same commit - so it is named for that here rather than in the source
    # build's meta.json, where it would make every copy claim to be bundled. and the author goes: it
    # is whoever produced the app, said once on the app's own screens rather than on the card for the
    # build that arrived with it.
    $envMap = if ($null -eq $meta.env) { [ordered]@{} } else { $meta.env }
    $bundleMeta = [ordered]@{
        id              = [string]$meta.id
        name            = "Bundled build"
        sharpemuVersion = $(if ($meta.sharpemuVersion) { [string]$meta.sharpemuVersion } else { "0" })
        hostContract    = $contract
        payload         = $bundlePayload
        env             = $envMap
        notes           = $(if ($meta.notes) { [string]$meta.notes } else { "" })
        # **the commit is how the app tells whether an app update brought a new build**, so a bundle
        # without one re-extracts on any meta.json change instead. that is the archive-packaged case
        # and it is the format's own answer, not a special case invented here.
        commit          = $(if ($meta.commit) { [string]$meta.commit } else { "" })
        source          = $(if ($meta.source) { [string]$meta.source } else { "" })
    }
    [System.IO.File]::WriteAllText((Join-Path $bundleAsset "meta.json"),
        ($bundleMeta | ConvertTo-Json -Depth 4), (New-Object System.Text.UTF8Encoding $false))

    # **the listing the unpacker reads, and it is packaging's rather than the build format's.** an
    # asset in an APK is deflated, so it has no length until it has been read to the end - which
    # would put the free-space check halfway through the write it exists to prevent, and would leave
    # the progress bar counting files when one of them is four fifths of the total. AssetManager also
    # reports names without kinds, so without this, telling a file from a directory means opening
    # each one and reading a failure as "directory".
    $listed = @(Get-ChildItem -LiteralPath $bundleAsset -Recurse -File | Sort-Object FullName)
    $lines = foreach ($file in $listed) {
        # tab-separated, because a path may contain a space and a size never contains a tab.
        "{0}`t{1}" -f $file.Length, ($file.FullName.Substring($bundleAsset.Length + 1) -replace '\\', '/')
    }
    [System.IO.File]::WriteAllText((Join-Path $bundleAsset "contents"),
        (($lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding $false))

    $bundleBytes = ($listed | Measure-Object -Property Length -Sum).Sum
    Write-Host ""
    # the source build's own name, not the one the bundle is given: this line says what is going in.
    Write-Host ("bundling {0} ({1} {2} {3}, contract {4})" -f $meta.name, $bundleMeta.id,
        $bundleMeta.sharpemuVersion,
        $(if ($bundleMeta.commit) { $bundleMeta.commit } else { "no commit" }), $contract)
    Write-Host ("  {0:N0} bytes in {1} files, from {2}" -f $bundleBytes, $listed.Count, $source)
} else {
    Write-Host "bundling no SharpEmu build - a launch naming none will use whatever is staged"
}

# --- local.properties ----------------------------------------------------------------------
# gradle's own way of being told where the SDK is, written from what the resolver found rather than
# left to ANDROID_HOME. it is gitignored: it holds a path that is true on one machine.
$localProperties = Join-Path $repoRoot "local.properties"
$sdkForGradle = $tc.AndroidSdk -replace '\\', '\\'
$localBody = @(
    "# written by app\build-app.ps1 from whatever scripts\toolchain.ps1 resolved. do not edit:",
    "# it is regenerated on every build, and it is gitignored because it is true on one machine.",
    "sdk.dir=$sdkForGradle"
) -join "`n"
[System.IO.File]::WriteAllText($localProperties, $localBody + "`n", (New-Object System.Text.UTF8Encoding $false))

# --- TEMP ------------------------------------------------------------------------------------
# **this is not tidiness and the build does not start without it.**
#
# gradle's client talks to its daemon through a java NIO Selector. on windows the JDK builds that
# selector's wakeup pipe out of an AF_UNIX socket pair, and it places the socket at an
# *automatically assigned* address -- which the native GetTempPath puts under %TEMP%, i.e.
# %LOCALAPPDATA%\Temp.
#
# on at least one machine this project is developed on, AF_UNIX connect() fails with
# "Invalid argument" for any socket under AppData\Local, while the same call in C:\Windows\Temp or
# on the project drive succeeds. the JDK reports that as
# "java.io.IOException: Unable to establish loopback connection", which names TCP loopback and is
# the wrong component entirely: plain 127.0.0.1 sockets work fine, and so does the JDK's own
# bind/connect/verify handshake. every java NIO Selector on such a machine is broken, gradle merely
# being the first thing to try one.
#
# it has to be the environment variable. GetTempPath is native, so -Djava.io.tmpdir does not move
# it, and -Djdk.nio.channels.unixdomain.tmpdir is ignored for an automatically assigned address --
# both were tried and neither changed the assigned path.
#
# setting it unconditionally costs nothing on a machine that does not have the problem.
$gradleTmp = Join-Path $repoRoot "build\gradle\tmp"
New-Item -ItemType Directory -Force -Path $gradleTmp | Out-Null
$env:TEMP = $gradleTmp
$env:TMP = $gradleTmp
$env:JAVA_HOME = $tc.Jdk

# --- the debug key ------------------------------------------------------------------------------
# a throwaway key, generated once and kept out of git. app\build.gradle.kts names it as the debug
# signing config rather than letting gradle use ~\.android\debug.keystore, because that one is
# per-machine: a device that already has this app installed refuses an update signed by a different
# key with INSTALL_FAILED_UPDATE_INCOMPATIBLE, and recovering costs an uninstall, which takes the
# app's save data with it.
$keystore = Join-Path $here "debug.keystore"
if (-not (Test-Path $keystore)) {
    Write-Host "generating a debug keystore"
    & (Join-Path $tc.Jdk "bin\keytool.exe") -genkeypair -keystore $keystore `
        -alias sharpemu -storepass android -keypass android `
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=sharpemu-android"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

# --- gradle ----------------------------------------------------------------------------------
$gradlew = Join-Path $repoRoot "gradlew.bat"
if (-not (Test-Path $gradlew)) { throw "missing $gradlew" }

# the STL, from the resolver rather than from gradle's own idea of which NDK is installed. the host
# layer links c++_shared and the copy in the APK has to be the one it was linked against.
$stlSo = Join-Path $tc.NdkSysroot "usr\lib\aarch64-linux-android\libc++_shared.so"
if (-not (Test-Path $stlSo)) { throw "libc++_shared.so not found at $stlSo" }

$gradleArgs = @(":app:assembleDebug", "-PsharpemuStlSo=$stlSo", "-PsharpemuBundleAssets=$bundleRoot")
if ($Package) {
    Write-Host "  application id: $Package"
    $gradleArgs += "-PsharpemuApplicationId=$Package"
} else {
    # say so. the default is the debug identity, so a build that is *not* renamed is the unusual one
    # and a silent log here would leave you guessing which app you just installed.
    Write-Host "  application id: the default, not renamed"
}
if ($Name) {
    Write-Host "  name: $Name"
    $gradleArgs += "-PsharpemuAppLabel=$Name"
}
if ($Offline) { $gradleArgs += "--offline" }

# **the previous APK is deleted so that AGP writes a whole new zip rather than editing that one.**
# its packaging step updates the archive in place, and an entry that changes size is appended while
# the old bytes are left where they were -- so an APK rebuilt all day accumulates holes that nothing
# ever reads. measured: 915 entries and 10,055,013 bytes of dead space, in a file whose entries come
# to 29 MB, with a single 8.96 MB hole in the middle of it. it installs and runs perfectly, which is
# why it went unnoticed; it costs a third of every `adb install` in the deploy loop, and it makes the
# APK size recorded against a milestone a number that depends on how many times it was built.
#
# deleting one file costs a repackage -- about a second, and no recompilation, since dexing and
# resource merging are upstream of it and stay up to date.
$gradleApk = Join-Path $repoRoot "build\gradle\app\outputs\apk\debug\app-debug.apk"
Remove-Item -LiteralPath $gradleApk -Force -ErrorAction SilentlyContinue

Write-Host "gradle :app:assembleDebug"
& $gradlew @gradleArgs
if ($LASTEXITCODE -ne 0) { throw "gradle build failed" }

# --- collect ----------------------------------------------------------------------------------
# AGP writes to build\gradle\app\outputs\... . every other script in this repository asks
# Get-ApkArtefact where the APK is, so it is copied to the path they predict rather than teaching
# eight scripts about AGP's output layout. $gradleApk is resolved above, where it is deleted.
if (-not (Test-Path $gradleApk)) { throw "gradle reported success and $gradleApk is not there" }

New-Item -ItemType Directory -Force -Path $out | Out-Null
$apk = Join-Path $out $apkName
Copy-Item $gradleApk $apk -Force

# --- verify -----------------------------------------------------------------------------------
# an APK missing its dex installs and then dies at ClassNotFoundException, and one missing the
# native library installs and dies at UnsatisfiedLinkError - both a long way from here. a missing
# adrenotools hook is worse than either: adrenotools falls back to the stock driver quietly, so a
# driver comparison would measure the same driver twice and report a difference of zero.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$expected = @("classes.dex", "lib/arm64-v8a/libsharpemu-host-layer.so", "lib/arm64-v8a/libc++_shared.so",
              "lib/arm64-v8a/libmain_hook.so", "lib/arm64-v8a/libhook_impl.so")
$zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
try {
    $names = $zip.Entries | ForEach-Object { $_.FullName }
    $sizes = @{}
    foreach ($e in $zip.Entries) { $sizes[$e.FullName] = $e.Length }
} finally {
    $zip.Dispose()
}
foreach ($entry in $expected) {
    if ($names -notcontains $entry) { throw "packaging failed: $entry is not in the APK" }
}
# **and the native libraries have to be stored rather than deflated.** useLegacyPackaging in
# build.gradle.kts is what android:extractNativeLibs="true" became, and it is a hard requirement of
# the driver path rather than a size preference: adrenotools opens its hooks by soname out of
# nativeLibraryDir, which only exists as real files when the installer extracts them. getting it
# wrong fails by quietly falling back to the stock driver.
foreach ($entry in $names | Where-Object { $_ -like "lib/arm64-v8a/*" }) {
    if ($sizes[$entry] -le 0) { throw "packaging failed: $entry is empty" }
}
# **and the bundled build, when one was named.** an APK that quietly shipped without it installs
# perfectly and then falls back to whatever the device has staged - which on a release install is
# nothing at all, so the first symptom is a game that does not start. asserted here, where the
# artefact is still on this machine.
if ($BundleSharpEmu) {
    foreach ($entry in @("assets/sharpemu/meta.json", "assets/sharpemu/contents",
                         "assets/sharpemu/$bundlePayload")) {
        if ($names -notcontains $entry) { throw "packaging failed: $entry is not in the APK" }
        if ($sizes[$entry] -le 0) { throw "packaging failed: $entry is empty" }
    }
    if (-not ($names | Where-Object { $_ -like "assets/sharpemu/plugins/*" })) {
        throw "packaging failed: the bundled build has no plugins/ in the APK"
    }
    # **every line of the listing, against what the zip actually holds.** the listing is what the app
    # walks, so a name in it that aapt2 declined to package is a launch that aborts on a
    # FileNotFoundException after writing most of the tree - a failure the device finds and this
    # machine could have.
    foreach ($line in (Get-Content -LiteralPath (Join-Path $bundleAsset "contents"))) {
        if (-not $line.Trim()) { continue }
        $listed = "assets/sharpemu/" + $line.Substring($line.IndexOf("`t") + 1)
        if ($names -notcontains $listed) {
            throw "packaging failed: the bundled build's contents names $listed and the APK has no such entry"
        }
    }
} elseif ($names | Where-Object { $_ -like "assets/sharpemu/*" }) {
    throw "packaging failed: no build was named and the APK carries one anyway"
}

Write-Host ""
Write-Host ("built: {0} ({1:N0} bytes)" -f $apk, (Get-Item $apk).Length)

if ($Install) {
    & $adb install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "adb install failed" }
}
