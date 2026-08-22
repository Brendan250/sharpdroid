package com.mircowuffwuff.sharpdroid

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * the zip behind the User data screen's Export and Import.
 *
 * **two shapes, one format.** an [Kind.EVERYTHING] archive carries `files/` and `shared_prefs/` as
 * they sit inside this app's data directory; a [Kind.SAVE_DATA] archive carries one directory per
 * title id. both open with an `export.json` at the root, and that file is what stops one being fed to
 * the other's Import -- a save-data archive extracted over a whole install would be a wipe wearing the
 * wrong button's name.
 *
 * **nothing is written until the whole archive has been read.** an import extracts into the cache
 * directory first and only then replaces anything, which is [BuildImport]'s `.partial` rule applied
 * to a larger target: a corrupt zip, a full disk or a backed-out picker leaves the install exactly as
 * it was. the two are on the same filesystem, so the move at the end is a rename rather than a second
 * copy of every byte.
 */
object UserDataArchive {

    private const val TAG = "sharpdroid"

    /** the marker at the root of every archive this app writes. */
    const val MANIFEST = "export.json"

    /**
     * **paths never packed and never replaced**, relative to the data directory.
     *
     * **matched exactly, never by substring**, and it is a rule about paths rather than names for a
     * reason that outlives this particular list. `gpu-drivers` is the name of three directories in
     * three roots, only one of which an export packs; the derived copies of staged drivers' libraries
     * are in the cache directory, which an export does not reach at all. so the day something here
     * shares a name -- or a prefix -- with a directory that *is* packed, a `contains` test would drop
     * every imported driver on the device, silently, which is the collection an export exists to
     * carry. a whole-path match cannot make that mistake.
     *
     * - `files/profileInstalled` is `androidx.profileinstaller`'s marker for *this* install.
     * - `files/builds/bundled` is the build unpacked out of this APK. every install can lay it down
     *   again in about a quarter of a second, so carrying 76 MB of it would roughly double an archive
     *   to deliver bytes the destination already has inside its own package.
     * - `files/guest-libs` is the x86-64 set unpacked out of this APK, and the argument above
     *   transfers word for word: 12 MB the destination already holds in its own package, remade in
     *   the time it takes to unpack. **never replaced matters more here than never packed** -- an
     *   archive written by an older app version must not lay an older library set over a newer one,
     *   because the failure is a guest resolving an old thunk stub against a new host layer rather
     *   than anything that looks like a missing file.
     */
    val IGNORED = setOf(
        "files/profileInstalled",
        "files/builds/bundled",
        "files/guest-libs",
    )

    enum class Kind(val id: String) {
        EVERYTHING("everything"),
        SAVE_DATA("savedata"),
    }

    /**
     * what an archive says about itself.
     *
     * [appVersion] is `versionCode`, which only ever rises -- ten of them to a release, the values
     * between being what a diagnostic build takes -- so an archive from a later build is
     * recognisable as one rather than merely failing oddly.
     */
    data class Manifest(val kind: Kind, val exportedAt: Long, val appVersion: Int)

    // ---------------------------------------------------------------------------------------------
    // export

    /**
     * packs [roots] into [out], each entry named by its path relative to [base].
     *
     * @return the number of bytes read off disk, or null if anything failed. a failure leaves the
     *   document the picker created behind -- it is the user's file in the user's chosen place, and
     *   this app has no business deleting it.
     */
    private fun pack(
        context: Context,
        out: Uri,
        base: File,
        roots: List<File>,
        kind: Kind,
    ): Long? {
        val started = System.currentTimeMillis()
        var bytes = 0L
        try {
            context.contentResolver.openOutputStream(out)?.use { stream ->
                ZipOutputStream(stream.buffered()).use { zip ->
                    // the manifest first, so a reader can refuse an archive without having read all
                    // of it -- the scan below stops at the moment it finds this.
                    zip.putNextEntry(ZipEntry(MANIFEST))
                    zip.write(manifest(context, kind).toString().toByteArray())
                    zip.closeEntry()
                    roots.forEach { bytes += write(zip, base, it) }
                }
            } ?: run {
                Log.e(TAG, "[app] could not open $out for writing")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "[app] could not write $out", e)
            return null
        }
        Log.i(TAG, "[app] packed $bytes bytes into $out in "
                + (System.currentTimeMillis() - started) + " ms")
        return bytes
    }

    private fun manifest(context: Context, kind: Kind): JSONObject = JSONObject()
        .put("kind", kind.id)
        .put("exportedAt", System.currentTimeMillis())
        .put("appVersion", versionCode(context))

    private fun versionCode(context: Context): Int = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    } catch (e: Exception) {
        0
    }

    /** walks [file], writing every ordinary file under it. skips [IGNORED] and follows nothing. */
    private fun write(zip: ZipOutputStream, base: File, file: File): Long {
        val name = relative(base, file) ?: return 0L
        if (name in IGNORED) return 0L
        if (file.isDirectory) {
            // a symlink into somewhere else would otherwise be walked as if it were ours.
            if (isLink(file)) return 0L
            return file.listFiles()?.sumOf { write(zip, base, it) } ?: 0L
        }
        if (!file.isFile || isLink(file)) return 0L
        zip.putNextEntry(ZipEntry(name))
        val bytes = file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
        return bytes
    }

    /**
     * whether [file] is itself a symlink.
     *
     * **the file, not its path.** comparing a canonical path against an absolute one answers a
     * different question -- whether *anything along the way* is a link -- and on android that is always
     * yes inside an app's own data: `/data/data/<package>` is a link to `/data/user/0/<package>`, so
     * every single entry resolves elsewhere and a walk guarded that way packs nothing at all.
     */
    private fun isLink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    private fun relative(base: File, file: File): String? {
        val root = base.absolutePath + File.separator
        val path = file.absolutePath
        if (!path.startsWith(root)) return null
        return path.substring(root.length).replace(File.separatorChar, '/')
    }

    /** everything: `files/` and `shared_prefs/`, less [IGNORED]. */
    fun exportEverything(context: Context, out: Uri): Long? {
        val data = dataDir(context)
        return pack(
            context,
            out,
            data,
            listOf(File(data, "files"), File(data, "shared_prefs")),
            Kind.EVERYTHING,
        )
    }

    /** Save data: one directory per title id, at the archive root. */
    fun exportSaveData(context: Context, out: Uri): Long? {
        val saves = AppStorage.saveData(context.filesDir)
        if (!saves.isDirectory) return null
        return pack(context, out, saves, saves.listFiles()?.toList().orEmpty(), Kind.SAVE_DATA)
    }

    /**
     * one title's save data, in the same shape and under the same [Kind.SAVE_DATA] manifest.
     *
     * **a per-game archive is a save-data archive that happens to hold one title, and that is worth
     * more than a kind of its own.** it means an archive written here can be fed to the whole-of-it
     * Save data import, which merges by title and would take it correctly; and an archive written
     * there can be fed to *this* game's import, which reads its own title out of it and leaves the
     * rest. a second kind would have bought a refusal in both directions and nothing else.
     */
    fun exportGameSaveData(context: Context, out: Uri, titleId: String): Long? {
        val saves = AppStorage.saveData(context.filesDir)
        val title = File(saves, titleId)
        if (!title.isDirectory) return null
        return pack(context, out, saves, listOf(title), Kind.SAVE_DATA)
    }

    // ---------------------------------------------------------------------------------------------
    // import

    /**
     * reads an archive's manifest without writing anything.
     *
     * returns null for a zip this app did not write, one whose manifest does not parse, and one whose
     * `kind` is not a kind this build knows -- all of which are "not an archive of ours" and get the
     * same refusal, because a reader cannot tell them apart usefully and neither can the person.
     */
    fun read(context: Context, zip: Uri): Manifest? = try {
        context.contentResolver.openInputStream(zip)?.use { input ->
            ZipInputStream(input).use { zis ->
                var found: Manifest? = null
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (normalise(entry.name) == MANIFEST) {
                        found = parse(zis.readBytes().decodeToString())
                        break
                    }
                    zis.closeEntry()
                }
                found
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "[app] could not read $zip", e)
        null
    }

    private fun parse(text: String): Manifest? = try {
        val json = JSONObject(text)
        val kind = Kind.entries.firstOrNull { it.id == json.optString("kind") }
        if (kind == null) null else Manifest(
            kind,
            json.optLong("exportedAt", 0L),
            json.optInt("appVersion", 0),
        )
    } catch (e: Exception) {
        null
    }

    /**
     * extracts [zip] into a fresh staging directory under the cache, and writes nothing else.
     *
     * **a sibling of `files/` and `shared_prefs/` rather than either of them**, because an Everything
     * import replaces both -- staging inside one would be staging inside what is about to be cleared.
     *
     * **and not the cache directory either**, which is the obvious other sibling and the wrong one:
     * `cacheDir` is setgid to the app's cache group, so every file created under it inherits that
     * group and keeps it through the rename -- leaving the imported tree sitting in `files/` wearing
     * the group the platform reclaims space from.
     */
    private fun stage(context: Context, zip: Uri): Staged? {
        val staging = File(dataDir(context), STAGING)
        staging.deleteRecursively()
        if (!staging.mkdirs()) {
            Log.e(TAG, "[app] could not create $staging")
            return null
        }
        var bytes = 0L
        try {
            context.contentResolver.openInputStream(zip)?.use { input ->
                ZipInputStream(input).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val name = normalise(entry.name)
                        val out = File(staging, name)
                        // **a zip entry is an untrusted name**, the rule BuildImport states: `../` in
                        // one writes outside the directory being extracted into, which for this app
                        // means anywhere in its own data. the canonical path is checked rather than
                        // the string, because the ways to spell an escape outnumber the ways to grep
                        // for one.
                        if (!out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            Log.e(TAG, "[app] $name points outside the staging directory")
                            staging.deleteRecursively()
                            return null
                        }
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            // the manifest is not counted, so that what an import reports and what
                            // the export that wrote it reported are the same measurement.
                            val written = FileOutputStream(out).use { zis.copyTo(it) }
                            if (name != MANIFEST) bytes += written
                        }
                        zis.closeEntry()
                    }
                }
            } ?: return null
        } catch (e: Exception) {
            Log.e(TAG, "[app] could not extract $zip", e)
            staging.deleteRecursively()
            return null
        }
        return Staged(staging, bytes)
    }

    /** an unpacked archive, and how much of it there was. */
    private class Staged(val dir: File, val bytes: Long)

    /**
     * replaces `files/` and `shared_prefs/` with what the archive holds.
     *
     * **the caller has to end the process afterwards.** `SharedPreferences` is cached per process, so
     * the framework is still holding the settings this call just replaced on disk -- and the next
     * write of any row would put the old ones straight back over the imported file. see
     * [UserDataActivity].
     */
    fun importEverything(context: Context, zip: Uri): Long? {
        val staged = stage(context, zip) ?: return null
        val data = dataDir(context)
        try {
            // the delete and the move are one short window and it is not atomic. what makes that
            // acceptable is that everything able to fail - reading the archive, the disk filling,
            // an entry pointing somewhere it should not - has already happened by here.
            listOf("files", "shared_prefs").forEach { top ->
                clear(File(data, top), data)
            }
            staged.dir.listFiles()?.forEach { entry ->
                if (entry.name == MANIFEST) return@forEach
                if (!move(entry, File(data, entry.name))) return null
            }
        } finally {
            staged.dir.deleteRecursively()
        }
        Log.i(TAG, "[app] imported ${staged.bytes} bytes over $data")
        return staged.bytes
    }

    /**
     * merges one archive's titles into the save directory.
     *
     * a title in the archive replaces the same title on the device, wholesale rather than file by
     * file -- half of one save set and half of another is not a state any emulator's save format
     * promises to survive. **a title the archive does not mention is left alone**, which is what makes
     * this a merge: restoring one game's saves onto a device holding five others should not be a way
     * to lose the other five.
     */
    fun importSaveData(context: Context, zip: Uri): Long? {
        val staged = stage(context, zip) ?: return null
        val saves = AppStorage.saveData(context.filesDir)
        try {
            if (!saves.isDirectory && !saves.mkdirs()) {
                Log.e(TAG, "[app] could not create $saves")
                return null
            }
            staged.dir.listFiles()?.forEach { title ->
                if (!title.isDirectory) return@forEach
                val target = File(saves, title.name)
                target.deleteRecursively()
                if (!move(title, target)) return null
            }
        } finally {
            staged.dir.deleteRecursively()
        }
        Log.i(TAG, "[app] merged ${staged.bytes} bytes into $saves")
        return staged.bytes
    }

    /**
     * what a one-title import did.
     *
     * **[Absent] is a third answer rather than a flavour of failure**, because it is the one outcome
     * with something useful to say: the archive was read perfectly well and simply does not hold this
     * game. somebody who picked another game's export wants to be told that, not told the file is
     * broken -- and the two are indistinguishable to a caller handed a null.
     */
    sealed class GameImport {
        /** [bytes] is this title's own, never the whole archive's. */
        data class Done(val bytes: Long) : GameImport()

        object Absent : GameImport()

        object Failed : GameImport()
    }

    /**
     * replaces one title's save data with what the archive holds under that title, and nothing else.
     *
     * **the archive's other titles are not merely left on the device -- they are not read out of it**,
     * which is what stops this being the whole-of-it import wearing one game's name. an archive
     * holding five games restores one here.
     *
     * **wholesale for that title**, [importSaveData]'s rule and for its reason: half of one save set
     * and half of another is not a state any save format promises to survive.
     */
    fun importGameSaveData(context: Context, zip: Uri, titleId: String): GameImport {
        val staged = stage(context, zip) ?: return GameImport.Failed
        try {
            val source = File(staged.dir, titleId)
            // the name is the one thing about a staged entry this app chose, so it cannot escape -
            // but it is checked anyway, because that is true of the caller rather than of this
            // function, and a title id read off a dump is not a string this app wrote.
            if (!source.canonicalPath.startsWith(staged.dir.canonicalPath + File.separator)) {
                Log.e(TAG, "[app] $titleId points outside the staging directory")
                return GameImport.Failed
            }
            if (!source.isDirectory) return GameImport.Absent
            // measured before the move, because afterwards there is nothing at this path to measure.
            val bytes = sizeOf(source)
            val saves = AppStorage.saveData(context.filesDir)
            if (!saves.isDirectory && !saves.mkdirs()) {
                Log.e(TAG, "[app] could not create $saves")
                return GameImport.Failed
            }
            val target = File(saves, titleId)
            target.deleteRecursively()
            if (!move(source, target)) return GameImport.Failed
            Log.i(TAG, "[app] restored $bytes bytes of $titleId into $saves")
            return GameImport.Done(bytes)
        } finally {
            staged.dir.deleteRecursively()
        }
    }

    /** bytes under [file], following the tree and counting nothing else. */
    private fun sizeOf(file: File): Long =
        if (file.isDirectory) file.listFiles()?.sumOf { sizeOf(it) } ?: 0L else file.length()

    /** empties [dir], keeping anything [IGNORED] names. */
    private fun clear(dir: File, base: File) {
        dir.listFiles()?.forEach { child ->
            val name = relative(base, child)
            if (name != null && IGNORED.any { it == name || it.startsWith("$name/") }) {
                // the ignored path is this directory or sits beneath it, so it is walked rather than
                // removed: files/builds survives so that files/builds/bundled can.
                if (name in IGNORED) return@forEach
                clear(child, base)
                return@forEach
            }
            child.deleteRecursively()
        }
    }

    /**
     * moves [from] onto [to], **merging directories and replacing only files**.
     *
     * **it must not replace a directory wholesale, and that is the whole point of this function.**
     * renaming a staged `files/` over the real one begins by deleting the real one -- which throws
     * away every path [clear] has just gone to the trouble of keeping. the ignore list would be
     * honoured and then undone one line later, and what disappears is exactly what nobody would think
     * to check: the derived driver libraries and the bundled build, neither of which is in the
     * archive because neither belongs there.
     *
     * so a directory is walked into and a file is what gets replaced. [clear] has already emptied
     * everything that was not spared, so merging cannot leave a stale file behind.
     */
    private fun move(from: File, to: File): Boolean {
        if (from.isDirectory) {
            if (!to.isDirectory && !to.mkdirs()) {
                Log.e(TAG, "[app] could not create $to")
                return false
            }
            return from.listFiles()?.all { move(it, File(to, it.name)) } ?: true
        }
        to.parentFile?.mkdirs()
        to.delete()
        if (from.renameTo(to)) return true
        val copied = runCatching { from.copyTo(to, overwrite = true) }.isSuccess
        if (!copied) Log.e(TAG, "[app] could not move $from to $to")
        return copied
    }

    private fun normalise(name: String): String = name.replace('\\', '/')

    /** the app's data directory -- the parent of both `files/` and `shared_prefs/`. */
    private fun dataDir(context: Context): File = context.filesDir.parentFile!!

    /** where an import is unpacked before anything on the device is touched. */
    private const val STAGING = ".user-data-import"
}
