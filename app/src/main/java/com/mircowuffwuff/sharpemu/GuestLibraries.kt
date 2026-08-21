package com.mircowuffwuff.sharpemu

import android.content.Context
import java.io.File

/**
 * The x86-64 shared objects the guest's own `ld.so` searches: where they are, and which set a launch
 * is handed as `--libs`.
 *
 * **The APK carries them**, as an [AssetTree] at `assets/guest-libs/`, unpacked to internal storage
 * by the first launch that finds them missing. That is what makes the app self-sufficient: a device
 * that has never seen `adb` holds the guest's dynamic linker, and a wipe of the app's data cannot
 * take it away, because the platform's own "delete everything" takes the *external* files directory
 * with it and this set no longer lives there.
 *
 * **Two tiers, and the order reads in one line.**
 *
 * | | |
 * | --- | --- |
 * | a staged set on external storage | **wins.** Written by `scripts/stage.py --guest-libs`, which only `adb` can do |
 * | otherwise | the copy unpacked out of this APK, which the APK is always authoritative for |
 *
 * **Those two are not in tension, and it is worth saying why**, because "the APK is always
 * authoritative" and "external wins when present" read as contradictory out of context. The first
 * governs the *internal* copy: unlike a build, where a person picks one of several and a stored choice
 * has to survive a newer one arriving, there is exactly one right set for a given APK and nothing
 * negotiates — so the question is never "is what is unpacked newer than what we ship" but only "is
 * what is unpacked what we ship". The second is a tier above that, and only a machine with `adb` can
 * create it. A release install has no external set and therefore always runs what it shipped with.
 *
 * **The hazard the override carries is that it never expires**, so a set staged weeks ago keeps
 * winning after an update ships newer thunk stubs — and that failure is a guest resolving an old
 * `libvulkan.so.1` against a new host thunk, a version skew across the boundary rather than a missing
 * file. It will not present as "the staged libraries are old". So [ensure] says which tier answered
 * on every launch, the way a launch already names the build it resolved and where it found it.
 *
 * **Nothing is hashed on the device.** The identity is a content hash computed when the APK was
 * packaged and shipped beside the tree as one short asset; the unpacked copy carries the same string
 * in [STAMP], written as the last file of the extraction. The check at launch is *read two short
 * files and compare*, which does not grow with the set — a hash of the whole 14 MB measures 15 to 20
 * ms warm on this hardware and never has to run.
 */
object GuestLibraries {

    private const val TAG = "sharpemu"

    /** The asset directory the APK carries the set in. `scripts/build-apk.py` populates it. */
    private const val ASSETS = "guest-libs"

    /**
     * The content hash of the packaged set, one line, computed by `scripts/build-apk.py`.
     *
     * **A content hash rather than a version.** Twenty-five of these files come from fixed debian
     * packages and the rest are the thunks' guest halves, which this repository builds — so no
     * package version names the whole set, and only its content does.
     *
     * It is not in the tree's listing, so it is never extracted: what lands on disk is [STAMP], which
     * this is compared against.
     */
    private const val IDENTITY = "identity"

    /**
     * The unpacked copy's record of which packaged set it is, holding exactly what [IDENTITY] holds.
     *
     * **Written last and inside the `.partial` directory**, so no stamp means an unfinished
     * extraction whatever files are lying about, and the rename is the moment the set becomes
     * complete. A dot name because the directory it sits in is a linker search path, and everything
     * else there is a library.
     */
    private const val STAMP = ".identity"

    /**
     * The two the guest cannot start at all without: its interpreter, and what everything else needs.
     *
     * **A named test rather than a count of files.** `scripts/run.py` used to repair the staged set
     * whenever fewer than twenty of the twenty-eight were on the device, which twenty-seven satisfied
     * — the same class of mistake as reading "the folder is already there" as "the right bytes are
     * there". These two are what a staged set is asserted on when it is written, so they are what it
     * is recognised by here.
     */
    private val ESSENTIAL = listOf("ld-linux-x86-64.so.2", "libc.so.6")

    /** What [ensure] resolved, and where. */
    sealed class Outcome {
        /** A set staged over `adb`, which outranks the APK's. [dir] is on external storage. */
        data class Staged(val dir: File) : Outcome()

        /** The APK's own, on internal storage, whether it was already there or was unpacked just now. */
        data class Ready(val dir: File) : Outcome()

        /** This APK carries no set and none is staged, so there is nothing to run a guest with. */
        object Missing : Outcome()

        /** Refused before writing anything. Both numbers are named so the toast can be specific. */
        data class OutOfSpace(val needed: Long, val free: Long) : Outcome()

        /** Anything else. Already logged; [why] is what a toast may say. */
        data class Failed(val why: String) : Outcome()
    }

    /**
     * The directory a launch should hand the host layer as `--libs`, unpacking the APK's copy if this
     * is the launch that needs it.
     *
     * **Off the main thread.** It may write 12 MB.
     *
     * @param externalRoot the app's external files directory, or null when there is none — in which
     *   case there is no staged set to prefer and the APK's copy is the only answer.
     */
    @JvmStatic
    fun ensure(
        context: Context,
        externalRoot: File?,
        internal: File,
        progress: AssetTree.Progress,
    ): Outcome {
        if (externalRoot != null) {
            val staged = AppStorage.guestLibs(externalRoot)
            if (usable(staged)) {
                return Outcome.Staged(staged)
            }
        }

        val identity = AssetTree.text(context, "$ASSETS/$IDENTITY") ?: return Outcome.Missing
        val target = AppStorage.unpackedGuestLibs(internal)
        if (stamp(target) == identity && usable(target)) {
            return Outcome.Ready(target)
        }
        if (target.isDirectory) {
            AppLog.i(TAG, "[app] the unpacked guest libraries are not the set in this APK, so"
                    + " re-unpacking")
        }

        val contents = AssetTree.contents(context, ASSETS) ?: return Outcome.Failed(
            context.getString(R.string.guest_libs_failed)
        )
        val total = contents.sumOf { it.bytes }
        if (!AssetTree.hasSpace(internal, total)) {
            val free = AssetTree.freeSpace(internal)
            AppLog.e(TAG, "[app] the guest libraries need $total bytes and $internal has $free free")
            return Outcome.OutOfSpace(total, free)
        }

        AssetTree.extract(context, ASSETS, "the guest libraries", contents, target, progress,
            STAMP to identity)
            ?: return Outcome.Failed(context.getString(R.string.guest_libs_failed))
        // read back rather than trusting the extraction: this is the last moment a set missing the
        // interpreter is cheap to notice, and after it the failure is the guest's linker exiting.
        if (!usable(target)) {
            AppLog.e(TAG, "[app] $target unpacked without " + ESSENTIAL.joinToString(" or "))
            target.deleteRecursively()
            return Outcome.Failed(context.getString(R.string.guest_libs_failed))
        }
        return Outcome.Ready(target)
    }

    /**
     * Says which tier answered, in one line, on every launch.
     *
     * **Not decoration.** The staged tier is an override that never expires, so a run under a skewed
     * set has to be traceable to the directory it resolved without asking the person running it what
     * they staged and when. `scripts/stage.py --guest-libs --restage` is the fix, and the line names
     * it where it would be needed.
     */
    @JvmStatic
    fun report(outcome: Outcome) {
        when (outcome) {
            is Outcome.Staged -> AppLog.i(TAG, "[app] guest libraries: staged, " + outcome.dir
                    + " — this overrides the set in the app. re-stage it after an app update")
            is Outcome.Ready -> AppLog.i(TAG, "[app] guest libraries: the app's own, " + outcome.dir)
            else -> {}
        }
    }

    /** Whether [dir] holds a set a guest could actually start against. */
    private fun usable(dir: File): Boolean =
        dir.isDirectory && ESSENTIAL.all { File(dir, it).length() > 0 }

    /** What the unpacked copy says it is, or null when it says nothing. */
    private fun stamp(dir: File): String? =
        runCatching { File(dir, STAMP).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
}
