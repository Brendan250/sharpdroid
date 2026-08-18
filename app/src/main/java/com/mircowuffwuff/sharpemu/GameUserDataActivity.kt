package com.mircowuffwuff.sharpemu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mircowuffwuff.sharpemu.databinding.ActivityManagerBinding
import com.mircowuffwuff.sharpemu.databinding.DialogMessageBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * One game's user data: what the emulator has written for this title and nothing else.
 *
 * **A screen of its own rather than [UserDataActivity] told which game**, which is the opposite call
 * to the one the settings sections take — and the reason is that the two screens differ in what their
 * cards mean rather than only in what they act on. There is no Everything here, because a title has
 * no install to replace; there is no Settings card, because a game's overrides are shown as overrides
 * on the rows themselves; and Save data on the app's screen is a merge across the library where here
 * it is one title, replaced. Two of the four cards survive that and neither survives it unchanged.
 *
 * **What it does share is every part below the meaning**: the manager frame, the card, the adapter
 * and the archive. So a card gains a button here the day it gains one there.
 *
 * **A game whose dump names no title id gets no cards at all.** The emulator files such a dump under
 * one shared name, so this screen's whole premise — that a directory belongs to the game whose
 * artwork is one step back — is false for it. Offering to export or delete there would name one game
 * and act on several, and drawing the cards without their buttons would be an explanation on four
 * lines of a thing better said in one.
 */
class GameUserDataActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagerBinding
    private lateinit var adapter: UserDataAdapter
    private lateinit var drawnWith: String
    private val worker = Executors.newSingleThreadExecutor()

    /** The name of this title's directories — [Game.emulatorTitleId], not its config key. */
    private lateinit var titleId: String

    /** What to call the game in a question about it. */
    private lateinit var gameName: String

    /**
     * Registered at construction, for [UserDataActivity]'s reason: a picker can outlive this screen.
     *
     * **Neither remembers which card asked, where the app's screen has to.** Only one card here
     * exports or imports anything, so there is no second answer for a result to belong to.
     */
    private val exportTo =
        registerForActivityResult(ActivityResultContracts.CreateDocument(ZIP)) { out ->
            if (out != null) export(out)
        }

    private val importFrom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { zip ->
        if (zip != null) offerImport(zip)
    }

    override fun onCreate(state: Bundle?) {
        Theme.apply(this)
        drawnWith = Theme.signature(this)
        super.onCreate(state)

        titleId = intent.getStringExtra(EXTRA_TITLE_ID).orEmpty()
        gameName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        if (titleId.isEmpty()) {
            // nothing but a hand-written intent reaches this. finishing beats a screen whose Delete
            // button would be pointed at the root of every title's save data.
            finish()
            return
        }

        binding = ActivityManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        binding.toolbar.setTitle(R.string.settings_user_data)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = UserDataAdapter(emptyList(), this::act)
        binding.list.layoutManager =
            GridLayoutManager(this, resources.getInteger(R.integer.settings_section_columns))
        binding.list.adapter = adapter

        // the manager frame's Import button belongs to a card here, exactly as on the app's own
        // screen: what is imported is one card's worth of data rather than the list's.
        binding.importZip.visibility = View.GONE

        if (shared()) {
            binding.empty.setText(R.string.game_user_data_shared)
            binding.empty.visibility = View.VISIBLE
        }
    }

    /**
     * Whether this dump's directories are the shared ones.
     *
     * [Game.UNKNOWN_TITLE_ID] is the emulator's answer for a dump naming no title id, and it is the
     * same answer for every such dump — so what is under it belongs to no one game.
     */
    private fun shared(): Boolean = titleId == Game.UNKNOWN_TITLE_ID

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        if (Theme.recreateIfStale(this, drawnWith)) return
        SystemBars.apply(this, binding.root)
        refresh()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /** Measures this title's two directories on the worker and draws them on the main thread. */
    private fun refresh() {
        if (shared()) return
        val files = filesDir
        val id = titleId
        worker.execute {
            val items = UserDataItem.measureGame(files, id)
            runOnUiThread { if (!isFinishing) adapter.submit(items) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // what a button does

    private fun act(kind: UserDataItem.Kind, action: UserDataAdapter.Action) = when (action) {
        UserDataAdapter.Action.EXPORT -> exportTo.launch(suggestedName())
        // the zip mime type rather than everything, so the picker greys out what cannot be one.
        UserDataAdapter.Action.IMPORT -> importFrom.launch(arrayOf(ZIP, "application/octet-stream"))
        UserDataAdapter.Action.DELETE -> confirmDelete(kind)
        // neither card here offers one, and a `when` over the enum has to say so.
        UserDataAdapter.Action.RESET -> Unit
    }

    /**
     * **The title id is in the filename and the game's name is not.**
     *
     * A display name is whatever the dump says it is — punctuation, a colon, a language nobody
     * else's filesystem agrees about — and the id is the string the archive is actually keyed by, so
     * it is what makes two exports on a desktop tellable apart by the thing that matters.
     */
    private fun suggestedName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "sharpemu-savedata-$titleId-$stamp.zip"
    }

    // ---------------------------------------------------------------------------------------------
    // export

    private fun export(out: Uri) = busy {
        val bytes = UserDataArchive.exportGameSaveData(this, out, titleId)
        runOnUiThread {
            done()
            if (bytes == null) {
                // the directory is not there, which is a game that has never been saved rather than
                // a failure to write - and it is worth its own words, since the picker has just been
                // through a whole filename dialog to produce nothing.
                toast(getString(R.string.game_user_data_export_nothing))
            } else {
                toast(getString(R.string.user_data_exported, formatted(bytes)))
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // import

    /**
     * Reads the archive's manifest, then asks.
     *
     * **The manifest is checked here and the title is not**, which is the one place this screen has
     * to differ from the app's: whether an archive holds *this* game cannot be known without reading
     * the whole of it, and reading it twice to ask a question first would double the wait on the
     * larger of the two cases. So the question is asked about the archive, and an archive that turns
     * out not to hold this title changes nothing and says so.
     */
    private fun offerImport(zip: Uri) = busy {
        val manifest = UserDataArchive.read(this, zip)
        runOnUiThread {
            done()
            if (manifest == null) {
                toast(getString(R.string.user_data_import_not_ours))
                return@runOnUiThread
            }
            if (manifest.kind != UserDataArchive.Kind.SAVE_DATA) {
                toast(
                    getString(
                        R.string.user_data_import_wrong_kind,
                        getString(R.string.user_data_everything),
                    )
                )
                return@runOnUiThread
            }
            ask(
                R.string.user_data_import_title,
                getString(
                    R.string.game_user_data_import_message,
                    gameName,
                    provenance(manifest),
                ),
                R.string.action_import,
            ) { runImport(zip) }
        }
    }

    /** *"exported 5 days ago"*, or a date where the clock disagrees. [UserDataActivity]'s rule. */
    private fun provenance(manifest: UserDataArchive.Manifest): String {
        val now = System.currentTimeMillis()
        val when_ = if (manifest.exportedAt in 1 until now) {
            DateUtils.getRelativeTimeSpanString(
                manifest.exportedAt, now, DateUtils.MINUTE_IN_MILLIS
            ).toString()
        } else {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(manifest.exportedAt))
        }
        return getString(R.string.user_data_provenance, when_)
    }

    private fun runImport(zip: Uri) = busy {
        val result = UserDataArchive.importGameSaveData(this, zip, titleId)
        runOnUiThread {
            done()
            when (result) {
                is UserDataArchive.GameImport.Done ->
                    toast(getString(R.string.user_data_imported, formatted(result.bytes)))
                // **named rather than described.** somebody who has just picked the wrong file out of
                // a folder of exports wants to know which game this screen is about.
                UserDataArchive.GameImport.Absent ->
                    toast(getString(R.string.game_user_data_import_absent, gameName))
                UserDataArchive.GameImport.Failed ->
                    toast(getString(R.string.user_data_import_failed))
            }
            refresh()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // delete

    private fun confirmDelete(kind: UserDataItem.Kind) = when (kind) {
        UserDataItem.Kind.SAVE_DATA -> ask(
            R.string.game_user_data_delete_saves_title,
            getString(R.string.game_user_data_delete_saves_message, gameName),
            R.string.action_delete,
        ) { wipe(File(AppStorage.saveData(filesDir), titleId)) }
        else -> ask(
            R.string.game_user_data_delete_shaders_title,
            getString(R.string.game_user_data_delete_shaders_message, gameName),
            R.string.action_delete,
        ) { wipe(AppStorage.pipelineCacheOf(filesDir, titleId)) }
    }

    private fun wipe(directory: File) = busy {
        val ok = directory.deleteRecursively() || !directory.exists()
        runOnUiThread {
            done()
            toast(getString(if (ok) R.string.user_data_deleted else R.string.user_data_delete_failed))
            refresh()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // the plumbing

    private fun busy(work: () -> Unit) {
        binding.progress.visibility = View.VISIBLE
        worker.execute(work)
    }

    private fun done() {
        binding.progress.visibility = View.GONE
    }

    private fun ask(title: Int, message: String, confirm: Int, then: () -> Unit) {
        val view = DialogMessageBinding.inflate(LayoutInflater.from(this))
        view.message.text = message
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(confirm) { _, _ -> then() }
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun formatted(bytes: Long): String = Formatter.formatShortFileSize(this, bytes)

    companion object {

        /**
         * **The emulator's name for the title, not the key its settings are filed under.** The two
         * agree for every dump that names a title id and differ for one that does not — and what
         * this screen measures, exports and deletes are directories the *emulator* named. See
         * [Game.emulatorTitleId].
         */
        const val EXTRA_TITLE_ID = "titleId"

        const val EXTRA_NAME = "name"

        private const val ZIP = "application/zip"

        fun intent(activity: AppCompatActivity, titleId: String, name: String): Intent =
            Intent(activity, GameUserDataActivity::class.java)
                .putExtra(EXTRA_TITLE_ID, titleId)
                .putExtra(EXTRA_NAME, name)
    }
}
