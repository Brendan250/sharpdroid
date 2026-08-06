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

    /** Game directories, each holding an `eboot.bin`. Staged by `scripts/stage-game.ps1`. */
    @JvmStatic
    fun games(externalRoot: File): File = File(externalRoot, "games")

    /** Staged SharpEmu builds, as packaged by `scripts/package-build.ps1`. */
    @JvmStatic
    fun stagedBuilds(externalRoot: File): File = File(externalRoot, "builds")

    /** Staged adrenotools driver packages. */
    @JvmStatic
    fun stagedDrivers(externalRoot: File): File = File(externalRoot, "gpu-drivers")

    /** The x86-64 shared objects the guest's `ld.so` searches. Handed to the host layer as `--libs`. */
    @JvmStatic
    fun guestLibs(externalRoot: File): File = File(externalRoot, "guest-libs")

    /**
     * Builds copied onto internal storage. Durability rather than a technical requirement: a payload
     * is read into anonymous memory and never `dlopen`ed, so it would run from external storage
     * perfectly well.
     */
    @JvmStatic
    fun installedBuilds(filesDir: File): File = File(filesDir, "builds")

    /**
     * Where a driver package's `.so` is installed, one directory per driver.
     *
     * Per driver so that switching between two packages cannot leave the previous one's library
     * sitting in the directory being pointed at. Unlike a build, this copy **is** a requirement: the
     * linker refuses to `dlopen` a library from a volume another app could have written.
     */
    @JvmStatic
    fun installedDriver(filesDir: File, driverName: String): File =
        File(File(filesDir, "gpu-driver"), driverName)
}
