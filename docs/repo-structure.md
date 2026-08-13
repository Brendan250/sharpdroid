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

- `hostContract`, the launcher↔payload interface generation, is emitted by `scripts/package-build.py` and checked by `app/src/main/java/.../SharpEmuBuild.java`. bumping it is one change in two files
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
│   ├── pad.md               the gamepad bridge, the wire format, rumble delivery
│   ├── app.md                the screens, the surface, the launch extras, the settings and merge
│   └── scripts.md            every script, and the arguments worth knowing
│
├── external/                 three pinned submodules
│   ├── FEX/                  FEXCore, and sixteen submodules of its own. never modified
│   ├── libadrenotools/       never modified
│   └── sharpemu/             the fork, pinned at the commit a bundled build is cut from. a pin
│                             rather than a workspace -- see below
│
├── host/                     the host layer — one cmake project
│   ├── CMakeLists.txt        assembles FEXCore for bionic, then builds the host layer twice
│   ├── include/              bionic-compat.h
│   ├── regression.sh         the on-device regression modes
│   ├── src/                  ELF loader, syscall dispatch, signal delegation, VMA/SMC tracking,
│   │                         and the file layer that answers a granted game directory
│   ├── thunks/vulkan/        the generated halves, and the host probe
│   └── thunks/audio/         the generated halves
│
├── settings.gradle.kts  build.gradle.kts  gradle.properties  gradlew  gradlew.bat
├── gradle/
│   ├── libs.versions.toml    the app's dependency versions, in one place
│   └── wrapper/              the gradle distribution the wrapper fetches
│
├── app/                      the android app
│   ├── build.gradle.kts      the APK: AGP, kotlin, androidx, Material3
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/mircowuffwuff/sharpemu/   java and kotlin, side by side
│       └── res/
│
├── guests/                   x86-64 test guests the host layer is exercised against
├── guest-libs/               the x86-64 shared objects the guest's linker searches
├── scripts/                  Python 3, standard library only
│   ├── run.py                build, stage and launch on a connected device. one command
│   ├── build.py              the whole pipeline, in dependency order
│   ├── fetch-toolchain.py    acquires the toolchains into toolchain/
│   ├── fetch-guest-libs.py   the x86-64 glibc set, out of debian packages
│   ├── build-adrenotools.py  build-host.py   build-guests.py   build-apk.py
│   ├── gen-thunks.py         regenerates both thunks from the NDK's headers
│   ├── build-thunks.py       assembles their guest halves
│   ├── package-build.py      a fork publish, or an archive, into a build with an identity
│   ├── stage.py              a build, a game, the guest libraries, a driver, the shell binary
│   ├── regression.py         stage, run the 15 host-layer modes, report
│   └── sharpemu/             the eight modules they share: shell, paths, toolchain, native,
│                             vocabulary, device, builds, resolve
├── toolchain.json            every required toolchain version, and where to get it
├── toolchain/                toolchains fetched into the repo. ignored
└── build/                    all local output. ignored
```

five notes on the shape:

- **the app is a gradle build, and the frontend is what that buys.** a game list and an Eden-shaped settings screen want Material3, RecyclerView, SAF and the rest, and hand-resolving that transitive dependency graph offline is a job with no end — which is what calling aapt2, javac, d8, zipalign and apksigner directly would mean. the trade is a maven graph and a gradle distribution against an offline one-command build. **`scripts/build-apk.py` is the entry point rather than `gradlew`** — it resolves the SDK and JDK through the toolchain resolver and writes `local.properties` from what it found, so the app step cannot disagree with the native step about which SDK it built against, and it copies the APK to the path every other script predicts

- **`.gitattributes` forces LF on everything**, and that is correctness rather than tidiness. `host/regression.sh` is pushed to an android device and run by `/system/bin/sh`, where a CR is a syntax error; git for windows sets `core.autocrlf = true` in its system config, so without the attribute a clone on windows would break them on checkout

- **`host/` builds FEXCore too.** FEX's own top-level `CMakeLists.txt` refuses to configure for android, but `FEXCore/` is a standalone project with no references to parent targets, so `host/CMakeLists.txt` assembles the dependency graph itself. every line of that glue is ours and the FEX checkout stays pristine
- **the thunks live under `host/`** rather than beside it. each is host-layer code that happens to emit a guest-side artefact as well
- **the public scripts are what a contributor needs to build, bundle and run, and nothing else.** an instrument that exists to profile one bug belongs to whoever is chasing that bug, and it is not carried here

## external dependencies

three **git submodules under `external/`**, each pinned to an exact commit:

| | pin | licence | what it is |
| --- | --- | --- | --- |
| [FEX](https://github.com/FEX-Emu/FEX) | tag `FEX-2607`, `1cc4b93e7` | MIT | the x86-64 translation core the host layer links |
| [libadrenotools](https://github.com/bylaws/libadrenotools) | `8fae8ce` | BSD-2-Clause | custom GPU driver loading |
| [the SharpEmu fork](https://github.com/sharpemu-android/sharpemu) | the `android` commit a bundled build is cut from | GPL-2.0-or-later | the emulator itself |

**FEX and libadrenotools are never modified**, and their submodules enforce that for free: a patched FEX shows dirty in `git status` the moment it happens, where a checkout beside the tree would go unnoticed. FEX bans AI-generated contributions, so a patch of ours could never go upstream and would become a permanent private delta against a fast-moving project.

**`--recurse-submodules` is not optional**: FEX carries sixteen submodules of its own — vixl, fmt, xxhash, range-v3, unordered_dense and the rest — and `host/CMakeLists.txt` fails to configure with a message about them if they are absent. a full recursive clone is around 840 MB, of which the fork is about 21 MB.

`host/CMakeLists.txt` and `scripts/build-adrenotools.py` resolve `external/<name>` first and fall back to a checkout in the workspace, which is what makes a working tree with those two submodules uninitialised still buildable for anyone who already has them.

### the fork's submodule is a pin, not a workspace

**it exists so the SharpEmu build an APK bundles is reproducible from a clone.** a build's `meta.json` records the commit it was cut from, but that is a receipt written from whatever the packager had checked out; nothing else would say which commit to build. the submodule is the repository naming it.

**the pointer names what ships** — the `android` commit the bundled build comes from — and nothing else. it does not constrain what anyone checks out: a `perf/` topic branch is checked out inside the submodule and packaged by hand like any other build, and the pointer is left where it is. so a single pointer describing a fork that is developed across several branches is not the contradiction it looks like. it is not describing the fork; it is describing the one branch that ships.

**it is not where the fork is developed.** the toolchain resolver resolves `SHARPEMU_ANDROID_FORK` first and the submodule second, so a checkout you commit in is reached by pointing that variable at it — the shape `go mod replace` uses, where the pin is what builds unless a local checkout is declared in writing. **the pin is the default rather than the fallback on purpose**: a pin no ordinary build ever takes is one nothing notices has gone stale, and the first person to find out would be somebody cloning this repository.

**`android`'s history is load-bearing.** every tag here names a commit in the fork, so an upstream release is merged into `android` and never rebased onto it. rewriting that history orphans the pointer in every earlier tag, and the symptom is a `git submodule update` failing on a fetch rather than anything visible in this tree.

**`scripts/build-apk.py` refuses a shippable APK bundling a build the pointer does not name.** that is the moment the pointer's claim becomes true or false, so it is where the claim is checked — and it is a refusal rather than a warning because a warning on a package that then succeeds is a build somebody keeps. because the submodule can only be moved to a commit it fetched, the same check also catches a build cut from a commit that was never pushed.

## where each artefact is built

**`scripts/run.py` does all of it in one command** — build, stage, launch, follow the log, and it needs no arguments. [`scripts.md`](scripts.md) documents every script and its arguments.

**development happens on the debug app, and that is the default everywhere.** `scripts/build-apk.py` and `scripts/build.py` rename the application id to **`com.mircowuffwuff.sharpemu.debug`** and the launcher label to *SharpEmu Debug*; `run.py`, `stage.py` and `regression.py` all drive that same id. android treats it as a separate app with its own storage and save data, so **nothing done while developing can reach a personal install of the release build on the same device**.

**`--release` builds under the manifest's own id and label**, `--package` and `--name` override the id and the label, and `--package` aims any of the other scripts elsewhere. `scripts/sharpemu/device.py` holds the rules — which app a script talks to, what it is labelled, and the component name a launch has to spell in full — so no two scripts can disagree about any of them.

**that last one is why the JNI note above matters in the tooling too.** an activity is `<application id>/<java package>.MainActivity` and the two halves move independently: the id is renamed per build, the java package never is. renaming the java package would break the native link, the two JNI symbols, the `-Wl,-u` in `host/CMakeLists.txt` **and** every launch command — which is why the component name is built in one place rather than spelled out in each script.

**`scripts/build.py` runs the whole pipeline in dependency order** — `--list` prints the steps and which of them are up to date, `--install` puts the APK on the device. the ordering is real rather than editorial: `host/CMakeLists.txt` refuses to configure without `build/adrenotools/libadrenotools.a`, `scripts/build-apk.py` refuses without `libsharpemu-host-layer.so`, and `scripts/build-guests.py` refuses without the generated guest-side `libvulkan.so.1` and `libaaudio.so`. every step asserts the artefact it was supposed to produce rather than trusting that it returned quietly.

**`scripts/stage.py` puts things on a device** — a build, a game, the guest libraries, a driver, the shell binary, any number of them in one command — and verifies what landed rather than trusting the push. it takes `--package <application id>` and defaults to the debug app like everything else; the shell binary is the exception, because the directory it goes to belongs to no app.

| artefact | built by | from |
| --- | --- | --- |
| a SharpEmu payload (the `android` branch, linux-x64) | `dotnet publish`, driven by `scripts/package-build.py` | `external/sharpemu`, or the checkout `SHARPEMU_ANDROID_FORK` names |
| a build directory and zip — payload, `plugins/`, `meta.json` | `scripts/package-build.py` | that publish output, **or `--from-archive <path\|url>`**, which needs no fork, no .NET SDK and no git |
| `libFEXCore.a`, `libsharpemu-host-layer.so`, the `sharpemu-host-layer` shell binary | `scripts/build-host.py` (NDK, cmake, ninja) | this tree + the FEX checkout |
| the adrenotools hooks and static archives | `scripts/build-adrenotools.py` | the libadrenotools checkout |
| the guest-side `libvulkan.so.1` and `libaaudio.so` | `scripts/build-thunks.py` | generated stubs |
| the APK — `build/apk/<application id>.apk` | `scripts/build-apk.py`, driving gradle | the host layer's `.so`, the STL, the adrenotools hooks |

**the fork's own CI is not in that table, and that is accurate rather than an omission.** it builds every branch and uploads workflow artifacts, and it cuts releases only on `v*` tags; neither produces the build format this app imports.

**the packaging script lives here rather than in the fork on purpose.** it is an app tool with three lines of SharpEmu knowledge in it: it knows the app's package name, the `meta.json` schema the app reads, the on-device directory naming and the `hostContract` semantics. and a packager that lives inside the thing it packages gets versioned by its own input — checking out an older branch of the fork would hand you that branch's frozen copy of the packager.

## the workspace

several things this tree builds against are large, are somebody else's, or are the user's own files, and none of them are vendored here: the **android SDK and NDK**, the **JDK**, the **.NET SDK**, GPU driver packages and the test games. (the three submodules are the exception — they are pinned to an exact commit, which none of these are.)

**the simplest way to get the toolchains is to let the repository fetch its own:**

```
py scripts/fetch-toolchain.py              # prints the plan, downloads nothing
py scripts/fetch-toolchain.py --install    # about 1 GB into .\toolchain\, which is ignored by git
```

that needs nothing outside this directory but Python itself, and no environment variable. **`scripts/fetch-toolchain.py` never touches a machine-wide install or `PATH`** — it pulls Temurin from the Adoptium API, the android components through `sdkmanager`, and the .NET SDK through Microsoft's own installer, all into `toolchain/`.

the *workspace* — the directory this tree sits in — is searched as well, which is a convenience for anyone who keeps a clone beside their SDKs rather than an assumption that they do:

```
workspace/
├── sharpemu-android/     this tree
├── sharpemu/             a fork checkout of your own, if you keep one. reached by
│                       SHARPEMU_ANDROID_FORK,
│                         never found here -- external/sharpemu is what a build resolves to
├── FEX/  libadrenotools/ only consulted if external/ is empty
└── android-sdk/  jdk-*/  dotnet-sdk/, driver packages, games
```

set **`SHARPEMU_ANDROID_WORKSPACE`** to point that elsewhere.

### finding a toolchain

**`toolchain.json`** declares every required version in one place, and **`scripts/sharpemu/toolchain.py`** resolves them. no build script contains a version number or a toolchain path of its own; each piece is found when it is first used, so a missing JDK cannot break the native build:

```python
from sharpemu import toolchain as tc

toolchain = tc.resolve().require("ndk", "cmake")
```

per tool, first hit wins, and there are only two:

| | |
| --- | --- |
| 1 | **`SHARPEMU_ANDROID_SDK`, `SHARPEMU_ANDROID_NDK`, `SHARPEMU_ANDROID_CMAKE`, `SHARPEMU_ANDROID_JDK`, `SHARPEMU_ANDROID_DOTNET`, `SHARPEMU_ANDROID_FORK`, `SHARPEMU_ANDROID_WORKSPACE`** — project-scoped, so pointing one at your own copy disturbs nothing else on the machine |
| 2 | **`toolchain/`** in this repository — where `fetch-toolchain.py` installs |

**`ANDROID_HOME`, `JAVA_HOME`, `DOTNET_ROOT` and `PATH` are deliberately not among them.** a resolver that falls back to whatever the machine happens to have is a resolver that quietly builds against something other than the pinned version — and gradle finding its own SDK that way is the exact disagreement this exists to prevent. a machine that would rather use its own says so in writing, through the variables above.

**the SharpEmu fork has an order of its own**: `SHARPEMU_ANDROID_FORK`, then `external/sharpemu`, then a checkout beside this tree. it is never fetched into `toolchain/`, because it is a source repository rather than a toolchain. every build says which of the two it resolved, unlike the other components, because two checkouts of one fork on a machine is the ordinary arrangement and which one a build came from has to be answerable from the log.

a working tree with `external/sharpemu` uninitialised is an error rather than a fallback, and the message names the way out — `git submodule update --init --recursive`.

**every hit is version-checked**, which is what makes layers 3 and 4 safe: a java 8 on `JAVA_HOME` is named and skipped rather than failing later inside `javac`, and a runtime-only .NET install is recognised as having no SDKs. layers 3 and 4 say what they picked. **an explicit `SHARPEMU_*` that fails its check is an error, never a silent fallback** — if you set it, you meant it.

the versions required, and why they are not incidental: **NDK 29.0.14206865** — r29 is a floor because FEXCore uses `std::atomic_ref`, which libc++ did not implement until LLVM 19, and this exact build is the one the turnip package was compiled with, so a different r29+ is accepted with a warning. **cmake 3.22.1** and ninja from the SDK. **build-tools 35.0.0**, **platform android-35**, **JDK 21**, and a **.NET SDK satisfying the fork's `global.json`** — 10.0.103 or a later 10.0.x. the APK targets API 35 with a minimum of API 28.

`fetch-toolchain.py` asks the resolver what is already present rather than guessing, and reports where it found each thing — "present" can legitimately mean "on your `PATH`, somewhere else entirely".

## licensing

**GPL-2.0-or-later**, matching the SharpEmu fork exactly.

that is a deliberate choice rather than a default. code moves across the boundary between this tree and the fork in both directions — a fix for the same bug can be written on either side — and matching licences means moving it is a copy-paste rather than a legal question, and anything written here stays upstreamable to SharpEmu. GPLv3 would have closed that direction, because a GPLv3 file cannot be contributed into a GPL-2.0-or-later project without forcing v3 on the combination. "or later" also keeps us compatible with the Apache-2.0 headers the thunk generators read.

dependencies keep their own licences and their texts are carried in `LICENSES/`: **FEXCore is MIT**, **libadrenotools is BSD-2-Clause**, both compatible.

**the licence is declared once, in `REUSE.toml`, and no source file carries an SPDX header.** that is a deliberate choice: the GPL's *"attach them to the start of each source file"* language is in its **How to Apply These Terms** appendix, which is advice rather than a condition — what the licence requires is a notice on each copy of the program, and the root `LICENSE` is that. one aggregate annotation keeps the tree machine-readable for a scanner and stays true on its own as files come and go, where a header per file is a chance per file to forget one — and the file count only grows.

**the fork is the exception, and it is not ours to change.** SharpEmu follows REUSE strictly and its CI rejects a new file without a header. code does move across that boundary in both directions, so **anything that crosses into the fork gains a header at the moment it crosses** — which is one line of work at the time it happens rather than a standing tax on every file here.

SharpEmu builds are GPLv2 binaries. the corresponding source is our fork, public, and each build's `meta.json` records the exact commit it was cut from. running one is aggregation rather than linking: SharpEmu is a guest process image executed under emulation, never a library the app links against.

**the guest libraries in an APK are the same kind of thing.** they are unmodified Debian binaries under LGPL and GPL-with-exception terms, they are what the *guest's* own dynamic linker searches rather than anything the app links against, and the directory can be replaced with any compatible set. `guest-libs/x86_64/licences.txt`, written by the fetch and packaged with them, names each package's licence and the Debian source it came from.

## what is deliberately not here

- **games.** PS5 titles are the user's own dumps and never appear here
- the android SDK, the JDK and the .NET SDK. `toolchain/` is where they land and is ignored — `toolchain.json` is the committed declaration, `toolchain/` the materialisation, in the same relationship as `package.json` to `node_modules/`
- all build output
- **the x86-64 guest libraries.** `guest-libs/x86_64/` is ignored: mostly Debian glibc, libstdc++ and openssl binaries, fetched by `scripts/fetch-guest-libs.py` rather than committed. **the APK does carry them**, since a device with no guest linker cannot start a game at all, and that makes it a redistribution — so the fetch writes a `licences.txt` beside them naming every package, its licence and where Debian keeps its source, `build-apk.py` refuses to package a set without it, and the file travels with the libraries wherever they go
- a debug signing keystore. `scripts/build-apk.py` generates one on demand. it is named explicitly as the debug signing config rather than left to gradle's per-machine `~/.android/debug.keystore`, because a device that already has the app installed refuses an update signed by a different key
- **gradle's own caches.** `.gradle/` and `local.properties` are per-machine and ignored; `gradle/wrapper/` and `gradle/libs.versions.toml` are the committed declarations, in the same relationship as `toolchain.json` to `toolchain/`
- **the maintainer's working notes.** the long-form investigation records — measurement logs, dated snapshots, root causes that turned out to be wrong — are kept privately. this repository documents how the project works; it is not the record of how each thing was found out
