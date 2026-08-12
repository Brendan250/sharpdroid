package com.mircowuffwuff.sharpemu

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The one SharpEmu build that ships inside the APK: where it is before it is a directory, and what
 * turns it into one.
 *
 * **It is an asset tree rather than a zip**, `assets/sharpemu/` — the payload, its `plugins/`, the
 * licences, and a `meta.json` generated when the APK was built. A zip would be a second archive
 * inside an archive that is already one, so the APK would carry the payload compressed twice and the
 * device would pay for both. [BuildImport] handles zips because a zip is what somebody *sends* you;
 * nothing sends you the build you shipped.
 *
 * **Nothing is extracted until a launch needs it**, which is GameNative's shape: an app update that
 * changed only the app costs nothing, and a user who never runs the bundled build never pays 76 MB
 * of internal storage for it. The extraction is what [MainActivity] runs before it resolves a
 * payload.
 *
 * **Absent is a normal state.** A development build of the app bundles nothing — that is what keeps
 * the deploy loop's APK small and its staged builds authoritative — so every entry point here
 * answers "there is none" without treating it as a fault.
 */
object BundledBuild {

    private const val TAG = "sharpemu"

    /** The asset directory the APK carries the build in. `scripts/build-apk.py` populates it. */
    private const val ASSETS = "sharpemu"

    /**
     * The generated file listing every asset in the tree, `<size>\t<path>` per line.
     *
     * **It is packaging's, not the build format's.** `docs/build-format.md` describes a build and
     * says nothing about this, because it exists for two things an APK asset cannot otherwise
     * answer:
     *
     * - **how big the extraction is, before it starts.** A compressed asset has no length —
     *   `openFd` refuses one, and `open` only tells you when it hits the end — so without this the
     *   free-space check would have to happen halfway through the write it exists to prevent, and
     *   progress would have to be counted in files. One of the 27 files here is 61 MB and the rest
     *   are small, so a count of files sits at 4% for the entire extraction
     * - **which names are directories.** `AssetManager.list` returns names and not kinds, so telling
     *   a file from a directory means trying to open each one and reading a failure as "directory" —
     *   a guess that gets an empty directory and an unreadable file exactly backwards
     *
     * It is not extracted with the rest: the directory that lands on the device is the build, and
     * this is a note the packaging step left for the unpacking step.
     */
    private const val CONTENTS = "contents"

    /**
     * Slack over the extraction's own size before it is attempted at all.
     *
     * A tree costs a little more than the sum of its files, and a device with nothing left after
     * writing 76 MB is a device that fails somewhere else a minute later — inside the guest, where
     * the cause is much harder to see than it is here.
     */
    private const val SLACK_BYTES = 16L * 1024 * 1024

    /** What [ensure] did. */
    sealed class Outcome {
        /** This APK ships no build. A development build of the app is in this state. */
        object NotBundled : Outcome()

        /** On disk and readable, whether it was already there or was extracted just now. */
        data class Ready(val build: SharpEmuBuild) : Outcome()

        /** Refused before writing anything. Both numbers are named so the toast can be specific. */
        data class OutOfSpace(val needed: Long, val free: Long) : Outcome()

        /** Anything else. Already logged; [why] is what a toast may say. */
        data class Failed(val why: String) : Outcome()
    }

    /** Reports extraction progress in bytes. Called on the worker, often. */
    fun interface Progress {
        fun onProgress(done: Long, total: Long)
    }

    /** True when this APK carries a build. Cheap: it opens one small asset. */
    @JvmStatic
    fun isBundled(context: Context): Boolean = assetMeta(context) != null

    /**
     * What the bundled build *is*, without extracting it.
     *
     * **Read from the asset when there is one, and from disk otherwise.** The build manager and the
     * settings row both have to name the bundled build before any game has been launched — the whole
     * point of pinning it is that it is there and selected from the first time the screen is opened
     * — and at that moment it is 76 MB of APK and not a directory. Its identity is in the asset's
     * `meta.json` either way, so the screen can be honest about a build the disk has never seen.
     *
     * The disk fallback covers a real case rather than a hypothetical one: installing a development
     * APK over a release one leaves the extracted directory behind, and it is still a build that
     * runs.
     */
    @JvmStatic
    fun identity(context: Context, internal: File): SharpEmuBuild? {
        val meta = assetMeta(context) ?: return SharpEmuBuild.bundled(internal)
        return SharpEmuBuild.fromAsset(target(internal), meta)
    }

    /**
     * Puts the bundled build on disk if this APK ships one and it is not already there, and answers
     * with what a launch may run.
     *
     * **Off the main thread.** It writes 76 MB.
     *
     * @param progress called as bytes land. Nothing draws it today — a launch shows a black screen
     *   throughout — and it is reported anyway, because the caller is the only place that can decide
     *   whether a wait is worth showing and this is the only place that knows how far along it is.
     */
    @JvmStatic
    fun ensure(context: Context, internal: File, progress: Progress): Outcome {
        val meta = assetMeta(context) ?: return Outcome.NotBundled
        val target = target(internal)

        val onDisk = SharpEmuBuild.read(target)
        if (onDisk != null && !isStale(context, target, meta)) {
            return Outcome.Ready(onDisk)
        }
        if (onDisk != null) {
            Log.i(TAG, "[app] the bundled build on disk is not the one in this APK, so re-extracting")
        }

        val contents = contents(context) ?: return Outcome.Failed(
            context.getString(R.string.bundled_failed)
        )
        val total = contents.sumOf { it.bytes }
        // asked before a byte is written, because the alternative is discovering it 60 MB in and
        // leaving the user with a black screen and a partially filled disk.
        val free = internal.usableSpace
        if (free < total + SLACK_BYTES) {
            Log.e(TAG, "[app] the bundled build needs $total bytes and $internal has $free free")
            return Outcome.OutOfSpace(total, free)
        }

        val extracted = extract(context, internal, contents, total, progress)
            ?: return Outcome.Failed(context.getString(R.string.bundled_failed))
        return Outcome.Ready(extracted)
    }

    /** Where it lands: the reserved folder, which no derived build folder name can collide with. */
    private fun target(internal: File) = File(internal, SharpEmuBuild.BUNDLED)

    /**
     * Whether what is on disk is the build this APK carries.
     *
     * **The commit is the test**, and it is the reason `meta.json` records one: `sharpemuVersion` is
     * upstream's tag and the fork moves faster than upstream, so two builds of one tag are the
     * ordinary case and are identical in every other field a comparison could use. Re-extracting on
     * every app update would be the alternative, and it would charge 76 MB of writes for an update
     * that changed a string in the settings scene.
     *
     * **With no commit on either side the whole `meta.json` is compared instead.** A build packaged
     * from a published archive records none — there was no checkout to ask — and something still has
     * to notice when the APK starts carrying a different one.
     *
     * What it cannot see is a payload rebuilt from a dirty fork tree at the same commit.
     * `scripts/package-build.py` warns when it packages one, which is where that belongs.
     */
    private fun isStale(context: Context, target: File, assetMeta: JSONObject): Boolean {
        val diskMeta = File(target, "meta.json")
        val assetCommit = assetMeta.optString("commit", "")
        val diskCommit = runCatching { JSONObject(diskMeta.readText()) }
            .getOrNull()?.optString("commit", "") ?: return true
        if (assetCommit.isNotEmpty() && diskCommit.isNotEmpty()) {
            return assetCommit != diskCommit
        }
        val asset = runCatching { context.assets.open("$ASSETS/meta.json").use { it.readBytes() } }
            .getOrNull() ?: return true
        return !asset.contentEquals(runCatching { diskMeta.readBytes() }.getOrNull() ?: return true)
    }

    /** One line of [CONTENTS]. */
    private class Item(val bytes: Long, val path: String)

    private fun contents(context: Context): List<Item>? = try {
        context.assets.open("$ASSETS/$CONTENTS").use { input ->
            input.readBytes().decodeToString().lineSequence()
                .filter { it.isNotBlank() }
                .map { line ->
                    // tab, because a path may contain a space and a size never contains a tab.
                    val tab = line.indexOf('\t')
                    if (tab < 1) throw IOException("malformed line in $CONTENTS: '$line'")
                    Item(line.substring(0, tab).trim().toLong(), line.substring(tab + 1))
                }
                .toList()
        }
    } catch (e: Exception) {
        Log.e(TAG, "[app] could not read the bundled build's $CONTENTS", e)
        null
    }

    /**
     * Writes the tree beside its target and renames it into place.
     *
     * **`.partial` then rename, the shape [BuildImport] uses**, and for the same reason: a
     * half-extracted directory has a `meta.json`, so it would be listed and resolved and would then
     * fail somewhere inside SharpEmu. [SharpEmuBuild.list] and [SharpEmuBuild.mostRecent] both skip
     * a `.partial`, so one left behind by a process that died is invisible rather than dangerous.
     */
    private fun extract(
        context: Context,
        internal: File,
        contents: List<Item>,
        total: Long,
        progress: Progress,
    ): SharpEmuBuild? {
        val partial = File(internal, SharpEmuBuild.BUNDLED + ".partial")
        partial.deleteRecursively()
        if (!partial.mkdirs()) {
            Log.e(TAG, "[app] could not create $partial")
            return null
        }
        val started = System.currentTimeMillis()
        var done = 0L
        try {
            for (item in contents) {
                val out = File(partial, item.path)
                out.parentFile?.mkdirs()
                context.assets.open("$ASSETS/${item.path}").use { input ->
                    FileOutputStream(out).use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            progress.onProgress(done, total)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[app] could not extract the bundled build", e)
            partial.deleteRecursively()
            return null
        }

        val target = target(internal)
        // the old one goes only once the new one is complete, so an extraction that dies leaves the
        // build that was working exactly where it was.
        target.deleteRecursively()
        if (!partial.renameTo(target)) {
            Log.e(TAG, "[app] could not move $partial to $target")
            partial.deleteRecursively()
            return null
        }
        Log.i(TAG, "[app] extracted the bundled build, $done bytes to $target in "
                + (System.currentTimeMillis() - started) + " ms")
        // read back rather than trusting the asset's meta: what is on disk is what will be launched,
        // and this is the last moment a mismatch is cheap to notice.
        val build = SharpEmuBuild.read(target)
        if (build == null || !build.runnable()) {
            Log.e(TAG, "[app] $target extracted and is not a runnable build")
            target.deleteRecursively()
            return null
        }
        return build
    }

    /** The asset's `meta.json`, or null when this APK ships no build. */
    private fun assetMeta(context: Context): JSONObject? = try {
        context.assets.open("$ASSETS/meta.json").use {
            JSONObject(it.readBytes().decodeToString())
        }
    } catch (e: IOException) {
        // the ordinary answer in a development build, so it is not logged.
        null
    } catch (e: Exception) {
        Log.e(TAG, "[app] this APK carries a bundled build with an unreadable meta.json", e)
        null
    }
}
