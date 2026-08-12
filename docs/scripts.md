# scripts

every script in this repository, what it does, and the arguments worth knowing.

everything is Python 3 and there is no build system on top of it. every script is safe to run from the repository root, every script resolves its own location, and **none of them contains a toolchain path or a version number** — those live in [`toolchain.json`](../toolchain.json) and are resolved in exactly one place. where each artefact comes from, and how a toolchain is found, is [`repo-structure.md`](repo-structure.md).

## from nothing to a game on your phone

**you need Python and git. that is the whole list.** everything else — the JDK, the android SDK, the NDK, the .NET SDK — is fetched into this repository's own `toolchain/` by the first command below.

```
git clone --recurse-submodules https://github.com/sharpemu-android/sharpemu-android
cd sharpemu-android
py scripts/fetch-toolchain.py --install
py scripts/run.py --sharpemu build --game "D:/games/Dreaming Sarah"
```

**that last command is the whole build.** it publishes and packages the emulator from the fork, builds the host layer and everything under it, builds the APK with that build inside it, installs it on the attached device, puts the game and the guest libraries there, launches it and follows the log. it takes a while the first time; afterwards `py scripts/run.py --game existing` is the loop, and it needs nothing else said.

four notes, and then the rest of this document is detail:

- **Python 3.9 or newer, and only the standard library.** there is nothing to `pip install` and no virtual environment to activate.
- **on windows the command is `py`, not `python`.** a machine without Python installed answers `python` with an app-store stub that prints an install prompt and exits non-zero; the launcher does not have that problem.
- **`--recurse-submodules` is not optional.** the fork the emulator is built from is one of three submodules, and `--sharpemu build` has nothing to publish without it. already cloned without it? `git submodule update --init --recursive`.

### the pieces of that one command, when you want them separately

```
py scripts/package-build.py            publish and package the emulator from the fork
py scripts/build.py --install          the host layer, the thunks, the guests, the APK. installs it
py scripts/run.py --game existing      launch what is already on the device
```

**do the first one before the first `build.py`.** exactly one emulator build ships inside each APK, so the APK step needs one to bundle — and it **refuses** rather than quietly producing an APK with nothing in it. that refusal names these same commands.

`fetch-toolchain.py` never touches a machine-wide install and never modifies `PATH`. already have your own JDK, android SDK, NDK or .NET SDK? point `SHARPEMU_ANDROID_JDK`, `SHARPEMU_ANDROID_SDK`, `SHARPEMU_ANDROID_NDK` or `SHARPEMU_ANDROID_DOTNET` at it and that piece is left alone. run it with no arguments to see what it would fetch without fetching anything.

**no fork checkout, or no .NET SDK?** `py scripts/package-build.py --from-archive <a linux-x64 release archive> --id android` gives any published SharpEmu tree an identity, and needs neither.

## the everyday loop

| | |
| --- | --- |
| **`scripts/run.py`** | **build it, put it on the device, start it, show the log.** the one command you want most of the time |
| `scripts/build.py` | build everything in dependency order. `--list` prints the steps and which are up to date, `--install` installs the APK, `--clean` wipes what the native steps write, `--force` rebuilds even what is up to date, `--only <step>` runs one |
| `scripts/regression.py` | stage the shell binary and run the host layer's 15 regression modes on the device. **exits non-zero if any fail**, so it can gate anything |

### which app you are building

**every script here works against the debug app by default** — application id `com.mircowuffwuff.sharpemu.debug`, labelled *SharpEmu Debug*. that is a different app to android: its own internal storage, its own external files directory, its own save data, installed beside a release SharpEmu. so **nothing you do while developing can disturb a personal install on the same phone**, and you have to ask for the release identity rather than remember to ask for the debug one.

`--release` builds and acts on the manifest's own identity. `--package <application id>` names a third one. the two are mutually exclusive, and every script that acts on an *app* takes both.

**`regression.py` takes neither, and that is right rather than an omission**: it stages the shell binary to a directory that belongs to no app, which is the whole reason it can run the host layer without an APK at all. `--serial` is on every script that reaches a device, including that one.

**it does not mean a release *build type*.** only the debug build type is ever assembled — the two senses of the word are deliberately not the same thing here.

## existing, build, none, or a path

`--game`, `--sharpemu` and `--driver` read one vocabulary, the same in every script. **a value names a source; a flag never does.**

| | |
| --- | --- |
| `existing` | use what is already on the device. creates nothing, which is the whole difference from `build` |
| `build` | produce one from the fork checkout now. `--sharpemu` only |
| `none` | name nothing. for a driver that pins the platform's own over whatever the app has stored |
| *a path here* | staged if the device has not got those bytes, reused if it has |
| `/storage/emulated/0/…` | used where it lies. nothing is staged and nothing is copied |
| *omitted* | the script names nothing, and whatever is downstream answers |

**each script accepts the values that mean something for it and refuses the rest by name.** staging takes a path here and only that, because `existing` names nothing to copy and a device path names something already there. the APK build refuses a device path too, because the build it bundles has to be readable on *this* machine — `--install` afterwards is the only part of it that reaches a device at all.

**a bare name is not a value.** an id names a family of builds rather than one artefact, so answering with the newest of that id would let an older build beat one that was just staged. a path is how a build is named.

**omitting one is not "pick one for me".** it names nothing and lets the app answer:

- **no `--game`** — the app opens its game list and no guest runs. this is the frontend run, and `py scripts/run.py` with nothing else said is what asks for it
- **no `--sharpemu`** — no build is named, so the app runs what its build manager settled on, which on an untouched install is the build it ships with. **that is a choice the app holds across runs**, so a run naming no build is not necessarily a run on the bundled one
- **no `--driver`** — the app loads what its settings hold, which on an untouched install is the platform's own

`--restage` pushes over what the device has regardless of what the byte counts say. it is rarely needed, since a size mismatch restages by itself; it is the escape hatch for the one case a byte count cannot see, which is two different dumps or builds of exactly the same length.

## building the pieces

each of these is one job, and `scripts/build.py` runs them in this order. **the order is real rather than editorial** — every link in it is an actual refusal by the step that comes after.

| | |
| --- | --- |
| `scripts/fetch-guest-libs.py` | the x86-64 glibc set the guest's own linker searches, built out of debian packages. `--keep-packages` keeps the downloads |
| `scripts/build-adrenotools.py` | the GPU driver loading library. the host project imports it as a static library at configure time and will not configure without it |
| `scripts/gen-thunks.py` | regenerates both halves of both thunks from the NDK's headers. **the output is committed**, so this is what you run when the NDK moves rather than on every build. `--check` reports what would change and writes nothing |
| `scripts/build-thunks.py` | assembles the guest halves into `libvulkan.so.1` and `libaaudio.so`, beside the glibc set |
| `scripts/build-host.py` | the host layer: the library the app loads, and the same thing as a shell binary. `--clean` wipes first, `--probe` builds the host vulkan probe instead |
| `scripts/build-guests.py` | the x86-64 test guests the regression set runs. `--only <name>` builds one |
| `scripts/build-apk.py` | the APK, with exactly one SharpEmu build inside it. `--install` installs it afterwards, `--offline` makes gradle resolve everything from its cache or fail |

`py scripts/build.py --list` prints the whole sequence and says which steps are already up to date. it also reports when the committed thunk sources no longer match the NDK's headers, which is the only thing that would otherwise need someone to think of asking.

## putting things on a device

```
py scripts/stage.py --sharpemu build\builds\<a build>
py scripts/stage.py --game "Y:\games\Dreaming Sarah"
py scripts/stage.py --guest-libs
py scripts/stage.py --driver <a driver package>.zip --driver-name turnip
py scripts/stage.py --shell
```

more than one may be named in a single command. everything lands on the app's external storage, which is the volume `adb` can write and the app can read — except `--shell`, which goes to a directory belonging to no app and therefore takes no application id.

**existence is not sameness.** what decides whether something is already there is the byte count of its payload, never its name: a rebuilt artefact keeps its directory name, so "the folder is already there" would silently run yesterday's bytes.

**the on-device name of a build comes from its own metadata**, never from what the directory or zip is called here. a build directory or a zip of one both work; the zip is unpacked here rather than on the device.

## producing a SharpEmu build

```
py scripts/package-build.py                                        whatever branch the fork has checked out
py scripts/package-build.py --branch android                       the timestamp stamps itself
py scripts/package-build.py --no-publish                           repackage what is already published
py scripts/package-build.py --from-archive <path or url> --id android
```

it produces a **directory and a zip** under `build/builds/` and stops. producing a build and putting one on a device are two jobs, which is what lets a build packaged last week — or one somebody else packaged — be staged without republishing anything.

**`--from-archive` needs no fork checkout, no .NET SDK and no git.** that is the path a third party takes, and the one any automated job would take. what it cannot do is record a commit, so the build's `commit` is empty and its `source` names the archive instead.

the fork checkout is resolved by **`SHARPEMU_ANDROID_FORK`**, falling back to the `external/sharpemu` submodule and to nothing else. the submodule is a pin, nothing develops in it, and a checkout beside this repository is deliberately not in the order -- if it were, the pin would be the one path no machine ever took and would go stale with nothing to notice.

[`build-format.md`](build-format.md) is what a build is.

## shipping a build inside the APK

```
py scripts/build-apk.py                              bundles the newest under build\builds\
py scripts/build-apk.py --sharpemu <a build>         bundles that one
py scripts/build-apk.py --sharpemu none              no asset at all
py scripts/build-apk.py --release --sharpemu <dir>   the shippable identity
```

**bundling is the default and that is deliberate**: an APK without a build in it looks identical to one with it, right up to the moment you want to test the bundled build and find it is not installed. **nothing to bundle is a refusal, never a silent bundle-less APK.**

the asset is a plain directory tree rather than a zip — a zip inside an APK is compressed twice and the device pays to undo both. the first launch that resolves to it unpacks it.

two things a shippable APK refuses that a development one does not:

- **a build the submodule pointer does not name.** that pointer is what makes an APK reproducible from a clone. the development identity prints the mismatch and builds anyway, because it installs under its own application id and there is no clone to reproduce it from
- **a build packaged from an archive**, since it records no commit to check

both identities refuse a build whose contract generation the app does not speak, and the range is read out of the app's own source so that a script cannot bless a build the app will refuse.

## the shared package

`scripts/sharpemu/` is the half every entry point shares, eight modules: `shell` is how a script talks, runs things and refuses; `paths` is where every artefact in this repository is; `toolchain` resolves the compilers and SDKs; `native` is the cmake build both native steps use; `vocabulary` is the argument scheme; `device` is `adb` and the app's identity; `builds` reads the build format; and `resolve` turns one of the vocabulary's values into a thing on a device.

**one rule lives in one place** is the whole organising idea — the app's identity, an artefact's path, a build's on-device name and what each argument accepts are each resolved by one function every caller shares.

## one rule worth knowing

**a script refuses; it never prompts.** stdin here is a pipe under anything driving these scripts, where a prompt either throws or reads EOF — and a naive prompt takes EOF for a yes. so a refusal names the words that resolve it, which is an instruction a person and a pipe can both act on. a refusal exits **2**; a tool one of these ran failing exits with whatever that was.

and **a step that returned zero is not a step that produced something**. every script asserts the artefact it was supposed to produce rather than trusting an exit code, because a tool exiting cleanly having done nothing is the most common failure shape here.
