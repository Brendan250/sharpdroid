package com.mircowuffwuff.sharpemu

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mircowuffwuff.sharpemu.databinding.ActivityManagerBinding
import java.io.File
import java.util.concurrent.Executors

/**
 * The SharpEmu build manager: which builds are on the device, which one runs, and how one arrives.
 *
 * Reached from **Settings → Emulation → SharpEmu build**, and from nowhere else.
 *
 * **A build runs where it is, so selecting one writes a line and copies nothing.** The bundled build
 * is pinned at the top with no delete button — exactly one ships per APK, so there is never a
 * question of which of ours is the default — and everything below it was either staged from a PC or
 * imported from a zip.
 *
 * **The two things that do touch a build directory are on the worker**: an import extracts tens of
 * megabytes, and a scan parses a `meta.json` per build across FUSE-backed external storage. Either
 * on the main thread is seconds of frozen screen and, past five, a dialog offering to kill the app.
 */
class BuildsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagerBinding
    private lateinit var adapter: BuildAdapter
    private lateinit var settings: Settings
    private lateinit var drawnWith: String
    private val worker = Executors.newSingleThreadExecutor()

    private val internalRoot: File get() = AppStorage.installedBuilds(filesDir)
    private val staged: File get() = AppStorage.stagedBuilds(getExternalFilesDir(null)!!)

    /**
     * The zip picker.
     *
     * Registered at construction, which the contract requires — the activity can be recreated while
     * the picker is in front of it, and a launcher registered later than `onCreate` has nothing to
     * deliver the result to.
     *
     * **`OpenDocument` rather than `OpenDocumentTree`**, because this takes one file and wants no
     * persisted grant: the zip is read once and its contents live in the app's own storage
     * afterwards, so holding access to the original would be access nobody needs.
     */
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { zip ->
        // null is the back button, which is not an error and not worth a word.
        if (zip != null) importZip(zip)
    }

    override fun onCreate(state: Bundle?) {
        Theme.apply(this)
        drawnWith = Theme.signature(this)
        super.onCreate(state)
        // which store this manager is choosing for, forwarded by the row that opened it — see
        // DriversActivity, which takes it the same way and for the same reason.
        settings = intent.getStringExtra(SettingsSectionActivity.EXTRA_GAME)
            ?.takeIf { it.isNotEmpty() }
            ?.let { Settings.forGame(this, it) }
            ?: Settings.of(this)
        binding = ActivityManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        binding.toolbar.setTitle(R.string.setting_build)
        binding.toolbar.setNavigationOnClickListener { finish() }
        // **the manager screen carries an empty state and this is the manager that can reach one.**
        // a device can hold no SharpEmu builds at all, where the system GPU driver is always a card.
        binding.empty.setText(R.string.build_list_empty)

        adapter = BuildAdapter(emptyList(), this::select, this::confirmDelete)
        // **the settings scene's grid, and its column count**, because these are that scene's cards
        // and the driver manager's: a build is chosen by reading a handful of alternatives against
        // each other, which is what a grid is for. two screens that look alike would be a poor place
        // for the column count to differ.
        binding.list.layoutManager =
            GridLayoutManager(this, resources.getInteger(R.integer.settings_section_columns))
        binding.list.adapter = adapter

        // **the mime types are a filter and never the check.** a provider is free to report a zip as
        // application/octet-stream, so narrowing to application/zip alone hides files the user can
        // see in every other app. BuildImport decides what a file actually is.
        binding.importZip.setOnClickListener {
            picker.launch(
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream",
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (Theme.recreateIfStale(this, drawnWith)) return
        SystemBars.apply(this, binding.root)
        refresh()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /**
     * Rescans and redraws.
     *
     * On the worker because it opens and parses a `meta.json` per build across two volumes, one of
     * which is FUSE-backed external storage — reads there can be slow for reasons that have nothing
     * to do with how many builds there are.
     */
    private fun refresh() {
        worker.execute {
            val listing = BuildLibrary.of(this, internalRoot, staged, settings.build)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                show(listing)
            }
        }
    }

    private fun show(listing: BuildLibrary.Listing) {
        binding.progress.visibility = View.GONE
        val items = mutableListOf<BuildAdapter.Item>()
        // **the bundled build first, and no sort reaches it.** it is the one card that is always in
        // the same place, which is what "pinned" has to mean for it to be worth anything. absent in
        // a debug app, which ships none.
        listing.bundled?.let { items += BuildAdapter.Item(it, bundled = true) }
        listing.entries.forEach { items += BuildAdapter.Item(it) }
        adapter.submit(items)
        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Chooses a build. **Nothing is copied and nothing waits.**
     *
     * A build runs where it is, so selecting one is a single line in the store. A 76 MB copy would
     * buy durability against re-staging, which is a thing only a developer does and exactly the
     * thing they mean to do — and `docs/build-format.md` measures the volume as costing nothing:
     * 874–902 ms from external against 879–907 from internal.
     *
     * **What is stored is the folder name**, a concrete identity derived from `meta.json`, so the
     * choice survives a newer build of the same id arriving.
     */
    private fun select(entry: BuildLibrary.Entry) {
        // **the row already marked is still worth a write when this manager answers for one game**,
        // and only then. what is marked there is whatever the app's own row currently says, so
        // tapping it means "this game runs this build" rather than "no change" — and without the
        // write the game would keep following the app's row the day it moves. on the app's own
        // screen the mark and the store are the same thing, so tapping it is genuinely nothing.
        if (entry.selected && !settings.perGame) return
        settings.build = entry.build.folder
        Log.i(TAG, "[app] selected " + entry.build.identity() + " at " + entry.build.dir)
        refresh()
    }

    /**
     * Imports a picked zip, and selects it — which is the only reading that makes sense: importing a
     * build is how somebody says they want to run it.
     */
    private fun importZip(zip: Uri) {
        binding.progress.visibility = View.VISIBLE
        worker.execute {
            val result = BuildImport.import(this, zip, internalRoot)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.progress.visibility = View.GONE
                when (result) {
                    is BuildImport.Result.Refused ->
                        Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()

                    is BuildImport.Result.Imported -> {
                        settings.build = result.build.folder
                        Toast.makeText(
                            this,
                            getString(R.string.build_imported, result.build.name),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                refresh()
            }
        }
    }

    /**
     * **The warning is about the staged copy, not about which copy the row is showing.**
     *
     * A deletion removes both copies of an identity, because the list shows one entry per identity
     * and leaving half of it behind would look like a deletion that did nothing. So the sentence
     * about a PC has to be chosen by whether a staged copy exists — an installed entry with a staged
     * twin got the shorter message and lost the twin anyway, which is the dialog saying less than
     * the button does.
     */
    private fun confirmDelete(entry: BuildLibrary.Entry) {
        val alsoStaged = File(staged, entry.build.folder).isDirectory
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.build_delete_title)
            .setMessage(
                getString(
                    if (alsoStaged) R.string.build_delete_message_staged
                    else R.string.build_delete_message,
                    entry.build.name,
                )
            )
            .setPositiveButton(R.string.manager_delete) { _, _ -> delete(entry) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Removes a build, and moves the selection off it if it was the chosen one.
     *
     * **The replacement is the bundled build, or the newest one left, rather than nothing**, because
     * a cleared selection means the app resolves whatever was staged most recently — a different
     * answer arrived at silently. Naming the replacement says which build took over and leaves it
     * visible on the row that now carries the radio.
     *
     * Nothing is copied for it. A build runs where it is, so taking over is a line in the store and
     * costs the same whether it happens here or at the next launch.
     */
    private fun delete(entry: BuildLibrary.Entry) {
        binding.progress.visibility = View.VISIBLE
        val folder = entry.build.folder
        val dir = entry.build.dir
        worker.execute {
            val gone = SharpEmuBuild.delete(dir)
            // both copies, when there are two: the list shows one entry per identity, so leaving the
            // staged half behind would look like a deletion that did nothing.
            SharpEmuBuild.delete(File(internalRoot, folder))
            SharpEmuBuild.delete(File(staged, folder))
            val listing = BuildLibrary.of(this, internalRoot, staged, settings.build)
            // the bundled build first if there is one, because it is the answer that always exists;
            // otherwise the newest thing left.
            val replacement = if (settings.build == folder) {
                listing.bundled ?: listing.entries.firstOrNull()
            } else {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!gone) {
                    Toast.makeText(this, R.string.build_delete_failed, Toast.LENGTH_LONG).show()
                }
                if (settings.build == folder) {
                    if (replacement == null) {
                        // nothing left to name, so the row goes back to the app deciding rather
                        // than to a folder that is not there.
                        settings.clear(Settings.KEY_BUILD)
                    } else {
                        settings.build = replacement.build.folder
                        Toast.makeText(
                            this,
                            getString(R.string.build_now_selected, replacement.build.name),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                refresh()
            }
        }
    }

    private companion object {
        const val TAG = "sharpemu"
    }
}
