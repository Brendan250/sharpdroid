# repository structure

why sharpemu-android is two repositories, what lives in each, and where every artefact is built.

**this file describes the repository as it is.** it is not a plan and carries nothing that has not been done; where it is out of date, it is wrong and should be corrected.

## the two repositories

| repository | what it is | cadence |
| --- | --- | --- |
| [`sharpemu-android/sharpemu`](https://github.com/sharpemu-android/sharpemu) | our fork of [SharpEmu](https://github.com/sharpemu/sharpemu), the PS5 emulator itself. `main` mirrors upstream and `android` carries everything android needs to be *correct*, which is the only maintained branch and the one that ships. localized performance work lives on `perf/` topic branches and goes upstream as pull requests | follows upstream, which moves fast. absorbs upstream releases |
| **sharpemu-android** | **this tree.** the android app, the host layer it runs SharpEmu inside, the thunks, the test guests and the build tooling | follows android and our own work. releases an APK |

**the rule that drew that line is release cadence, not architecture.** two things belong in one repository when they must change in the same commit; they belong apart when they are released independently. the fork tracks somebody else's project and must be rebasable against it, so it is separate. everything here ships as one APK and versions together, so it is one.

### why the host layer is not a third repository

it is an architectural layer, and layers are the classic wrong reason to split a repository. the app and the host layer are one build unit with a source-level interface between them:

- `hostContract`, the launcher↔payload interface generation, is emitted by `scripts/package-build.ps1` and checked by `app/src/main/java/.../SharpEmuBuild.java`. bumping it is one change in two files
- every launch flag is parsed in `host/src/host_layer.cpp` and constructed in `MainActivity.java`
- the JNI entry point's symbol name is hardcoded in `host/CMakeLists.txt` so the linker cannot garbage-collect it. renaming the java package breaks the native link
- each thunk generator emits two halves from one source: a `.inc` compiled into the host layer and a `.S` assembled into a guest-side shared object

a repository boundary there would buy an independent version number nobody would ever bump, and cost a submodule pointer update on most commits.

## the tree

```
├── LICENSE  LICENSES/  REUSE.toml  .gitignore  .gitattributes  .gitmodules
├── docs/
│   ├── repo-structure.md     this file
│   ├── build-format.md       what a SharpEmu build is, and what a payload must implement
│   ├── host-layer.md         the loader, the address space, syscalls, threads, signals, SMC
│   ├── guest-files.md        a granted game directory, answered underneath the guest's syscalls
│   ├── vulkan.md             the vulkan thunk, both window systems, custom driver injection
│   ├── audio.md              the AAudio thunk, the callback boundary, the stall watchdog
│   ├── app.md                the screens, the surface, the launch extras, the settings and merge
│   └── scripts.md            every script, and the flags worth knowing
│
├── external/                 three pinned submodules
│   ├── FEX/                  FEXCore, and sixteen submodules of its own. never modified
│   ├── libadrenotools/       never modified
│   └── sharpemu/             the fork, pinned at the commit a bundled build is cut from. a pin
│                             rather than a workspace -- see below
│
├── host/                     the host layer — one cmake project
│   ├── CMakeLists.txt        assembles FEXCore for bionic, then builds the host layer twice
│   ├── build.ps1
│   ├── include/              bionic-compat.h
│   ├── regression.sh         the on-device regression modes
│   ├── src/                  ELF loader, syscall dispatch, signal delegation, VMA/SMC tracking,
│   │                         and the file layer that answers a granted game directory
│   ├── thunks/vulkan/        generator, generated halves, the host probe
│   ├── thunks/audio/         generator, generated halves
│   └── tools/python3.cmd     a shim FEXCore's generators need on windows
│
├── settings.gradle.kts  build.gradle.kts  gradle.properties  gradlew  gradlew.bat
├── gradle/
│   ├── libs.versions.toml    the app's dependency versions, in one place
│   └── wrapper/              the gradle distribution the wrapper fetches
│
├── app/                      the android app
│   ├── build.gradle.kts      the APK: AGP, kotlin, androidx, Material3
│   ├── build-app.ps1         drives gradle, and resolves the SDK for it
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/mircowuffwuff/sharpemu/   java and kotlin, side by side
│       └── res/
│
├── guests/                   x86-64 test guests the host layer is exercised against
├── guest-libs/               fetch script for the staged x86-64 shared objects
├── scripts/
│   ├── run.ps1               build, stage and launch on a connected device. one command
│   ├── build-all.ps1         the whole pipeline, in dependency order
│   ├── toolchain.ps1         resolves the toolchains; every script includes it
│   ├── device.ps1            the device operations the staging scripts share
│   ├── fetch-toolchain.ps1   acquires the toolchains into toolchain/
│   ├── package-build.ps1     a fork publish, or an archive, into a build with an identity
│   ├── stage-build.ps1       a packaged build onto a device
│   ├── stage-game.ps1        a game, by path
│   ├── stage-guest-libs.ps1  the x86-64 libraries the guest's ld.so searches
│   ├── stage-driver.ps1      an adrenotools driver package
│   ├── stage-shell.ps1       the shell binary and guests to /data/local/tmp
│   ├── regression.ps1        stage, run the 15 host-layer modes, report
│   ├── soak.ps1              many runs, interleaved arms, one classification per run
│   ├── compare-builds.ps1    compare-drivers.ps1    compare-file-modes.ps1
│   └── device/               scripts that run on the device
├── toolchain.json            every required toolchain version, and where to get it
├── toolchain/                toolchains fetched into the repo. ignored
└── build/                    all local output. ignored
```

five notes on the shape:

- **the app is a gradle build, and the frontend is what that buys.** a game list and an Eden-shaped settings screen want Material3, RecyclerView, SAF and the rest, and hand-resolving that transitive dependency graph offline is a job with no end — which is what calling aapt2, javac, d8, zipalign and apksigner directly would mean. the trade is a maven graph and a gradle distribution against an offline one-command build. **`app/build-app.ps1` is the entry point rather than `gradlew`** — it resolves the SDK and JDK through `scripts/toolchain.ps1` and writes `local.properties` from what it found, so the app step cannot disagree with the native step about which SDK it built against, and it copies the APK to the path every other script predicts


- **`.gitattributes` forces LF on everything**, and that is correctness rather than tidiness. `host/regression.sh` and `scripts/device/*.sh` are pushed to an android device and run by `/system/bin/sh`, where a CR is a syntax error; git for windows sets `core.autocrlf = true` in its system config, so without the attribute a clone on windows would break them on checkout

- **`host/` builds FEXCore too.** FEX's own top-level `CMakeLists.txt` refuses to configure for android, but `FEXCore/` is a standalone project with no references to parent targets, so `host/CMakeLists.txt` assembles the dependency graph itself. every line of that glue is ours and the FEX checkout stays pristine
- **the thunks live under `host/`** rather than beside it. each is host-layer code that happens to emit a guest-side artefact as well
- **measurement scripts are tools, not part of the app.** `scripts/soak.ps1` runs the app many times and classifies every run, because the failures worth chasing here are intermittent and a handful of clean runs proves nothing

## external dependencies

three **git submodules under `external/`**, each pinned to an exact commit:

| | pin | licence | what it is |
| --- | --- | --- | --- |
| [FEX](https://github.com/FEX-Emu/FEX) | tag `FEX-2607`, `1cc4b93e7` | MIT | the x86-64 translation core the host layer links |
| [libadrenotools](https://github.com/bylaws/libadrenotools) | `8fae8ce` | BSD-2-Clause | custom GPU driver loading |
| [the SharpEmu fork](https://github.com/sharpemu-android/sharpemu) | the `android` commit a bundled build is cut from | GPL-2.0-or-later | the emulator itself |

**FEX and libadrenotools are never modified**, and their submodules enforce that for free: a patched FEX shows dirty in `git status` the moment it happens, where a checkout beside the tree would go unnoticed. FEX bans AI-generated contributions, so a patch of ours could never go upstream and would become a permanent private delta against a fast-moving project.

**`--recurse-submodules` is not optional**: FEX carries sixteen submodules of its own — vixl, fmt, xxhash, range-v3, unordered_dense and the rest — and `host/CMakeLists.txt` fails to configure with a message about them if they are absent. a full recursive clone is around 840 MB, of which the fork is about 21 MB.

`host/CMakeLists.txt` and `scripts/build-adrenotools.ps1` resolve `external/<name>` first and fall back to a checkout in the workspace, which is what makes a working tree with those two submodules uninitialised still buildable for anyone who already has them.

### the fork's submodule is a pin, not a workspace

**it exists so the SharpEmu build an APK bundles is reproducible from a clone.** a build's `meta.json` records the commit it was cut from, but that is a receipt written from whatever the packager had checked out; nothing else would say which commit to build. the submodule is the repository naming it.

**the pointer names what ships** — the `android` commit the bundled build comes from — and nothing else. it does not constrain what anyone checks out: a `perf/` topic branch is checked out inside the submodule and packaged by hand like any other build, and the pointer is left where it is. so a single pointer describing a fork that is developed across several branches is not the contradiction it looks like. it is not describing the fork; it is describing the one branch that ships.

**it is not where the fork is developed.** `scripts/toolchain.ps1` resolves `SHARPEMU_FORK` first and the submodule second, so a checkout you commit in is reached by pointing that variable at it — the shape `go mod replace` uses, where the pin is what builds unless a local checkout is declared in writing. **the pin is the default rather than the fallback on purpose**: a pin no ordinary build ever takes is one nothing notices has gone stale, and the first person to find out would be somebody cloning this repository.

**`android`'s history is load-bearing.** every tag here names a commit in the fork, so an upstream release is merged into `android` and never rebased onto it. rewriting that history orphans the pointer in every earlier tag, and the symptom is a `git submodule update` failing on a fetch rather than anything visible in this tree.

**`app/build-app.ps1 -BundleSharpEmu` refuses a build the pointer does not name.** that is the moment the pointer's claim becomes true or false, so it is where the claim is checked — and it is a refusal rather than a warning because a warning on a package that then succeeds is a build somebody keeps. because the submodule can only be moved to a commit it fetched, the same check also catches a build cut from a commit that was never pushed.

## where each artefact is built

**`scripts/run.ps1` does all of it in one command** — build, stage, launch, follow the log, and it needs no arguments. [`scripts.md`](scripts.md) documents every script and its parameters.

**development happens on the debug app, and that is the default everywhere.** `app/build-app.ps1` and `scripts/build-all.ps1` rename the application id to **`com.mircowuffwuff.sharpemu.debug`** and the launcher label to *SharpEmu Debug*; `run.ps1`, every `stage-*.ps1`, `soak.ps1` and every `compare-*.ps1` all drive that same id. android treats it as a separate app with its own storage and save data, so **nothing done while developing can reach a personal install of the release build on the same device**.

**`-Release` builds under the manifest's own id and label**, `-Package` / `-Name` override both, and `-Package` aims any of the other scripts elsewhere. three functions in `scripts/toolchain.ps1` hold the rules — `Resolve-AppIdentity` for what an APK is built as, `Resolve-AppPackage` for which app a script talks to, and `Resolve-AppActivity` for the component name to launch — so no two scripts can disagree about any of them.

**that last one is why the JNI note above matters in the tooling too.** an activity is `<application id>/<java package>.MainActivity` and the two halves move independently: the id is renamed per build, the java package never is. renaming the java package would break the native link, the two JNI symbols, the `-Wl,-u` in `host/CMakeLists.txt` **and** every launch command — which is why the component name is built in one place rather than spelled out in each script.

**`scripts/build-all.ps1` runs the whole pipeline in dependency order** — `-List` prints the steps and what each one is waiting on, `-Install` puts the APK on the device. the ordering is real rather than editorial: `host/CMakeLists.txt` refuses to configure without `build/adrenotools/libadrenotools.a`, `app/build-app.ps1` refuses without `libsharpemu-host-layer.so`, and `guests/build-guests.ps1` refuses without the generated guest-side `libvulkan.so.1` and `libaaudio.so`. every step asserts the artefact it was supposed to produce rather than trusting that it returned quietly.

**staging is one script per thing** — `stage-build.ps1`, `stage-game.ps1`, `stage-guest-libs.ps1`, `stage-driver.ps1`, `stage-shell.ps1` — all verifying what landed rather than trusting the push. the first four take `-Package <application id>` and default to the debug app like everything else; `stage-shell.ps1` has no `-Package` because `/data/local/tmp` belongs to no app.

| artefact | built by | from |
| --- | --- | --- |
| a SharpEmu payload (the `android` branch, linux-x64) | `dotnet publish`, driven by `scripts/package-build.ps1` | `external/sharpemu`, or the checkout `SHARPEMU_FORK` names |
| a build directory and zip — payload, `plugins/`, `meta.json` | `scripts/package-build.ps1` | that publish output, **or `-FromArchive <path\|url>`**, which needs no fork, no .NET SDK and no git |
| `libFEXCore.a`, `libsharpemu-host-layer.so`, the `sharpemu-host-layer` shell binary | `host/build.ps1` (NDK, cmake, ninja) | this tree + the FEX checkout |
| the adrenotools hooks and static archives | `scripts/build-adrenotools.ps1` | the libadrenotools checkout |
| the guest-side `libvulkan.so.1` and `libaaudio.so` | `host/thunks/*/build-guest-*.ps1` | generated stubs |
| the APK — `build/app-debug/` by default, `build/app/` with `-Release` | `app/build-app.ps1`, driving gradle | the host layer's `.so`, the STL, the adrenotools hooks |

**the fork's own CI is not in that table, and that is accurate rather than an omission.** it builds every branch and uploads workflow artifacts, and it cuts releases only on `v*` tags; neither produces the build format this app imports.

**the packaging script lives here rather than in the fork on purpose.** it is an app tool with three lines of SharpEmu knowledge in it: it knows the app's package name, the `meta.json` schema the app reads, the on-device directory naming and the `hostContract` semantics. and a packager that lives inside the thing it packages gets versioned by its own input — checking out an older branch of the fork would hand you that branch's frozen copy of the packager.

## the workspace

several things this tree builds against are large, are somebody else's, or are the user's own files, and none of them are vendored here: the **android SDK and NDK**, the **JDK**, the **.NET SDK**, GPU driver packages and the test games. (the three submodules are the exception — they are pinned to an exact commit, which none of these are.)

**the simplest way to get the toolchains is to let the repository fetch its own:**

```powershell
.\scripts\fetch-toolchain.ps1            # prints the plan, downloads nothing
.\scripts\fetch-toolchain.ps1 -Install   # ~1 GB into .\toolchain\, which is gitignored
```

that needs nothing outside this directory and no environment variable. **`scripts/fetch-toolchain.ps1` never touches a machine-wide install or `PATH`** — it pulls Temurin from the Adoptium API, the android components through `sdkmanager`, and the .NET SDK through Microsoft's own `dotnet-install.ps1`, all into `toolchain/`.

the *workspace* — the directory this tree sits in — is searched as well, which is a convenience for anyone who keeps a clone beside their SDKs rather than an assumption that they do:

```
workspace/
├── sharpemu-android/     this tree
├── sharpemu/             a fork checkout of your own, if you keep one. reached by SHARPEMU_FORK,
│                         never found here -- external/sharpemu is what a build resolves to
├── FEX/  libadrenotools/ only consulted if external/ is empty
└── android-sdk/  jdk-*/  dotnet-sdk/, driver packages, games
```

set **`$env:SHARPEMU_WORKSPACE`** to point that elsewhere.

### finding a toolchain

**`toolchain.json`** declares every required version in one place, and **`scripts/toolchain.ps1`** resolves them. no build script contains a version number or a toolchain path of its own; each asks for what it uses, so a missing JDK cannot break the native build:

```powershell
. (Join-Path $PSScriptRoot "..\scripts\toolchain.ps1")
$tc = Resolve-Toolchain -Need Ndk, Cmake
```

per tool, first hit wins:

| | |
| --- | --- |
| 1 | **`SHARPEMU_ANDROID_SDK`, `SHARPEMU_NDK`, `SHARPEMU_JDK`, `SHARPEMU_DOTNET`, `SHARPEMU_FORK`** — project-scoped, so pointing one at your own copy disturbs nothing else on the machine |
| 2 | **`toolchain/`** in this repository — where `fetch-toolchain.ps1` installs |
| 3 | the workspace — `android-sdk`, `jdk-*` (globbed), `dotnet-sdk` |
| 4 | `ANDROID_HOME` / `ANDROID_SDK_ROOT`, `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT`, `JAVA_HOME`, `DOTNET_ROOT` |
| 5 | `PATH` |

**the SharpEmu fork is the one exception, and it has a two-layer order of its own**: `SHARPEMU_FORK`, then `external/sharpemu`. it is never fetched into `toolchain/` and never looked for in the workspace, so a fork checkout beside this tree is used only when `SHARPEMU_FORK` names it. every build says which of the two it resolved, unlike the other components, because two checkouts of one fork on a machine is the ordinary arrangement and which one a build came from has to be answerable from the log.

a working tree with `external/sharpemu` uninitialised is an error rather than a fallback, and the message names the way out — `git submodule update --init --recursive`.

**every hit is version-checked**, which is what makes layers 3 and 4 safe: a java 8 on `JAVA_HOME` is named and skipped rather than failing later inside `javac`, and a runtime-only .NET install is recognised as having no SDKs. layers 3 and 4 say what they picked. **an explicit `SHARPEMU_*` that fails its check is an error, never a silent fallback** — if you set it, you meant it.

the versions required, and why they are not incidental: **NDK 29.0.14206865** — r29 is a floor because FEXCore uses `std::atomic_ref`, which libc++ did not implement until LLVM 19, and this exact build is the one the turnip package was compiled with, so a different r29+ is accepted with a warning. **cmake 3.22.1** and ninja from the SDK. **build-tools 35.0.0**, **platform android-35**, **JDK 21**, and a **.NET SDK satisfying the fork's `global.json`** — 10.0.103 or a later 10.0.x. the APK targets API 35 with a minimum of API 28.

`fetch-toolchain.ps1` asks the resolver what is already present rather than guessing, and reports where it found each thing — "present" can legitimately mean "on your `PATH`, somewhere else entirely".

## licensing

**GPL-2.0-or-later**, matching the SharpEmu fork exactly.

that is a deliberate choice rather than a default. code moves across the boundary between this tree and the fork in both directions — a fix for the same bug can be written on either side — and matching licences means moving it is a copy-paste rather than a legal question, and anything written here stays upstreamable to SharpEmu. GPLv3 would have closed that direction, because a GPLv3 file cannot be contributed into a GPL-2.0-or-later project without forcing v3 on the combination. "or later" also keeps us compatible with the Apache-2.0 headers the thunk generators read.

dependencies keep their own licences and their texts are carried in `LICENSES/`: **FEXCore is MIT**, **libadrenotools is BSD-2-Clause**, both compatible.

**the licence is declared once, in `REUSE.toml`, and no source file carries an SPDX header.** that is a deliberate choice: the GPL's *"attach them to the start of each source file"* language is in its **How to Apply These Terms** appendix, which is advice rather than a condition — what the licence requires is a notice on each copy of the program, and the root `LICENSE` is that. one aggregate annotation keeps the tree machine-readable for a scanner and stays true on its own as files come and go, where a header per file is a chance per file to forget one — and the file count only grows.

**the fork is the exception, and it is not ours to change.** SharpEmu follows REUSE strictly and its CI rejects a new file without a header. code does move across that boundary in both directions, so **anything that crosses into the fork gains a header at the moment it crosses** — which is one line of work at the time it happens rather than a standing tax on every file here.

SharpEmu builds are GPLv2 binaries. the corresponding source is our fork, public, and each build's `meta.json` records the exact commit it was cut from. running one is aggregation rather than linking: SharpEmu is a guest process image executed under emulation, never a library the app links against.

## what is deliberately not here

- **games.** PS5 titles are the user's own dumps and never appear here
- the android SDK, the JDK and the .NET SDK. `toolchain/` is where they land and is ignored — `toolchain.json` is the committed declaration, `toolchain/` the materialisation, in the same relationship as `package.json` to `node_modules/`
- all build output
- **the staged x86-64 guest libraries.** they are Debian glibc, libstdc++ and openssl binaries and are LGPL; `guest-libs/fetch-guest-libs.ps1` fetches them, and they are not redistributed here
- a debug signing keystore. `app/build-app.ps1` generates one on demand. it is named explicitly as the debug signing config rather than left to gradle's per-machine `~/.android/debug.keystore`, because a device that already has the app installed refuses an update signed by a different key
- **gradle's own caches.** `.gradle/` and `local.properties` are per-machine and ignored; `gradle/wrapper/` and `gradle/libs.versions.toml` are the committed declarations, in the same relationship as `toolchain.json` to `toolchain/`
- **the maintainer's working notes.** the long-form investigation records — measurement logs, dated snapshots, root causes that turned out to be wrong — are kept privately. this repository documents how the project works; it is not the record of how each thing was found out
