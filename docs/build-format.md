# the build format

what a SharpEmu build is, what its `meta.json` says, and what a payload has to do to be launchable by this app.

**this is the document a third-party build has to satisfy.** the app refuses a build it does not recognise, and the refusal is the point: every failure this format exists to prevent is a silent one. a payload that ignores `SHARPEMU_HOST_AUDIO` renders perfectly, makes no sound, and reports no error anywhere — which arrives as "the emulator has no audio" and names the wrong component entirely.

`scripts/package-build.ps1` produces builds and [`scripts.md`](scripts.md) documents how to drive it. nothing here requires that script: everything below is the format itself, and a build assembled by hand that holds to it is a build.

## a build is a directory, not a file

a payload on its own is not a build. SharpEmu resolves `plugins/` **relative to its own executable** in two places — managed plugins in `SharpEmu.CLI`'s `Program.cs`, and `ffmpeg.RootPath` in `FfmpegNativeBinkFrameSource.cs` — so a payload staged alone is a payload with no audio and no video.

```
performance-0.0.3-hotfix-2-b1/
├── meta.json          the identity. read by the app, and by every staging script
├── SharpEmu           the payload: a linux-x64 ELF, executed as guest code
├── *.dll  *.so        the rest of the publish output
└── plugins/           managed plugins, and the ffmpeg tree the Bink decoder wants
```

**the host layer needs no part of this.** it takes a payload path and stays a thing that runs an ELF; `GuestProcFS::SetExe` is the `realpath` of the path it is handed, so `AppContext.BaseDirectory` follows the build directory by itself. name resolution, `meta.json` and the contract check are the launcher's job, in `app/java/.../SharpEmuBuild.java`, because that is where a build list needs them anyway and two implementations of one contract is one too many.

**the zip is the distribution format and the directory is what runs.** android's shell has no unzip worth relying on, so a zip is unpacked on a PC and pushed. `meta.json` sits at the **zip root**, not inside a wrapper directory — that is the single thing most likely to differ between two hand-made packages.

## `meta.json`

UTF-8, **no BOM**. `java.util.zip` and `org.json` both cope with one; every other tool that has to read this one day may not.

```json
{
  "id": "performance",
  "name": "Performance",
  "sharpemuVersion": "0.0.3-hotfix-2",
  "buildVersion": 1,
  "hostContract": 2,
  "payload": "SharpEmu",
  "env": {},
  "notes": "parity plus the memory-type preference and the flip-snapshot pool.",
  "commit": "a1b2c3d",
  "source": "fork performance"
}
```

| field | | absent means |
| --- | --- | --- |
| `id` | what this build *is*, stable across versions of it. the first-party ids are the fork's branch names | the folder name |
| `name` | the display name a build list shows | the `id` |
| `sharpemuVersion` | the SharpEmu version the payload was built from, upstream's own string | `"0"` |
| `buildVersion` | monotonic per `(id, sharpemuVersion)`, so "latest" is sortable without parsing anything | `0` |
| `hostContract` | the launcher↔payload interface generation. see below | `0`, **which is refused** |
| `payload` | the executable's file name within the directory | `"SharpEmu"` |
| `env` | guest environment this build wants defaulted on. the lowest-precedence source there is | empty |
| `notes` | one line, printed at launch under the identity | empty |
| `commit` | **provenance, not format.** the commit the payload was built from | — |
| `source` | **provenance, not format.** where it came from when there was no commit to record | — |

the app reads the first eight and ignores the last two. they are recorded anyway, and deliberately: a build that renders differently from another has to be traceable to where it came from without a changelog. **`commit` is empty rather than absent** when a build was packaged from a published archive, and `source` says what it was instead — a build whose provenance is unknown should say so rather than leave a reader to notice a missing field.

**identity lives here and never in a filename.** the on-device folder name is *derived* from it, `<id>-<sharpemuVersion>-b<buildVersion>` lowercased and restricted to `[a-z0-9._-]`, so two builds of the same source coexist and nothing ever has to guess which is which. both the packaging script and the staging script derive it the same way; neither uses what the directory or zip happens to be called on your disk.

## `hostContract`

**the launcher-to-payload interface generation**: which environment variables the payload is expected to understand, and which host window it must implement.

the app declares a **range** rather than a single number — `CONTRACT_MIN` and `CONTRACT_MAX` in `SharpEmuBuild.java` — so bumping it does not silently invalidate every build a user has already imported. outside the range the launch is refused and both numbers are named in the log.

**the range is 2..2.** generation 2 is a payload that implements both the host window selector and the host audio selector, and it is the only generation this app runs.

**generation 1 implements the window selector alone, and is refused rather than run.** the range does not extend down to it, and that is the deliberate part: a generation-1 payload does not know `SHARPEMU_HOST_AUDIO`, so it asks SDL for a device, SDL names four backends android does not have, and the port degrades to `backend=silent`. the game renders, makes no sound, and nothing anywhere reports an error. that is precisely the class of failure this check exists to turn into a refusal, so running such a build is worth less than refusing it.

**it is a courtesy and not a guarantee.** a build that declares 2 and lies still dies inside SDL. the real guarantee is knowing where a build came from, which is what the launch log is for — it prints the name, id, version, build number, contract and directory of whatever it resolved.

**it gates the payload, and nothing else.** it moves when what a payload must do changes, and it stays still for a change on the launcher's side of the line that leaves every existing build byte-for-byte compatible. bumping it for one of those refuses working builds by name — a false negative in the mechanism built to prevent false negatives.

## what contract 2 asks of a payload

three environment variables, all set by the launcher, all read by the payload at startup. each selects an implementation **at runtime rather than at compile time**, because the payload's runtime identifier is still `linux-x64` — nothing about the build is android, and on a desktop linux none of these implementations must be reachable.

| variable | what the payload must do |
| --- | --- |
| `SHARPEMU_HOST_WINDOW=android` | construct a host window that does not go through SDL. SDL's linux build has X11, Wayland and KMSDRM and android has none of them, so an `SdlHostWindow` throws *"No available video device"* before the presenter makes its first vulkan call. the fork's `AndroidHostWindow` asks for a surface through `VK_EXT_headless_surface` instead — a standard extension meaning "a surface with no window", which the host layer supplies and owns the swapchain behind |
| `SHARPEMU_HOST_WINDOW_SIZE=WIDTHxHEIGHT` | report exactly that as the window's pixel size. **the host is the only thing that knows it** — it has the `ANativeWindow` and the guest process does not. a window whose size disagrees with the surface makes the vulkan presenter conclude the drawable was resized on every frame, so it recreates its swapchain forever and never renders, silently, with no call returning an error. a malformed value should be warned about and ignored, not thrown on |
| `SHARPEMU_HOST_AUDIO=android` | open host audio through AAudio. SDL's linux build knows PipeWire, PulseAudio, JACK and ALSA; android runs none of the three sound servers and does not let an app open the kernel device, so `/dev/snd/pcmC0D0p` is `system:audio` and an app's uid is not in group `audio`. implement the full PCM seam rather than only stereo PCM16: the guest asks for 48000 Hz, 2 channels, float32, and `AAUDIO_FORMAT_PCM_FLOAT` is exactly that, so guest float32 passes through without a conversion |

**a fourth variable is set by the launcher and asks nothing of the payload.** `DOTNET_EnableWriteXorExecute=0` is .NET's own, not SharpEmu's: without it the host layer's SMC tracker cannot see CoreCLR's JIT writes and a boot costs 65x. it is listed here so that nobody removes it on the grounds that no payload code reads it.

the payload is otherwise an ordinary linux-x64 publish. it is `ET_DYN` and links the system libc, and it runs as guest code under the host layer's own dynamic linker search path — the x86-64 shared objects staged alongside it, not a rootfs and not a container.

## `env`, and what a build may not ask for

`env` is the **lowest-precedence** environment source there is. per launch, last wins:

```
the build's own env  <  the launcher's four  <  the launch intent's extra guest environment
```

so a build **cannot** override `SHARPEMU_HOST_WINDOW`, `SHARPEMU_HOST_WINDOW_SIZE`, `SHARPEMU_HOST_AUDIO` or `DOTNET_EnableWriteXorExecute` by declaring them: the launcher writes those after the build's map. below all of it, the host layer's shell binary takes explicit `--env` on its own command line, which is a development path and not something the app reaches.

it is a **map** and not a list of flags, so a variable a build defaults on and a launch overrides reaches the guest once, with the override's value. two `--env` arguments naming one variable would be a coin toss over which the guest reads.

**a build may set guest environment and nothing else.** the host layer's own switches — SMC tracking mode, async signal delivery, the vulkan family — are properties of its correctness, and a payload able to ask for SMC tracking to be turned off is a payload able to break the thing running it. they are launch-time choices, never build-time ones.

what is worth defaulting here is a knob that is genuinely a property of the build: `SHARPEMU_AUDIO_LATENCY_MS` sets how much audio the device is asked to hold, and is the same variable SharpEmu's SDL backend reads with the same default, so it stays one knob on every platform.

## producing a build without a fork checkout

`scripts/package-build.ps1 -FromArchive <path|url> -Id <id>` takes a published `linux-x64` tree — a `.tar.gz`, a `.zip`, or a bare publish directory — and gives it an identity. **it needs no fork clone, no .NET SDK and no git**, which is the path a third party takes and the one any CI job would take. what it cannot do is record a commit, so `commit` is empty and `source` names the archive.

the payload is *found* inside the archive rather than assumed at a fixed depth, since whether there is a wrapper directory depends on who packed it: the archive is searched recursively for a file named `SharpEmu` with a `plugins/` beside it, and the layout it was found in is named in the log. more than one candidate is an error rather than a guess. that search is what fixes the payload's name in this mode — a build whose `payload` field is something else has to be assembled by hand.

**packaging by hand is fine, and these are the parts that go wrong:**

- `plugins/` beside the payload, in the same directory
- `meta.json` at the **root** of the zip, not inside a wrapper folder
- UTF-8 without a BOM
- a `hostContract` inside the app's declared range, and a payload that actually implements it
- unix permissions are irrelevant. the payload is never executed as a file — the host layer reads it into guest memory — so an archiver that drops the executable bit costs nothing

## where a build lives on a device

| | |
| --- | --- |
| **staged** | `/storage/emulated/0/Android/data/<application id>/files/builds/<folder>` — external storage, which is what `adb` can write and the app can read |
| **installed** | the app's internal files directory, `builds/<folder>` — copied from staged on first selection, beside its target as `.partial` and then renamed, so an interrupted install leaves nothing that resolution would find |

**internal storage is a durability decision here, not a technical requirement**, and the distinction matters because the other thing this app installs internally *does* have a hard reason. the GPU driver must be internal: the linker refuses to `dlopen` a library from a volume other apps can write. the payload has no such constraint — it is read into anonymous memory, never `dlopen`ed, never executed as a file — and a build run in place off external FUSE storage measured 874–902 ms against 879–907 ms from internal. nobody should later "optimise" the copy away on the grounds that the driver's reason does not apply to it.

the copy happens on first **selection** rather than on install or update: it is 76 MB, a build that is never chosen never costs anything, and once chosen it stays — so "the new build broke my game and the old one is gone" cannot happen.

## how a build is selected

`--es sharpemu <absolute path to a build directory>` runs that directory where it lies. **nothing at all means the most recently staged build**, and a staged build wins over an installed one wholesale rather than by date — `adb` writes the staged directory, so that is the one that moves, while an installed copy's timestamp says when it was copied and not when its bytes were chosen.

**a bare id is refused outright rather than resolved**, and the refusal says so. an id names a build only up to its `buildVersion`, and resolving one means answering with the highest — so a freshly staged `b1` loses to a `b3` still lying around, and the run is a plausible one attributed to the wrong artefact with nothing erroring. a path cannot be ambiguous about which directory it meant.

resolution by id exists in `SharpEmuBuild.java` and no launch intent reaches it. it is what a build list resolves with: a user picking one build per id wants the newest of that id, which is exactly the answer a deploy loop must not get.

**versions are compared numerically, not lexicographically.** `0.0.10` is above `0.0.9`, and a suffix orders *below* a bare version of the same numbers — `0.0.3-hotfix-2` is a `0.0.3`, not something after it — with two suffixes of the same shape ordered by their own numbers, so `hotfix-10` beats `hotfix-2`. third-party builds are why that is a real comparator rather than an assumption about our own version strings.

## save data lives in the build directory

SharpEmu resolves save data to `user/savedata/<title id>/` **next to its own executable**, following its portable-data convention, unless `SHARPEMU_SAVEDATA_DIR` names somewhere else. inside a build directory that means **re-staging a build replaces its save data along with it**, because staging drops the previous directory once the new one is completely there.

that is a property of the format worth knowing before a build is treated as disposable.
