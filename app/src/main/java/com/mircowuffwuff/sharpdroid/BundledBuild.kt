package com.mircowuffwuff.sharpdroid

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * the one SharpEmu build that ships inside the APK: where it is before it is a directory, and what
 * turns it into one.
 *
 * **it is an [AssetTree]**, `assets/sharpemu/` -- the payload, its `plugins/`, the licenses, and a
 * `meta.json` generated when the APK was built -- which is where the listing, the space check and the
 * `.partial` write live, shared with the guest libraries in [GuestLibraries]. [BuildImport] handles
 * zips because a zip is what somebody *sends* you; nothing sends you the build you shipped.
 *
 * **nothing is extracted until a launch needs it**, which is GameNative's shape: an app update that
 * changed only the app costs nothing, and a user who never runs the bundled build never pays 76 MB
 * of internal storage for it. the extraction is what [MainActivity] runs before it resolves a
 * payload.
 *
 * **absent is a normal state.** a development build of the app bundles nothing -- that is what keeps
 * the deploy loop's APK small and its staged builds authoritative -- so every entry point here
 * answers "there is none" without treating it as a fault.
 */
object BundledBuild {

    private const val TAG = "sharpdroid"

    /** the asset directory the APK carries the build in. `scripts/build-apk.py` populates it. */
    private const val ASSETS = "sharpemu"

    /**
     * the content hash of the packaged tree, one line, computed by `scripts/build-apk.py`.
     *
     * **a content hash rather than the build's own commit**, which is the only other string that
     * could name this tree and is not enough: a payload rebuilt from a dirty fork tree sits at the
     * commit it was already at, so the APK would carry bytes the directory on disk does not have and
     * every field a comparison could use would agree. the failure that buys is a launch running the
     * *previous* payload and saying nothing, which is the most expensive shape of bug this project
     * has -- a plausible run attributed to the wrong artefact.
     *
     * it is not in the tree's listing, so it is never extracted: what lands on disk is [STAMP],
     * which this is compared against.
     */
    private const val IDENTITY = "identity"

    /**
     * the extracted copy's record of which packaged tree it is, holding exactly what [IDENTITY]
     * holds.
     *
     * **written last and inside the `.partial` directory**, so the rename is the moment the tree
     * becomes complete. a dot name so that nothing reading a build directory has to know about it,
     * and [SharpEmuBuild] does not.
     */
    private const val STAMP = ".identity"

    /** what [ensure] did. */
    sealed class Outcome {
        /** this APK ships no build. a development build of the app is in this state. */
        object NotBundled : Outcome()

        /** on disk and readable, whether it was already there or was extracted just now. */
        data class Ready(val build: SharpEmuBuild) : Outcome()

        /** refused before writing anything. both numbers are named so the toast can be specific. */
        data class OutOfSpace(val needed: Long, val free: Long) : Outcome()

        /** anything else. already logged; [why] is what a toast may say. */
        data class Failed(val why: String) : Outcome()
    }

    /** true when this APK carries a build. cheap: it opens one small asset. */
    @JvmStatic
    fun isBundled(context: Context): Boolean = assetMeta(context) != null

    /**
     * what the bundled build *is*, without extracting it.
     *
     * **read from the asset when there is one, and from disk otherwise.** the build manager and the
     * settings row both have to name the bundled build before any game has been launched -- the whole
     * point of pinning it is that it is there and selected from the first time the screen is opened
     * -- and at that moment it is 76 MB of APK and not a directory. its identity is in the asset's
     * `meta.json` either way, so the screen can be honest about a build the disk has never seen.
     *
     * the disk fallback covers a real case rather than a hypothetical one: installing a development
     * APK over a release one leaves the extracted directory behind, and it is still a build that
     * runs.
     */
    @JvmStatic
    fun identity(context: Context, internal: File): SharpEmuBuild? {
        val meta = assetMeta(context) ?: return SharpEmuBuild.bundled(internal)
        return SharpEmuBuild.fromAsset(target(internal), meta)
    }

    /**
     * puts the bundled build on disk if this APK ships one and it is not already there, and answers
     * with what a launch may run.
     *
     * **off the main thread.** it writes 76 MB.
     *
     * @param progress called as bytes land. nothing draws it today -- a launch shows a black screen
     *   throughout -- and it is reported anyway, because the caller is the only place that can decide
     *   whether a wait is worth showing and this is the only place that knows how far along it is.
     */
    @JvmStatic
    fun ensure(context: Context, internal: File, progress: AssetTree.Progress): Outcome {
        assetMeta(context) ?: return Outcome.NotBundled
        val target = target(internal)

        // **no identity is a refusal rather than an extraction.** `scripts/build-apk.py` writes one
        // beside every tree it packages and asserts it inside the finished archive, so an APK
        // reaching this line carries a bundle nothing can identify -- and the alternative to
        // refusing is writing 76 MB on every launch, or running a directory that may be older than
        // the APK it came from.
        val packaged = AssetTree.text(context, "$ASSETS/$IDENTITY")
        if (packaged == null) {
            AppLog.e(TAG, "[app] this APK carries a bundled build and no $IDENTITY beside it, so"
                    + " nothing can say whether $target is that build")
            return Outcome.Failed(context.getString(R.string.bundled_failed))
        }

        val onDisk = SharpEmuBuild.read(target)
        if (onDisk != null && stamp(target) == packaged) {
            return Outcome.Ready(onDisk)
        }
        if (onDisk != null) {
            AppLog.i(TAG, "[app] the bundled build on disk is not the one in this APK, so re-extracting")
        }

        val contents = AssetTree.contents(context, ASSETS) ?: return Outcome.Failed(
            context.getString(R.string.bundled_failed)
        )
        val total = contents.sumOf { it.bytes }
        // asked before a byte is written, because the alternative is discovering it 60 MB in and
        // leaving the user with a black screen and a partially filled disk.
        if (!AssetTree.hasSpace(internal, total)) {
            val free = AssetTree.freeSpace(internal)
            AppLog.e(TAG, "[app] the bundled build needs $total bytes and $internal has $free free")
            return Outcome.OutOfSpace(total, free)
        }

        val extracted = extract(context, contents, target, packaged, progress)
            ?: return Outcome.Failed(context.getString(R.string.bundled_failed))
        return Outcome.Ready(extracted)
    }

    /** where it lands: the reserved folder, which no derived build folder name can collide with. */
    private fun target(internal: File) = File(internal, SharpEmuBuild.BUNDLED)

    /**
     * what the extracted copy says it is, or null when it says nothing.
     *
     * **nothing is hashed on the device.** the comparison this feeds is *read two short files*, and
     * it does not grow with the tree -- hashing 76 MB here would sit on every launch, in front of a
     * game, to answer a question `scripts/build-apk.py` already answered once on a desktop.
     */
    private fun stamp(dir: File): String? =
        runCatching { File(dir, STAMP).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * lays the tree down and reads back what landed.
     *
     * **the read back is not belt and braces.** what is on disk is what will be launched, and this is
     * the last moment a mismatch is cheap to notice -- after this the failure is somewhere inside
     * SharpEmu. a tree that is not a runnable build is removed rather than left: [SharpEmuBuild.list]
     * would otherwise find it and the build manager would offer it.
     *
     * a `.partial` left behind by a process that died is invisible rather than dangerous, since
     * [SharpEmuBuild.list] and [SharpEmuBuild.mostRecent] both skip one. so [STAMP] is not what marks
     * this tree finished -- `meta.json` and the rename already do that -- and is only what names
     * which packaged tree it is.
     */
    private fun extract(
        context: Context,
        contents: List<AssetTree.Item>,
        target: File,
        packaged: String,
        progress: AssetTree.Progress,
    ): SharpEmuBuild? {
        AssetTree.extract(context, ASSETS, "the bundled build", contents, target, progress,
            STAMP to packaged)
            ?: return null
        val build = SharpEmuBuild.read(target)
        if (build == null || !build.runnable()) {
            AppLog.e(TAG, "[app] $target extracted and is not a runnable build")
            target.deleteRecursively()
            return null
        }
        return build
    }

    /** the asset's `meta.json`, or null when this APK ships no build. */
    private fun assetMeta(context: Context): JSONObject? = try {
        context.assets.open("$ASSETS/meta.json").use {
            JSONObject(it.readBytes().decodeToString())
        }
    } catch (e: IOException) {
        // the ordinary answer in a development build, so it is not logged.
        null
    } catch (e: Exception) {
        AppLog.e(TAG, "[app] this APK carries a bundled build with an unreadable meta.json", e)
        null
    }
}
