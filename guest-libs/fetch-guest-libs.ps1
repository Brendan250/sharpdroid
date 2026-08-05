# fetch-guest-libs.ps1 — stage an x86-64 glibc set for the guest.
#
# SharpEmu published for linux-x64 is ET_DYN and links against the system glibc, so the guest's
# own ld-linux-x86-64.so.2 needs a directory of x86-64 shared objects to find. this script
# builds that directory out of debian amd64 .deb packages.
#
# this is NOT a rootfs. it is a set of files the guest's dynamic linker searches. there is still
# one process, one address space, no proot and no socket hop — the libraries run under FEXCore
# exactly like SharpEmu does, and their syscalls arrive at our dispatch layer exactly like
# SharpEmu's do.
#
# why bookworm (debian 12, glibc 2.36) and not something newer: the version rule is >=, never ==.
# glibc symbol-versions everything, so any set newer than the highest node the payload references
# works. measured on the published apphost with readelf -V, those are GLIBC_2.27, GLIBCXX_3.4.22
# and CXXABI_1.3.7 — 2.36 clears all three comfortably. picking the oldest set that clears them
# means the fewest new syscalls for the host layer to chase (glibc 2.41+ leans harder on clone3
# and friends), and it is the same set .NET's own support matrix is built against.

[CmdletBinding()]
param(
  # set to keep the downloaded .debs. they are only inputs; the staged tree is what matters.
  [switch]$KeepDebs
)

$ErrorActionPreference = 'Stop'

$Root = $PSScriptRoot
$DebDir = Join-Path $Root 'debs'
$WorkDir = Join-Path $Root 'work'
$OutDir = Join-Path $Root 'x86_64'
$BinDir = Join-Path $Root 'bin'

# bsdtar, not GNU tar: it reads the .deb ar container directly, and the xz inside it. git bash's
# GNU tar cannot do either.
$Tar = Join-Path $env:SystemRoot 'System32\tar.exe'
if (-not (Test-Path $Tar)) { throw "bsdtar not found at $Tar" }

$Mirror = 'https://deb.debian.org/debian/pool/main'

# Want lists the archive prefixes to take out of each package. everything else in these .debs —
# gconv's 9 MB of charset modules, locales, docs, conffiles, ldconfig — is weight the guest
# linker never looks at. gconv would matter if glibc's own iconv were ever used; .NET carries
# ICU instead.
$Packages = @(
  @{ Name = 'libc6';      Url = "$Mirror/g/glibc/libc6_2.36-9+deb12u14_amd64.deb";
     Want = @('./lib/x86_64-linux-gnu/') },
  @{ Name = 'libc-bin';   Url = "$Mirror/g/glibc/libc-bin_2.36-9+deb12u14_amd64.deb";
     Want = @('./usr/bin/') },
  @{ Name = 'libgcc-s1';  Url = "$Mirror/g/gcc-12/libgcc-s1_12.2.0-14+deb12u1_amd64.deb";
     Want = @('./lib/x86_64-linux-gnu/') },
  @{ Name = 'libstdc++6'; Url = "$Mirror/g/gcc-12/libstdc++6_12.2.0-14+deb12u1_amd64.deb";
     Want = @('./usr/lib/x86_64-linux-gnu/') },
  # .NET's crypto stack is a thin shim over OpenSSL — libSystem.Security.Cryptography.Native.OpenSsl
  # dlopen's libssl.so.3 by soname and FailFasts with "No usable version of libssl was found" if it
  # is not there. SharpEmu reaches that path while creating its runtime, before it ever opens an
  # eboot, so this is not optional the way a rootfs package would be.
  @{ Name = 'libssl3';    Url = "$Mirror/o/openssl/libssl3_3.0.17-1~deb12u2_amd64.deb";
     Want = @('./usr/lib/x86_64-linux-gnu/') }
)

foreach ($Dir in @($DebDir, $WorkDir)) {
  New-Item -ItemType Directory -Force -Path $Dir | Out-Null
}
foreach ($Dir in @($OutDir, $BinDir)) {
  if (Test-Path $Dir) { Remove-Item -Recurse -Force $Dir }
  New-Item -ItemType Directory -Force -Path $Dir | Out-Null
}

foreach ($Package in $Packages) {
  $File = Join-Path $DebDir (Split-Path -Leaf $Package.Url)
  if (Test-Path $File) {
    Write-Host "have    $($Package.Name)"
  } else {
    Write-Host "fetch   $($Package.Name)"
    & curl.exe -sSL --fail --max-time 300 -o $File $Package.Url
    if ($LASTEXITCODE -ne 0) { throw "download failed: $($Package.Url)" }
  }

  # a .deb is an ar archive holding data.tar.xz. unpack both, into a per-package directory so
  # the four packages cannot overwrite each other's paths.
  $Stage = Join-Path $WorkDir $Package.Name
  if (Test-Path $Stage) { Remove-Item -Recurse -Force $Stage }
  New-Item -ItemType Directory -Force -Path $Stage | Out-Null

  & $Tar -x -f $File -C $Stage data.tar.xz
  if ($LASTEXITCODE -ne 0) { throw "could not read the ar container of $File" }

  # extract by explicit member list rather than by exclusion, because the thing being avoided is
  # symlinks: debian ships ./lib64/ld-linux-x86-64.so.2, ./usr/bin/ld.so and
  # ./usr/lib/.../libstdc++.so.6 as links, and an unprivileged Windows account cannot create a
  # symlink at all — bsdtar fails the whole extraction on the first one. so the archive is listed,
  # symlinks are identified by the " -> " in the verbose listing and dropped, and only the regular
  # files under the wanted prefixes are asked for by name. that survives debian rearranging which
  # paths happen to be links.
  $Data = Join-Path $Stage 'data.tar.xz'
  $Symlinks = @()
  & $Tar -tvf $Data | ForEach-Object {
    if ($_ -match '\s(\S+)\s->\s') { $Symlinks += $Matches[1] }
  }
  $Members = @()
  & $Tar -tf $Data | ForEach-Object {
    $Entry = $_
    if ($Entry.EndsWith('/')) { return }
    if ($Symlinks -contains $Entry) { return }
    foreach ($Prefix in $Package.Want) {
      if ($Entry.StartsWith($Prefix)) { $Members += $Entry; break }
    }
  }
  if ($Members.Count -eq 0) { throw "nothing matched the wanted prefixes in $File" }

  & $Tar (@('-x', '-f', $Data, '-C', $Stage) + $Members)
  if ($LASTEXITCODE -ne 0) { throw "could not unpack data.tar.xz of $File" }
  Remove-Item -Force $Data
}

# --- the libraries -----------------------------------------------------------------------------
#
# everything debian puts in lib/x86_64-linux-gnu, flattened into one directory. flattening is
# deliberate: the guest gets one LD_LIBRARY_PATH entry and no multiarch layout to reproduce.
#
# taking the whole set rather than only the eight SharpEmu names is the cheaper mistake. it is
# about 4 MB, and glibc dlopen's libnss_*.so.2 behind getpwuid_r and getaddrinfo — which CoreCLR
# calls during startup to find a home directory — so a hand-picked list would fail late and
# obscurely rather than not at all.
$LibSources = @(
  (Join-Path $WorkDir 'libc6\lib\x86_64-linux-gnu'),
  (Join-Path $WorkDir 'libgcc-s1\lib\x86_64-linux-gnu'),
  (Join-Path $WorkDir 'libstdc++6\usr\lib\x86_64-linux-gnu'),
  (Join-Path $WorkDir 'libssl3\usr\lib\x86_64-linux-gnu')
)
foreach ($Source in $LibSources) {
  if (-not (Test-Path $Source)) { throw "expected library directory missing: $Source" }
  Get-ChildItem -Path $Source -File -Filter '*.so*' | ForEach-Object {
    Copy-Item -Force $_.FullName (Join-Path $OutDir $_.Name)
  }
}

# libstdc++ is shipped as libstdc++.so.6.0.30 with libstdc++.so.6 a symlink beside it, and the
# symlink is what got excluded above. the guest linker searches by soname, so the name it will
# actually ask for has to exist as a file. copying costs 2 MB and removes a whole class of
# "works on the host, not on the device" problem.
Get-ChildItem -Path $OutDir -File -Filter 'libstdc++.so.6.*' | ForEach-Object {
  Copy-Item -Force $_.FullName (Join-Path $OutDir 'libstdc++.so.6')
}

# --- test binaries -----------------------------------------------------------------------------
#
# libc-bin's own tools are the test guests: dynamically linked x86-64 glibc executables built
# against the exact libc6 staged above, so nothing has to be cross-compiled against a glibc the
# NDK cannot produce. getent is the useful one — it starts, resolves, prints and exits without
# needing the gconv modules that iconv would.
$BinSource = Join-Path $WorkDir 'libc-bin\usr\bin'
foreach ($Name in @('getent')) {
  $Path = Join-Path $BinSource $Name
  if (-not (Test-Path $Path)) { throw "expected test binary missing: $Path" }
  Copy-Item -Force $Path (Join-Path $BinDir $Name)
}

Remove-Item -Recurse -Force $WorkDir
if (-not $KeepDebs) { Remove-Item -Recurse -Force $DebDir }

$LibCount = (Get-ChildItem -Path $OutDir -File).Count
$LibBytes = (Get-ChildItem -Path $OutDir -File | Measure-Object -Property Length -Sum).Sum
Write-Host ''
Write-Host ("staged  {0} shared objects, {1:N1} MiB -> {2}" -f $LibCount, ($LibBytes / 1MB), $OutDir)
Get-ChildItem -Path $BinDir -File | ForEach-Object { Write-Host ("        test guest: {0}" -f $_.Name) }
