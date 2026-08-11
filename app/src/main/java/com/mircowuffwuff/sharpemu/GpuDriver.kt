package com.mircowuffwuff.sharpemu

import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * A GPU driver package: a directory holding a Vulkan `.so` and the `meta.json` that names it.
 *
 * **The format is adrenotools', not ours.** It is what every turnip package on the internet already
 * ships and what `scripts/stage-driver.ps1` unpacks, so a driver a user has for another emulator
 * imports here unchanged. Nothing in this app knows that turnip's library is called
 * `libvulkan_freedreno.so` — `libraryName` says, and two of the packages this project tests with
 * call it something else entirely.
 *
 * **A driver's copy onto internal storage is a requirement and a build's is not**, which is the one
 * place these two managers genuinely differ. adrenotools stats the library and then `dlopen`s it, and
 * the linker refuses a library from a volume another app could have written — which external storage
 * is by definition. So an imported package is extracted straight onto internal storage and loaded
 * where it lands, and a staged one has its library copied there at launch. [AppStorage] owns both
 * directories and says which is which.
 */
class GpuDriver private constructor(
    /** Where the package is. Its library is loaded from here when this is on internal storage. */
    val dir: File,
    val folder: String,
    json: JSONObject,
) {

    val name: String = json.optString("name", folder)
    val description: String = json.optString("description", "")
    val author: String = json.optString("author", "")
    val vendor: String = json.optString("vendor", "")
    val driverVersion: String = json.optString("driverVersion", "")
    val packageVersion: String = json.optString("packageVersion", "")

    /**
     * The lowest android version the package says it runs on.
     *
     * **Checked rather than decorative.** The five packages this project has to hand declare 28, 29
     * and 30 against a `minSdk` of 28, so a device this app supports can genuinely be below one —
     * and a driver loaded on a platform it was not built for fails inside the loader, where the
     * report is a black screen rather than a message.
     */
    val minApi: Int = json.optInt("minApi", 0)

    /** The `.so` the loader is pointed at. The package names it; nothing here assumes it. */
    val libraryName: String = json.optString("libraryName", "")

    fun library(): File = File(dir, libraryName)

    /** What the launch log says: enough to attribute a frame rate to an artefact. */
    fun identity(): String {
        val version = driverVersion.ifEmpty { packageVersion.ifEmpty { "unknown version" } }
        return "$name ($version)"
    }

    /** Author and vendor, for the line under the name. Either may be missing. */
    fun attribution(): String = listOf(author, vendor).filter { it.isNotEmpty() }.joinToString(" · ")

    /** True once this package is in the app's own directory, which is where one is loaded from. */
    fun isInstalled(internal: File): Boolean =
        dir.absolutePath.startsWith(internal.absolutePath + File.separator)

    /**
     * Whether this package could be loaded at all: this platform is new enough for it, and the
     * library it names is present.
     *
     * The manager draws a package that fails this rather than hiding it, for the same reason the
     * build list draws an unrunnable build — somebody who imported it should find out why on the
     * screen they imported it from.
     */
    fun usable(): Boolean = minApi <= Build.VERSION.SDK_INT && library().isFile

    companion object {

        private const val TAG = "sharpemu"

        /**
         * The platform's own driver, as the store spells it.
         *
         * **A reserved word rather than an absence**, so that selecting the pinned row is a write
         * like any other and the radio marks a choice the user made rather than a store that happens
         * to be empty. An absent setting means the same thing, which is what makes the system driver
         * the default without a constant saying so.
         *
         * It cannot collide with a package: [folderName] always produces a name with a `-` in it.
         */
        const val SYSTEM = "system"

        /** `stock` is what the scripts and [MainActivity]'s extra have always spelled it. */
        @JvmStatic
        fun isSystem(folder: String?): Boolean =
            folder == null || folder.isEmpty() || folder == SYSTEM || folder == "stock"

        /**
         * The on-device folder name, derived from the identity and never from what the zip is called.
         *
         * The same rule the build format uses, for the same reason: a zip renamed on somebody's disk
         * imports to the place it always would, and two versions of one driver coexist instead of
         * overwriting each other.
         */
        fun folderName(name: String, version: String): String =
            "$name-$version".lowercase(Locale.ROOT).map {
                if (it.isDigit() || it in 'a'..'z' || it == '.' || it == '_' || it == '-') it else '-'
            }.joinToString("").trim('-').ifEmpty { "driver" }

        /** A package directory's identity, or null if it has no readable `meta.json`. */
        @JvmStatic
        fun read(dir: File): GpuDriver? {
            val meta = File(dir, "meta.json")
            if (!meta.isFile) return null
            return try {
                GpuDriver(dir, dir.name, JSONObject(meta.readText()))
            } catch (e: Exception) {
                Log.e(TAG, "[app] could not read $meta", e)
                null
            }
        }

        /**
         * Resolves what the store holds, or what `--es driver` named, to a package on the device.
         *
         * **The app's own copy wins over a staged one of the same name**, which is the order the
         * build list resolves in too: an imported package is the one this app is responsible for,
         * and a staged directory of the same name is a developer's, put there deliberately.
         *
         * Null when it is gone — deleted from a PC, or the external volume wiped. The caller falls
         * back to the system driver and says so, which is a run that renders rather than one that
         * does not.
         */
        @JvmStatic
        fun resolve(folder: String, internal: File, staged: File): GpuDriver? {
            File(internal, folder).takeIf { it.isDirectory }?.let { return read(it) }
            File(staged, folder).takeIf { it.isDirectory }?.let { return read(it) }
            return null
        }

        /**
         * Every readable package on the device, the app's own and the staged ones, one per folder.
         *
         * The system driver is not among them: it is not a package and there is nothing to read.
         * [DriverLibrary] is what puts it at the top.
         */
        fun list(internal: File, staged: File): List<GpuDriver> {
            val byFolder = LinkedHashMap<String, GpuDriver>()
            collect(staged, byFolder)
            // the app's own second, so it replaces a staged directory of the same name rather than
            // losing to it.
            collect(internal, byFolder)
            return byFolder.values.toList()
        }

        private fun collect(root: File, into: MutableMap<String, GpuDriver>) {
            val entries = root.listFiles() ?: return
            for (entry in entries) {
                if (!entry.isDirectory || entry.name.endsWith(".partial")) continue
                read(entry)?.let { into[it.folder] = it }
            }
        }

        /** Removes a package outright. Nothing here is recoverable and nothing here is unique. */
        fun delete(dir: File): Boolean {
            dir.deleteRecursively()
            val gone = !dir.exists()
            Log.i(TAG, "[app] " + (if (gone) "deleted " else "could not delete ") + dir)
            return gone
        }
    }
}
