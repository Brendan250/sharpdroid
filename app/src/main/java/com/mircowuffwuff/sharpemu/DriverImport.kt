package com.mircowuffwuff.sharpemu

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * taking a driver package the user picked and turning it into a directory the loader can be pointed
 * at.
 *
 * **the mirror of [BuildImport], and it inherits that file's two lessons rather than rediscovering
 * them.** the zip is read twice -- a first pass that validates and writes nothing, a second that
 * extracts -- because the alternative leaves a half-written directory to clean up on every refusal,
 * and a cleanup is the step that gets skipped on the path nobody tests. and **what a file is gets
 * decided by what is inside it**: a size check separates a driver from a build perfectly well and
 * separates them by the wrong property, so a size here only ever chooses a sentence.
 *
 * **what this refuses matters more than what it accepts.** a package for a platform older than this
 * one, or one whose `.so` is missing, fails inside the loader -- where the report is a black screen
 * with nothing in it naming a driver.
 */
object DriverImport {

    private const val TAG = "sharpemu"

    /**
     * above this, a zip with no identity is called too large rather than called unidentifiable.
     *
     * a driver package is a few megabytes and a SharpEmu build is tens, so this is the wording for
     * somebody who picked a build zip that has no `meta.json` to say so with. it decides a sentence
     * and never an outcome.
     */
    private const val MAXIMUM_BYTES = 64L * 1024 * 1024

    /** what happened, in the words the toast will use. */
    sealed class Result {
        /** Imported and selected. */
        data class Imported(val driver: GpuDriver) : Result()

        /** refused, with the reason already resolved to a string. */
        data class Refused(val reason: String) : Result()
    }

    /**
     * validates and imports, or refuses and writes nothing.
     *
     * off the main thread: it reads and writes megabytes through a content provider.
     *
     * @param internal `getFilesDir()/gpu-drivers` -- where a package is loaded from, because the
     *   linker will not `dlopen` a library from anywhere else.
     */
    fun import(context: Context, zip: Uri, internal: File): Result {
        val name = displayName(context, zip)
        val found = scan(context, zip)
            ?: return refuse(name, context.getString(R.string.driver_import_unreadable))

        // **a SharpEmu build also has a meta.json at its root**, which is why a meta file settles
        // nothing on its own. the fields are what tell the two apart, and this is the same test
        // BuildImport applies from the other side -- a build names a `hostContract` and a payload.
        if (found.looksLikeBuild) {
            return refuse(name, context.getString(R.string.driver_import_is_a_build))
        }
        val json = found.meta ?: run {
            val size = sizeOf(context, zip)
            return refuse(
                name,
                if (size > MAXIMUM_BYTES) {
                    context.getString(R.string.driver_import_too_large)
                } else {
                    context.getString(R.string.driver_import_no_meta)
                }
            )
        }

        val libraryName = json.optString("libraryName", "")
        if (libraryName.isEmpty()) {
            return refuse(name, context.getString(R.string.driver_import_no_library_name))
        }
        // the library the package names, which is the one thing a driver has to contain. nothing
        // here knows what it should be called: two of the packages this project tests with do not
        // call it libvulkan_freedreno.so.
        if (libraryName !in found.files) {
            return refuse(name, context.getString(R.string.driver_import_no_library, libraryName))
        }

        val minApi = json.optInt("minApi", 0)
        if (minApi > Build.VERSION.SDK_INT) {
            return refuse(
                name,
                context.getString(R.string.driver_import_min_api, minApi, Build.VERSION.SDK_INT)
            )
        }

        val driverName = json.optString("name", "").ifEmpty {
            return refuse(name, context.getString(R.string.driver_import_no_name))
        }
        val version = json.optString("driverVersion", "").ifEmpty { json.optString("packageVersion", "") }

        val folder = GpuDriver.folderName(driverName, version)
        // **the reserved word, refused rather than allowed to shadow the pinned row.** no derived
        // name reaches it -- [GpuDriver.folderName] always leaves a `-` in -- so this is a guard
        // against the derivation changing rather than against a package that exists.
        if (GpuDriver.isSystem(folder)) {
            return refuse(name, context.getString(R.string.driver_import_reserved, folder))
        }
        if (File(internal, folder).isDirectory) {
            return refuse(name, context.getString(R.string.driver_import_already_there, folder))
        }

        val installed = extract(context, zip, internal, folder)
            ?: return refuse(name, context.getString(R.string.driver_import_failed))
        Log.i(TAG, "[app] imported " + installed.identity() + " from " + name + " to " + installed.dir)
        return Result.Imported(installed)
    }

    /**
     * **the file is named in the log and not in the toast.**
     *
     * a toast is two lines and truncates without warning, and driver packages are distributed under
     * names like `turnip_mrpurple_T29-toasted.adpkg.zip` -- long enough that a message opening with
     * one loses the half that says what the file actually is, which is the half worth having. the
     * person reading it picked the file a second ago; the log is what somebody reads later.
     */
    private fun refuse(file: String, reason: String): Result {
        Log.e(TAG, "[app] refused the driver zip $file: $reason")
        return Result.Refused(reason)
    }

    /**
     * a zip entry's path, with separators made the ones the format actually uses.
     *
     * PowerShell's `Compress-Archive` writes backslashes, which most tools quietly tolerate -- and
     * which would make a repackaged driver look like it was missing the library it names.
     */
    private fun normalise(name: String): String = name.replace('\\', '/')

    /** what the first pass learned, without having written anything. */
    private class Scan(
        val meta: JSONObject?,
        val files: Set<String>,
        val looksLikeBuild: Boolean,
    )

    /**
     * reads the zip's entry names and its root `meta.json`, and writes nothing.
     *
     * `meta.json` has to be at the **zip root**, which is where every adrenotools package puts it. a
     * wrapper directory is refused as "no meta file" rather than searched for: guessing which of two
     * candidate roots was meant is how a package gets extracted half-flattened, and a driver whose
     * `.so` ends up one level down is one the loader cannot find.
     */
    private fun scan(context: Context, zip: Uri): Scan? {
        var meta: JSONObject? = null
        val files = HashSet<String>()
        try {
            open(context, zip).use { input ->
                ZipInputStream(input).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val entryName = normalise(entry.name)
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
        val build = json != null && (json.has("hostContract") || json.has("payload"))
        return Scan(json, files, build)
    }

    /**
     * the second pass: writes the tree, beside its target and then renamed.
     *
     * **`.partial` then rename**, the shape both other extractors in this app use, so a package
     * interrupted by the process dying leaves nothing the list or a launch would find. a
     * half-extracted driver that looks complete is the worst outcome available here: it has a
     * `meta.json`, so it would be listed and selectable, and it would fail inside the loader.
     */
    private fun extract(context: Context, zip: Uri, internal: File, folder: String): GpuDriver? {
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
                        // own data. the canonical path is checked rather than the string.
                        val name = normalise(entry.name)
                        val out = File(partial, name)
                        if (!out.canonicalPath.startsWith(partial.canonicalPath + File.separator)) {
                            Log.e(TAG, "[app] $name points outside the driver directory")
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
        // will be loaded, and this is the last moment a mismatch is cheap to notice.
        val driver = GpuDriver.read(target)
        if (driver == null || !driver.usable()) {
            Log.e(TAG, "[app] $target extracted and is not a usable driver package")
            target.deleteRecursively()
            return null
        }
        return driver
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
