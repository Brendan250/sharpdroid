# scripts

every script in this repository, what it does, and the flags worth knowing.

everything is a PowerShell script and there is no build system on top of them. they are all safe to run from the repository root, they all resolve their own location, and **none of them contains a toolchain path or a version number** — those live in [`toolchain.json`](../toolchain.json) and are resolved by `scripts/toolchain.ps1`. where each artefact comes from, and how a toolchain is found, is [`repo-structure.md`](repo-structure.md).

## start here

```powershell
.\scripts\fetch-toolchain.ps1            # what it would download, and from where. downloads nothing
.\scripts\fetch-toolchain.ps1 -Install   # ~1 GB into .\toolchain\, which is gitignored
.\scripts\build-all.ps1                  # build everything
.\scripts\run.ps1 -Game "D:\games\Dreaming Sarah [PPSA02929]"
```

`fetch-toolchain.ps1` installs a JDK, the android SDK components and a .NET SDK **into this repository's own `toolchain/`**. it never touches a machine-wide install and never modifies `PATH`. already have your own? point `SHARPEMU_JDK`, `SHARPEMU_ANDROID_SDK`, `SHARPEMU_NDK` or `SHARPEMU_DOTNET` at it instead.

**you also need a SharpEmu build to run.** `scripts/package-build.ps1` turns a publish of the [fork](https://github.com/sharpemu-android/sharpemu) — or any linux-x64 release archive — into a build the app can run or import, and `scripts/run.ps1 -BuildSharpEmu` does it for you.

## the everyday loop

| | |
| --- | --- |
| **`scripts/run.ps1`** | **build it, put it on the device, start it, show the log.** the one command you want most of the time |
| `scripts/build-all.ps1` | build everything in dependency order. `-List` prints the steps, `-Install` installs the APK, `-Clean` wipes the native build directories, `-Force` rebuilds even what is up to date, `-Release` builds the APK as the real app |
| `scripts/regression.ps1` | stage the shell binary and run the host layer's 15 regression modes on the device. **exits non-zero if any fail**, so it can gate anything |

### which app you are building

**every script here works against the debug app by default** — application id `com.mircowuffwuff.sharpemu.debug`, labelled *SharpEmu Debug*. that is a different app to android: its own internal storage, its own external files directory, its own save data, installed beside a release SharpEmu. so **nothing you do while developing can disturb a personal install on the same phone**, and you have to ask for the release identity rather than remember to ask for the debug one.

that is true of the whole set, not just the two that build an APK: **`run.ps1`, the `stage-*.ps1` scripts, `soak.ps1`, `compare-builds.ps1` and `compare-drivers.ps1` all default `-Package` to the debug id**, and it is resolved in one place — `Resolve-AppPackage` in `scripts/toolchain.ps1` — so the tooling cannot disagree with itself about which app it is driving. pass `-Package` to aim elsewhere, including at the release id.

**the component name to launch is `Resolve-AppActivity`, in the same file, and it is not the shorthand.** an activity is `<application id>/<java package>.MainActivity`, and those are two different things: `--rename-manifest-package` moves the id and leaves the java package alone. so `<id>/.MainActivity` expands to `<id>.MainActivity` and resolves to nothing under any renamed id.

| what you pass | application id | label |
| --- | --- | --- |
| nothing | `com.mircowuffwuff.sharpemu.debug` | SharpEmu Debug |
| `-Release` | the manifest's own | the manifest's own |
| `-Package <id>` and/or `-Name <label>` | what you passed, the other from the manifest | |
| `-Release` **and** either of those | what you passed — **`-Release` does nothing** | |

**where the APK is written follows the application id, and `-OutName` is the one part of that a caller can set.** `Get-ApkArtefact` puts a renamed build at `build/app-<leaf>/sharpemu-android-<leaf>.apk`, where the leaf is the id's last component — so two application ids cannot overwrite each other's output, and every script predicts the path the same way. `-OutName` replaces that leaf, for the case where two builds share a last component and would otherwise collide.

naming a build replaces the whole default pair rather than half of it, which is what makes `-Release` alongside `-Package` or `-Name` a no-op instead of something surprising: the alternative would hand `-Release -Name "SharpEmu Nightly"` the *debug* application id. the rule lives in `Resolve-AppIdentity` in `scripts/toolchain.ps1`, so `build-app.ps1` and `build-all.ps1` cannot disagree about it — including about where the APK is written, since two application ids must not overwrite each other's output.

`run.ps1` passes the debug identity explicitly and is unaffected by `-Release`; it is a debug deploy loop by definition. give it `-Package` and `-Name` if you want it pointed somewhere else.

```powershell
.\scripts\run.ps1                                        # reuse what is already staged
.\scripts\run.ps1 -Game "D:\games\Dead Cells [PPSA15552]"
.\scripts\run.ps1 -SharpEmu C:\wip\publish\linux-x64     # a bare folder: SharpEmu + plugins/
.\scripts\run.ps1 -SharpEmu .\build\builds\android-0.0.3-hotfix-2-b1.zip
.\scripts\run.ps1 -BuildSharpEmu                         # publish and package the fork first
.\scripts\run.ps1 -Driver ..\Turnip_Gen8_V33.zip -Turbo
.\scripts\run.ps1 -Seconds 90                            # a timed run, summarised at the end
.\scripts\run.ps1 -Check                                 # run the regression set first
.\scripts\run.ps1 -NoGame                                # the app's own game list, no guest
```

| flag | |
| --- | --- |
| `-Game <path>` | a path to a game directory. its last component is the name on the device. **omitted, it picks one off the device** — see below |
| `-NoGame` | open the app's game list and run no guest — see below |
| `-SharpEmu <folder\|zip>` | a path to a build. **omitted, it reuses whatever is already staged** — see below |
| `-BuildSharpEmu` | publish and package the fork checkout first |
| `-Driver <path\|name>` | a path to a driver package, or the name of one already on the device. **omitted, the platform's own Adreno driver** |
| `-Turbo`, `-GuestEnv`, `-Smc`, `-FexPreset` | passed to the app as launch options |
| `-Seconds N` | run for N seconds, then stop and summarise. otherwise it follows the log until the process exits or you Ctrl-C |
| `-Restage` | push `-Game`, `-SharpEmu` and `-Driver` over what the device has regardless of size |
| `-NoBuild`, `-NoLogs`, `-Check` | |

**omitting `-SharpEmu` is the default on purpose.** rebuilding the emulator every time you change one line of the host layer moves two variables per iteration and hands you a different payload from the one your last measurement used. `-BuildSharpEmu` is the explicit opt-in.

**when nothing at all is staged it asks** rather than telling you to run the same command again with one more flag: `publish and package the fork now? [Y/n]`, where Enter or `y` does what `-BuildSharpEmu` does and anything else stops. **if that packaging fails — no fork checkout, a .NET SDK that does not satisfy `global.json`, a branch that will not build — the run aborts**; it does not fall through and launch whatever happened to be staged. and it never prompts into a redirected stdin, which reads EOF instantly and would look like an answer: piped or under CI it refuses and names the two flags instead.

**omitting `-Game` picks one off the device.** `Dreaming Sarah` if it is staged, since every measurement in this project is against it and the match is loose (`*Dreaming Sarah*`, because the directory carries your own dump's title id); otherwise any staged game, chosen in sorted order so it is the same one every time; and if nothing is staged, it says so and stops. **the line it prints always says which it took and why**, because a run attributed to the wrong game is worse than no run.

**`-NoGame` is the frontend run.** it starts `GameListActivity` instead of `MainActivity`, so the app opens on its list and no guest process is ever forked — which is what you want when the thing you are looking at is a screen rather than a game. it skips everything only a guest launch needs: no game is resolved, and no build or driver either unless you name one, so a device with nothing staged is a perfectly good device to open the frontend on and there is no question to answer. `-SharpEmu`, `-BuildSharpEmu` and `-Driver` still stage what they name. **the flags a guest reads are refused rather than dropped** — `-Game`, `-Turbo`, `-GuestEnv`, `-Smc` and `-FexPreset` all name something the list neither receives nor honours, so passing one alongside `-NoGame` stops with the reason instead of starting a run that quietly is not the one asked for. the log still follows, and its summary reports no frames, which is correct.

## you type a PC path, never a device path

**this is one rule and it covers every script here.**

- **naming something means a path on this machine** — a build directory or zip, a game directory, a driver package. `-SharpEmu`, `-Game`, `-Driver`, `-Builds`, `-Drivers` and `-Build` all read this way, and **the path on the device is computed from it and never typed**
- **it is staged if the device does not already have it, and reused if it does** — where "has it" compares the **payload's byte count**, not the name. on a mismatch it restages by itself and says why. `-Restage` pushes over it regardless, and is the escape hatch for the one case that defeats a byte count: two artefacts that happen to be the same length
- **omitting it means "whatever the device already has"**, per build, game and driver alike. there is nothing to stage in that case and so nothing to name

so `-Restage` is the word everywhere — not `-Force`, which means "rebuild what is up to date" on `build-all.ps1`. `stage-game.ps1` answers to `-Force` as an alias.

**the on-device name of a build comes from its `meta.json`**, `<id>-<version>-<packagedAt>`, and never from what the directory or zip is called on your disk. that is also what labels a row in `soak.ps1` and a column in `compare-builds.ps1`, because a full device path would be the whole table.

**the app does not accept a build id.** `--es sharpemu` takes an absolute path to a build directory, or nothing, which means whatever the build manager settled on. **`--es sharpemu android` fails rather than resolving**, and that is the point: an id names a family and not a build, so resolving one means answering with the newest of it — and a freshly staged build then loses to a later-stamped one still lying around, which is a plausible run attributed to the wrong artefact with nothing erroring. a path cannot be ambiguous about which directory it meant.

**`hostContract` does not gate that**, so a refusal carries no version signal explaining itself. that is deliberate: the contract gates the *payload*, and this is a rule on the launcher's side of the line that leaves every build byte-for-byte compatible either way. bumping it would refuse working builds by name — a false negative in the mechanism built to prevent false negatives.

**the platform's own GPU driver is the absence of a name rather than a name.** it is not a file, so it cannot be a path, and no script takes `stock` as a sentinel. for `compare-drivers.ps1` that means stock is the implicit control and is always run, and each `-Drivers` entry adds a driver to compare against it.

## building the pieces

`build-all.ps1` runs these in order and skips the ones whose output already exists. run them individually when you are working on one.

| | |
| --- | --- |
| `guest-libs/fetch-guest-libs.ps1` | downloads the debian x86-64 glibc set the guest's own `ld.so` searches. `-KeepDebs` keeps the packages |
| `scripts/build-adrenotools.ps1` | libadrenotools, for custom GPU driver loading. `host/CMakeLists.txt` will not configure without it |
| `host/thunks/vulkan/build-guest-vulkan.ps1` | assembles 623 x86-64 stubs into the `libvulkan.so.1` the guest links against |
| `host/thunks/audio/build-guest-aaudio.ps1` | the same for 72 AAudio entry points |
| `host/build.ps1` | **FEXCore for bionic plus the host layer**, as a JNI library and a shell binary. `-Clean`, `-BuildType`, `-ApiLevel` |
| `guests/build-guests.ps1` | the nine x86-64 test guests `regression.sh` exercises the host layer with |
| `app/build-app.ps1` | the APK, by driving gradle. `-Install`, `-Release`, `-Package <id>`, `-Name <label>`, `-OutName <leaf>`, `-Offline`, `-BundleSharpEmu <build directory>` — see [which app you are building](#which-app-you-are-building) and [shipping a build inside the APK](#shipping-a-build-inside-the-apk) |

the ordering is real rather than editorial — the host cmake project refuses without `build/adrenotools/libadrenotools.a`, `build-app.ps1` refuses without `libsharpemu-host-layer.so`, and `build-guests.ps1` refuses without the generated guest-side libraries.

**call `build-app.ps1` rather than `gradlew` directly.** it resolves the SDK and JDK through `toolchain.ps1` and writes `local.properties` from what it found, generates the debug key if it is missing, passes the application id and label, and copies the APK to the path every other script predicts. `gradlew` on its own finds its own SDK through `ANDROID_HOME`, which is exactly the disagreement the resolver exists to prevent. it also points `TEMP` at a directory inside `build/`, without which gradle does not start at all on some machines: the JDK builds a NIO selector's wakeup pipe out of an AF_UNIX socket placed under `%TEMP%`, and where `connect` on such a socket fails, gradle reports it as `Unable to establish loopback connection` — which names TCP loopback and is the wrong component entirely.

**regenerating the thunks** is separate, because their output is committed and only changes when the NDK's headers do:

```powershell
.\host\thunks\vulkan\gen-thunk.ps1     # both halves, from the NDK's vulkan_core.h
.\host\thunks\audio\gen-thunk.ps1      # both halves, from aaudio/AAudio.h
```

**one more script is outside the pipeline on purpose.** `host/thunks/vulkan/build-probe.ps1` builds a host-side vulkan probe for bionic arm64 — it links nothing, takes two seconds, and answers questions about the *host's* vulkan rather than about the guest's, which is why it is not a step in `build-all.ps1`.

## putting things on a device

one script per thing, because they change on completely different cadences. every one of them **verifies what landed** rather than trusting that the push returned.

| | |
| --- | --- |
| `scripts/stage-build.ps1 -Build <dir\|zip>` | a packaged SharpEmu build. the on-device folder name comes from its `meta.json`, never from the file name — it is pushed into a scratch directory and renamed into place, because `adb push` would otherwise name it after the source directory |
| `scripts/stage-game.ps1 -Game <path>` | a game. reused when the device's `eboot.bin` is the same size; `-Restage` pushes over it |
| `scripts/stage-guest-libs.ps1` | the x86-64 libraries the guest's `ld.so` searches |
| `scripts/stage-driver.ps1 -Driver <zip> -Name <name>` | an adrenotools driver package |
| `scripts/stage-shell.ps1` | the shell binary, the test guests and the guest libraries to `/data/local/tmp` — the non-app path |

all of them take **`-Package <application id>`** to choose which app's storage to write to, and all of them default to the debug app — see [which app you are building](#which-app-you-are-building). `stage-shell.ps1` is the exception with no `-Package` at all: `/data/local/tmp` belongs to no app.

## producing a SharpEmu build

```powershell
.\scripts\package-build.ps1                       # from the fork branch that is checked out
.\scripts\package-build.ps1 -Branch android       # packagedAt stamps itself
.\scripts\package-build.ps1 -FromArchive .\sharpemu-0.0.3-hotfix-2-linux-x64.tar.gz -Id android
.\scripts\package-build.ps1 -FromArchive https://.../sharpemu-0.0.3-linux-x64.tar.gz -Id android
.\scripts\package-build.ps1 -FromArchive C:\wip\publish\linux-x64 -Id dev -SharpEmuVersion dev
```

it produces a build **directory and zip** under `build/builds/` and stops there — `stage-build.ps1` puts one on a device. **`-FromArchive` needs no fork checkout, no .NET SDK and no git**, which is the path a third party takes; it accepts an archive by path or URL, or a bare publish directory. what it cannot do is record a commit, so `meta.json`'s `commit` is empty and `source` names where it came from instead.

**what a build is, field by field, is [`build-format.md`](build-format.md)** — the `meta.json` schema, the `hostContract` generations and what a payload has to implement to satisfy one. read that rather than this one if you are packaging a build by hand or producing one the app has never seen.

## shipping a build inside the APK

```powershell
.\app\build-app.ps1 -Release -BundleSharpEmu .\build\builds\android-0.0.3-hotfix-2-20260808015108
```

**exactly one build ships per APK, and naming one is the whole of how it gets there.** absent means no asset, so a development build of the app keeps its small APK and its staged builds without anyone deciding that it should — and the staging directory is emptied on every build that names none, so yesterday's bundle cannot ride along in today's.

**there is deliberately no coupling to `-Release`.** a release APK with no build in it and a debug APK with one are both things you may want, and a rule tying the two together is a rule to get wrong. it is also explicit rather than "the newest thing under `build\builds\`": a release is rare, and an APK carrying a build nobody intended is the plausible-artefact-attributed-to-the-wrong-source failure that costs the most to find.

what it checks before it packages anything, and again on the APK afterwards:

- the directory has a `meta.json`, the payload that `meta.json` names, and a `plugins/` beside it
- **`external/sharpemu` is recorded at the commit the build was cut from.** the submodule pointer is what makes an APK's contents reproducible from a clone, so bundling a build it does not name would publish an APK nothing can reconstruct. it is refused rather than warned about, and it also catches a build cut from a commit that was never pushed, since a submodule can only be moved to one it fetched. a build packaged with `-FromArchive` has no commit at all and is refused for the same reason
- **its `hostContract` is in the app's own range**, read out of `SharpEmuBuild.java` rather than restated here. the bundled build cannot mismatch, since the two ship together — so a mismatch is a build-system bug, and catching it on this machine beats an APK that refuses its own build
- names android's asset packer would silently drop are removed rather than listed. aapt2 ignores dot-prefixed assets without a word, and a file listing that names one describes a tree the APK does not contain
- every line of the generated listing resolves to a real APK entry, and the payload, `plugins/`, `meta.json` and the listing are all present and non-empty

the identity is **regenerated** rather than copied: `packagedAt` goes, since exactly one of this build exists and there is nothing to order it against, and the `commit` stays and becomes what tells the app whether an app update brought a new build. `build-format.md` has [the rest of it](build-format.md#the-build-that-ships-inside-the-apk).

## measuring

| | |
| --- | --- |
| `scripts/soak.ps1` | **run the app many times and classify every run.** `-Builds <path>,<path>` and `-Arms` interleave, which is what makes a rate comparison survive the device warming up mid-soak. omitted, it soaks what is staged and says which |
| `scripts/compare-builds.ps1` | one run per SharpEmu build, reporting payload size, fps and render passes per frame. omitted, `-Builds` compares every build on the device |
| `scripts/compare-drivers.ps1` | one run per GPU driver, steady-state fps from frame 300 onwards. the platform's driver is the control and always runs; `-Build <path>` pins the SharpEmu build the comparison holds constant |
| `scripts/compare-file-modes.ps1` | **one game reached every way it can be** — staged as a path, through a directory grant, and, with `-GamePath <path on the device>`, that same granted directory reached as an ordinary path, which is what all-files access buys. compares the guest's own file counts, which must be *identical*, then boot and fps. **needs a grant the app already holds** — add the folder your games are in from Settings -> Data -> Game folders, and stage the same game with `stage-game.ps1` so every arm has one. the third arm also needs All files access on, from the same section |
| `scripts/device/` | `monitor.sh`, `cpuburn.sh` and `run-monitored.sh` — these run **on the device** |

the first four launch an app that is already installed, and like everything else here they measure the **debug** app unless `-Package` says otherwise — which is the one `run.ps1` has been deploying, so a soak measures what you just built.

**at low rates a clean streak proves nothing.** measure the rate, keep the arm you are comparing against selectable, and run both arms in the same sitting.

**and alternating the arms is not the same as changing their order.** alternate them and the same arm still goes first in every round, which on a device that drifts downward as it warms makes "which arm" and "which position" indistinguishable — the one that goes first wins, and it looks like a result. `compare-file-modes.ps1` rotates the order one place each round for exactly that reason, so over as many rounds as there are arms each arm holds each position once, and it prints the spread *within* each arm beside the difference *between* them, because a gap smaller than the noise is not a finding.

## the two libraries

`scripts/toolchain.ps1` and `scripts/device.ps1` are dot-sourced by the others and are not run directly. `toolchain.ps1` resolves the toolchains and documents the two conventions every script here follows: `$PSScriptRoot` rather than `$MyInvocation.MyCommand.Path`, and `$ErrorActionPreference = "Continue"` in any script that invokes a native executable, with `$LASTEXITCODE` checked explicitly — because windows PowerShell turns every stderr line from a native tool into an error record, and cmake, adb, javac and sdkmanager all warn routinely.

## one rule worth knowing

**every script asserts the artefact it was supposed to produce**, rather than trusting that it returned quietly. that is not defensive habit — it is the single most common failure this project has met: `sdkmanager` exiting 0 having installed nothing, a staging step reporting success and leaving nothing behind, an argument list silently binding to the wrong parameter. if you add a script, assert the outcome.
