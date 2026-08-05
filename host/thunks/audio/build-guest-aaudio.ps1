# assembles the guest half of the audio thunk into an x86-64 libaaudio.so.
#
# this is the library the guest's own ld.so finds on LD_LIBRARY_PATH when the fork's
# AndroidHostAudio P/Invokes AAudio, so it is staged into guest-libs/x86_64/ alongside glibc
# rather than built into anything.
#
# it links nothing at all -- no libc, no crt, no DT_NEEDED -- which is why the NDK's x86_64 clang
# can build it even though that target is bionic and the guest set is glibc. what comes out is
# 72 stubs and a DT_SONAME, and glibc's loader has no opinion about either.
#
# note for anyone editing this file: keep quoted strings ASCII. this is read as cp1252 by windows
# powershell, where a UTF-8 em-dash ends in the byte 0x94 -- a right double quote -- and silently
# terminates whatever string it sits in. comments are unaffected.
#
#   .\host\thunks\audio\gen-thunk.ps1            # if the generated sources are missing or stale
#   .\host\thunks\audio\build-guest-aaudio.ps1

param(
    [int]$ApiLevel = 21
)

$ErrorActionPreference = "Continue"

$here = $PSScriptRoot
. (Join-Path $here "..\..\..\scripts\toolchain.ps1")
$tc   = Resolve-Toolchain -Need Ndk
$bin  = $tc.NdkBin
$cc   = Join-Path $bin "x86_64-linux-android$ApiLevel-clang.cmd"
$src  = Join-Path $here "generated\aaudio_stubs.S"
$out  = Join-Path $tc.RepoRoot "guest-libs\x86_64"

if (-not (Test-Path $cc))  { throw "missing x86_64 cross compiler: $cc" }
if (-not (Test-Path $src)) { throw "missing generated stubs: $src, run .\host\thunks\audio\gen-thunk.ps1" }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$target = Join-Path $out "libaaudio.so"

& $cc -o $target $src `
    -shared -nostdlib -fPIC `
    "-Wl,-soname,libaaudio.so" `
    "-Wl,--hash-style=both" `
    "-Wl,-z,noexecstack"
if ($LASTEXITCODE -ne 0) { throw "libaaudio.so failed to build" }

Write-Host ""
Write-Host ("built: {0}  {1:N0} bytes" -f $target, (Get-Item $target).Length)
$readelf = Join-Path $bin "llvm-readelf.exe"
& $readelf -h $target | Select-String -Pattern "Type:|Machine:"
& $readelf -d $target | Select-String -Pattern "SONAME|NEEDED"
$exports = (& $readelf --dyn-syms $target | Select-String -Pattern "\bAAudio\w+$").Count
Write-Host "exported AAudio entry points: $exports"
