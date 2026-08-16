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

/**
 * The documents this app redistributes its guest libraries under.
 *
 * Reached from **About → Guest libraries → Third-party licences**.
 *
 * **The obligation is met by the APK and this is what makes it reachable.** The x86-64 set the guest's
 * own linker searches is mostly unmodified Debian binaries, and the terms they travel under are
 * packaged beside them — an index, each source package's own copyright statement as Debian writes it,
 * and the full text of every licence those statements refer to. `scripts/build-apk.py` refuses to
 * package a set missing any of them and asserts them again inside the finished archive. A notice
 * nobody can open without unzipping the APK is doing half the job, and this is the other half.
 *
 * **Read out of the assets, never copied into `res/`.** The files beside the binaries are the notice;
 * a second copy in string resources is one that can silently disagree with the one that shipped, and
 * the one that shipped is the one that counts.
 *
 * **The unpacked copy on internal storage is deliberately not used.** It only exists after a launch
 * has needed it, and this screen has to work on an install that has never started a game. The asset is
 * always there.
 *
 * **A set staged over `adb` is not shown either.** That override is a development path and what a
 * recipient was handed is what the APK carries, so this lists the APK's and nothing else.
 *
 * **The list is not hardcoded.** The directory is what names the entries, so a package added to the
 * set appears here with nothing in this file changing — a screen that spelled out seven filenames
 * would go stale the day an eighth arrived, and would do it silently.
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

    /** One document: what it is called, what kind it is, and where in the APK it is. */
    private class Document(val name: String, val kind: Int, val asset: String)

    /**
     * The index first, then the copyright statements, then the licence texts.
     *
     * **That is the order `licences.txt` itself puts them in**, and it is the order somebody reads
     * them: what the set is, then who holds each package, then the terms those statements refer to. A
     * flat alphabetical list would open on `Apache-2.0`, which is the last thing anybody arrives here
     * wanting.
     *
     * A copyright statement is named for its source package rather than for its file, since `glibc` is
     * what the reader is looking for and `glibc.copyright` is where this app happens to keep it.
     */
    private fun documents(): List<Document> {
        val listed = runCatching { assets.list(LICENCES)?.toList() }
            .onFailure { Log.e(TAG, "[app] could not list $LICENCES", it) }
            .getOrNull()
            .orEmpty()

        val copyrights = listed.filter { it.endsWith(COPYRIGHT) }.sorted().map {
            Document(it.removeSuffix(COPYRIGHT), R.string.licences_copyright, "$LICENCES/$it")
        }
        val texts = listed.filterNot { it.endsWith(COPYRIGHT) }.sorted().map {
            Document(it, R.string.licences_text, "$LICENCES/$it")
        }

        val notice = if (AssetTree.text(this, NOTICE) == null) {
            emptyList()
        } else {
            listOf(
                Document(getString(R.string.licences_notice), R.string.licences_notice_kind, NOTICE)
            )
        }
        return notice + copyrights + texts
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
            holder.binding.kind.setText(document.kind)
            holder.binding.root.setOnClickListener { onClick(document) }
        }
    }

    companion object {
        private const val TAG = "sharpemu"

        /** The directory in the APK holding the copyright statements and the licence texts. */
        const val LICENCES = "guest-libs/licences"

        /** The index over it, which is also the one document here that explains the others. */
        const val NOTICE = "guest-libs/licences.txt"

        private const val COPYRIGHT = ".copyright"

        /** Where a licence text named [name] lives, for a caller that wants one by name. */
        fun textAsset(name: String) = "$LICENCES/$name"
    }
}
