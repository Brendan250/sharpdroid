package com.mircowuffwuff.sharpemu

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mircowuffwuff.sharpemu.databinding.ActivityManagerBinding
import com.mircowuffwuff.sharpemu.databinding.ItemLicenceBinding
import org.json.JSONObject
import java.io.File

/**
 * everything this APK redistributes but did not write, and the terms each of those is under.
 *
 * reached from **About → Licence → Third-party licences**.
 *
 * **the obligation is met by the APK and this is what makes it reachable.** an APK is a binary
 * redistribution, and the permission notices in it are addressed to the person holding one -- not to
 * somebody who cloned the repository and can read `external/`. `scripts/build-apk.py` assembles the
 * notices, refuses to package without them, and asserts them again inside the finished archive. a
 * notice nobody can open without unzipping the APK is doing half the job, and this is the other half.
 *
 * **one row per library, and the row opens its terms.** three provenances land in this list -- what is
 * compiled into the host layer or shipped beside it, what gradle resolved into the dex, and the
 * x86-64 set the guest's own linker searches. they are one obligation and they read as one list; a
 * heading per provenance would sort a reader's attention by a distinction they did not come here to
 * make.
 *
 * **read out of the assets, never copied into `res/`.** the files packaging wrote are the notice; a
 * second copy in string resources is one that can silently disagree with the one that shipped, and
 * the one that shipped is the one that counts.
 *
 * **the unpacked copy on internal storage is deliberately not used.** it only exists after a launch
 * has needed it, and this screen has to work on an install that has never started a game. the asset is
 * always there.
 *
 * **a guest set staged over `adb` is not shown either.** that override is a development path and what
 * a recipient was handed is what the APK carries, so this lists the APK's and nothing else.
 *
 * **nothing here is hardcoded.** the index and the directory beside it are what name the entries, so a
 * dependency that arrives transitively appears with nothing in this file changing -- a screen that
 * spelled out its rows would go stale the day the graph moved, and would do it silently.
 */
class LicencesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagerBinding
    private lateinit var drawnWith: String

    override fun onCreate(state: Bundle?) {
        Theme.apply(this)
        drawnWith = Theme.signature(this)
        super.onCreate(state)
        binding = ActivityManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        binding.toolbar.setTitle(R.string.licences_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // the manager screen's shell without the two things a manager has that this does not: nothing
        // is imported here and nothing waits.
        binding.importZip.visibility = View.GONE

        val documents = documents()
        binding.list.layoutManager =
            GridLayoutManager(this, resources.getInteger(R.integer.settings_section_columns))
        binding.list.adapter = Adapter(documents) {
            LicenceTextActivity.open(this, it.asset, it.path, it.name)
        }

        // packaging asserts every one of these is in the APK, so an empty list is something having
        // gone wrong rather than a state to design for. it still says so instead of drawing nothing.
        if (documents.isEmpty()) {
            binding.empty.setText(R.string.licences_unreadable)
            binding.empty.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        if (Theme.recreateIfStale(this, drawnWith)) return
        SystemBars.apply(this, binding.root)
    }

    /**
     * one row: a library, its licence, and where that licence is stated.
     *
     * [kind] is the row's second line and is always a licence -- `MIT`, `Apache-2.0`,
     * `GPL-3.0-or-later WITH GCC-exception-3.1`. **every row means the same thing**, which is what
     * lets one list hold four provenances without a reader having to work out which kind of row they
     * are looking at.
     *
     * exactly one of [asset] and [path] is set. the APK's own notices are assets; a selected build's
     * are files on the device, since that build is not part of this app and may not have come from it.
     */
    private class Document(
        val name: String,
        val kind: String,
        val asset: String? = null,
        val path: String? = null,
    )

    /**
     * every library, sorted by name and case-insensitively.
     *
     * **one index, and this does not second-guess it.** which libraries are in the APK was settled
     * where it can be settled -- against the symbols in the host layer, against gradle's resolved
     * classpath, and against the pins the guest set was fetched with -- and packaging refuses rather
     * than writing a row it cannot back with a document. so a malformed index is logged and dropped
     * whole; recovering half of it would produce a list that looks complete and is not.
     *
     * sorting is case-insensitive because otherwise every capitalised name sorts ahead of every
     * lowercase one, which puts `okio` after `Transition` and reads as unsorted rather than as sorted
     * by a rule.
     */
    private fun documents(): List<Document> =
        (packaged() + selectedBuild()).sortedBy { it.name.lowercase() }

    /** what packaging wrote: everything the APK itself redistributes. */
    private fun packaged(): List<Document> = try {
        val index = assets.open(NOTICES).use { it.readBytes().decodeToString() }
        val libraries = JSONObject(index).getJSONArray("libraries")
        (0 until libraries.length()).map { at ->
            val library = libraries.getJSONObject(at)
            Document(library.getString("name"), library.getString("licence"),
                asset = "$OURS/" + library.getString("text"))
        }
    } catch (e: Exception) {
        Log.e(TAG, "[app] could not read $NOTICES", e)
        emptyList()
    }

    /**
     * what the emulator build a launch would run brings with it, read when this screen opens.
     *
     * **a build is not part of this app and its notices are not packaging's to write.** one can be
     * imported from a zip this project never packaged, so the set of libraries a build carries is
     * known only to that build -- which is why these are read at the moment they are shown rather than
     * baked into the index beside everything else. **a build that starts shipping a notice it does not
     * ship today appears here with nothing in this app changing, and without an update.**
     *
     * **the bundled build is read from the APK, never from its unpacked copy.** that copy only exists
     * after a launch has needed it, and this screen has to work on an install that has never started a
     * game -- and the asset is what the unpack copies anyway, so it is the same bytes either way.
     *
     * **only the selected build.** what is shown is what a launch would run; listing every build on
     * the device would state terms for code this install may never execute, and the reader has no way
     * to tell which of the rows applied to them.
     */
    private fun selectedBuild(): List<Document> = try {
        val internalRoot = AppStorage.installedBuilds(filesDir)
        // a device with no external storage has nothing staged on it, so the internal root answers
        // for both and finds nothing in the second.
        val staged = AppStorage.stagedBuilds(getExternalFilesDir(null) ?: filesDir)
        val listing = BuildLibrary.of(this, internalRoot, staged, Settings.of(this).build)
        val chosen = (listOfNotNull(listing.bundled) + listing.entries)
            .firstOrNull { it.selected }?.build
        when {
            chosen == null -> emptyList()
            chosen.inApk -> bundledNotices()
            else -> stagedNotices(chosen.dir)
        }
    } catch (e: Exception) {
        // the rest of the list is the APK's own and is still true, so a build that cannot be read
        // costs its own rows rather than the screen.
        Log.e(TAG, "[app] could not read the selected build's licences", e)
        emptyList()
    }

    /** the bundled build's, out of the asset tree the unpack copies from. */
    private fun bundledNotices(): List<Document> {
        val listed = assets.list("$BUNDLED/$BUILD_LICENCES").orEmpty().sorted()
        return buildNotice(
            { assets.open("$BUNDLED/$it").use { stream -> stream.readBytes().decodeToString() } },
            { Document(SHARPEMU, it, asset = "$BUNDLED/$BUILD_LICENCE") },
            listed.map { name ->
                name to { title: String ->
                    Document(name.removeSuffix(TEXT), title,
                        asset = "$BUNDLED/$BUILD_LICENCES/$name")
                }
            },
        )
    }

    /** a staged or imported build's, out of the directory it runs from. */
    private fun stagedNotices(directory: File): List<Document> {
        val listed = File(directory, BUILD_LICENCES).listFiles()
            ?.filter { it.isFile && it.name.endsWith(TEXT) }
            ?.map { it.name }
            .orEmpty()
            .sorted()
        return buildNotice(
            { File(directory, it).readText() },
            { Document(SHARPEMU, it, path = File(directory, BUILD_LICENCE).path) },
            listed.map { name ->
                name to { title: String ->
                    Document(name.removeSuffix(TEXT), title,
                        path = File(File(directory, BUILD_LICENCES), name).path)
                }
            },
        )
    }

    /**
     * the build's own licence, then one row per document beside it, each titled from what it says.
     *
     * **the licence is quoted from the document rather than worked out from it.** these files are
     * written by whoever packaged the build, so nothing here knows in advance which licence any of
     * them states -- and an identifier inferred from prose is a confident wrong answer on the one
     * screen whose value is that every line of it is true. a licence text names itself on its first
     * line, so that line is what the row says.
     */
    private fun buildNotice(
        read: (String) -> String,
        own: (String) -> Document,
        others: List<Pair<String, (String) -> Document>>,
    ): List<Document> {
        val documents = mutableListOf<Document>()
        runCatching { own(titleOf(read(BUILD_LICENCE))) }.getOrNull()?.let { documents.add(it) }
        for ((name, make) in others) {
            runCatching { make(titleOf(read("$BUILD_LICENCES/$name"))) }
                .onFailure { Log.e(TAG, "[app] could not read the build's $name", it) }
                .getOrNull()?.let { documents.add(it) }
        }
        return documents
    }

    /**
     * what a licence document calls itself: its first non-blank line, and the version line under it.
     *
     * the GPL's title and its version are two lines and the pair is the identity -- a row saying only
     * *GNU GENERAL PUBLIC LICENSE* names three different licences at once.
     */
    private fun titleOf(text: String): String {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(2).toList()
        val title = lines.firstOrNull().orEmpty().ifEmpty { getString(R.string.licences_untitled) }
        val version = lines.getOrNull(1).orEmpty()
        return if (version.startsWith("Version ", ignoreCase = true)) "$title, $version" else title
    }

    private class Adapter(
        private val documents: List<Document>,
        private val onClick: (Document) -> Unit,
    ) : RecyclerView.Adapter<Adapter.Holder>() {

        class Holder(val binding: ItemLicenceBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            ItemLicenceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun getItemCount() = documents.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val document = documents[position]
            holder.binding.name.text = document.name
            holder.binding.kind.text = document.kind
            holder.binding.root.setOnClickListener { onClick(document) }
        }
    }

    companion object {
        private const val TAG = "sharpemu"

        /** where packaging puts the terms of everything this APK redistributes. */
        private const val OURS = "licences"

        /** its index: one entry per library, each naming its licence and the document stating it. */
        private const val NOTICES = "$OURS/notices.json"

        /**
         * the directory in the APK holding the guest set's own notices.
         *
         * **that set carries its own terms because it is redistributable on its own** -- it is a plain
         * directory staged to a device, and a copy of it that travelled without them would be one this
         * app had stripped. the rows on this screen are assembled from it at packaging time rather
         * than read from here, so what a reader opens is one document per library like every other.
         */
        private const val LICENCES = "guest-libs/licences"

        /** the bundled build's asset tree, which is also where its own notices are. */
        private const val BUNDLED = "sharpemu"

        /**
         * what a build calls its own licence, and the directory beside it holding the rest.
         *
         * **these are the emulator's spelling and not this project's.** everything written here says
         * *licence*; a build's layout is upstream's, so it is read under the name upstream gives it.
         */
        private const val BUILD_LICENCE = "LICENSE.txt"
        private const val BUILD_LICENCES = "licenses"
        private const val TEXT = ".txt"

        /** what the build's own licence row is called, which is the emulator rather than a library. */
        private const val SHARPEMU = "SharpEmu"

        /** where a licence text named [name] lives, for a caller that wants one by name. */
        fun textAsset(name: String) = "$LICENCES/$name"
    }
}
