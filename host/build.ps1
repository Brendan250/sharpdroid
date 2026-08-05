# builds FEXCore for android/bionic arm64.
#
# everything here is project-local: the NDK, cmake and ninja all come out of android-sdk,
# and the FEX checkout is never modified. see CMakeLists.txt for why that last part matters.
#
#   .\host\build.ps1              # configure + build
#   .\host\build.ps1 -Clean       # wipe the build dir first

param(
    [switch]$Clean,
    [string]$BuildType = "Release",
    [int]$ApiLevel = 28
)
# the NDK revision is declared in toolchain.json and checked by scripts\toolchain.ps1, not spelled out
# here. r29 (clang 21) is a floor rather than a preference: FEXCore's SpinWaitLock.h uses
# std::atomic_ref, which libc++ did not implement until LLVM 19, so anything on clang 18 or older
# fails to compile FEXCore at all — the r27 that shipped with the SDK did exactly that. the pinned
# build 14206865 is also the one Turnip_Gen8_V33 was compiled with, which keeps us aligned with the
# driver, so the resolver warns when a different r29+ is used.

# deliberately NOT "Stop". windows powershell wraps every stderr line from a native exe in a
# NativeCommandError record, so "Stop" aborts the build on any tool that merely warns — cmake
# emits a harmless "[range-v3 warning]: unknown system Android !" during configure and that
# was enough to kill the run. correctness comes from the explicit $LASTEXITCODE checks below.
$ErrorActionPreference = "Continue"

$here = $PSScriptRoot
. (Join-Path $here "..\scripts\toolchain.ps1")

# only what this script uses: a missing JDK must not break the native build.
$tc       = Resolve-Toolchain -Need Ndk, Cmake
$repoRoot = $tc.RepoRoot
$ndk      = $tc.Ndk
$cmakeBin = $tc.CmakeBin
$build    = Join-Path $repoRoot "build\host"

if ($Clean -and (Test-Path $build)) {
    Write-Host "wiping $build"
    Remove-Item -Recurse -Force $build
}
New-Item -ItemType Directory -Force -Path $build | Out-Null

# FEXCore also compresses its man page with "gzip -kf9n", which windows has no equivalent for.
# git ships one, so point a shim at it rather than putting git's whole usr\bin on PATH — that
# directory is full of unix tools (sh, find, sort) that would shadow the windows ones mid-build.
# the man page is a pure docs artifact, but it is wired into the default target.
$shimDir = Join-Path $build "_shims"
New-Item -ItemType Directory -Force -Path $shimDir | Out-Null
$gzip = (Get-Command gzip.exe -ErrorAction SilentlyContinue).Source
if (-not $gzip) {
    $git = (Get-Command git.exe -ErrorAction SilentlyContinue).Source
    if ($git) {
        $cand = Join-Path (Split-Path (Split-Path $git)) "usr\bin\gzip.exe"
        if (Test-Path $cand) { $gzip = $cand }
    }
}
if ($gzip) {
    "@echo off`r`n`"$gzip`" %*" | Set-Content -Encoding ascii (Join-Path $shimDir "gzip.cmd")
} else {
    Write-Warning "no gzip found; the FEX.1.gz man page step will fail (harmless to us, but it stops the default target)"
}

# FEXCore's generators shell out to "python3", which on windows hits the microsoft store
# alias stub. tools\python3.cmd forwards to the real interpreter.
$env:PATH = "$shimDir;$here\tools;$cmakeBin;$env:PATH"

# every -D is a fully quoted single string. powershell does not reliably expand a variable
# that sits after "=" inside an unquoted -Dkey=value token: $BuildType came through to cmake
# as the literal text "$BuildType", which it then embedded in ninja rule names, and ninja
# read the "$B" as an undefined variable reference and died with a lexing error.
& "$cmakeBin\cmake.exe" -G Ninja -S "$here" -B "$build" `
    "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
    "-DANDROID_ABI=arm64-v8a" `
    "-DANDROID_PLATFORM=android-$ApiLevel" `
    "-DANDROID_STL=c++_shared" `
    "-DCMAKE_BUILD_TYPE=$BuildType" `
    "-DCMAKE_MAKE_PROGRAM=$cmakeBin\ninja.exe"
if ($LASTEXITCODE -ne 0) { throw "cmake configure failed" }

& "$cmakeBin\ninja.exe" -C $build
if ($LASTEXITCODE -ne 0) { throw "build failed" }

Write-Host ""
Write-Host "built:"
Get-ChildItem -Recurse -Path $build -Include *.a, *.so |
    ForEach-Object { "  {0,10:N0}  {1}" -f $_.Length, $_.FullName.Substring($build.Length + 1) }
