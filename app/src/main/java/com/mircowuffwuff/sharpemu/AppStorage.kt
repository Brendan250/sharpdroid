package com.mircowuffwuff.sharpemu

import java.io.File

/**
 * Where the app's own files live, on both volumes.
 *
 * These names were spelled inline in [MainActivity] and are collected here because the frontend
 * needs every one of them from more than one screen: a game list scans [games], a build manager
 * writes [installedBuilds], a driver manager writes [installedDriver]. A directory name that is a
 * literal in two places is a directory name that gets renamed in one of them.
 *
 * **The two volumes are not interchangeable and the split is deliberate.** External is where `adb`
 * can write and the app can read, which is what makes staging from a PC possible at all. Internal is
 * the only one a library can be mapped executable from, since external is mounted `noexec` — and it
 * is where anything written *during* a run goes, since external storage is FUSE-backed and private
 * data has no business being world-readable. `docs/app.md` has the reasoning per directory.
 *
 * **The rule is what a file is for rather than where it came from**: anything read as data is left on
 * external where it was staged, and only what a linker maps executable is copied in. Of everything
 * staged from a PC the GPU driver is the sole member of the second set.
 */
object AppStorage {

    /** Game directories, each holding an `eboot.bin`. Staged by `scripts/stage.py`. */
    @JvmStatic
    fun games(externalRoot: File): File = File(externalRoot, "games")

    /** Staged SharpEmu builds, as packaged by `scripts/package-build.py`. */
    @JvmStatic
    fun stagedBuilds(externalRoot: File): File = File(externalRoot, "builds")

    /** Staged adrenotools driver packages. */
    @JvmStatic
    fun stagedDrivers(externalRoot: File): File = File(externalRoot, "gpu-drivers")

    /**
     * A **staged** set of the x86-64 shared objects the guest's `ld.so` searches.
     *
     * **This is the development override rather than the source of record.** The APK carries the set
     * and [unpackedGuestLibs] is where it lands; this is what `scripts/stage.py --guest-libs` writes,
     * it is the only way the external set comes into existence, and it wins when it is there.
     * [GuestLibraries] is where that order is decided and why.
     */
    @JvmStatic
    fun guestLibs(externalRoot: File): File = File(externalRoot, "guest-libs")

    /**
     * The same set, unpacked out of this APK — what a launch resolves to when nothing is staged.
     *
     * **Internal storage, and that is what closes the gap it was moved for.** The platform's own
     * "delete everything" takes the external files directory with it, so a set living there was
     * deleted as user data and took the guest's dynamic linker with it; the recovery was a PC. It is
     * also where the linker is happiest, which is the same reason a staged GPU driver is copied to
     * [installedDriver] before it is loaded.
     */
    @JvmStatic
    fun unpackedGuestLibs(filesDir: File): File = File(filesDir, "guest-libs")

    /**
     * Builds the app itself owns — the bundled one, and anything imported from a zip.
     *
     * **Not a copy of a staged build.** A build runs where it is: staged ones run from external
     * storage, where `adb` put them. This is where builds land that had nowhere else to go, and
     * `docs/build-format.md` has why external would have served for those too — a payload is read
     * into anonymous memory and never `dlopen`ed, unlike the GPU driver, whose internal copy the
     * linker genuinely demands.
     */
    @JvmStatic
    fun installedBuilds(filesDir: File): File = File(filesDir, "builds")

    /**
     * **Everything the emulator writes for the person using it, one set for the whole app.**
     *
     * SharpEmu is portable software: it resolves save data, its pipeline cache and a title's own log
     * mounts to `user/` **next to its own executable**, and upstream's updater treats that directory
     * as the one an update must not replace. On android the build directory is not something a user
     * owns — it is re-staged from a PC, re-unpacked from the APK on an app update, and deleted by
     * the build manager — so `user/` inside it is data with a lifetime nobody chose.
     *
     * So it is lifted out whole and put here, and the shape underneath is upstream's own:
     * [saveData], [pipelineCache] and the two log mounts are the same names in the same layout,
     * reached by [MainActivity] pointing one environment variable at each. **A build directory
     * therefore holds only what was staged into it**, and anything that rewrites one is free to.
     *
     * **Internal storage rather than external.** This is written *during* a run rather than staged
     * into before one, and external storage is FUSE-backed on android 11+, where every operation is
     * a userspace round trip. It is also nobody else's business: a save is not something another app
     * should be able to read.
     *
     * What that costs is that `adb pull` needs `run-as` and a file manager cannot see any of it. The
     * app is what gets a person their data back.
     */
    @JvmStatic
    fun user(filesDir: File): File = File(filesDir, "user")

    /**
     * Save data — `SHARPEMU_SAVEDATA_DIR`. The `<title id>/` level underneath is the guest's, and it
     * writes the same layout here that it writes beside a desktop executable.
     *
     * **Not keyed by build, and that is the decision rather than the easy default.** A save belongs
     * to the game, not to the emulator binary that happened to write it: keying by build would mean
     * trying a different build silently starting the game over, and switching back to look at an old
     * save would be indistinguishable from losing it. Save format compatibility across builds is the
     * emulator's problem to keep, which is the same bargain every other emulator makes.
     */
    @JvmStatic
    fun saveData(filesDir: File): File = File(user(filesDir), "savedata")

    /**
     * The vulkan pipeline cache of one title — `SHARPEMU_VK_PIPELINE_CACHE_PATH`. Losing it is not
     * cosmetic: it is what stops a launch recompiling every pipeline the title has already built.
     *
     * **The layout is the emulator's own, one blob per title, and the launcher has to build all of
     * it.** That variable takes the blob's path rather than a root to hang a layout under, so
     * setting it replaces the whole construction and not just its first half — which is why
     * [Game.emulatorTitleId] exists and why it matches the emulator's rule character for character
     * instead of approximating it.
     *
     * **Per title rather than one blob for the app.** Sharing would cost the ability to clear one
     * game's shaders and grow a single file with the whole library, and it would key this directory
     * differently from [saveData] beside it, which the guest keys by title id whatever we do.
     *
     * **Changing the GPU driver needs no invalidation of ours.** Vulkan requires an implementation
     * to validate the blob's header and ignore contents it cannot use, so a driver reads whatever is
     * here, discards what is not its own and saves its own over it. What that does not do is keep
     * both: one file per title means the last driver to write wins, so alternating between two of
     * them recompiles every switch.
     */
    @JvmStatic
    fun pipelineCache(filesDir: File, titleId: String): File =
        File(pipelineCacheOf(filesDir, titleId), "vulkan-pipeline-cache.bin")

    /**
     * One title's own level of that layout.
     *
     * **The directory rather than the blob in it**, which is what a screen measuring or clearing one
     * game's shaders wants: the blob is what the emulator is pointed at, and everything the driver
     * decides to write beside it is still that game's cache.
     */
    @JvmStatic
    fun pipelineCacheOf(filesDir: File, titleId: String): File =
        File(pipelineCacheRoot(filesDir), titleId)

    /**
     * The level every title's cache sits under.
     *
     * Nothing points a variable at it — [pipelineCache] names a blob and the emulator is given that —
     * but it is what the User data screen measures and what clearing the cache removes, and both of
     * those want the whole of it rather than one title's.
     */
    @JvmStatic
    fun pipelineCacheRoot(filesDir: File): File = File(user(filesDir), "pipeline_cache")

    /**
     * The guest's `/hostapp` mount — `SHARPEMU_HOSTAPP_DIR`.
     *
     * **Flattened, for [pipelineCache]'s reason**: upstream puts this under a per-title directory
     * and the app has no title id to key one with. No title measured here has ever created it, so
     * what this really buys is that the first title which does writes here rather than into a build
     * directory that the next re-stage removes.
     */
    @JvmStatic
    fun hostApp(filesDir: File): File = File(gameLogs(filesDir), "hostapp")

    /** The guest's `/devlog/app` mount — `SHARPEMU_DEVLOG_APP_DIR`. [hostApp]'s note applies. */
    @JvmStatic
    fun devLogApp(filesDir: File): File = File(File(gameLogs(filesDir), "devlog"), "app")

    /**
     * The parent both log mounts sit under, kept because it is the name upstream gives that level.
     * Nothing points a variable at it: it has no override and needs none, since the emulator reaches
     * it only through [hostApp] and [devLogApp], each of which consults its own variable first.
     */
    private fun gameLogs(filesDir: File): File = File(user(filesDir), "game_logs")

    /**
     * Driver packages the app itself owns — whatever was imported from a zip.
     *
     * **Unlike a build, this is where a package has to be**, and the difference is the platform's
     * rather than a preference. External storage is mounted `noexec`, so mapping a library's
     * executable segment off it fails with `EPERM` and `dlopen` reports `couldn't map … segment 2`;
     * adrenotools' hook then falls back to the system driver and returns a perfectly good handle. A
     * build is unaffected because its payload is read into anonymous memory and never mapped
     * executable from the file. So an imported package is extracted straight onto internal storage
     * and loaded where it lands, while a staged one goes through [installedDriver] at launch.
     */
    @JvmStatic
    fun installedDrivers(filesDir: File): File = File(filesDir, "gpu-drivers")

    /**
     * Where a **staged** driver package's `.so` is copied so that it can be mapped executable, one
     * directory per driver.
     *
     * **The cache directory, because every byte here is derived.** The library exists on external
     * storage already and this copy is made from it whenever it is missing or a different length, so
     * it is exactly what a cache is for — the platform may reclaim it under storage pressure and the
     * next launch remakes it in the time one copy takes. Keeping it in `files/` instead would put
     * megabytes of reproducible bytes where an export has to be told to skip them by name.
     *
     * **`gpu-drivers` in every root that has one**, so the name says what a directory holds and the
     * root says whose it is: under `files/` are packages this app owns, under the cache are libraries
     * derived from packages that live elsewhere, and on external storage are the ones a script
     * staged. A name differing from the one beside it by a single letter is a collision waiting for
     * whoever writes the next substring test.
     *
     * Per driver so that switching between two packages cannot leave the previous one's library
     * sitting in the directory being pointed at. An imported package needs none of this: it is
     * already on internal storage, so it is loaded out of [installedDrivers] in place.
     */
    @JvmStatic
    fun installedDriver(cacheDir: File, driverName: String): File =
        File(File(cacheDir, "gpu-drivers"), driverName)
}
