# builds the M5 APK, offline, with no gradle.
#
#   .\app\build-app.ps1              # build, as SharpEmu Debug
#   .\app\build-app.ps1 -Install     # build, then adb install -r
#   .\app\build-app.ps1 -Release     # build under the manifest's own id and label
#
# gradle is not used on purpose. this app is one activity and one JNI class, and the android
# gradle plugin is a maven-resolved dependency we do not have cached and would have to fetch;
# aapt2, javac, d8, zipalign and apksigner are all sitting in android-sdk already. the real
# frontend can bring gradle with it when it needs a dependency graph - this does not.
#
# note the native library is NOT built here. run host\build.ps1 first; this script
# picks libsharpemu-host-layer.so out of its build directory.

param(
    [switch]$Install,
    # build under a different application id. **passing nothing gets the debug id**, not the
    # manifest's -- see -Release below and Resolve-AppIdentity in scripts\toolchain.ps1.
    #
    # **this is how a debug deploy cannot touch a personal install.** a different application id is a
    # different app to android: its own internal storage, its own
    # /storage/emulated/0/Android/data/<id>/files, its own save data, installed side by side. it is
    # done with aapt2's --rename-manifest-package, which is the same mechanism gradle's
    # applicationIdSuffix uses.
    #
    # **the java package does not move, and that is what keeps this cheap.** the JNI entry points are
    # named Java_com_mircowuffwuff_sharpemu_HostLayer_*, which keys on the *java* package; only the
    # manifest's application id changes. so the native library needs no rebuild and the symbols still
    # resolve. what does change is the component name: the activity becomes
    # <id>/com.mircowuffwuff.sharpemu.MainActivity rather than <id>/.MainActivity.
    [string]$Package = "",
    # the name under the icon. a renamed application id installs beside the release app, so without
    # this you get two entries in the launcher both called "SharpEmu" and no way to tell which is
    # which.
    [string]$Name = "",
    # where the APK is written, so two application ids do not overwrite each other's output.
    [string]$OutName = "",
    # build the real thing: the application id and label exactly as AndroidManifest.xml has them,
    # with nothing rewritten. **ignored if -Package or -Name is passed**, because those already say
    # what the identity should be.
    [switch]$Release
)
# build-tools, the platform jar and the NDK come from toolchain.json via scripts\toolchain.ps1, so
# there is one place a version is written down rather than one per script.

# same reasoning as the FEXCore build script: windows powershell turns any stderr line from a
# native tool into an error record, and several of these tools warn routinely. the explicit
# $LASTEXITCODE checks are what makes this correct.
$ErrorActionPreference = "Continue"

$here = $PSScriptRoot
. (Join-Path $here "..\scripts\toolchain.ps1")

# the NDK is needed only for its libc++_shared.so, but it is still version-checked: the STL that
# ships in the APK has to be the one the host layer was linked against.
$tc = Resolve-Toolchain -Need BuildTools, Jdk, Ndk, Adb
$repoRoot = $tc.RepoRoot
$jdk = $tc.Jdk
$tools = $tc.BuildTools
$androidJar = $tc.AndroidJar
$adb = $tc.Adb
$nativeBuild = Join-Path $repoRoot "build\host"

# the identity, and where it writes. both come out of scripts\toolchain.ps1 so that build-all.ps1
# predicts the same path this script is about to use rather than reimplementing the rule.
$identity = Resolve-AppIdentity -Package $Package -Name $Name -Release:$Release
$Package = $identity.Package
$Name    = $identity.Name

$artefact = Get-ApkArtefact -Package $Package -OutName $OutName
$out      = Join-Path $repoRoot (Split-Path -Parent $artefact)
$apkName  = Split-Path -Leaf $artefact

$hostLayerSo = Join-Path $nativeBuild "libsharpemu-host-layer.so"
if (-not (Test-Path $hostLayerSo)) {
    throw "libsharpemu-host-layer.so not found. run .\host\build.ps1 first."
}

# the host layer links c++_shared, so the APK has to carry it. the shell binary got away with
# LD_LIBRARY_PATH=. and a copy pushed beside it; an app gets it out of its own lib directory.
$stlSo = Join-Path $tc.NdkSysroot "usr\lib\aarch64-linux-android\libc++_shared.so"
if (-not (Test-Path $stlSo)) { throw "libc++_shared.so not found at $stlSo" }

# the adrenotools hooks. these are not linked against anything - they are opened by soname out of
# nativeLibraryDir, into an isolated linker namespace, by adrenotools itself. so the only way to
# put them where it will look is to package them as native libraries and let the installer unpack
# them, which is what extractNativeLibs="true" in the manifest is for. that flag was already on
# for M5's own reason (a 33 MB .so is simpler left compressed in the zip) and happens to be
# exactly adrenotools' documented useLegacyPackaging requirement.
#
# the other two hooks adrenotools builds, file_redirect and gsl_alloc, are deliberately not here:
# they back feature flags we do not pass, and an unused hook in nativeLibraryDir is one more thing
# that could be loaded by accident.
$adrenoBuild = Join-Path $repoRoot "build\adrenotools"
$hookSos = @(
    (Join-Path $adrenoBuild "src\hook\libmain_hook.so"),
    (Join-Path $adrenoBuild "src\hook\libhook_impl.so")
)
foreach ($h in $hookSos) {
    if (-not (Test-Path $h)) { throw "$h not found. run .\scripts\build-adrenotools.ps1 first." }
}

Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null
$classes = Join-Path $out "classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null

# --- 1. resources and the manifest ---------------------------------------------------------
# there are no resources at all, so this only compiles the manifest into the APK's resource
# table. that is still a required step: an APK without a binary manifest will not install.
Write-Host "aapt2 link"
$baseApk = Join-Path $out "base.apk"

# aapt2 renames the *package* and has no flag for the label, and the label in our manifest is a
# literal rather than a @string resource, so a renamed build gets a rewritten manifest in its own
# output directory. the source manifest is never touched.
$manifest = Join-Path $here "AndroidManifest.xml"
if ($Name) {
    $txt = [System.IO.File]::ReadAllText($manifest)
    $escaped = [System.Security.SecurityElement]::Escape($Name)
    $rewritten = $txt -replace 'android:label="[^"]*"', ('android:label="' + $escaped + '"')
    # **assert the edit landed.** a regex that silently matches nothing would produce an APK named
    # exactly like the release one, installed beside it, with no way to tell them apart.
    if ($rewritten -eq $txt) { throw "no android:label found in $manifest - cannot set -Name" }
    $manifest = Join-Path $out "AndroidManifest.xml"
    [System.IO.File]::WriteAllText($manifest, $rewritten, (New-Object System.Text.UTF8Encoding $false))
    Write-Host "  name: $Name"
}

$linkArgs = @(
    "link",
    "-o", $baseApk,
    "-I", $androidJar,
    "--manifest", $manifest,
    "--min-sdk-version", "28",
    "--target-sdk-version", "35"
)
if ($Package) {
    Write-Host "  application id: $Package"
    $linkArgs += @("--rename-manifest-package", $Package)
} else {
    # say so. the default is the debug identity, so a build that is *not* renamed is the unusual one
    # and a silent log here would leave you guessing which app you just installed.
    Write-Host "  application id: the manifest's own, not renamed"
}
& (Join-Path $tools "aapt2.exe") @linkArgs
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# --- 2. java -----------------------------------------------------------------------------
Write-Host "javac"
$sources = Get-ChildItem -Recurse -Path (Join-Path $here "java") -Filter *.java | ForEach-Object { $_.FullName }
& (Join-Path $jdk "bin\javac.exe") `
    -source 17 -target 17 -nowarn `
    -classpath $androidJar `
    -d $classes `
    $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# --- 3. dex ------------------------------------------------------------------------------
Write-Host "d8"
$classFiles = Get-ChildItem -Recurse -Path $classes -Filter *.class | ForEach-Object { $_.FullName }
$env:JAVA_HOME = $jdk
& (Join-Path $tools "d8.bat") `
    --lib $androidJar `
    --min-api 28 `
    --output $out `
    $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# --- 4. assemble -------------------------------------------------------------------------
# aapt2 produced a zip with the manifest and resource table in it; the dex and the native
# libraries go in beside them. done with System.IO.Compression rather than a zip tool because
# the SDK ships no zip and this is four entries.
Write-Host "packaging"
# both assemblies: FileSystem carries ZipFile and the CreateEntryFromFile extension, and the
# ZipArchiveMode enum lives in the other one. loading only the first leaves the enum unresolvable
# and the whole packaging block fails silently into an 8 KB APK.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($baseApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $add = {
        param($source, $entry)
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $source, $entry,
            [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        Write-Host ("  {0,12:N0}  {1}" -f (Get-Item $source).Length, $entry)
    }
    & $add (Join-Path $out "classes.dex") "classes.dex"
    & $add $hostLayerSo "lib/arm64-v8a/libsharpemu-host-layer.so"
    & $add $stlSo "lib/arm64-v8a/libc++_shared.so"
    foreach ($h in $hookSos) {
        & $add $h ("lib/arm64-v8a/" + (Split-Path -Leaf $h))
    }
} finally {
    $zip.Dispose()
}

# an APK missing its dex installs and then dies at ClassNotFoundException, and one missing the
# native library installs and dies at UnsatisfiedLinkError - both a long way from here. check.
$expected = @("classes.dex", "lib/arm64-v8a/libsharpemu-host-layer.so", "lib/arm64-v8a/libc++_shared.so",
              "lib/arm64-v8a/libmain_hook.so", "lib/arm64-v8a/libhook_impl.so")
$zip = [System.IO.Compression.ZipFile]::OpenRead($baseApk)
try {
    $names = $zip.Entries | ForEach-Object { $_.FullName }
} finally {
    $zip.Dispose()
}
foreach ($entry in $expected) {
    if ($names -notcontains $entry) { throw "packaging failed: $entry is not in the APK" }
}

# --- 5. align and sign -------------------------------------------------------------------
Write-Host "zipalign + apksigner"
$alignedApk = Join-Path $out "aligned.apk"
& (Join-Path $tools "zipalign.exe") -f 4 $baseApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

# a throwaway debug key, generated once and kept in the build directory. an unsigned APK will not
# install, and this is the whole of what gradle's debug signing does.
$keystore = Join-Path $here "debug.keystore"
if (-not (Test-Path $keystore)) {
    Write-Host "generating a debug keystore"
    & (Join-Path $jdk "bin\keytool.exe") -genkeypair -keystore $keystore `
        -alias sharpemu -storepass android -keypass android `
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=sharpemu-android"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

$apk = Join-Path $out $apkName
& (Join-Path $tools "apksigner.bat") sign `
    --ks $keystore --ks-pass pass:android --key-pass pass:android `
    --min-sdk-version 28 `
    --out $apk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host ""
Write-Host ("built: {0} ({1:N0} bytes)" -f $apk, (Get-Item $apk).Length)

if ($Install) {
    & $adb install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "adb install failed" }
}
