# builds the host-side vulkan probe for android/bionic arm64.
#
# deliberately not part of the host cmake project: this links nothing, takes two seconds, and
# answers questions about the *host's* vulkan rather than about the guest.
#
#   .\host\thunks\vulkan\build-probe.ps1

param(
    [int]$ApiLevel = 28
)

$ErrorActionPreference = "Continue"

$here = $PSScriptRoot
. (Join-Path $here "..\..\..\scripts\toolchain.ps1")
$tc   = Resolve-Toolchain -Need Ndk
$cc   = Join-Path $tc.NdkBin "clang.exe"
$out  = Join-Path $tc.RepoRoot "build\vulkan"

if (-not (Test-Path $cc)) { throw "missing NDK clang: $cc" }
New-Item -ItemType Directory -Force -Path $out | Out-Null

& $cc --target=aarch64-linux-android$ApiLevel -O2 -Wall `
    -o "$out\host-vk-probe" "$here\host_vk_probe.c" -ldl
if ($LASTEXITCODE -ne 0) { throw "build failed" }

Write-Host "built: $out\host-vk-probe"
