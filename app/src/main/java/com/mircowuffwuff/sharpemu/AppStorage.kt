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
 * can write and the app can read, which is what makes staging from a PC possible at all; internal is
 * where the linker will accept a library it is asked to `dlopen`, which external can never be
 * because another app could have written it. `docs/app.md` has the reasoning per directory.
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

    /** The x86-64 shared objects the guest's `ld.so` searches. Handed to the host layer as `--libs`. */
    @JvmStatic
    fun guestLibs(externalRoot: File): File = File(externalRoot, "guest-libs")

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
     * Save data. **One set for the whole app, and deliberately outside every build directory.**
     *
     * SharpEmu resolves saves to `user/savedata/<title id>/` next to its own executable unless
     * `SHARPEMU_SAVEDATA_DIR` names somewhere else — so without this, re-staging a build destroys
     * that build's saves and the bundled build would lose them on every app update. The variable is
     * upstream's own, read by `SaveDataStorage` and covered by upstream's tests; the launcher only
     * has to point it somewhere that outlives a build directory.
     *
     * **Not keyed by build, and that is the decision rather than the easy default.** A save belongs
     * to the game, not to the emulator binary that happened to write it: keying by build would mean
     * trying a different build silently starting the game over, and switching back to look at an old
     * save would be indistinguishable from losing it. Save format compatibility across builds is the
     * emulator's problem to keep, which is the same bargain every other emulator makes.
     *
     * **On external storage so that saves can be pulled off the device**, by `adb` with no `run-as`
     * and by a file manager through the storage provider. They are kilobytes, so the volume costs
     * nothing measurable.
     */
    @JvmStatic
    fun saveData(externalRoot: File): File = File(externalRoot, "savedata")

    /**
     * Driver packages the app itself owns — whatever was imported from a zip.
     *
     * **Unlike a build, this is where a package has to be**, and the difference is the linker's
     * rather than a preference: adrenotools `dlopen`s the driver, and a library on a volume another
     * app could have written is one the linker refuses. So an imported package is extracted straight
     * onto internal storage and loaded where it lands, while a staged one goes through
     * [installedDriver] at launch.
     */
    @JvmStatic
    fun installedDrivers(filesDir: File): File = File(filesDir, "gpu-drivers")

    /**
     * Where a **staged** driver package's `.so` is copied so that the linker will accept it, one
     * directory per driver.
     *
     * Per driver so that switching between two packages cannot leave the previous one's library
     * sitting in the directory being pointed at. An imported package needs none of this: it is
     * already on internal storage, so it is loaded out of [installedDrivers] in place.
     */
    @JvmStatic
    fun installedDriver(filesDir: File, driverName: String): File =
        File(File(filesDir, "gpu-driver"), driverName)
}
