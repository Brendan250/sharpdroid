# the guest file layer

a game the user granted rather than staged, answered file by file underneath a guest that issues raw syscalls.

android does not hand an app a path to a directory the user picked. it hands it a **grant on a tree**, and everything behind that grant is a document reached through a content provider, across binder, by document id. that is fine for a program that owns its own file class — it branches inside it, once. SharpEmu is guest x86-64 .NET: `File.Open` becomes guest libc, becomes `openat`, becomes a syscall on an ordinary string, and there is no call site of ours anywhere in it.

so the branch goes where the host layer does own one. `host/src/guest_files.{h,cpp}` sits in the syscall dispatcher beside the `/proc/self` substitution [`host-layer.md`](host-layer.md) describes, and answers the path-taking calls itself. `host/src/saf_bridge.{h,cpp}` is the JNI half, and `app/src/main/java/com/mircowuffwuff/sharpdroid/GuestFiles.kt` is the java one. this document is here because **this part's cost is a design choice rather than a measurement**, and where the milliseconds are should be readable without reverse-engineering it from the source.

## the shape of the problem, in one table

what the guest asks of a game directory, counted with `--trace-files` over a full run of each title:

| | `Dreaming Sarah` | `Dead Cells` |
| --- | --- | --- |
| the dump | 104 MB, 816 files | 2.1 GB, 21 files |
| distinct paths touched | 118 | 6 |
| opens | 124, of which 20 are `ENOENT` | 7 |
| path stats | 283 | 18 |
| directories opened | **1** | **1** |
| `getdents64` | 2 | 2 |
| writes | **0** | **0** |
| `mmap` of a game file | **0** | **0** |
| reads | ~11 per second, forever, on descriptors already open |

**every path-taking call is over by the end of boot.** the counters freeze there and only reads keep ticking. three consequences follow, and they are what make this small:

- **reads are free.** a descriptor from the provider is a real kernel descriptor on a real file, so `read`, `pread`, `lseek`, `mmap` and `fstat` stay bare pass-throughs and are not intercepted at all
- **the layer is read-only.** measured rather than assumed — zero writes on both titles. a write that arrives anyway is refused with `EROFS` and says so once, loudly, because a design assumption failing silently is worse than one failing
- **exactly one directory is enumerated per boot**, so the `getdents64` synthesis has to be correct and does not have to be fast

## the invented path

**the guest is handed `/game/eboot.bin`, and nothing is there.** the mount prefix is passed down as `--saf-mount /game`; the app names the same constant on its own side so the two halves cannot disagree.

a real path would have been the obvious choice and is the wrong one. nothing has to be made to appear to work at `/storage/...`, the prefix test cannot be ambiguous, and a path that climbs out of the mount with `..` is recognised as naming the real filesystem rather than this one. `/gamesave` is not under `/game`, which a plain prefix compare would have accepted.

## a document id is built by concatenation

this is the single decision the whole part rests on.

resolving `sce_sys/param.json` by querying for each component in turn is what a `DocumentFile` does and is what looks obviously correct. it is also **163,800 µs per path** on the 816-file dump against 10,586 µs on the 21-file one, because every level lists all of its children — it is O(children per level) and gets worse exactly as a library grows. appending to the parent's id is a flat **~1,000 µs** at any size.

so the walk is not a slow-but-safe fallback, it is disqualified. the concatenated form assumes the id scheme the platform's own external-storage provider uses, which holds for internal storage and an SD card alike. a third-party provider would need the walk, and none is any use for a 2 GB dump.

## what one operation costs

measured against the platform's provider on the development device, warmed up, beside the same operation on a real path on the same volume:

| | provider | path, same volume |
| --- | --- | --- |
| resolve, id by concatenation | ~1,000 µs | — |
| lookup of a file that is not there | ~1,020 µs | — |
| open + close | ~3,500 µs | ~118 µs |
| reopen the same file | ~3,850 µs — no cheaper | — |
| list a directory | 3,258 µs (21 entries) / 5,887 µs (816) | — |
| stat | ~1,000 µs | ~157 µs |

and the datum that says what the cost tracks: opening the 2,107,229,692-byte file costs 3,471 µs and opening a 10-byte one costs 3,837 µs. **an open is a lookup, not a read** — a flat binder-and-provider tax whatever sits behind it. the bill tracks **file count, never bytes**, which is why the twenty-times-larger title is the cheaper one.

the second column is the honest control rather than a strawman: the app's own directory is on the same volume behind the same FUSE daemon, so this is binder against the userspace round trip a staged game already costs, not against a raw filesystem.

## what the layer does about it

| | |
| --- | --- |
| **a stat cache** | keyed by relative path, holding size, kind and modification time. it is what turns 283 stats into at most 118 provider queries |
| **negative entries too** | a fifth of the opens are of files that are not there, and they are asked for repeatedly. caching an absence is licensed by the layer being read-only and by the guest's path-taking finishing at boot: nothing this process does can make an absent file appear |
| **an fd table for directories only** | a file's descriptor is real and nothing has to remember it. a directory has no descriptor a provider can hand back at all |
| **`dirent64` records written by hand** | including `.` and `..`, which the kernel returns and which code that counts entries relies on. the struct is byte-identical on both architectures, so this is a synthesis and never a translation |
| **the seekable check, once** | a provider is allowed to answer with a pipe. one `lseek` of zero from the current position says so at the first open, rather than at the guest's first seek, thousands of instructions away and looking like anything but this |

**nothing is prefetched.** filling the cache from one recursive listing would delete every stat and every miss for ~10–20 ms, leaving only the opens — around 450 ms for the larger title. it is not built, because the plain version does not disappoint; the note is here so that the next person to look does not have to rediscover that it is the obvious next move.

## what it costs in a real boot

the same game, on the same build, reached both ways, six runs of each interleaved and with the order reversed halfway so that thermal drift cannot masquerade as an arm:

| | boot to first frame | frame rate |
| --- | --- | --- |
| staged path | 6.93–7.31 s | 58.6–59.7 fps |
| granted tree | 6.60–7.22 s | 58.5–59.0 fps |

**there is no measurable difference in either.** the granted arm's mean boot is fractionally *lower*, which is not a speedup — it is what a difference below the noise floor looks like. the frame rates decline across all twelve runs in the order they were taken, on both arms, and the last three of each sit at 58.65 and 58.53.

that is a good deal cheaper than the ~1,050 ms this was budgeted at before it was built, and half of the gap is arithmetic: the budget priced every open as an open *plus* a size query, and priced all 283 stats rather than the 118 distinct paths behind them. with this implementation's caching the same sums come to ~560 ms. **the remaining ~560 ms not appearing in wall-clock boot is not explained**, and the likely reason is that the provider work overlaps other boot work rather than being serial with it. it is written down as unexplained rather than as a win.

**both counts are identical, character for character**, which is the strongest evidence here and the reason `--trace-files` exists:

```
paths=118 opens=124 (dir 1, write 0, failed 20) stat=283 access=0 readlink=0 statfs=0 | getdents=2 write=0
```

the guest asks the mount exactly what it asks a real directory, and gets the same answers.

**the caveat, stated because it is the one thing not measured.** both titles are small. the larger touches 118 of its 816 files, 14%. a 3D title with ten thousand files, at that ratio, wants ~1,400 opens ≈ 5 s of boot, and that is where opening ahead of the guest stops being optional. there is no such title on hand to measure.

## a game is reachable three ways, and that is the point

| mode | interception | what it costs |
| --- | --- | --- |
| **staged path** — an absolute path under the app's own external files directory | **none**, the prefix never matches | today's, exactly |
| **granted tree** | **on** | nothing measurable, and zero per frame by construction |
| **granted tree, with all-files access** | **none** — the grant resolves to an ordinary path, and the layer is never registered | today's, exactly |

**the first mode keeps working untouched, and that is a hard requirement rather than a courtesy.** every script in this repository stages by path and launches with `am start`; none of them knows this part exists, and no run through them touches an intercepted path. that is what keeps a performance question answerable — a frame rate measured through the scripts cannot have been changed by a file layer that was not in it. lose it and every future measurement acquires an alibi.

**the third mode is not a third implementation — it is the first one, pointed somewhere else.** with all-files access held, the app turns the granted directory's document id into an ordinary path and launches it exactly as it launches a staged game, so nothing below the app changes and this part is not in the run at all. the permission is an opt-in the user switches on from the settings scene, never the way in: with it off, which is the default, a granted game is reached the way this document describes. [`app.md`](app.md) has the branch.

## the JNI, which is the one call back up

**this is the only place the host layer is not one-way.** everywhere else the app calls down and nothing calls back: a window is an `ANativeWindow*` and audio is pure NDK AAudio. a content provider has no NDK, so this is the one thing that has to ask java a question from a guest thread.

| | |
| --- | --- |
| the `JavaVM*` and the helper class | cached in `JNI_OnLoad`. **the class can only be found there** — on a thread the host layer attached itself, `FindClass` searches the system class loader, which has never heard of anything in the APK |
| a guest thread | attached on first use and **detached when it ends**. the runtime aborts a process whose thread exits while still attached, so the detach is not optional and lives in a destructor rather than at a call site |
| an exception | cleared at every call site and turned into the errno the syscall should return. one left pending would be delivered at the next call into java, which could be about an entirely different file |
| `JNI_OnLoad` | named in a linker `-u`, like the other entry points. nothing in the link graph refers to it, and dropping it costs no error at all — the library loads and the layer simply never has a class to call |

the java side is three static methods taking a relative path: an open, a stat, and a listing. it never throws, and it answers absence rather than failing.

## what does not change

- **the emulator**: nothing. SharpEmu keeps opening ordinary paths and never learns any of this exists, which is the entire reason this sits at the syscall layer
- **FEXCore**: nothing
- **the read path**: nothing. `read`, `pread`, `lseek`, `mmap` and `fstat` are not intercepted
- **the scripts**: nothing

## limitations, all of them deliberate

- **a working directory inside the mount cannot work.** the kernel owns the cwd and there is nothing there to point at, so a guest `chdir` under the mount fails with `ENOENT` — and says so in the log, because neither measured title does it and a third one that did would look like a broken dump
- **nothing under the mount is a symlink.** `readlink` answers `EINVAL` for a file that is there, which is what the kernel says about a real file that is not a link
- **only the platform's external-storage provider is supported**, for the id reason above
- **an inode number is invented** — a hash of the relative path, stable for the run and unique within the mount. a document id is a string and there is nothing else to make one from
- **the `statx` branch has never been exercised.** a guest glibc on x86-64 issues `newfstatat` for a stat and not `statx`, measured rather than assumed. it is implemented because a guest libc that did reach it would otherwise be handed the kernel's answer about a path that does not exist there — a plausible `ENOENT` rather than an error — but it is untested code and should be treated as such
- **the mount is refused rather than half-made.** no provider on the other side, or a game directory that holds no `eboot.bin`, and the run stops with a sentence naming which. a mount onto nothing would hand the guest a directory in which every file is missing, and that reads as a corrupt dump rather than as a layer that was never wired up

## measuring it yourself

**`--trace-files <prefix>` on the host layer, or `--ez tracefiles true` on the app**, counts everything above under one directory; under a mount, pass the mount. run the same game every way it can be reached and the counts can be put side by side, which is the only thing that keeps this a measurement rather than an opinion. **the counts must be identical** — a game reached three ways is one game, and a difference in them means one arm is not doing what the others are.

two things about the comparison, both paid for:

- **alternate the arms, and rotate which one goes first each round.** this device drifts downward as it warms, so alternating alone leaves whichever arm runs first in every round winning by about half a frame per second that is not real
- **report the spread *within* each arm beside the difference *between* them.** a difference smaller than the noise inside one arm is not a difference

the third arm — the game's own path, reached directly — needs all-files access switched on.
