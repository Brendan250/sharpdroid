# the app

the APK: eleven activities. `GameListActivity` is what the launcher icon opens and lists the games on the device; `SettingsActivity` and `SettingsSectionActivity` are the settings scene behind its cog, with `BuildsActivity` the build manager, `DriversActivity` the GPU driver manager, `FoldersActivity` the game folder manager, `UserDataActivity` the user data screen and `AboutActivity` the credits page behind five of its cards, and `LicencesActivity` and `LicenceTextActivity` behind that last one; `MainActivity` holds a `SurfaceView` with a guest running underneath it — it gets a window from android, hands it down, chooses a build and a driver, assembles an argument vector and calls into the host layer, which blocks until the guest is done. it is the one activity in a **process of its own**, given to one run and ended with it.

**it is an early frontend.** there is a game list, a tap to run one, a settings scene with a handful of rows in it, and a build manager, a driver manager and a game folder manager behind three of them, and a run is left through the one-button panel the back button opens over it: no per-game menu. most of what a run does is still a launch extra with a compiled-in default, and a run started by an intent reaches the same activity the list does. everything below describes it as it is rather than as something on the way somewhere.

`app/src/main/AndroidManifest.xml`, a small `res/` tree, four java files and forty-six kotlin files are all of it, with `host/src/entry_jni.cpp` on the other side of the JNI boundary — and, since a game can come from a grant rather than a path, `GuestFiles.kt` on the *other* side of it, called from the host layer rather than into it. [`host-layer.md`](host-layer.md) describes everything below `RunMain`; [`guest-files.md`](guest-files.md) describes that callback and what it costs; [`build-format.md`](build-format.md) describes what the app installs and launches; [`repo-structure.md`](repo-structure.md) says where the APK is built and under which application id, and [`scripts.md`](scripts.md) says how to drive any of it.

## three invariants

**the argument vector is the whole interface.** everything the app decides becomes a flag or an `--env` on the vector handed to `RunMain`, so a run in the app takes the same arguments a run from `adb shell` does and the two stay comparable — which is also what lets a JIT problem be bisected outside an app entirely. two things are *not* arguments, and both are things a string cannot carry: the surface, because it is a live object, which is the whole reason `HostLayer` has a second native method; and a game that came from a grant, because answering for it means the host layer calling *back* into the app, file by file, for as long as the guest keeps asking. the mount those answers appear at is still a flag.

**the app decides and the host layer runs.** build resolution, `meta.json`, the contract check and putting a driver's library where the linker will take it all live above the JNI boundary. the host layer takes a payload path and stays a thing that runs an ELF, so the regression set and every bisect command still name a path and none of them grew a mode for any of this. it is also where a build list would need all of it anyway, and two implementations of one contract is one too many.

**every choice is a launch extra rather than a constant.** comparing two builds, two drivers or two SMC tracking modes is a loop over `am start` and not an APK rebuild per candidate. the defaults in `MainActivity` are what a launch naming nothing gets, so two runs differ by exactly the extras between them.

## the build

**the app is a gradle build**, on AGP with the kotlin plugin, and `scripts/build-apk.py` is the entry point rather than a wrapper that may be skipped: it resolves the SDK and JDK through the toolchain resolver and writes `local.properties` from what it found, so the app step and the native step cannot build against different SDKs. [`repo-structure.md`](repo-structure.md) has why gradle is here at all and [`scripts.md`](scripts.md) has the arguments.

**the dependency set is modelled on Eden's android frontend** — Material3, RecyclerView, ConstraintLayout, SAF through `documentfile`, `preference`, Navigation, SwipeRefreshLayout, Coil — and the versions are Eden's too, because the UI is modelled on Eden's and reading one of its adapters against ours is worth more than being on the newest of everything. Eden's shipping frontend is view-based with `viewBinding` rather than Compose, and this one follows it. `gradle/libs.versions.toml` is the single declaration.

**only the debug build type is ever assembled**, including for `-Release`. the two senses of the word are deliberately not the same thing here: `-Release` means the manifest's own application id and label, not an optimised non-debuggable build.

**the previous APK is deleted before gradle runs, and that is not tidiness.** AGP updates the archive in place, so an entry that changes size is appended and the old bytes stay where they are — an APK rebuilt through a day of work fills with holes nothing ever reads. it installs and runs perfectly, which is why it goes unnoticed; measured, **10,055,013 bytes of dead space in a 39 MB file whose entries come to 29 MB**, one hole of it 8.96 MB. deleting one file costs a repackage of about a second and no recompilation, and it is what makes the APK size a property of the source rather than of how many times it was built. **measure an APK from a clean package or not at all.**

## the manifest

| | |
| --- | --- |
| API | `minSdk` 28, `targetSdk` 35, `compileSdk` 35 — in `app/build.gradle.kts`, not the manifest |
| the activities | eleven. `GameListActivity` carries the `MAIN`/`LAUNCHER` filter; `MainActivity` is exported with no filter at all, so `am start -n` reaches it and nothing resolves it implicitly; the two settings activities and everything behind them are not exported, since they are reached from the cog and from nowhere else |
| processes | two. `MainActivity` declares `android:process=":guest"` and every other activity is in the app's own — see [the guest's own process](#the-guests-own-process) |
| orientation | `MainActivity` is **locked landscape**, and its `configChanges` claims orientation, screen size, layout, density and UI mode so it is never recreated under a running guest. the game list is unconstrained |
| theme | `Theme.SharpEmu` on the application, Material3 following the platform between light and dark. it is what an activity wears for the moment before `Theme.kt` sets the chosen one and is not itself offered in the list. every palette is set per activity before `setContentView`, since a theme is resolved while a view hierarchy is inflated. `MainActivity` overrides all of it back to `Theme.Black.NoTitleBar.Fullscreen`, because its window is a surface a guest renders into |
| `extractNativeLibs` | **on**, for two independent reasons — as `packaging { jniLibs { useLegacyPackaging } }` |
| `debuggable` | true, and it comes from the debug build type. hardcoding it in the manifest is a lint error |
| permissions | one, `MANAGE_EXTERNAL_STORAGE`, and **declaring it is not holding it** — it is granted in the platform's own settings and nowhere else. an opt-in, described under the game list below and switched on from Settings -> Data. nothing else is declared: a library needs a folder grant rather than a permission, and audio plays rather than records |

**`MainActivity` is exported although it is not the launcher activity, and that is load-bearing.** every script in this repository launches a guest with `am start -n <application id>/com.mircowuffwuff.sharpemu.MainActivity`, which is where a run gets its build, its driver and its diagnostic flags named per launch. keeping that path independent of the list is also what makes the list falsifiable: a game that boots by intent and not by tap says which of the two is at fault.

**the app's own screens do something about the system bars, and that is `targetSdk` 35 rather than a style choice.** from android 15 the platform draws every window edge to edge and ignores one that does not ask, so a layout with no inset handling puts its toolbar behind the status bar. `SystemBars.apply` is where both behaviours live: by default the screen's root is padded by the system-bar and display-cutout insets, and with **Fullscreen mode** on the bars are hidden instead, with a swipe bringing them back for a moment.

**asking for the bars to be hidden and padding for them are separate, and that split is a fix rather than a style.** with the hide call and a `fullscreen` branch both inside the inset listener, a swipe that brought the transient bars back re-entered it — the system reported new insets, the listener asked for them to be hidden again, and the two fought until the bars stayed up with the content padded as though the toggle were off. **the listener needs no branch at all**: android reports insets only for bars that are actually visible, so with them hidden the system-bar inset is already zero and padding by whatever is there is right in both modes. a listener rather than a one-time read, because insets are not final when a view is first attached and they change under a running screen — which is also what lets the toggle take effect with no restart.

**the failure the default fixes is easy to miss**: it looks like a tight margin, and an activity restarted for any reason receives its insets late enough to appear correct until it is left. verify a screen from a cold start rather than from a recreate.

`MainActivity` is not a caller of either. its bars are hidden whatever the setting says, for the reason below.

**it keeps the framework fullscreen theme rather than the application's Material one.** its window is a surface a guest renders into rather than a themed screen, and the system bars shrinking it would change the extent the guest presents at — see the surface section below for why that is fatal rather than cosmetic.

the manifest carries the activities, the theme and the label, and nothing else: the application id, the SDK levels, `extractNativeLibs` and `debuggable` all live in `app/build.gradle.kts`, which is where AGP owns them — `debuggable` in the manifest is a lint error, and the other three are what a build is parameterised by. the label is `@string/app_name`, generated by `resValue` from the identity the script passes, so a renamed build does not mean a rewritten manifest.

**`extractNativeLibs` is on for a size reason and turns out to be an adrenotools requirement.** the host layer is a large `.so` and leaving it compressed in the zip is simpler than the uncompressed page-aligned layout an in-place load wants, which costs install time once rather than launch time every time. and adrenotools needs its hooks to exist as real files in the app's `nativeLibraryDir`, which is exactly what extraction produces — so one flag satisfies a packaging convenience and a hard requirement of the driver path at the same time.

the APK carries the dex files, the host layer's `.so`, `libc++_shared.so` and the two adrenotools hooks, all `arm64-v8a`. **the four native libraries are collected by a gradle task from three places** — build output for the host layer and the hooks, the NDK for the STL — because none of them live in a source directory. the STL is packaged rather than assumed: the host layer links `c++_shared`, and the copy in the APK has to be the one it was linked against.

**they ship unstripped, deliberately**, which costs about 7 MB on a build only ever installed over adb and keeps a native backtrace from being a list of addresses. AGP strips by default and is currently failing to, so it is pinned with `keepDebugSymbols` rather than left to an accident.

`build-apk.py` asserts every expected entry is present before it reports success, because an APK missing its dex installs and then dies at `ClassNotFoundException` and one missing the native library dies at `UnsatisfiedLinkError` — both a long way from the packaging step that caused it. a missing adrenotools hook is worse than either, since the failure is not in the packaging step or anywhere near it: every run that asks for a custom driver refuses to start, and the reason is a file that was never put in the APK.

## the guest's own process

**`MainActivity` runs in `:guest`, a second process of this app, and every other activity runs in the app's own.** the manifest is the whole mechanism — one `android:process` attribute — and the reason is the guest's exit: `exit_group` ends the process it is issued in, which is what the syscall means, and the host layer answers it with `::_exit` because the other guest threads are inside translated code and cannot be unwound. sharing a process with the game list makes a finished game a closed app with nowhere to go back to.

**the process runs exactly one guest and is ended with it, whichever way the run ends.** `exit_group` gets there by itself; a guest that returns, a payload that does not resolve and a game that is not there all reach it through the activity, which finishes and then ends its own process. a warm process would carry a mapped payload, a reserved guest address space, a populated JIT and a settings store cached from before whatever was changed on the other side — so a second launch would not be the first launch again, and every launch in this project is meant to be comparable to every other. it is also the only place a *different* host layer could be loaded, since a library is loaded once per process.

**the leading colon makes it private to the app**: same uid, own address space. everything the guest reaches is reached as the package, which is why nothing had to be handed across — the app's own directories are the same directories, and a folder grant is persisted against the package rather than the process that asked for it, so a game inside one is reachable from `:guest` exactly as it is from the list. `--saf-mount` and the callbacks in [`guest-files.md`](guest-files.md) work from there unchanged.

**it costs about 125 ms on a launch and nothing after it.** a tap from a list that is already running has to fork and initialise a process before the activity is displayed, against about 25 ms when the activity lands in a process that is already warm. nothing downstream moves: boot to first frame and frame rate are the same on either side of the split, which is what a guest doing identical work in a different address space should look like. the launch cost is paid once against a boot measured in seconds.

**the guest process's stdout is pumped to the log by a thread inside it, so `::_exit` can cut the last lines off** — the run summary and the exit status are the ones at risk. this is a property of ending a process abruptly rather than of the split, and the same lines were lost the same way when the app was what ended. a number that has to survive the end of a run wants a stronger source than the tail of a log.

## the game list

`GameListActivity` is a `RecyclerView` of every game the app can see, and a tap starts `MainActivity` with the one that was chosen. a game is in one of two places and the row does not say which, because nothing about playing it differs:

| | |
| --- | --- |
| **staged** | a directory under the app's own external `games/`, written by `scripts/stage.py`. this is the arm every measurement in the project was taken on |
| **granted** | a directory inside a folder tree the user picked, in the folder manager or from the empty list. [`guest-files.md`](guest-files.md) describes what the guest then pays for reading one, which was measured rather than assumed |

**a game is a directory holding an `eboot.bin`, and both halves of that are tested on either volume.** an empty directory left behind by a half-finished staging run is not a game, and offering it would mean the host layer reporting the failure a scan could have avoided. it is the same test the staging script applies before it copies anything.

**`Game` is a display identity over a `GameSource`, and the source is the only thing that knows where the files are.** a granted directory has no `java.io.File` — android grants a *tree*, and everything under it is a document reached through a content provider — so the source answers three questions, the same three either way: what the directory is called, what to hand coil for the artwork, and how to open `param.json`. one scan, one adapter, one row.

### the folders the user granted

**`FoldersActivity`, reached from Settings -> Data -> Game folders, is where a folder arrives and where one goes.** it is the build and driver managers' screen with folders in it — a toolbar over a list, an add button floating at the bottom right, one destructive action per row — because a third list of things that looked different would be a third thing to learn. removing is deliberately reachable rather than deferred, since a folder picked by mistake would otherwise be undoable only by clearing the app's data. **a row is a granted folder and not a game**: how many games are inside one is the game list's question, which it answers by scanning.

**the game list's toolbar carries only the cog**, and the one other way to the picker is the button on its **empty state**. that is a duplicated picker and deliberately not a duplicated rule: with no games yet, a person wants to point at their library rather than visit a manager that is also empty and press a second button — so the empty state opens the picker directly, and both callers go through `GameLibrary.add` for the decision and `GameLibrary.message` for the wording, so a folder that is itself a game is refused in the same words either way.

**the library is stored twice on purpose.** the tree uris live in a `SharedPreferences` line, which is what remembers *which* folders and in what order; android's own persisted-permission list is the authority on whether each is still readable. keeping only the second would mean a grant taken later for something else — a driver package, a build archive — appearing as a library; keeping only the first would mean offering a folder whose grant was revoked in the platform's settings, or whose volume is not mounted. a folder that fails that cross-check is dropped, and the count is logged so a list that lost rows says why.

**a folder that is itself a game is refused rather than accepted and left empty.** picking the game directory instead of the one above it is the likeliest way to get this wrong, and it presents as a grant that worked and shows nothing. the check is one query against the grant the picker already handed the process, before anything is persisted — so a refused folder leaves nothing behind to release.

**a document is addressed by building its id, never by walking to it.** a child's id is its parent's plus `/name`. resolving a path by querying for each component in turn is what a `DocumentFile` does and costs a fifth of a second per path on a dump with 816 files in it, because every level lists all of its children; appending is one query at any size. `TreeDocument` holds that rule for both the scan and the guest file layer, and [`guest-files.md`](guest-files.md) has the measurement and the assumption it rests on.

the scan of a granted folder is **one level, not a search**: the children of the tree, each directory of them checked for an `eboot.bin`. the child ids come out of the cursor that listed them rather than being built, which is free and is one fewer place relying on that assumption.

**a row shows the dump's own identity, and the directory name is what the app works in.** the display name and the title id come from `sce_sys/param.json` and the artwork from `sce_sys/icon0.png`, while the launch intent, the log lines and the staging scripts all still name the directory — so a row whose identity is missing or wrong is still one that can be launched and found on disk.

**every part of that identity degrades to the directory name rather than failing.** no `sce_sys/`, a truncated `param.json`, a file that is not JSON at all, no `icon0.png` — each of those is a game that boots perfectly well, so none of them may cost a row. the title id falls back to whatever a directory named `Dreaming Sarah [PPSA02929]` says in its brackets, and a dump with no artwork draws the same placeholder as one whose artwork has not been decoded yet.

the display name is chosen out of `localizedParameters` by the device's language: the exact tag, then any entry in that language whatever its region, then the dump's own `defaultLanguage`, then simply the first name present — the last step so that a dump localised into languages the device is not set to still shows a name rather than a directory.

**which extra carries the game is the whole difference between the two sources**, and nothing else on the intent moves: a staged game is `game`, a granted one is `safgame` beside `saftree` — unless all-files access is on, in which case it is `game` too, and the section below is why. **every other extra is left absent deliberately.** absent is a real answer everywhere `MainActivity` reads one — no `sharpemu` means whatever the build manager settled on, no `driver` means the platform's own — and a stored setting is merged by `MainActivity` rather than by the list. sending a value the list invented would quietly make it the default instead, and a launch from the list would stop being comparable to a launch from `am start`.

the list rescans on the pull gesture and whenever the screen comes forward. a game arrives while the app is open — a PC writes the directory over adb, or a file manager finishes a copy into a granted folder — so the gesture is the deliberate answer and looking again on resume is what keeps it from being mandatory. **the scan parses a file per game and queries a provider for the granted ones, so it runs on a worker** — one thread, so two scans cannot hand their results back out of order, which a pull starting one while a resume already did is exactly how to arrange; and the result is dropped rather than drawn if the screen went away while it ran.

**the refresh gesture is told what counts as scrolled up.** `SwipeRefreshLayout` decides by asking its direct child, and its direct child is the frame holding both the list and the empty label — a frame never scrolls, so without an explicit callback a drag anywhere in a scrolled list would refresh instead of scrolling. both views sit inside it rather than only the list, so the gesture works on a screen with nothing on it, which is the screen most likely to want it.

### all-files access, which is an opt-in and never the way in

a library is reached by asking the user for one folder, and everything under it is answered through a content provider. that is what the app does with nothing switched on, it is what every reference emulator this one was modelled on does, and what it costs was measured rather than argued — [`guest-files.md`](guest-files.md). **`MANAGE_EXTERNAL_STORAGE` is an opt-in on top of that and is never the mechanism**; a build with it off must reach a granted library exactly as it does today, and does.

what it changes is one thing, and only for a game inside a folder the user already granted: **the directory becomes an ordinary path**, so the launch carries `game` instead of `safgame`, the guest opens its files with ordinary syscalls, and the interception layer is never registered. that is the same code path a staged game takes, which is why the whole feature is a branch at the tap rather than a second implementation of anything.

| | |
| --- | --- |
| the toggle | a switch in Settings -> Data showing the state. tapping the row opens the platform's own per-app screen, which is where the permission is both given and taken back |
| below API 30 | the row is hidden. the permission does not exist there, and the provider is what it is for |
| when it is read | **at every launch and at every return to the screen**, never remembered. it can be revoked from the platform's settings while the app is running |
| the path | derived from the document id — `<volume>:<relative>`, where `primary` is the device's own external storage and anything else is `/storage/<volume id>` |
| **and checked** | the derived directory must hold an `eboot.bin`, the same test the scan applies. a mapping that is wrong would otherwise be a game whose every file is missing, which reads as a corrupt dump rather than as a bad path — so a failed check logs what it tried and falls back to the grant |

what it buys is a standing comparison: one library reachable two ways on the same build, which is what keeps the measured cost of the provider honest as the code changes underneath it.

the artwork is loaded by coil rather than decoded in the bind, and it is the recycling that decides it: `icon0.png` is a quarter of a megabyte, so decoding it on the main thread would stutter a scroll, and decoding it on a worker means a row that scrolled away before the bitmap arrived must not receive it. coil takes a `File` and a `content://` uri alike, which is why a granted dump's artwork needs no decoding or copying of the app's own — and why an absent icon is not checked for first, since it draws the same placeholder a failed decode does.

## the settings scene

the cog on the game list opens `SettingsActivity`, a grid of section buttons carrying an icon, a title and a line each; tapping one opens `SettingsSectionActivity`, which draws that section's rows. one activity for every section rather than one per section, because the rows differ and nothing else does. **the shape is Eden's** — typed rows in a `RecyclerView` rather than a `PreferenceScreen`, which is the yuzu lineage's way and is why their settings screens look the way they do.

**the section buttons are one column upright and two on a wide screen**, which is Eden's layout and worth the qualifier: a button is a title and one line, so a single column in landscape wastes two thirds of the width. the count is `values/integers.xml` against `values-land/`, resolved by android rather than measured by us, and an activity is recreated across a rotation so it is re-read with nothing watching for one. **a card in a grid cell must be `wrap_content` tall** — `match_parent` there means the height of the whole list, not of the row, and two buttons then fill the screen.

a subsection is a label above a run of rows rather than another button press. a row that is only meaningful on this platform carries a small android beside its title.

| section | rows |
| --- | --- |
| App | Theme, Theme color while Custom is chosen, Fullscreen mode |
| Emulation | under a SharpEmu label, SharpEmu build; under a FEXCore label, FEXCore preset |
| Graphics | Internal resolution, and under a Vulkan label, Custom driver and Disk shader cache |
| Controls | Automatic controller mapping, Vibrate handheld motor |
| Game files | Game folders, and All files access where the platform has it |
| User data | none. the card opens `UserDataActivity` |
| About | none. the card opens `AboutActivity` |

**two of the seven cards open a screen rather than a list of rows**, and the enum carries that as a class rather than the scene carrying a special case. User data is a manager screen and About is a page; neither is a set of settings, and a list holding one row that opened the real thing would be a screen nobody wanted to be on.

**the two Controls rows are the only ones that reach neither an argument vector nor the guest environment.** every other row here becomes something on the payload's command line or in its environment; these two are read by the process that runs the guest and change what the app does with events it receives and with a request it is handed. [`pad.md`](pad.md) owns what they govern and why they are two switches rather than one.

**only the sections that have rows are shown.** a button opening an empty screen is worse than a button that is not there, because the empty screen looks like a fault in the one that is.

**there is no Strict dynlib resolution row, and its absence is the rule this scene is held to.** the payload parses `--strict` and carries it as far as the options its dispatcher is handed, and nothing reads it from there — so a switch would promise that a launch fails on an unresolved import when a launch does no such thing. it is also a diagnostic rather than a setting: what it offers a user is a game that refuses to start. the flag stays reachable, `--ez strict true` still passes it, and the field behind it is still three-valued; what is absent is the row. the same test excludes a FEXCore *version* row — exactly one FEXCore is linked into the host layer, so a dropdown with one entry would offer a capability the app does not have.

**five palettes, and the default is SharpEmu** — a fresh install should look like this emulator rather than like a stock android app. **Light and Dark are fully monochrome**: every role either names is a white, a neutral or a black, including the ones a Material3 theme would otherwise hand them, since the outline it supplies in the dark is `#49454F` and that is a purple. Light also sets `windowLightStatusBar` and `windowLightNavigationBar`, which is what turns the *system's* own bar contents dark — without them a white bar draws white icons on white and the clock disappears.

**the last two are generated rather than written.** Material You seeds Material's own scheme generator from the wallpaper; **Custom** seeds the same generator from a colour the user picks, and the accent, the surfaces, the outlines and every text colour come out of it. that is why a custom theme is one `Int` in the store rather than the four colours a hand-written scheme balances. it applies over whatever theme is current and reads nothing from `themes.xml`.

**both need dynamic colour, so both are dropped from the dropdown where it does not exist** — API 31 against a `minSdk` of 28, the same guard for the same reason. a *Custom color* row appears under Theme while Custom is chosen and nowhere else. **its indicator is the theme's own `colorPrimary`** rather than the stored seed or a generated colour: the screen is already wearing the scheme that seed produced, so the accent is there to be read, and a row and its theme cannot disagree about it. it needs no live updating — a colour change restarts the screen. tapping it opens **one rectangle — hue across, chroma down, at a pinned tone**, which are the generator's own axes rather than a conversion into them. `Hct.fromInt` is the first thing that happens to a seed, so picking in hue and chroma is picking the two numbers that decide the theme. **the tone is read too** — `SchemeContent` is a content scheme and its accent tracks the seed's tone — but the tracking is clamped: measured, a seed at tone 40 and one at tone 60 both produce an accent at tone 80, and only above about 60 does the accent drift lighter and lose chroma. so the tone is the *container* that decides how much chroma a hue can hold rather than a third choice, which is why it is picked per hue rather than offered.

**tone is perceptual lightness and HSV's value is not**, which is why the axes are the generator's own: the field's top edge holds between tone 59 and 61 across every hue, where an HSV field spans tone 26 to 71 — a spread of 45 — and so means something different in each column.

**the tone is pinned at 60 where that is where the colour is, and slides down where it is not.** chroma peaks at a different lightness for every hue: greens and blues peak near tone 60 and stay there, while reds, pinks and violets peak well below it, so a single pinned tone leaves those columns pale. **capping the axis at the accent's own ceiling is worse than either**, because `SchemeContent` derives its **neutral** palettes from the seed's chroma too, so a red seed capped from chroma 85 to 34 takes the tint out of every surface in the theme: measured, a red's background chroma is 13.2 with the seed at full chroma against about 4.3 with it capped, while greens and blues sit at 5.7 to 7.3 throughout.

**nothing can make a red *accent* more vivid**, and that is the generator rather than this app: a dark scheme's primary is rendered at tone 80, which holds about chroma 30 at that hue whatever the seed carries. the chroma above that is not wasted — it is what tints the surfaces.

**so the field is painted in accents rather than in seeds**, and choosing from it is choosing the colour the *Selected* line in the preview will be: the two sample identical. the position still means chroma, so it still drives the surfaces — and a **dashed line across each column marks where the accent saturates**. below it, dragging changes the accent; above it, dragging goes on tinting the backgrounds, which the preview shows. measured at one red hue: two positions above the line gave the same accent `#F4B6B5` with backgrounds `#1E100F` and `#1A1110`, and one below gave `#DBBFBF`. a hue whose accent can hold everything the seed can — most greens and blues — has no line at all. a seed stored by an older build opens at its own hue and chroma and is normalised the moment it is saved again.

the field is built once per size into a small bitmap and scaled — every pixel is a CAM16 solve, and 96x48 of them plus two gamut bisections per column takes **108 ms** on this device.

**the picker previews the generated scheme rather than the seed, and it is live.** `SchemePreview` asks the same generator the theme is built with, so the panel beside the square is the theme drawn small — measured across a save, it named `#12140E` background and `#1F211A` card and that is exactly what arrived. the screens *around* the dialog cannot repaint as the knob moves: a scheme is resolved while a hierarchy is inflated, so repainting them means recreating the activity, which would close the dialog doing the picking.

**the seed is part of what a screen was drawn with.** `Theme.signature` is the theme's name plus the seed, because a new colour leaves the name at `custom` — and every screen behind the picker would otherwise decide it was already up to date.

**a theme change restarts every screen that is underneath it, not just the one below.** a palette is resolved while a view hierarchy is inflated, so an already-inflated screen cannot be repainted; each app screen records what it was drawn with and restarts itself on the way back if that is now stale. only checking the screen immediately below means backing out of a change lands on a stale one.

**a single-choice dialog has no Cancel button**, which is Eden's shape and is also what stops the theme list scrolling: a tap on an entry both chooses and dismisses, so the button was a second way to do what the back gesture and a tap outside already do, and its row of padding was the difference between five entries fitting and not. the dialogs that *commit* something — the colour picker and the two confirmations — keep theirs.

**a long press on a row the user has set offers to put it back to the default**, which is the affordance a per-game scene mirrors as "use the global setting". **nothing on screen says which rows are set**, and that is a trade rather than an omission: a mark saying so has to reserve its width on every row, which indents the whole list. the distinction is real — only a set row contributes anything to a launch — so the gesture has no sign of itself, and that is the accepted cost.

**one row is a switch this app cannot flip.** all-files access is granted in android's own settings and nowhere else, so the row shows the state and a tap opens that screen; it is read back on the way in, never remembered. a switch rather than a description beginning "Off." because a switch is what the thing is.

**and two rows are a count and a place to go.** SharpEmu build, Custom driver and Game folders each open a screen rather than holding a value, and each says underneath itself what is behind it — the chosen build, the chosen package, and how many folders are granted. the folder row's count is read on the main thread, which is the one `SharedPreferences` line cross-checked against the grants android holds; the screen behind it does the same read on a worker, where it is followed by drawing a list rather than a number.

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

**the activity handles no input the guest could want.** there is no touch, key or gamepad handling anywhere in it; the one thing it does answer is the back press, and that is a view of its own drawn above the surface rather than anything reaching the guest.

### the back overlay

**the back button opens a panel over the running game, on the left third of the screen, and it never leaves.** a run holds nothing that survives being left — no pause, no save of the app's — so a back press that finished the activity would end a game at the depth of one accidental gesture, on a full-screen surface where such a gesture is easy to make. back opens the panel, back again closes it, a tap past it closes it, and leaving is a labelled button inside it. the framework's own answer is never invoked, which is also what stops the process being left alive and warm.

it carries **one button, Exit game**, which calls the same ending every launch that is not the guest's own `exit_group` already takes: the activity finishes and the process goes with it. **the guest is not asked to stop first**, because there is nothing to ask with — its threads are inside translated code, which is the same reason the host layer answers `exit_group` with `_exit`.

there is **no confirmation behind the button**, deliberately: reaching it is already a back press and a deliberate tap on something that says what it does, and a dialog would be a third step on the only way out of a run.

**it is built in code, like the unpacking bar beside it, and for the same reason** — this activity wears the framework fullscreen theme, so a Material layout inflated here would ask a theme for attributes it does not define. the panel is a layout weight rather than a third of the pixel width, so it is a third of any panel and not of this one; and it pads itself for a display cutout, which is the one inset that survives the bars being hidden and the one place on this screen where anything pads at all.

**it is `INVISIBLE` rather than `GONE` while closed, and that choice carries a trap worth stating.** `GONE` is never laid out, so the panel would have no width to slide in from on the first open of a run — but **a view over a `SurfaceView` that goes from `INVISIBLE` to `VISIBLE` does not reach the display until something asks for a layout**, and leaving `INVISIBLE` does not ask by itself where leaving `GONE` does. until it does, the view is laid out at the right size, draws frames, and reports itself visible at full alpha while the screen shows the game: every reading true, none of them the answer. `OverGuestSurface` is the container that holds both halves, and it is where anything else drawn over a guest belongs. the unpacking bar needs none of it, being toggled through `GONE`.

it exists from the moment the activity does rather than from the moment a guest starts, so unpacking a bundled build — the one part of a launch that takes visible time — is not a state the run cannot be left from.

### the window, and where it stops being the app's

`nativeSetSurface` turns the java `Surface` into an `ANativeWindow` and gives it to the vulkan thunk, releasing whatever it held before. that is the app's entire involvement with the window: [`vulkan.md`](vulkan.md) owns everything from the `ANativeWindow` inward — the surface, the swapchain and the extent authority.

**the guest is not told when the window goes away.** a null surface leaves the guest running and its presents becoming no-ops, which is the point of the host layer owning that side: surface loss and restore work, in one process, without the guest learning anything happened.

what does not work is a surface that comes back a *different size*, and that is why the activity locks to landscape. the guest decides its presentation size once, at swapchain creation; an extent mismatch under a swapchain it already created makes the presenter conclude the drawable was resized on every frame, so it recreates the swapchain forever and never renders — silently, with no call returning an error.

## the launch extras

all of them are read in `onCreate`, because the intent is not readable from a worker thread. **an extra that is absent means the stored setting if the user chose one, and the compiled-in default otherwise** — the precedence is below, and the half worth reading first is that a setting nobody has touched contributes nothing at all, so a launch that names no extras produces a vector nothing on a settings screen can move.

| extra | | default |
| --- | --- | --- |
| `--es game <name\|path>` | a directory under `games/` on external storage, **or an absolute path to a game directory**, `eboot.bin` appended either way. a leading slash is the whole distinction and a name under `games/` never has one | a hardcoded title |
| `--es sharpemu <path>` | an **absolute path** to a build directory | whatever the build manager settled on |
| `--es driver <name>` | a staged adrenotools package under `gpu-drivers/`. `stock` and the empty string both mean the platform's own driver | the stock driver |
| `--es driverenv A=1,B=2` | comma-separated, one `--vulkan-driver-env` each. mesa's knobs, which reach the *host* process | none |
| `--es guestenv A=1,B=2` | comma-separated, merged into the guest environment. these reach SharpEmu | none |
| `--es smc none\|mtrack\|full` | the host layer's SMC tracking mode, validated against those three | `mtrack` |
| `--es fexpreset <name>` | the FEXCore JIT preset, validated against the ladder this build knows. it becomes one `--fex` per knob on the host layer's command line | the stored setting, or absent |
| `--ez profile true` | the vulkan thunk's profiling | off |
| `--ez turbo true` | pins the GPU clocks. a thermal and battery trade rather than a free win, which is why it is opt-in | off |
| `--ez audiowatchdog true` | the audio thunk's periodic stream report | off |
| `--ez tracefiles true` | counts what the guest asks of the game's own directory — the game's, not the one above it, so a second staged title cannot land in the counts | off |
| `--es safgame <name>` | a directory inside a **granted tree** instead of a staged one, which mounts the guest file layer and hands the guest an invented path. absent, the game is a path and no interception is registered at all | absent |
| `--es saftree <uri>` | which granted tree that directory is in, checked against the grants the app actually holds. the game list sends it because it knows which folder the row came from; absent, the first persisted read grant is used — exact with one granted folder, arbitrary with two | absent |
| `--ez strict true` | `--strict` on the **payload's** own command line, which fails a launch on an unresolved import instead of continuing without it. everything after the payload path is the guest's command line, which the host layer passes through without reading | the stored setting, or absent |

**a path in `game` is one code path with two callers, not a mode.** the game list sends one for a game in a granted folder while all-files access is on; a script sends one to reach a library outside the app's own directory. either way the guest is handed an ordinary directory it opens with ordinary syscalls, which is what a staged game is — so a run reached that way carries no machinery a staged run does not.

**`smc` and `turbo` are the two whose defaults a measurement rests on**: both change how the whole run behaves rather than what it does, so a comparison is only attributable to the extra that moved if neither of these is the thing that moved with it.

**an unrecognised `smc` value is ignored rather than passed on**, so a typo runs the default rather than reaching the host layer as a bad argument. a `guestenv` entry that is not `NAME=VALUE` is reported and skipped for the same reason.

## the settings, and what a launch does with them

the settings scene stores what the user chose. a launch merges three sources, **last winning**:

```
the build's own env  <  the stored settings  <  the launch intent's extras
```

which is the same order [`build-format.md`](build-format.md) states for the environment, extended to everything else.

**"unset" is a stored state distinct from "set to the default", and that distinction is the whole design.** a row that has never been touched contributes nothing to the argument vector — not its default, nothing — so a launch naming no extras produces a vector the settings scene had no hand in, byte for byte. if an untouched row reported its default as a *choice*, the app would start passing that value on every launch and omitting the extra would no longer reach the default; a default that cannot be reached by saying nothing is not a default, and the scripts launch by saying nothing.

the store is a `SharedPreferences` line and the state is `contains(key)`. nothing on screen marks a row as set, and a long press on one that is offers to clear it. **the gesture does nothing at all on an untouched row**, which is the cost of not marking one: there is no way to tell by looking which rows will answer a long press, and a screen where none of them has been set reads as a screen where the gesture is missing.

**that dialog names neither a decider nor a mechanism, and both omissions are deliberate.** who decides a cleared row differs by row — the app, the payload, or FEXCore — and so does whether clearing it changes an argument vector, since the App section's rows never reach one. a sentence naming either was wrong for some row on the screen it was shown from. what it says instead is the part true of every row: the state it returns to is *untouched*, which is not the same as set to the default value, and that distinction is what the whole precedence rule rests on.

| row | where it lands |
| --- | --- |
| Theme | the app's own screens. never reaches the guest and never reaches the argument vector |
| Theme color | likewise — one seed colour, and Material generates the scheme from it |
| Fullscreen mode | the app's own screens, likewise. a guest's window is fullscreen either way |
| Game folders | nothing on a vector either — it is a screen, and what it edits is which trees the game list scans |
| All files access | nothing this app stores — it is android's permission, and the row shows it |
| SharpEmu build | which build a launch that named none runs — a folder name, which is a concrete build identity |
| Internal resolution | `SHARPEMU_RENDER_SCALE` in the guest environment |
| Custom driver | `--vulkan-driver` and `--vulkan-hooks`, or neither — a folder name, or the reserved word for the system driver |
| FEXCore preset | one `--fex Name=Value` per knob, on the **host layer's** command line |

### the FEXCore preset

five rungs from most faithful to fastest — Stability, Compatibility, Intermediate, Performance, Extreme — named as other FEX frontends name them, so the setting means the same thing to someone arriving from one. each names every knob it sets rather than inheriting from the rung below, because the question a reader has is what one setting does and not what it does differently.

**the row opens a slider rather than a list, because these are a ladder and not alternatives.** every other choice in this scene is a set of options where no one of them is between two others; this one is ordered, each rung trading faithfulness for speed against the one before it, and a control with an order in it says so without the row having to. it also makes the middle somewhere a user can aim rather than a position they have to count out. the rung's name and a line saying what it costs sit above the slider and track the thumb, and the two ends are labelled — a slider with no scale is a control whose direction has to be guessed.

**it commits on a button, unlike the single-choice dialogs.** those choose and dismiss on one tap because a tap there *is* the choice; a slider is dragged across every position between where it started and where it is going, so writing as it moves would store four rungs nobody asked for on the way to the fifth — and each would be a value the precedence rule then treats as deliberately chosen.

**the id is matched case-insensitively wherever it arrives from outside**, because it is typed by hand into `am start` and into a script's own parameter. matching exactly would drop `Intermediate`, fall back to the stored setting, and produce a run that silently was not the one asked for.

**it is a host-layer argument and deliberately not a guest environment variable**, which is the one thing about this row worth stating twice. other FEX frontends express these as `FEX_TSOENABLED` and friends in the environment of a FEX process they launch; here the core is a library and those variables are read by machinery this project does not build, so that spelling reaches nothing while looking like it worked. beyond that, the environment map has a build's own `env` merged underneath it — and how guest code is translated is not a payload's to choose, the same rule `--smc` and the `--vulkan-*` family follow.

**Intermediate is FEXCore's own defaults and sets nothing to get there.** that is what makes the row honest rather than merely plausible: choosing the middle of the ladder and never opening the screen produce the same argument vector, byte for byte, so the row cannot name one thing while the launch does another. it is also the only way to ask for the defaults deliberately, which matters more the further this row moves from a list towards a slider.

**Compatibility is the rung whose name most misleads.** `VectorTSOEnabled` and `MemcpySetTSOEnabled` both default to off and it turns both on, asking for atomic vector loadstores and atomic `REP MOVS`/`REP STOS` that a default run does not emit — so it is more faithful than the defaults and measurably slower than every rung except Stability.

**Extreme is the rung that spends memory.** it is Performance plus `DisableL2Cache=0` and `DynamicL1Cache=0`, which stop the JIT's block lookup being kept small: the L1 sits at its maximum of 16 MB per guest thread rather than being resized to fit, and the L2 is consulted rather than skipped. both default to the lean side and FEX's own text says what that costs — "saving memory", "can potentially introduce more stutters". **the limit it reaches is memory and nothing announces it**: no knob refuses, and the process simply has more to lose the longer it runs.

it also asks for `HalfBarrierTSOEnabled=0`, which repairs an unaligned access to a plain load or store rather than a half-barrier atomic. **that is the only setting on this ladder that can corrupt data rather than merely run slowly**, and it is bounded by TSO — with TSO off the JIT emits few atomics to fault, so on a title that behaves like the ones measured here it is honoured and never reached.

**an option being in FEXCore's table does not mean anything reads it.** `VolatileMetadata`, `MonoHacks`, `KernelUnalignedAtomicBackpatching` and `HostFeatures` are consumed only by FEX's frontend or its Windows sources, none of which a library host builds, so they would be accepted and echoed and reach nothing. the ladder names them and refuses to emit them, for the same reason it refuses the `FEX_` environment spelling.

**there is no second extra for the internal resolution.** `--es guestenv` already reaches the same map and already wins, so a spelling of its own would be one more thing to keep in step with it for no new capability.

**the launcher's eight variables are written after the settings map and cannot be reached from it.** they are the contract a payload runs under rather than a preference — the same rule a build's `env` is held to, for the same reason. the four that name directories are held to it just as firmly: a settings row that could relocate somebody's saves is a settings row that can lose them.

## the build manager

**Settings -> Emulation -> SharpEmu build** is the list of builds on the device. the shape is Eden's GPU driver manager with builds in it and no *Fetch* button: there is no index to fetch from, and a build arrives as a zip.

**exactly one build ships inside each APK**, and it is pinned at the top under *Included with the app*, with a *Bundled* badge and no delete button - the way Eden pins the system GPU driver. that is the whole reason this screen has no *recommended* anything: there is never a question of which of ours is the default, so there is no per-release constant to keep current, no toggle in front of it and no badge that could disagree with a launch. **a development build of the app bundles none**, so the pinned row is simply absent there, and that is a normal state rather than a fault.

**the pinned row is drawn from the APK's own asset and not from a directory**, because on a fresh install there is no directory: the bundled build is unpacked by the first launch that resolves to it. this screen and the settings row above it both have to name it before that has happened - it is selected, and it is what a launch would run - so both read the asset's `meta.json`. it is also why it is not drawn as unrunnable while its payload is still inside the APK, which is where `build-apk.py` checks it is before it packages one.

everything below the pinned row is grouped by `id` and `sharpemuVersion` and sorted newest first, by the same comparator a launch resolves with. two badges: **Latest** on the newest build of each id, and **Staged** on one that lives on external storage because `adb` put it there. **the badges are computed from the functions the launcher uses rather than from a rule restated on the screen** - a badge that says one thing while a launch does another is worse than no badge, because that screen is where somebody checks after a run came out wrong.

a build this app cannot run is **drawn and marked rather than hidden**, in red, naming the contract it declares. the only way one gets into the list is by having been staged, since an import refuses it - and somebody who put it there should find out why on the screen they would look at.

**a build runs where it is, and nothing is ever copied.** a staged build runs from external storage, an imported one from the app's own directory because that is where a zip had to land, and the bundled one from its reserved folder. selecting is a single line in the store and takes no time at all. the volume costs nothing measurable — **874-902 ms from external FUSE against 879-907 ms from internal** — so a copy would buy only durability against re-staging, which is a thing only a developer can do and exactly what they mean to do when they do it.

**the selection is a folder name and never an id.** the folder name is derived from `meta.json` - `<id>-<sharpemuVersion>-<packagedAt>` - so it names one build rather than a family, and a choice survives a newer build of the same id arriving. the bundled build's folder is a plain word, which no derived name can ever collide with, so it needs no sentinel beside it and per-game selection later stores it exactly like an import.

deleting removes **both** copies of an identity, because the list shows one entry per identity and leaving half behind would look like a deletion that did nothing; if the deleted build was the chosen one, the bundled build takes over, or the newest remaining if there is none.

### importing a zip

*Import* opens a document picker for one file. the mime types offered are a filter and never the check - a provider is free to report a zip as `application/octet-stream`, so narrowing to `application/zip` alone would hide files the user can see everywhere else.

**the zip is read twice: once to judge it, once to extract it.** nothing is written until it has passed, because the alternative leaves a half-written directory to clean up on every refusal, and a cleanup is the step that gets skipped on the path nobody tests. the extraction itself writes to `<folder>.partial` and renames, so an import interrupted by the process dying leaves nothing the list or a launch would find.

what is checked, and why each one is there:

- **is it a GPU driver package?** an adrenotools package *also* has a `meta.json` at its root, so a meta file does not settle what a zip is. its own fields do: a driver names a `libraryName` and a `schemaVersion` and has neither `hostContract` nor a payload
- **does it declare a contract this app speaks**, and is the payload its `meta.json` names actually in the zip
- **is there a `plugins/` folder** - a build is a directory, and a payload without its plugins is a payload with no audio and no video
- **is it already imported**, by the folder name the identity derives to
- **size decides a sentence and never an outcome.** a size floor separates a build from a driver package, but by the wrong property: a driver refused for being small is a true statement that sends somebody looking in the wrong place. what a file is gets decided by what is inside it, and the size only chooses the wording for a file with no identity at all

**zip entry names are normalised and treated as untrusted.** PowerShell's `Compress-Archive` writes backslash separators, which the specification does not allow and which is the first thing somebody on Windows reaches for - without normalising, such a zip is refused for having no `plugins/`, naming a cause that is not the cause. and every entry's canonical path is checked to be inside the target before anything is written, because `../` in an entry name writes wherever the app can write.

an import that passes is **selected automatically**, which is the only reading that makes sense: importing a build is how somebody says they want to run it.

## choosing a build

[`build-format.md`](build-format.md) owns the format, the `hostContract` check and the selection rule — a path runs that directory where it lies, nothing at all means whatever the build manager settled on, and a bare id is refused outright. `SharpEmuBuild.java` is where all of that is implemented.

**what "nothing at all" means is the build manager's answer, and shipping exactly one build is what makes it concrete:**

| state | what runs |
| --- | --- |
| nothing chosen | the bundled build - one artefact, the same on every device, with no constant behind it |
| a build chosen | that folder, wherever it is |
| nothing chosen and nothing bundled | the most recently staged build. a development build of the app is in this state, which is what the deploy loop wants: the one that was last put there |

**a chosen build that is gone falls back loudly rather than refusing.** it is a state a user reaches without doing anything wrong - deleted from a PC, or the external volume wiped - and with no error UI the alternative is a game that does not start with the reason only in a log. the line names the build that was wanted *and* the one that ran, and the stored choice is left alone: a launch working around a problem is not a launch resolving it.

**the reserved folder is the one stored choice that is not resolved as a folder.** before the first launch it is not a folder at all, so resolving it would find nothing and fall back to a staged build - a launch quietly running something other than what the build manager's radio says it will.

### unpacking the bundled build

the first launch that resolves to it writes `builds/bundled` out of the APK, and that is the only progress bar in this app. it is drawn over the guest's own surface rather than on a screen of its own, since what is being waited for is this launch; both the bar and its label are built in code, because this activity wears a framework fullscreen theme rather than the app's Material one.

| | |
| --- | --- |
| **it is written to `bundled.partial` and renamed** | the shape an import uses, and for the same reason: a half-unpacked directory has a `meta.json`, so it would be listed and resolved and would then fail somewhere inside SharpEmu. the previous one is removed only once the new one is complete |
| **out of space aborts back to the game list** with a toast naming what it needs and what is free | rather than failing into a black screen. it is checked before a byte is written |
| **any other failure ends the launch too**, rather than falling back | running the most recently staged build because the bundled one could not be written would be a plausible run attributed to the wrong artefact - and on a release install there is usually nothing staged to fall back to. shipping *nothing* is a different answer, and it is the one a development build gives |
| **it is measured in hundreds of milliseconds** on a device with UFS storage - 76 MB in ~240 ms | so the bar is usually a flicker. it is there for the device where it is not |

what belongs to the app around all of it:

- **the launch log names the build** — display name, id, version, build number, contract and directory, plus its `notes` line if it has one. that is not decoration. a third-party build misbehaving arrives as "your emulator is broken", so a run has to be traceable to the artefact that produced it without asking the person who ran it
- **the resolved build's `env` becomes the lowest-precedence tier** of the environment merge below
- **nothing resolves a build by id.** the id groups the list and decides the *Latest* badge, and that is the whole of its job — answering "the newest build of this id" would only ever serve a recommendation, and with exactly one build shipping per APK there is none to make

the app also checks that the game's `eboot.bin` is there before it starts anything, and names what would fix it — a name that is not there was never staged, while a path that is not there is one the app is not allowed to read. a guest that starts and then cannot find its game fails much further from the cause.

### the guest's libraries

the x86-64 shared objects the guest's own `ld.so` searches — glibc, libstdc++, openssl and the guest halves of both thunks — **ship in the APK as a second asset tree** and are unpacked on the launch that finds them missing, exactly as the build above is. 12 MB in ~65 ms.

**that is what makes an install self-sufficient.** the set used to reach a device over `adb` alone, which meant an install nobody had ever plugged into a PC could not start a game at all — the failure being that the guest's own dynamic linker was absent, several layers below anything the app prints. it is also why the platform's own *delete everything* is now survivable: that call takes the app's external files directory with it, so a set living there was deleted as user data, and one living in the APK cannot be.

**two tiers, and every launch says which answered:**

| | |
| --- | --- |
| a set on external storage | **wins.** only `scripts/stage.py --guest-libs` creates one, so only a machine with `adb` can |
| otherwise | the copy unpacked out of this APK, which the APK is always authoritative for |

**those are not in tension.** unlike a build, where a person picks between several and a stored choice has to survive a newer one arriving, there is exactly one right set for a given APK — so the only question about the internal copy is *"is what is unpacked what we ship"*, never *"is it newer"*. the external tier sits above that and a release install never has one.

**the override never expires**, which is the hazard worth naming: a set staged weeks ago keeps winning after an update ships newer thunk stubs, and the failure is then a guest resolving an old `libvulkan.so.1` against a new host thunk — a version skew across the boundary rather than a missing file, which will not present as *"the staged libraries are old"*. hence the line naming the tier on every launch.

**nothing is hashed on the device.** a content hash is computed when the APK is packaged and shipped beside the tree as one short asset; the unpacked copy carries the same string in a stamp written as the last file of the extraction, inside the `.partial` directory, so the rename is the moment the set becomes complete and no stamp means unfinished whatever files are lying about. the check at launch is *read two short files and compare*, and it does not grow with the set — where hashing 12 MB measures 15 to 20 ms warm and would sit on every launch.

## the guest's environment

the eight launcher variables are [`build-format.md`](build-format.md)'s and are defined there, along with the precedence order. what is the app's is **where each value comes from**, and the answers are not alike:

| | |
| --- | --- |
| `SHARPEMU_HOST_WINDOW` | a constant. there is one right answer inside an app and nothing to choose from |
| `SHARPEMU_HOST_AUDIO` | a constant, for the same reason |
| `SHARPEMU_HOST_WINDOW_SIZE` | **the surface**, as reported to `surfaceChanged`. the host has the window and the guest does not, so the size travels from here rather than being agreed by two separately hand-set defaults |
| `DOTNET_EnableWriteXorExecute` | a constant, and .NET's own rather than SharpEmu's |
| `SHARPEMU_SAVEDATA_DIR`, `SHARPEMU_HOSTAPP_DIR`, `SHARPEMU_DEVLOG_APP_DIR` | **the app's own `user/` directory**, on internal storage — three of the four things SharpEmu resolves next to its own executable, lifted out of a build directory whose lifetime nobody chose. `AppStorage` is where the names live |
| `SHARPEMU_VK_PIPELINE_CACHE_PATH` | the fourth, and **the one the app has to spell in full**: that variable names the cache blob rather than a root, so the per-title directory in the middle is the launcher's to build. it reads the dump's title id the way the emulator reads it — `Game.emulatorTitleId`, matched field for field and character for character, because a disagreement here is silent |

the merge is a **map**, seeded with the build's `env`, overwritten by the settings, overwritten by those eight, then overwritten by anything from `guestenv`, and only then turned into `--env` flags. that ordering is the precedence order, and the map is what makes it work: two `--env` arguments naming one variable would be a coin toss over which value the guest reads.

**a build may set guest environment and nothing else** — no SMC mode, no signal delivery mode, none of the vulkan family. those are properties of the host layer's correctness, and the app enforces it by construction rather than by validation, since a build's `env` becomes `--env` and can become nothing else.

## the two directories a run needs

| | |
| --- | --- |
| `--libs` | the staged x86-64 shared objects, on **external** storage — where `adb` can write and the app can read |
| `--tmp` | the app's cache directory, on **internal** storage |

**the payload does not live in `/data/local/tmp` and cannot.** that is `shell_data_file` and SELinux denies an app's domain access to it — same device, same files, unreachable. the app's own external files directory is the one place both `adb shell` and the app can see without a permission or a picker.

that volume is `noexec` and **it does not matter**, which is a property of the host layer rather than luck: guest images are mapped anonymous and read into rather than mapped from a file, and `PROT_EXEC` never reaches the host kernel at all. nothing in a payload is ever executed as a file. a conventional loader would be stuck here.

**the temp directory is internal on purpose.** .NET reaches for `TMPDIR` far more often than for its own bundle, and the external volume is FUSE-backed on android 11+, so every file operation there is a userspace round trip. the payload is large sequential reads and does not care; thousands of small runtime file operations do.

## the driver manager

**Settings -> Graphics -> Custom driver** is the list of GPU driver packages on the device. what a row contains is Eden's driver manager — a radio, the name, the version, the description, badges and a trash can — and there is no *Fetch* button, because there is no index to fetch from and a driver arrives as a zip.

**it is a grid of cards rather than a list of rows**, which is the settings scene's own shape: the same rounded rectangle, the same `colorSurfaceContainer` fill and outline, the same one column upright and two on a wide screen from `values/integers.xml`, with the radio sitting where a section button carries its icon. a driver is picked by reading a handful of alternatives against each other, which is what a grid is for; a build list is scanned down a column for the newest of an id, which is what a list is for. the two screens are deliberately not the same shape for that reason.

**the system driver is pinned at the top with no delete button**, and it is what a launch loads when nothing is chosen. it is not a package — there is no `meta.json` to read and no version to show — so it is a row of its own rather than an entry with invented fields, and no sort can reach it. **its card is the same size as every other one**: a card of a different width in a grid of identical ones reads as a different kind of thing rather than as the pinned one, and being first and wearing its own badge is what says it is pinned. it is also the row that is always there, which is what makes it the safe place for a deletion to move the selection back to.

there is **no grouping and no *Latest* badge**, which is where this screen differs from the build manager again. builds are a series this project cuts, so an id and a version group them and "newest" means something; driver packages come from unrelated people and target different Adreno generations, so there is nothing to group by and no comparison across two authors that this app could honestly make. the order is by name.

**every card is exactly four lines and every one of them says something**: a badge, the name, the version with its author and vendor, and one more. two cards sharing a grid row are each as tall as their own content, so anything optional leaves one of them short beside the other — and a card missing its last line does not read as shorter, it reads as empty. that constraint is what decides the two rules below.

**there is one badge and it is always drawn, because provenance has three values rather than two.** a package is the device's own, was staged by `adb`, or came in through *Import* — **System**, **Staged**, **Imported**. leaving the third unlabelled was what made the badge look like a decoration that some cards happened to earn and most did not. the vendor was the alternative and is the wrong thing to put there: it is the same word on every package in practice, so it would fill the slot without distinguishing anything, and it is already on the line below.

**the fourth line is the description, or the library the package loads when it has none**, or, in red, the reason this device cannot use it. a package with no description is ordinary; the library name is always present, differs between packages, and is what somebody comparing two turnip builds looks up anyway.

**a line too long for its card scrolls rather than ellipsizing, on the chosen card only.** a card is half the width of a row and a package names itself in full — author, generation and build tag — so an ellipsis eats the part that says which Adreno generation it is for. a marquee animates while its view is *selected*, and the card the radio is on is what that means here: six cards scrolling at once is legible and is a great deal of movement, and the one whose full name is worth reading is the one that is about to run. android animates a marquee only when the text is genuinely wider than the view, so a chosen card whose text fits is still.

**the radio's minimum size and padding are cleared**, which is what lets the block sit as far from it as it sits from the card's edge: a compound button reserves a 48dp touch target around a much smaller glyph, so a margin measured from the edge of that box is not the gap anybody sees. giving the target up costs nothing here and only here — the radio is not clickable, the card is what takes the tap.

a package this device cannot load is **drawn and marked rather than hidden**, in red, saying whether it wants a newer android than this one or is missing the library it names. an import refuses both, so the only way one reaches the list is by having been staged.

**a deletion moves the selection back to the system driver rather than to another package** — the other difference from the build manager, and not a shortcut. one build substitutes for another; promoting an unrelated driver would be the app choosing something nobody asked for, on the one setting most likely to decide whether a game renders at all.

### importing a driver zip

the checks mirror the build manager's, in the order that makes a refusal say something useful:

- **is it a SharpEmu build?** a build *also* has a `meta.json` at its root, so the file settles nothing; the fields do — a build names a `hostContract` and a payload, and this is the same test the build manager applies from the other side
- **does the `meta.json` name a library, and is that library in the zip**
- **is `minApi` no higher than this device.** it is checked rather than decorative: packages in the wild declare 28, 29 and 30 against a `minSdk` of 28, and a driver loaded on a platform it was not built for fails inside the loader, where the report is a black screen
- **is it already imported**, by the folder name the identity derives to
- **size decides a sentence and never an outcome**, exactly as it does for a build

zip entries are normalised and treated as untrusted, the zip is read twice, and the extraction goes to `<folder>.partial` and is renamed — all three for the reasons the build manager's section gives. an import that passes is selected automatically.

**no refusal names the file.** a toast is two lines and truncates without warning, and driver packages are distributed under names long enough that a message opening with one loses the half that says what the file is. the log names it; the person reading the toast picked it a moment ago.

## loading a driver

**a staged package's library is copied onto internal storage before it is used, and an imported one is loaded where it lies.** the copy is not ceremony: adrenotools stats the driver and then `dlopen`s it, and **external storage is mounted `noexec`** — so the library's executable segment cannot be mapped off it. the loader answers `couldn't map … segment 2: Operation not permitted`, adrenotools' hook falls back to the system driver, and it still returns a handle good enough that nothing downstream notices. external is also FUSE-backed and the package is large, which is the second reason not to load one in place. an imported package was extracted onto internal storage to begin with, because that is the only place it could go, so copying it again would be megabytes per launch to arrive at the same path.

**that is a rule about what a file is for rather than where it came from.** a build stays on external storage where it was staged, because its payload is read into anonymous memory and never mapped executable from the file; the same is true of the guest's x86-64 set, which our own loader maps. of everything staged from a PC, the GPU driver is the only thing a linker maps.

**the copy goes in the cache directory**, since every byte of it is derived from a file that is still on external storage and is remade whenever it is missing or a different length. the platform may reclaim it and the next launch pays one copy to get it back. what that avoids is megabytes of reproducible bytes sitting in `files/`, where the export on the user data screen would have to be told to skip them by name.

the directory is `gpu-drivers` in every root that has one, so **the name says what it holds and the root says whose it is**: packages the app owns are under `files/`, libraries derived from packages that live elsewhere are under the cache, and the ones a script staged are on external storage.

the copy's directory is **per driver**, so switching between two packages cannot leave the previous one's library sitting in the directory being pointed at. it is skipped when the copy already matches the staged one in length, which is enough to notice a re-staged driver and cheap enough to check every launch.

**a chosen package that is gone ends the launch rather than falling back to the system driver**, and that is the opposite answer a missing build gets. there is always a driver that works, which is what once made falling back look like the kind thing to do — but the game then starts, the picture is right, and the only evidence that the chosen driver did nothing is a line in a log. somebody comparing two drivers compares one of them with itself. the choice is stored, so it is also not one launch going wrong but every launch after it, silently.

**the package names its own library** in its `meta.json`, so nothing in the app knows or cares what any particular driver's `.so` is called, and the driver's name and version are logged once.

**a package that loads and a package that does not look the same from here**, and the app cannot tell them apart at all: adrenotools falls back to the platform loader silently and every check available on this side has already passed by then. so the app asks the host layer, which opens the driver and checks it against `/proc/self/maps` — see [`vulkan.md`](vulkan.md).

**it is asked before anything else a launch does**, ahead of resolving a build or touching a byte of a game, because it is the only thing in a launch that can refuse on grounds a person can act on and it is settled in milliseconds. a refused launch is back on the game list, with its message, inside a second — the difference between a refusal that reads as "the tap did nothing" and one that arrives after several seconds of black screen.

**the `[vulkan] driver:` line is worth reading and does not settle it.** on this device turnip reports the same `deviceName` as the proprietary driver, so what separates them there is the API and driver versions; and nothing there separates two turnip packages from each other, which is what "did the package I picked load" actually asks.

the app then passes `--vulkan-driver` and `--vulkan-hooks` together or neither. the hooks path is `nativeLibraryDir` and can only be `nativeLibraryDir` — the app is the only thing that knows it, which is why it is passed down rather than derived below. [`vulkan.md`](vulkan.md) describes what adrenotools does with the two.

**with no driver staged the flags are simply not passed**, and the host layer opens the platform loader exactly as it does from a shell. that is what keeps the stock-driver baseline reproducible from the same build rather than merely equivalent.

## the About screen

**Settings -> About** is `AboutActivity`: a drawing, the project's name, who made it, what version this is, and three labelled facts — what emulator it runs, what it was read against, and what it is under.

**a colophon rather than a list of credits**, which is the shape and the argument at once. five facts do not want five cards to live in: a label column and a value column states all of it, on a landscape handheld, with nothing scrolled, nothing expanded and nothing hidden behind a tap that only reveals text. **a card is a container for a thing among other things and this page is one statement**, so there is no card anywhere on it and no adapter behind it.

**it credits without thanking, and the reason is worth writing down** because it was learned the expensive way. an earlier shape gave every name a card that opened onto a paragraph, and every paragraph ended in *thank you* — not out of feeling but because the shape asked for prose and there was no prose of fact to give, so what filled it was sentiment. a labelled line states the same relationship. listing somebody under **Read against** is the credit.

**there is not one image on the screen but the drawing.** no logo, no avatar, no glyph on a link: each would be a second thing to look at, and three of them turn a page into a list. a link says where it goes with a character in its own text — an arrow out of the app, a chevron to a screen of this one — which costs no drawable, takes the line's own colour and size, and keeps the whole link inside one ripple.

**the drawing is the largest thing on the page and is meant to be.** a colophon's decoration is its one image; at the size a settings screen would give it, it reads as an icon beside a table rather than as the thing the table is arranged around. what caps it is the space — in the wide variant it is the tallest view in the row, so it sets the row's height, and the row has to stay inside the roughly 404dp a landscape handheld leaves under its toolbar. **the glow behind it is deliberately smaller than it is**, so the paws and the tuft of hair carry past the light and it reads as something being lit rather than as a disc with a character stamped in the middle.

**the drawing is a link, it moves before it leaves, and nothing on the screen says so.** a tap rocks it about its bottom edge for about six tenths of a second and opens the donation URL when that finishes, rather than on the press: a browser that arrived first would take the screen away before anybody saw it react. it carries no ripple, which makes it the one deliberate touch target in this app that does not look like one — the movement is the affordance and it is the only one. **the page does not sign itself and carries no attribution line**, which is what leaves the link unannounced: this screen names what the app is built on and declines to ask anybody for anything. the image's content description is where the destination is named, so a screen reader is told what the page does not print.

**the layout files are the frame, the composition and the table.** `activity_about.xml` is the toolbar and a scroll view; `part_about_body.xml` is the composition and has a `-land` variant, because the one thing the shape of the screen changes is whether the drawing sits above the text or beside it; `part_about_facts.xml` is the three-row table and is shared by both, so what differs between the two variants is a choice of axis and nothing that carries a word. **the scroll view sets `fillViewport`**, which is what lets the composition centre in the space rather than hang from the top of it, and it still scrolls on a screen shorter than either variant was written for.

**the Emulators label is plural because there are two of them, stacked.** SharpEmu emulates a PlayStation 5; FEXCore is what executes SharpEmu, which is x86-64, on an arm64 CPU — so a page naming what this app is built on and omitting it would be missing the half that makes any of it possible on this hardware. FEXCore's version is FEX's own release naming, resolved by `scripts/build-apk.py` out of the pinned submodule and emitted as a string resource beside the repository's own commit; a suffix on it is not a stale checkout but the rule that FEX is never modified having been broken.

**both names sit on one line with both versions under them, mapping by order**, which is the shape the references row already has. an entry each, stacked, is the obvious alternative and is what gives this label four lines where every other label has two — and the row that breaks the table's rhythm being the one at the top of it is what reads as clutter.

**the two versions are divided by the dot this screen separates values with**, so a row of values reads the way every other row of values here does. what that costs is that the line's two levels look alike: the dot inside a version divides it from its own commit and the dot between the two divides one emulator from the other, so which version belongs to which name above it is carried by order rather than shown. the alternative is whitespace at the width of the gap between the two names, which keeps the grouping visible and reads as a missing character; **this is the trade the other way, taken deliberately.**

**the line is one view holding one string rather than a field each with a separator between them**, and that is what makes the two dots the same mark rather than two marks that resemble each other. a separator given a view of its own has to match its neighbours in font, colour and alpha, and a near miss on any of the three shows: built that way it inherited the licence line's half-brightness and read as inconsistent. composed as one string it is the same character of the same string, so it cannot differ — and the format it is composed with is the one the app's own version line uses, so the two lines cannot drift apart either. **the dot goes with the value it introduces**: a build that could not describe the FEX submodule leaves the line at SharpEmu's half alone, since an empty field beside a full one reads as a value that failed and a trailing separator as one still arriving.

**what a launch would run is asked of `BuildLibrary` rather than resolved again.** the emulator's version and commit come from the same listing the build manager and [choosing a build](#choosing-a-build) use, so this screen cannot name a build the launcher would not. a device with no build at all is a normal state, since a development APK ships none, and so is a build recording no commit, which is one packaged from a published archive.

**the version line carries the commit this APK was built from, and a tap copies it.** `scripts/build-apk.py` resolves it and passes it to gradle, which emits it as a string resource; a tree with uncommitted changes in it is marked, because an APK built from one is not the commit it names and that is the ordinary case during development. an APK built outside a checkout knows no commit, and the line is then the version alone — a placeholder there would be a string somebody quotes into a report and then tries to resolve. **the copy is acknowledged by the platform above API 32** and by a toast below it, rather than by both: android draws its own preview of what was taken, and a toast on top of that is the same news twice.

## the licences the APK redistributes

**About -> Third-party licences** is `LicencesActivity`, and one document opens in `LicenceTextActivity`.

the x86-64 set the guest's own linker searches is mostly unmodified Debian binaries, so the APK redistributes them, and the terms they travel under are packaged beside them: an index, each source package's own copyright statement as Debian writes it, and the full text of every licence those statements refer to. `scripts/build-apk.py` refuses to package a set missing any of them and asserts them again inside the finished archive — **so the obligation is met by the artefact, and these two screens are what makes it reachable.** a notice nobody can open without unzipping the APK is doing half the job.

- **read out of the assets, never copied into `res/`.** the files beside the binaries are the notice; a second copy in string resources is one that can silently disagree with the one that shipped.
- **the unpacked copy on internal storage is deliberately not used.** it only exists after a launch has needed it, and this has to work on an install that has never started a game.
- **a set staged over `adb` is not shown either.** that override is a development path, and what a recipient was handed is what the APK carries.
- **the list is not hardcoded.** `AssetManager.list` over the licence directory is what names the entries, so a package added to the set appears with nothing in the app changing.
- **the app's own licence is one of these documents.** sharpemu-android is GPL-2.0-or-later and the GPL text already ships beside the guest libraries, so the About screen's *Read it* link opens that one rather than a second copy of it.
- **nothing is reformatted and the read does not trim.** these are laid out for a fixed-width column, and every one of them opens with a title centred by leading spaces — so a monospaced view that wraps and strips nothing is both the honest shape and the only correct one.

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

**a guest that calls `exit_group` does not return from it at all** — the host layer calls `::_exit`, described in [`host-layer.md`](host-layer.md), which ends the process it was loaded into. that process is `:guest` and not the app's, so what ends is the run: the task resumes the game list under it, and the next tap is an ordinary launch. see [the guest's own process](#the-guests-own-process).

**and a run can be ended from above rather than waiting for the guest**, through the back overlay's Exit game button. it takes the same path a payload that did not resolve takes — finish, then kill the process from `onDestroy` — so the two endings are one behaviour with two triggers, and `nativeRun` is simply never returned from.
