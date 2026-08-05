# builds the static x86-64 test guests for the host layer.
#
# the NDK's x86_64 clang is used purely as a cross compiler here. nothing about these binaries
# is android-specific — they are static linux x86-64 ELFs, which is exactly what the host layer
# claims to be able to run.
#
#   .\guests\build-guests.ps1

param(
    [int]$ApiLevel = 21
)

$ErrorActionPreference = "Continue"

$here = $PSScriptRoot
. (Join-Path $here "..\scripts\toolchain.ps1")
$tc   = Resolve-Toolchain -Need Ndk
$repoRoot = $tc.RepoRoot
$bin  = $tc.NdkBin
$cc   = Join-Path $bin "x86_64-linux-android$ApiLevel-clang.cmd"
$out  = Join-Path $repoRoot "build\guests"

if (-not (Test-Path $cc)) { throw "missing x86_64 cross compiler: $cc" }
New-Item -ItemType Directory -Force -Path $out | Out-Null

# -nostdlib: no libc, no crt startup files, _start is ours.
# -no-pie:   ET_EXEC, so the loader maps it where its program headers say and no bias is chosen.
# -fno-stack-protector: the canary would be read from FS before TLS exists.
Write-Host "building hello-nostdlib..."
& $cc -o (Join-Path $out "hello-nostdlib") (Join-Path $here "hello-nostdlib.c") `
    -static -nostdlib -no-pie -fno-stack-protector -fno-builtin -Os -Wall `
    "-Wl,-e,_start"
if ($LASTEXITCODE -ne 0) { throw "hello-nostdlib failed to build" }

# the signal test. also -nostdlib: it spells out the kernel signal ABI itself, since a libc in
# between would hide which side of the frame got something wrong.
Write-Host "building signals..."
& $cc -o (Join-Path $out "signals") (Join-Path $here "signals.c") `
    -static -nostdlib -no-pie -fno-stack-protector -fno-builtin -Os -Wall `
    "-Wl,-e,_start"
if ($LASTEXITCODE -ne 0) { throw "signals failed to build" }

# the self-modifying code test. also -nostdlib: it issues mmap/mprotect/munmap itself, so what is
# under test is the host layer's VMA tracking rather than a libc's allocator.
Write-Host "building smc..."
& $cc -o (Join-Path $out "smc") (Join-Path $here "smc.c") `
    -static -nostdlib -no-pie -fno-stack-protector -fno-builtin -Os -Wall `
    "-Wl,-e,_start"
if ($LASTEXITCODE -ne 0) { throw "smc failed to build" }

# the asynchronous signal test. also -nostdlib: it issues clone and tgkill itself, because what is
# under test is the host layer interrupting one guest thread on behalf of another and a pthreads
# implementation in between would decide half of that for us.
Write-Host "building asyncsig..."
& $cc -o (Join-Path $out "asyncsig") (Join-Path $here "asyncsig.c") `
    -static -nostdlib -no-pie -fno-stack-protector -fno-builtin -Os -Wall `
    "-Wl,-e,_start"
if ($LASTEXITCODE -ne 0) { throw "asyncsig failed to build" }

# the vulkan thunk test. the only *dynamic* guest here: it links against the generated
# libvulkan.so.1 and nothing else, so that the guest's own ld.so has to find it, run its
# .init_array and resolve 623 PLT entries before _start is reached. still -nostdlib, so the
# interpreter has to be named by hand -- there is no crt to carry it.
Write-Host "building vulkan..."
$guestLibs = Join-Path $repoRoot "guest-libs\x86_64"
if (-not (Test-Path (Join-Path $guestLibs "libvulkan.so.1"))) {
    throw "missing guest libvulkan.so.1, run .\host\thunks\vulkan\build-guest-vulkan.ps1 first"
}
& $cc -o (Join-Path $out "vulkan") (Join-Path $here "vulkan.c") `
    -nostdlib -fno-stack-protector -fno-builtin -Os -Wall `
    "-Wl,-e,_start" `
    "-Wl,--dynamic-linker=/lib64/ld-linux-x86-64.so.2" `
    "-L$guestLibs" "-l:libvulkan.so.1" `
    "-Wl,-rpath,`$ORIGIN/guest-libs"
if ($LASTEXITCODE -ne 0) { throw "vulkan failed to build" }

# and the one that makes the GPU actually run something. same linkage as above.
Write-Host "building vkrender..."
& $cc -o (Join-Path $out "vkrender") (Join-Path $here "vkrender.c") `
    -nostdlib -fno-stack-protector -fno-builtin -Os -Wall `
    "-Wl,-e,_start" `
    "-Wl,--dynamic-linker=/lib64/ld-linux-x86-64.so.2" `
    "-L$guestLibs" "-l:libvulkan.so.1" `
    "-Wl,-rpath,`$ORIGIN/guest-libs"
if ($LASTEXITCODE -ne 0) { throw "vkrender failed to build" }

# and the swapchain, on a window system the host layer invents. same linkage again.
Write-Host "building vkswap..."
& $cc -o (Join-Path $out "vkswap") (Join-Path $here "vkswap.c") `
    -nostdlib -fno-stack-protector -fno-builtin -Os -Wall `
    "-Wl,-e,_start" `
    "-Wl,--dynamic-linker=/lib64/ld-linux-x86-64.so.2" `
    "-L$guestLibs" "-l:libvulkan.so.1" `
    "-Wl,-rpath,`$ORIGIN/guest-libs"
if ($LASTEXITCODE -ne 0) { throw "vkswap failed to build" }

# the audio thunk test. same linkage again, against the generated libaaudio.so this time.
Write-Host "building aaudio..."
if (-not (Test-Path (Join-Path $guestLibs "libaaudio.so"))) {
    throw "missing guest libaaudio.so, run .\host\thunks\audio\build-guest-aaudio.ps1 first"
}
& $cc -o (Join-Path $out "aaudio") (Join-Path $here "aaudio.c") `
    -nostdlib -fno-stack-protector -fno-builtin -Os -Wall `
    "-Wl,-e,_start" `
    "-Wl,--dynamic-linker=/lib64/ld-linux-x86-64.so.2" `
    "-L$guestLibs" "-l:libaaudio.so" `
    "-Wl,-rpath,`$ORIGIN/guest-libs"
if ($LASTEXITCODE -ne 0) { throw "aaudio failed to build" }

# a full static bionic libc. bionic rather than glibc only because that is the cross compiler
# we have; what is being tested is the libc startup path, not which libc it is.
Write-Host "building hello-libc..."
& $cc -o (Join-Path $out "hello-libc") (Join-Path $here "hello-libc.c") -static -O1 -Wall
if ($LASTEXITCODE -ne 0) { throw "hello-libc failed to build" }

$readelf = Join-Path $bin "llvm-readelf.exe"
Get-ChildItem $out | ForEach-Object {
    Write-Host ""
    Write-Host ("{0}  {1:N0} bytes" -f $_.Name, $_.Length)
    & $readelf -h $_.FullName | Select-String -Pattern "Type:|Machine:|Entry point"
}
