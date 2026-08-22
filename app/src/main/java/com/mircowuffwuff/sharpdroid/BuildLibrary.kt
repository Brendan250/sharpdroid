package com.mircowuffwuff.sharpdroid

import android.content.Context
import java.io.File

/**
 * the builds on the device, arranged the way a list has to draw them.
 *
 * [SharpEmuBuild] answers what a build *is* and which one a launch runs; this answers what the
 * manager shows. the split is the same one [GameLibrary] has against [Game]: identity and
 * resolution below, presentation above, so that the launcher's rules cannot drift from a screen's.
 *
 * **the bundled build is not in the list, it is beside it.** exactly one build ships per APK, so
 * there is no question of which of ours is the default and nothing here has to answer one -- which is
 * why this file holds no recommendation. it is pinned at the top the way Eden pins the system GPU
 * driver, and it is [Listing.bundled] rather than a member of [Listing.entries] so that no ordering
 * rule can ever bury it.
 *
 * **one flat list under it, newest first.** a heading per id and version buys an ordering nothing
 * asks for: a build is picked by reading a handful of alternatives against each other, and a card
 * already says where it came from and whether it is behind the one the app ships.
 */
object BuildLibrary {

    /** one row: a build, and what the list has to say about it. */
    data class Entry(
        val build: SharpEmuBuild,
        /**
         * behind the build that ships inside the app, by SharpEmu version.
         *
         * **the question this answers is the one the order cannot**: a list sorted newest-first says
         * which build is newest among the ones you have, which is not worth a badge -- it is the top
         * card. whether the build you selected is older than the one the app came with is not visible
         * anywhere else, and the fix for it is one tap on the pinned card.
         *
         * **version only, never the packaging time.** a build's `packagedAt` compares nothing across
         * two ids -- a topic branch packaged this week off an older upstream is a different thing, not
         * an older one -- and it is assignable, since a build can be repackaged under a given one. the
         * upstream version is the field that means the same thing on every branch.
         *
         * always false where the app bundles no build, which a development build of it does not:
         * with no reference there is nothing to be behind.
         */
        val outdated: Boolean,
        /** chosen in Settings, and so what a launch runs. */
        val selected: Boolean,
        /** in the app's own directory -- imported or bundled -- rather than staged from a PC. */
        val internal: Boolean,
    )

    /**
     * the pinned build and everything else.
     *
     * [bundled] is null in a debug app, which does not ship one -- the dev loop keeps a small APK and
     * staged builds -- so **an absent pinned row is a normal state and not a fault**.
     */
    data class Listing(val bundled: Entry?, val entries: List<Entry>)

    /**
     * everything there is, sorted.
     *
     * **newest first**, through the launcher's own comparator -- a build list is opened to find the
     * one that was just added far more often than to browse what came before it. the id is not part
     * of the order, so two ids interleave by version and the newest thing on the device is the first
     * card whatever it is called.
     *
     * @param selectedFolder the folder name Settings holds, or null if nothing is chosen -- in which
     *   case the bundled build is what runs, so it is what draws as selected.
     */
    fun of(context: Context, internalRoot: File, staged: File, selectedFolder: String?): Listing {
        // **the asset's identity, not the extracted directory's, and that is why this needs a
        // context.** nothing extracts the bundled build until a game is launched with it selected,
        // so on a fresh install this screen is opened before that directory exists -- and a pinned
        // row that appeared only after the first game had been played would be missing at exactly
        // the moment somebody came here to find out what would run.
        val bundledBuild = BundledBuild.identity(context, internalRoot)
        val builds = SharpEmuBuild.list(internalRoot, staged)

        // **what the radio marks is what a launch would run, not what the store happens to hold**,
        // and the three answers below are `MainActivity.chosenBuild`'s, in its order.
        //
        // nothing stored means the bundled build, which is the whole point of shipping one: there is
        // always a concrete answer, so no row has to describe a rule instead of naming a build. a
        // development build of the app bundles none, and there the answer is the most recently staged
        // build -- which has to be marked too, or this screen shows nothing selected while the row
        // that opened it names a build and the launch runs one.
        //
        // **a stored folder that is gone falls through rather than marking nothing.** it is a state a
        // user reaches without doing anything wrong -- deleted from a PC, or the volume wiped -- and a
        // launch falls back loudly in exactly this order. a screen that showed no selection at all
        // would be the one place a user goes to fix it, saying nothing about what is running now.
        val stillThere = selectedFolder != null &&
                (bundledBuild?.folder == selectedFolder || builds.any { it.folder == selectedFolder })
        val chosen = selectedFolder.takeIf { stillThere }
            ?: bundledBuild?.folder
            ?: SharpEmuBuild.mostRecent(staged, internalRoot)?.folder

        val bundled = bundledBuild?.let {
            // **the bundled build is the reference and cannot be behind itself.**
            Entry(build = it, outdated = false, selected = it.folder == chosen, internal = true)
        }

        val entries = builds
            .map { build ->
                Entry(
                    build = build,
                    outdated = bundledBuild != null && SharpEmuBuild.compareVersions(
                        build.sharpemuVersion, bundledBuild.sharpemuVersion,
                    ) < 0,
                    selected = build.folder == chosen,
                    internal = build.isInstalled(internalRoot),
                )
            }
            .sortedWith { a, b -> SharpEmuBuild.compare(b.build, a.build) }

        return Listing(bundled, entries)
    }
}
