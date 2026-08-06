# the app

the APK: one activity holding a `SurfaceView`, and a guest running underneath it. the activity gets a window from android, hands it down, chooses a build and a driver, assembles an argument vector and calls into the host layer, which blocks until the guest is done.

**it is a launcher rather than a frontend.** there is no game list, no settings screen, no file picker and no overlay. every choice a run makes is a launch extra with a compiled-in default, and a run is started by an intent. that is the whole shape of it, and everything below describes it as it is rather than as something on the way somewhere.

`app/src/main/AndroidManifest.xml`, three java files and two kotlin files are all of it, with `host/src/entry_jni.cpp` on the other side of the JNI boundary — and, since a game can come from a grant rather than a path, `GuestFiles.kt` on the *other* side of it, called from the host layer rather than into it. [`host-layer.md`](host-layer.md) describes everything below `RunMain`; [`guest-files.md`](guest-files.md) describes that callback and what it costs; [`build-format.md`](build-format.md) describes what the app installs and launches; [`repo-structure.md`](repo-structure.md) says where the APK is built and under which application id, and [`scripts.md`](scripts.md) says how to drive any of it.

## three invariants

**the argument vector is the whole interface.** everything the app decides becomes a flag or an `--env` on the vector handed to `RunMain`, so a run in the app takes the same arguments a run from `adb shell` does and the two stay comparable — which is also what lets a JIT problem be bisected outside an app entirely. two things are *not* arguments, and both are things a string cannot carry: the surface, because it is a live object, which is the whole reason `HostLayer` has a second native method; and a game that came from a grant, because answering for it means the host layer calling *back* into the app, file by file, for as long as the guest keeps asking. the mount those answers appear at is still a flag.

**the app decides and the host layer runs.** build resolution, `meta.json`, the contract check and the driver install all live above the JNI boundary. the host layer takes a payload path and stays a thing that runs an ELF, so the regression set and every bisect command still name a path and none of them grew a mode for any of this. it is also where a build list would need all of it anyway, and two implementations of one contract is one too many.

**every choice is a launch extra rather than a constant.** comparing two builds, two drivers or two SMC tracking modes is a loop over `am start` and not an APK rebuild per candidate. the defaults in `MainActivity` are what a launch naming nothing gets, so two runs differ by exactly the extras between them.

## the build

**the app is a gradle build**, on AGP with the kotlin plugin, and `app/build-app.ps1` is the entry point rather than a wrapper that may be skipped: it resolves the SDK and JDK through `scripts/toolchain.ps1` and writes `local.properties` from what it found, so the app step and the native step cannot build against different SDKs. [`repo-structure.md`](repo-structure.md) has why gradle is here at all and [`scripts.md`](scripts.md) has the flags.

**the dependency set is modelled on Eden's android frontend** — Material3, RecyclerView, ConstraintLayout, SAF through `documentfile`, `preference`, Navigation, SwipeRefreshLayout, Coil — and the versions are Eden's too, because the UI is modelled on Eden's and reading one of its adapters against ours is worth more than being on the newest of everything. Eden's shipping frontend is view-based with `viewBinding` rather than Compose, and this one follows it. `gradle/libs.versions.toml` is the single declaration.

**only the debug build type is ever assembled**, including for `-Release`. the two senses of the word are deliberately not the same thing here: `-Release` means the manifest's own application id and label, not an optimised non-debuggable build.

**the previous APK is deleted before gradle runs, and that is not tidiness.** AGP updates the archive in place, so an entry that changes size is appended and the old bytes stay where they are — an APK rebuilt through a day of work fills with holes nothing ever reads. it installs and runs perfectly, which is why it goes unnoticed; it was measured at **10,055,013 bytes of dead space in a 39 MB file whose entries come to 29 MB**, one hole of it 8.96 MB. deleting one file costs a repackage of about a second and no recompilation, and it is what makes the APK size a property of the source rather than of how many times it was built. **measure an APK from a clean package or not at all.**

## the manifest

| | |
| --- | --- |
| API | `minSdk` 28, `targetSdk` 35, `compileSdk` 35 — in `app/build.gradle.kts`, not the manifest |
| the activity | one, `MainActivity`, exported, with a `MAIN`/`LAUNCHER` filter — so the launcher icon and `am start` reach the same thing |
| orientation | **locked landscape**, and `configChanges` claims orientation, screen size, layout, density and UI mode so the activity is never recreated under a running guest |
| theme | `Theme.Black.NoTitleBar.Fullscreen` |
| `extractNativeLibs` | **on**, for two independent reasons — as `packaging { jniLibs { useLegacyPackaging } }` |
| `debuggable` | true, and it comes from the debug build type. hardcoding it in the manifest is a lint error |

the manifest carries the activity, the theme and the label, and nothing else: the application id, the SDK levels, `extractNativeLibs` and `debuggable` all moved into `app/build.gradle.kts` when the build became a gradle one. the label is `@string/app_name`, generated by `resValue` from the identity the script passes, so a renamed build does not mean a rewritten manifest.

**`extractNativeLibs` is on for a size reason and turns out to be an adrenotools requirement.** the host layer is a large `.so` and leaving it compressed in the zip is simpler than the uncompressed page-aligned layout an in-place load wants, which costs install time once rather than launch time every time. and adrenotools needs its hooks to exist as real files in the app's `nativeLibraryDir`, which is exactly what extraction produces — so one flag satisfies a packaging convenience and a hard requirement of the driver path at the same time.

the APK carries the dex files, the host layer's `.so`, `libc++_shared.so` and the two adrenotools hooks, all `arm64-v8a`. **the four native libraries are collected by a gradle task from three places** — build output for the host layer and the hooks, the NDK for the STL — because none of them live in a source directory. the STL is packaged rather than assumed: the host layer links `c++_shared`, and the copy in the APK has to be the one it was linked against.

**they ship unstripped, deliberately**, which costs about 7 MB on a build only ever installed over adb and keeps a native backtrace from being a list of addresses. AGP strips by default and is currently failing to, so it is pinned with `keepDebugSymbols` rather than left to an accident.

`build-app.ps1` asserts every expected entry is present before it reports success, because an APK missing its dex installs and then dies at `ClassNotFoundException` and one missing the native library dies at `UnsatisfiedLinkError` — both a long way from the packaging step that caused it. a missing adrenotools hook is worse than either, since adrenotools then falls back to the stock driver quietly and a driver comparison measures the same driver twice.

## the surface

`MainActivity` implements `SurfaceHolder.Callback`, and the callbacks are where everything with a lifetime happens.

| | |
| --- | --- |
| `surfaceCreated` | **deliberately empty.** `surfaceChanged` always follows it and is the first point with a size |
| `surfaceChanged` | hands the surface down, records the size, and starts the guest thread the first time |
| `surfaceDestroyed` | hands down null |

the guest thread is started **once**, from the first `surfaceChanged`, and named so it is identifiable in a thread dump. `nativeRun` blocks for the whole run, so it can never be the UI thread; a later `surfaceChanged` re-attaches to the same running guest rather than starting a second one.

**the system bars are not decoration here.** they shrink the surface, and the surface is what decides the extent the guest renders at — a visible navigation bar means the guest presenting into a buffer shorter than the panel. so the activity goes immersive on creation and again on every focus gain, since android restores the bars after a number of interactions whatever was asked for once.

`FLAG_KEEP_SCREEN_ON` is set because a game boot is minutes of work with no touch input, and the screen going off takes the surface with it.

**the activity handles no input.** there is no touch, key or gamepad handling anywhere in it.

### the window, and where it stops being the app's

`nativeSetSurface` turns the java `Surface` into an `ANativeWindow` and gives it to the vulkan thunk, releasing whatever it held before. that is the app's entire involvement with the window: [`vulkan.md`](vulkan.md) owns everything from the `ANativeWindow` inward — the surface, the swapchain and the extent authority.

**the guest is not told when the window goes away.** a null surface leaves the guest running and its presents becoming no-ops, which is the point of the host layer owning that side: surface loss and restore work, in one process, without the guest learning anything happened.

what does not work is a surface that comes back a *different size*, and that is why the activity locks to landscape. the guest decides its presentation size once, at swapchain creation; an extent mismatch under a swapchain it already created makes the presenter conclude the drawable was resized on every frame, so it recreates the swapchain forever and never renders — silently, with no call returning an error.

## the launch extras

all of them are read in `onCreate`, because the intent is not readable from a worker thread. an extra that is absent means the compiled-in default, and **nothing persists between launches**: there is no settings store, so a run is exactly what its intent says plus the defaults.

| extra | | default |
| --- | --- | --- |
| `--es game <name>` | a directory under `games/` on external storage, `eboot.bin` appended | a hardcoded title |
| `--es sharpemu <path>` | an **absolute path** to a build directory | the most recently staged build |
| `--es driver <name>` | a staged adrenotools package under `gpu-drivers/`. `stock` and the empty string both mean the platform's own driver | the stock driver |
| `--es driverenv A=1,B=2` | comma-separated, one `--vulkan-driver-env` each. mesa's knobs, which reach the *host* process | none |
| `--es guestenv A=1,B=2` | comma-separated, merged into the guest environment. these reach SharpEmu | none |
| `--es smc none\|mtrack\|full` | the host layer's SMC tracking mode, validated against those three | `mtrack` |
| `--ez profile true` | the vulkan thunk's profiling | off |
| `--ez turbo true` | pins the GPU clocks. a thermal and battery trade rather than a free win, which is why it is opt-in | off |
| `--ez audiowatchdog true` | the audio thunk's periodic stream report | off |
| `--ez tracefiles true` | counts what the guest asks of the game's own directory — the game's, not the one above it, so a second staged title cannot land in the counts | off |
| `--es safgame <name>` | a directory inside a **granted tree** instead of a staged one, which mounts the guest file layer and hands the guest an invented path. absent, the game is a path and no interception is registered at all | absent |

**`smc` and `turbo` are the two whose defaults a measurement rests on**: both change how the whole run behaves rather than what it does, so a comparison is only attributable to the extra that moved if neither of these is the thing that moved with it.

**an unrecognised `smc` value is ignored rather than passed on**, so a typo runs the default rather than reaching the host layer as a bad argument. a `guestenv` entry that is not `NAME=VALUE` is reported and skipped for the same reason.

## choosing a build

[`build-format.md`](build-format.md) owns the format, the `hostContract` check and the selection rule — a path runs that directory where it lies, nothing at all means the most recently staged build, and a bare id is refused outright. `SharpEmuBuild.java` is where all of that is implemented.

what belongs to the app around it:

- **the launch log names the build** — display name, id, version, build number, contract and directory, plus its `notes` line if it has one. that is not decoration. a third-party build misbehaving arrives as "your emulator is broken", so a run has to be traceable to the artefact that produced it without asking the person who ran it
- **the resolved build's `env` becomes the lowest-precedence tier** of the environment merge below
- **resolution by id is implemented and unreachable from an intent.** it is what a build list resolves with — a user picking one build per id wants the newest of that id, which is exactly the answer a deploy loop must not get — and it carries the install-onto-internal-storage step that a list needs and a path launch does not

the app also checks that the game's `eboot.bin` and the staged guest libraries exist before it starts anything, and names the staging script for whichever is missing. a guest that starts and then cannot find its libraries fails much further from the cause.

## the guest's environment

the four launcher variables are [`build-format.md`](build-format.md)'s and are defined there, along with the precedence order. what is the app's is **where each value comes from**, and the answers are not alike:

| | |
| --- | --- |
| `SHARPEMU_HOST_WINDOW` | a constant. there is one right answer inside an app and nothing to choose from |
| `SHARPEMU_HOST_AUDIO` | a constant, for the same reason |
| `SHARPEMU_HOST_WINDOW_SIZE` | **the surface**, as reported to `surfaceChanged`. the host has the window and the guest does not, so the size travels from here rather than being agreed by two separately hand-set defaults |
| `DOTNET_EnableWriteXorExecute` | a constant, and .NET's own rather than SharpEmu's |

the merge is a **map**, seeded with the build's `env`, overwritten by those four, then overwritten by anything from `guestenv`, and only then turned into `--env` flags. that ordering is the precedence order, and the map is what makes it work: two `--env` arguments naming one variable would be a coin toss over which value the guest reads.

**a build may set guest environment and nothing else** — no SMC mode, no signal delivery mode, none of the vulkan family. those are properties of the host layer's correctness, and the app enforces it by construction rather than by validation, since a build's `env` becomes `--env` and can become nothing else.

## the two directories a run needs

| | |
| --- | --- |
| `--libs` | the staged x86-64 shared objects, on **external** storage — where `adb` can write and the app can read |
| `--tmp` | the app's cache directory, on **internal** storage |

**the payload does not live in `/data/local/tmp` and cannot.** that is `shell_data_file` and SELinux denies an app's domain access to it — same device, same files, unreachable. the app's own external files directory is the one place both `adb shell` and the app can see without a permission or a picker.

that volume is `noexec` and **it does not matter**, which is a property of the host layer rather than luck: guest images are mapped anonymous and read into rather than mapped from a file, and `PROT_EXEC` never reaches the host kernel at all. nothing in a payload is ever executed as a file. a conventional loader would be stuck here.

**the temp directory is internal on purpose.** .NET reaches for `TMPDIR` far more often than for its own bundle, and the external volume is FUSE-backed on android 11+, so every file operation there is a userspace round trip. the payload is large sequential reads and does not care; thousands of small runtime file operations do.

## the driver

**a staged driver package is copied onto internal storage before it is used**, and the copy is not ceremony. adrenotools stats the driver and then `dlopen`s it, and the linker refuses a library from anywhere another app could have written — which is what external storage is. it is also FUSE-backed and the package is large, which is the second reason not to load it in place.

the install directory is **per driver**, so switching between two packages cannot leave the previous one's library sitting in the directory being pointed at. the copy is skipped when the installed file already matches the staged one in length, which is enough to notice a re-staged driver and cheap enough to check every launch.

**the package names its own library** in its `meta.json`, so nothing in the app knows or cares what any particular driver's `.so` is called, and the driver's name and version are logged once.

the app then passes `--vulkan-driver` and `--vulkan-hooks` together or neither. the hooks path is `nativeLibraryDir` and can only be `nativeLibraryDir` — the app is the only thing that knows it, which is why it is passed down rather than derived below. [`vulkan.md`](vulkan.md) describes what adrenotools does with the two.

**with no driver staged the flags are simply not passed**, and the host layer opens the platform loader exactly as it does from a shell. that is what keeps the stock-driver baseline reproducible from the same build rather than merely equivalent.

## the log

everything the host layer and the guest print goes to stdout and stderr, and in an app both are `/dev/null`. rather than convert several thousand `printf` calls, `entry_jni.cpp` puts a pipe under the two descriptors on first run and pumps it into logcat on a detached thread.

**a run in the app then produces exactly the log a run in a shell does**, which is the only reason output from the two can be read against each other at all. lines are broken up at around 3800 bytes, because logcat drops a message past roughly 4000 and the guest can produce a longer one — a .NET stack trace does — and losing it silently costs a debugging round.

the activity's own lines are prefixed `[app]`, alongside the host layer's `[host-layer]`, `[vulkan]` and `[audio]`. `--timestamps` is passed on every launch, so every guest line carries elapsed time since process start while the app's and the host layer's stay unstamped and therefore instantly distinguishable.

## what the app never passes

worth stating, because each is a default reached by omission rather than by choice being absent:

- **no `--vulkan-wsi`**, so the host layer's `auto` decides — and it decides android WSI, because there is a window
- **no `--asyncsig`**, so signal delivery is the default `syscall` mode
- **no `--trace` or `--trace-signals`**. both are shell-side debugging and there is no extra for them
- **nothing at all for audio beyond the flag.** AAudio is a pure NDK C API, so there is no JNI, no looper and no permission to request, and the activity never learns audio exists. it is the shortest way to say what the surface path costs by contrast

## where the app stops

`nativeRun` blocks for the whole run and returns the host layer's status, which is logged. that is the end of the activity's involvement.

**a guest that calls `exit_group` does not return from it at all** — the host layer calls `::_exit`, described in [`host-layer.md`](host-layer.md), and in an app that takes the process with it. so a finished game is a closed app, and there is nowhere to go back to. there is no second run without a second launch.
