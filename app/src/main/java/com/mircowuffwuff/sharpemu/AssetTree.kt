package com.mircowuffwuff.sharpemu

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * a directory tree that ships inside the APK as assets, and what turns one into a directory on disk.
 *
 * **two trees use this** -- the one SharpEmu build in [BundledBuild] and the guest's x86-64 shared
 * objects in [GuestLibraries] -- and they want the same four things: how big the extraction is before
 * it starts, which names are directories, a write that cannot be seen half done, and a way to tell
 * what is on disk from what the APK carries. what differs between them is only that last one, so it
 * is the caller's, and everything above it is here.
 *
 * **an asset tree rather than a zip.** the APK is already a zip, so an archive inside it would be
 * compressed twice and the device would pay to undo both.
 */
object AssetTree {

    private const val TAG = "sharpemu"

    /**
     * the generated file listing every asset in a tree, `<size>\t<path>` per line.
     *
     * **it is packaging's file rather than part of anything's format.** it exists for two things an
     * APK asset cannot otherwise answer:
     *
     * - **how big the extraction is, before it starts.** a compressed asset has no length --
     *   `openFd` refuses one, and `open` only tells you when it hits the end -- so without this the
     *   free-space check would have to happen halfway through the write it exists to prevent, and
     *   progress would have to be counted in files. one file in the build's tree is 61 MB and the
     *   rest are small, so a count of files sits at 4% for the entire extraction
     * - **which names are directories.** `AssetManager.list` returns names and not kinds, so telling
     *   a file from a directory means trying to open each one and reading a failure as "directory" --
     *   a guess that gets an empty directory and an unreadable file exactly backwards
     *
     * it is not itself extracted: the packaging step writes it after walking the tree, so it does not
     * appear in its own listing, and what lands on the device is the tree and not the note packaging
     * left for the unpacking step.
     */
    const val CONTENTS = "contents"

    /**
     * slack over an extraction's own size, asked for before a byte is written.
     *
     * a tree costs a little more than the sum of its files, and a device with nothing left after
     * writing one is a device that fails somewhere else a minute later -- inside the guest, where the
     * cause is much harder to see than it is here.
     */
    const val SLACK_BYTES = 16L * 1024 * 1024

    /** reports extraction progress in bytes. called on the worker, often. */
    fun interface Progress {
        fun onProgress(done: Long, total: Long)
    }

    /** one line of [CONTENTS]. */
    class Item(val bytes: Long, val path: String)

    /** every line of [assets]`/`[CONTENTS], or null when this APK carries no such tree. */
    fun contents(context: Context, assets: String): List<Item>? = try {
        context.assets.open("$assets/$CONTENTS").use { input ->
            input.readBytes().decodeToString().lineSequence()
                .filter { it.isNotBlank() }
                .map { line ->
                    // tab, because a path may contain a space and a size never contains a tab.
                    val tab = line.indexOf('\t')
                    if (tab < 1) throw IOException("malformed line in $assets/$CONTENTS: '$line'")
                    Item(line.substring(0, tab).trim().toLong(), line.substring(tab + 1))
                }
                .toList()
        }
    } catch (e: Exception) {
        AppLog.e(TAG, "[app] could not read $assets/$CONTENTS", e)
        null
    }

    /** a short asset read whole, or null when it is not in this APK. used for identity files. */
    fun text(context: Context, path: String): String? = try {
        context.assets.open(path).use { it.readBytes().decodeToString().trim() }
    } catch (e: IOException) {
        // the ordinary answer for a tree this APK does not carry, so it is not logged.
        null
    } catch (e: Exception) {
        AppLog.e(TAG, "[app] could not read the asset $path", e)
        null
    }

    /** whether the volume [where] is on has room for [needed] and [SLACK_BYTES] on top of it. */
    fun hasSpace(where: File, needed: Long): Boolean = freeSpace(where) >= needed + SLACK_BYTES

    /**
     * free space for [where], **asked of the nearest ancestor that exists**.
     *
     * **that is the whole function and it is not a nicety.** `File.usableSpace` answers 0 for a path
     * that is not there, and the directory a tree unpacks into is precisely the thing that does not
     * exist yet on the launch which has to create it -- so asking it directly reports a full disk on a
     * device with 48 GB free, and the launch aborts with a toast saying so. it is the state the
     * platform's own *delete everything* leaves, which is exactly when this runs.
     *
     * free space is a property of the volume rather than of a directory, and every ancestor of a path
     * is on the same volume, so walking up costs nothing and cannot answer about somewhere else.
     */
    fun freeSpace(where: File): Long {
        var at: File? = where.absoluteFile
        while (at != null && !at.exists()) {
            at = at.parentFile
        }
        return at?.usableSpace ?: 0L
    }

    /**
     * writes [assets] to a sibling of [target] and renames it into place.
     *
     * **`.partial` then rename, the shape [BuildImport] uses**, and for the same reason: a half
     * extracted directory looks like a whole one to everything that reads it later, and would fail
     * much further from the cause. the old tree goes only once the new one is complete, so an
     * extraction that dies leaves the one that was working exactly where it was.
     *
     * **[stamp] is written into the partial directory as the last file of all**, which is what makes
     * an interrupted extraction detectable without hashing anything: no stamp means unfinished,
     * whatever files are lying about. it is written before the rename rather than after so that the
     * rename is the moment the tree becomes complete, and there is no window in which a finished
     * directory is missing the file that says so.
     *
     * @param label what this tree is called in the log -- the asset directory's own name is what the
     *   code calls it and not what a reader of a launch log is looking for.
     * @return the number of bytes written, or null if anything failed. already logged.
     */
    fun extract(
        context: Context,
        assets: String,
        label: String,
        contents: List<Item>,
        target: File,
        progress: Progress,
        stamp: Pair<String, String>? = null,
    ): Long? {
        val partial = File(target.parentFile, target.name + ".partial")
        partial.deleteRecursively()
        if (!partial.mkdirs()) {
            AppLog.e(TAG, "[app] could not create $partial")
            return null
        }
        val started = System.currentTimeMillis()
        val total = contents.sumOf { it.bytes }
        var done = 0L
        try {
            for (item in contents) {
                val out = File(partial, item.path)
                out.parentFile?.mkdirs()
                context.assets.open("$assets/${item.path}").use { input ->
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
            stamp?.let { (name, text) -> File(partial, name).writeText(text) }
        } catch (e: Exception) {
            AppLog.e(TAG, "[app] could not extract $label", e)
            partial.deleteRecursively()
            return null
        }

        target.deleteRecursively()
        if (!partial.renameTo(target)) {
            AppLog.e(TAG, "[app] could not move $partial to $target")
            partial.deleteRecursively()
            return null
        }
        AppLog.i(TAG, "[app] extracted $label, $done bytes to $target in "
                + (System.currentTimeMillis() - started) + " ms")
        return done
    }
}
