# builds the APK.
#
#   .\app\build-app.ps1              # build, as SharpEmu Debug
#   .\app\build-app.ps1 -Install     # build, then adb install -r
#   .\app\build-app.ps1 -Release     # build under the manifest's own id and label
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
    [switch]$Offline
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

$gradleArgs = @(":app:assembleDebug", "-PsharpemuStlSo=$stlSo")
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

Write-Host ""
Write-Host ("built: {0} ({1:N0} bytes)" -f $apk, (Get-Item $apk).Length)

if ($Install) {
    & $adb install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "adb install failed" }
}
