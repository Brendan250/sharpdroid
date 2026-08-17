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

/**
 * Everything this APK redistributes but did not write, and the terms each of those is under.
 *
 * Reached from **About → Licence → Third-party licences**.
 *
 * **The obligation is met by the APK and this is what makes it reachable.** An APK is a binary
 * redistribution, and the permission notices in it are addressed to the person holding one — not to
 * somebody who cloned the repository and can read `external/`. `scripts/build-apk.py` assembles the
 * notices, refuses to package without them, and asserts them again inside the finished archive. A
 * notice nobody can open without unzipping the APK is doing half the job, and this is the other half.
 *
 * **One row per library, and the row opens its terms.** Three provenances land in this list — what is
 * compiled into the host layer or shipped beside it, what gradle resolved into the dex, and the
 * x86-64 set the guest's own linker searches. They are one obligation and they read as one list; a
 * heading per provenance would sort a reader's attention by a distinction they did not come here to
 * make.
 *
 * **Read out of the assets, never copied into `res/`.** The files packaging wrote are the notice; a
 * second copy in string resources is one that can silently disagree with the one that shipped, and
 * the one that shipped is the one that counts.
 *
 * **The unpacked copy on internal storage is deliberately not used.** It only exists after a launch
 * has needed it, and this screen has to work on an install that has never started a game. The asset is
 * always there.
 *
 * **A guest set staged over `adb` is not shown either.** That override is a development path and what
 * a recipient was handed is what the APK carries, so this lists the APK's and nothing else.
 *
 * **Nothing here is hardcoded.** The index and the directory beside it are what name the entries, so a
 * dependency that arrives transitively appears with nothing in this file changing — a screen that
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
            LicenceTextActivity.open(this, it.asset, it.name)
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
     * One row: a library, its licence, and where in the APK that licence is stated.
     *
     * [kind] is the row's second line and is always a licence identifier — `MIT`, `Apache-2.0`,
     * `GPL-3.0-or-later WITH GCC-exception-3.1`. **Every row means the same thing**, which is what
     * lets one list hold three provenances without a reader having to work out which kind of row they
     * are looking at.
     */
    private class Document(val name: String, val kind: String, val asset: String)

    /**
     * Every library, sorted by name and case-insensitively.
     *
     * **One index, and this does not second-guess it.** Which libraries are in the APK was settled
     * where it can be settled — against the symbols in the host layer, against gradle's resolved
     * classpath, and against the pins the guest set was fetched with — and packaging refuses rather
     * than writing a row it cannot back with a document. So a malformed index is logged and dropped
     * whole; recovering half of it would produce a list that looks complete and is not.
     *
     * Sorting is case-insensitive because otherwise every capitalised name sorts ahead of every
     * lowercase one, which puts `okio` after `Transition` and reads as unsorted rather than as sorted
     * by a rule.
     */
    private fun documents(): List<Document> = try {
        val index = assets.open(NOTICES).use { it.readBytes().decodeToString() }
        val libraries = JSONObject(index).getJSONArray("libraries")
        (0 until libraries.length()).map { at ->
            val library = libraries.getJSONObject(at)
            Document(library.getString("name"), library.getString("licence"),
                "$OURS/" + library.getString("text"))
        }.sortedBy { it.name.lowercase() }
    } catch (e: Exception) {
        Log.e(TAG, "[app] could not read $NOTICES", e)
        emptyList()
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

        /** Where packaging puts the terms of everything this APK redistributes. */
        private const val OURS = "licences"

        /** Its index: one entry per library, each naming its licence and the document stating it. */
        private const val NOTICES = "$OURS/notices.json"

        /**
         * The directory in the APK holding the guest set's own notices.
         *
         * **That set carries its own terms because it is redistributable on its own** — it is a plain
         * directory staged to a device, and a copy of it that travelled without them would be one this
         * app had stripped. The rows on this screen are assembled from it at packaging time rather
         * than read from here, so what a reader opens is one document per library like every other.
         */
        private const val LICENCES = "guest-libs/licences"

        /** Where a licence text named [name] lives, for a caller that wants one by name. */
        fun textAsset(name: String) = "$LICENCES/$name"
    }
}
