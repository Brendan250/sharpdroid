package com.mircowuffwuff.sharpemu

import java.io.File

/**
 * the GPU driver packages on the device, arranged the way a list has to draw them.
 *
 * [GpuDriver] answers what a package *is* and which one a launch loads; this answers what the manager
 * shows. the same split [BuildLibrary] has against [SharpEmuBuild], so that a badge cannot disagree
 * with what a launch does.
 *
 * **the system driver is not in the list, it is above it.** it is not a package -- there is no
 * `meta.json` to read and no version to show -- so it is [Listing.systemSelected] rather than an entry
 * with empty fields, and no sort can reach it. it is also the one row that is always there, which is
 * what makes it a safe place for a deletion to move the selection to.
 *
 * **there is no grouping and no *Latest*.** a build list groups by id and version because a build is
 * one of a series this project cuts; driver packages come from unrelated people and share nothing to
 * group by, and "newest" across two authors is a claim nothing here can make. Eden's driver manager is
 * a flat list for the same reason.
 */
object DriverLibrary {

    /** one row: a package, and what the list has to say about it. */
    data class Entry(
        val driver: GpuDriver,
        /** chosen in Settings, and so what a launch loads. */
        val selected: Boolean,
        /** in the app's own directory -- imported -- rather than staged from a PC. */
        val internal: Boolean,
    )

    /** the pinned system row and everything else. */
    data class Listing(val systemSelected: Boolean, val entries: List<Entry>)

    /**
     * everything there is, newest package first by nothing at all: the order is the one the
     * directories come back in, with the app's own copies replacing staged ones of the same name.
     *
     * @param selectedFolder what Settings holds, or null for "nothing chosen" -- which means the
     *   system driver, so that is what draws as selected.
     */
    fun of(internal: File, staged: File, selectedFolder: String?): Listing {
        val drivers = GpuDriver.list(internal, staged).sortedBy { it.name.lowercase() }

        // **what the radio marks is what a launch would load**, in [MainActivity]'s own order:
        // a stored package if it is still there, and the system driver otherwise. a chosen package
        // that is gone falls through rather than marking nothing -- it is a state a user reaches
        // without doing anything wrong, a launch falls back to the system driver loudly, and this
        // screen is where somebody goes to find out what is running.
        val chosen = selectedFolder?.takeIf { folder ->
            !GpuDriver.isSystem(folder) && drivers.any { it.folder == folder }
        }

        return Listing(
            systemSelected = chosen == null,
            entries = drivers.map {
                Entry(driver = it, selected = it.folder == chosen, internal = it.isInstalled(internal))
            },
        )
    }
}
