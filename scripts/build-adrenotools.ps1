# builds libadrenotools for android/bionic arm64.
#
#   .\scripts\build-adrenotools.ps1              # configure + build
#   .\scripts\build-adrenotools.ps1 -Clean       # wipe the build dir first
#
# same shape as host\build.ps1: everything project-local, and the checkout at external\libadrenotools
# is never modified. cmake is pointed straight at it and writes into build\adrenotools, so there is
# no wrapper CMakeLists to keep in sync.
#
# what comes out that we actually use:
#
#   libmain_hook.so    the -z global shim the platform loader ends up calling
#   libhook_impl.so    the implementation behind it, holding the parameters
#   libadrenotools.a   adrenotools_open_libvulkan, which the host layer links
#   liblinkernsbypass.a  linked into the above
#
# the first two are *runtime* dependencies loaded by soname out of the app's nativeLibraryDir,
# not link-time ones, which is why they have to be packaged into the APK rather than linked.
# libfile_redirect_hook.so and libgsl_alloc_hook.so are built too and are deliberately not
# shipped: they back ADRENOTOOLS_DRIVER_FILE_REDIRECT and _GPU_MAPPING_IMPORT, neither of which
# we ask for.

param(
    [switch]$Clean,
    [string]$BuildType = "Release",
    [int]$ApiLevel = 28
)

# not "Stop", for the reason the FEXCore build script gives at length: windows powershell turns
# any stderr line from a native tool into an error record, and cmake warns routinely.
$ErrorActionPreference = "Continue"

$here     = $PSScriptRoot
. (Join-Path $here "toolchain.ps1")
$tc       = Resolve-Toolchain -Need Ndk, Cmake
$repoRoot = $tc.RepoRoot
$ndk      = $tc.Ndk
$cmakeBin = $tc.CmakeBin
# the submodule is the intended source; the workspace checkout beside the repository is the same
# fallback host\CMakeLists.txt applies to FEX, for a workspace that predates the submodule.
$source   = Join-Path $repoRoot "external\libadrenotools"
if (-not (Test-Path $source)) { $source = Join-Path $tc.Workspace "libadrenotools" }
$build    = Join-Path $repoRoot "build\adrenotools"

if (-not (Test-Path $source)) { throw "libadrenotools not found at $repoRoot\external\libadrenotools or $($tc.Workspace)\libadrenotools" }

# the nested submodule is the whole namespace-bypass mechanism; without it adrenotools does not
# build and the error cmake gives is an unhelpful one about a missing subdirectory.
if (-not (Test-Path (Join-Path $source "lib\linkernsbypass\CMakeLists.txt"))) {
    throw "$source\lib\linkernsbypass is empty. run: git submodule update --init --recursive"
}

if ($Clean -and (Test-Path $build)) {
    Write-Host "wiping $build"
    Remove-Item -Recurse -Force $build
}
New-Item -ItemType Directory -Force -Path $build | Out-Null

$env:PATH = "$cmakeBin;$env:PATH"

# every -D fully quoted as one string, for the same powershell expansion reason the FEXCore
# script documents.
& "$cmakeBin\cmake.exe" -G Ninja -S "$source" -B "$build" `
    "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
    "-DANDROID_ABI=arm64-v8a" `
    "-DANDROID_PLATFORM=android-$ApiLevel" `
    "-DANDROID_STL=c++_static" `
    "-DCMAKE_BUILD_TYPE=$BuildType" `
    "-DCMAKE_MAKE_PROGRAM=$cmakeBin\ninja.exe"
if ($LASTEXITCODE -ne 0) { throw "cmake configure failed" }

& "$cmakeBin\ninja.exe" -C $build
if ($LASTEXITCODE -ne 0) { throw "build failed" }

Write-Host ""
Write-Host "built:"
Get-ChildItem -Recurse -Path $build -Include *.a, *.so |
    ForEach-Object { "  {0,10:N0}  {1}" -f $_.Length, $_.FullName.Substring($build.Length + 1) }
