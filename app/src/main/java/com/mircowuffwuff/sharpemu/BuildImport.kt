package com.mircowuffwuff.sharpemu

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * taking a build zip the user picked and turning it into a build directory.
 *
 * **this is the one place in the frontend where a wrong answer is expensive**, and the failure it
 * exists to refuse is named in `docs/build-format.md`: a payload that ignores `SHARPEMU_HOST_AUDIO`
 * renders perfectly, makes no sound, and reports no error anywhere -- which arrives as "the emulator
 * has no audio" and names the wrong component entirely. everything below is arranged so that a zip
 * which is not a runnable build is refused *by name* before a byte of it is written.
 *
 * **the zip is read twice and that is deliberate.** a first pass validates without creating
 * anything; a second extracts. the alternative -- extract, then judge -- leaves a half-written
 * directory to clean up on every refusal, and a cleanup is the step that gets skipped on the path
 * nobody tests.
 *
 * `docs/build-format.md` owns the format. nothing here restates it beyond what it has to check.
 */
object BuildImport {

    private const val TAG = "sharpemu"

    /**
     * below this, a zip with no identity is called too small rather than called unidentifiable.
     *
     * **it decides a sentence and never an outcome.** a size check is the obvious thing to reach
     * for, and a size is the least informative thing a zip has: a real build is tens of megabytes
     * and an adrenotools driver package is a few, so a floor does separate them -- but it separates
     * them by the wrong property, and a driver refused for being small is a true statement that
     * sends somebody looking in the wrong place. what a file is gets decided by what is inside it.
     */
    private const val MINIMUM_BYTES = 4L * 1024 * 1024

    /** what happened, in the words the toast will use. */
    sealed class Result {
        /** Imported and selected. */
        data class Imported(val build: SharpEmuBuild) : Result()

        /** refused, with the reason already resolved to a string. */
        data class Refused(val reason: String) : Result()
    }

    /**
     * validates and imports, or refuses and writes nothing.
     *
     * off the main thread: it reads and writes tens of megabytes through a content provider.
     *
     * @param internal `getFilesDir()/builds` -- where builds are run from.
     */
    fun import(context: Context, zip: Uri, internal: File): Result {
        val name = displayName(context, zip)
        val found = scan(context, zip)
            ?: return refuse(context.getString(R.string.build_import_unreadable, name))

        // **an adrenotools driver package also has a meta.json at its root**, which is the whole
        // reason this check is worth writing down rather than assuming a meta file settles it. its
        // own fields are what tell the two apart: a driver names a `libraryName` and a
        // `schemaVersion` and has no `hostContract` and no payload.
        if (found.looksLikeDriver) {
            return refuse(context.getString(R.string.build_import_is_a_driver, name))
        }
        val json = found.meta ?: run {
            // **the size is a wording and not a gate, and it was a gate for exactly one build.** as
            // a gate it ran first and refused the driver package on the grounds of being small,
            // naming a cause that was true and useless -- on the one screen where a wrong diagnosis
            // is expensive. so the contents decide, and the size only chooses which sentence a file
            // with no identity gets.
            val size = sizeOf(context, zip)
            return refuse(
                if (size in 0 until MINIMUM_BYTES) {
                    context.getString(R.string.build_import_too_small, name)
                } else {
                    context.getString(R.string.build_import_no_meta, name)
                }
            )
        }
        val id = json.optString("id", "").ifEmpty {
            // a meta.json with no `id` is readable and still has no identity. the format says an
            // absent id means the folder name, and a zip has no folder name to mean.
            return refuse(context.getString(R.string.build_import_no_id, name))
        }
        val version = json.optString("sharpemuVersion", "0")
        val packagedAt = json.optLong("packagedAt", 0)
        val contract = json.optInt("hostContract", 0)
        val payload = json.optString("payload", "SharpEmu")

        if (contract < SharpEmuBuild.CONTRACT_MIN || contract > SharpEmuBuild.CONTRACT_MAX) {
            return refuse(
                context.getString(
                    R.string.build_import_contract, contract,
                    SharpEmuBuild.CONTRACT_MIN, SharpEmuBuild.CONTRACT_MAX,
                )
            )
        }
        // the payload and its plugins, because a build is a directory and a payload staged alone is
        // a payload with no audio and no video -- SharpEmu resolves `plugins/` relative to its own
        // executable. a zip missing either is a build that would boot and then be wrong.
        if (payload !in found.files) {
            return refuse(context.getString(R.string.build_import_no_payload, payload))
        }
        if (!found.hasPlugins) {
            return refuse(context.getString(R.string.build_import_no_plugins))
        }

        val folder = folderName(id, version, packagedAt)
        val target = File(internal, folder)
        if (target.isDirectory) {
            return refuse(context.getString(R.string.build_import_already_there, folder))
        }

        val installed = extract(context, zip, internal, folder)
            ?: return refuse(context.getString(R.string.build_import_failed, name))
        Log.i(TAG, "[app] imported " + installed.identity() + " from " + name + " to " + installed.dir)
        return Result.Imported(installed)
    }

    private fun refuse(reason: String): Result {
        Log.e(TAG, "[app] refused a build zip: $reason")
        return Result.Refused(reason)
    }

    /**
     * the on-device folder name, derived from the identity and never from what the zip is called.
     *
     * `docs/build-format.md` fixes this, and both the packaging script and the staging script derive
     * it the same way -- so a zip renamed on somebody's disk imports to the same place it always
     * would, and two builds of one source coexist instead of overwriting each other.
     */
    private fun folderName(id: String, version: String, packagedAt: Long): String =
        "$id-$version-$packagedAt".lowercase(Locale.ROOT).map {
            if (it.isDigit() || it in 'a'..'z' || it == '.' || it == '_' || it == '-') it else '-'
        }.joinToString("")

    /**
     * a zip entry's path, with separators made the ones the format actually uses.
     *
     * **PowerShell's `Compress-Archive` writes backslashes**, which the zip specification does not
     * allow and which most tools quietly tolerate. it matters here because it is the first thing a
     * person on Windows reaches for when packaging a build by hand: without this, such a zip is
     * refused for having no `plugins/` folder -- a message naming a cause that is not the cause, on
     * the one screen where a wrong diagnosis is expensive.
     *
     * `scripts/package-build.py` produces conforming zips; this is for the ones it did not make.
     */
    private fun normalise(name: String): String = name.replace('\\', '/')

    /** what the first pass learned, without having written anything. */
    private class Scan(
        val meta: JSONObject?,
        val files: Set<String>,
        val hasPlugins: Boolean,
        val looksLikeDriver: Boolean,
    )

    /**
     * reads the zip's entry names and its root `meta.json`, and writes nothing.
     *
     * `meta.json` has to be at the **zip root** rather than inside a wrapper directory, and that is
     * the single thing most likely to differ between two hand-made packages -- so a wrapper is
     * refused as "no meta file" rather than being searched for, because guessing which of two
     * candidate roots was meant is how a build gets imported half-flattened.
     */
    private fun scan(context: Context, zip: Uri): Scan? {
        var meta: JSONObject? = null
        val files = HashSet<String>()
        var hasPlugins = false
        try {
            open(context, zip).use { input ->
                ZipInputStream(input).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val entryName = normalise(entry.name)
                        if (entryName.startsWith("plugins/")) hasPlugins = true
                        if (!entry.isDirectory) files += entryName
                        if (entryName == "meta.json") {
                            meta = runCatching { JSONObject(zis.readBytes().decodeToString()) }
                                .getOrNull()
                        }
                        zis.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[app] could not read $zip", e)
            return null
        }
        val json = meta
        val driver = json != null && !json.has("hostContract") && !json.has("payload")
                && (json.has("libraryName") || json.has("schemaVersion"))
        return Scan(json, files, hasPlugins, driver)
    }

    /**
     * the second pass: writes the tree, beside its target and then renamed.
     *
     * **`.partial` then rename, the same shape [BundledBuild] unpacks with**, so an import
     * interrupted by the process dying leaves nothing that resolution or the list would find. a
     * half-extracted build that looks complete is the worst outcome available here -- it has a
     * `meta.json`, so it would be listed, and it would fail somewhere inside SharpEmu.
     */
    private fun extract(context: Context, zip: Uri, internal: File, folder: String): SharpEmuBuild? {
        val partial = File(internal, "$folder.partial")
        partial.deleteRecursively()
        if (!partial.mkdirs()) {
            Log.e(TAG, "[app] could not create $partial")
            return null
        }
        val started = System.currentTimeMillis()
        var bytes = 0L
        try {
            open(context, zip).use { input ->
                ZipInputStream(input).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        // **a zip entry is an untrusted name.** `../` in one writes outside the
                        // directory being extracted into, which for this app means anywhere in its
                        // own data. the canonical path is checked rather than the string, because
                        // the ways to spell an escape are more numerous than the ways to grep it.
                        val name = normalise(entry.name)
                        val out = File(partial, name)
                        if (!out.canonicalPath.startsWith(partial.canonicalPath + File.separator)) {
                            Log.e(TAG, "[app] $name points outside the build directory")
                            partial.deleteRecursively()
                            return null
                        }
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { bytes += zis.copyTo(it) }
                        }
                        zis.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[app] could not extract $zip", e)
            partial.deleteRecursively()
            return null
        }

        val target = File(internal, folder)
        if (!partial.renameTo(target)) {
            Log.e(TAG, "[app] could not move $partial to $target")
            partial.deleteRecursively()
            return null
        }
        Log.i(TAG, "[app] extracted $bytes bytes to $target in "
                + (System.currentTimeMillis() - started) + " ms")
        // read back rather than trusting the meta the first pass parsed: what is on disk is what
        // will be launched, and this is the last moment a mismatch is cheap to notice.
        val build = SharpEmuBuild.read(target)
        if (build == null || !build.runnable()) {
            Log.e(TAG, "[app] $target extracted and is not a runnable build")
            target.deleteRecursively()
            return null
        }
        return build
    }

    private fun open(context: Context, zip: Uri): InputStream =
        context.contentResolver.openInputStream(zip) ?: throw java.io.IOException("no stream for $zip")

    /** the provider's own size, or -1 when it does not offer one. */
    private fun sizeOf(context: Context, zip: Uri): Long =
        context.contentResolver.query(zip, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
                    cursor.getLong(column)
                } else {
                    -1L
                }
            } ?: -1L

    /** what to call the file in a message. the provider's display name, or the last path segment. */
    private fun displayName(context: Context, zip: Uri): String =
        context.contentResolver.query(zip, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            } ?: zip.lastPathSegment ?: "that file"
}
