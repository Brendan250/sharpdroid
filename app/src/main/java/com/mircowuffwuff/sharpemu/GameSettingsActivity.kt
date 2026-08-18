package com.mircowuffwuff.sharpemu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import android.text.format.Formatter
import androidx.recyclerview.widget.GridLayoutManager
import coil.load
import com.mircowuffwuff.sharpemu.databinding.ActivityGameSettingsBinding
import java.io.File
import java.util.concurrent.Executors

/**
 * One game's settings: which game, on the left, and what can be set for it on the right.
 *
 * **The sections behind these cards are the app's own**, not copies of them — [SettingsSectionActivity]
 * is handed a game and writes that game's store instead of the app's. So this scene is a way in rather
 * than a second implementation, and a row added to Emulation, Graphics or Controls is offered here the
 * day it is written.
 *
 * **Everything it draws travels in the intent, except the one thing that costs a walk.** The list that
 * opened it had already opened the dump to draw the row, so the name, the artwork and the identity are
 * handed over rather than read again — which for a game inside a granted tree is a provider round trip
 * saved on every long press. The dump's *size* is the exception: it is hundreds of files either way, so
 * what travels is where to look and this screen measures it on a worker.
 *
 * **It is reached by holding a cover and by nothing else.** Not exported, like every screen behind the
 * cog: what is set here decides what a launch runs.
 */
class GameSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameSettingsBinding

    /** The theme this screen was drawn with, so a change made behind it is noticed on the way back. */
    private lateinit var drawnWith: String

    private lateinit var configKey: String
    private lateinit var gameName: String

    /** What the emulator calls this game. See [Game.emulatorTitleId]. */
    private lateinit var emulatorTitleId: String

    /** One thread, for the one measurement this screen makes. */
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(state: Bundle?) {
        // before setContentView, or the theme is resolved after the views are already inflated.
        Theme.apply(this)
        drawnWith = Theme.signature(this)
        super.onCreate(state)

        configKey = intent.getStringExtra(EXTRA_CONFIG_KEY).orEmpty()
        gameName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        // **it falls back to the emulator's own answer for a dump naming nothing**, which is what an
        // intent missing the extra describes: the User data screen behind this one refuses an empty
        // string and says so for the shared name, and those are two different screens rather than
        // one screen and a crash.
        emulatorTitleId = intent.getStringExtra(EXTRA_EMULATOR_TITLE_ID)
            ?.takeIf { it.isNotEmpty() } ?: Game.UNKNOWN_TITLE_ID
        if (configKey.isEmpty()) {
            // nothing but a hand-written intent reaches this, and finishing beats a scene that would
            // write every row into a store named after nothing.
            finish()
            return
        }

        binding = ActivityGameSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        // **the game's name is this screen's title**, so it goes where every other screen's title
        // goes rather than under the artwork: set beside the artwork it competed with the two facts
        // below it for which line was the heading.
        binding.toolbar.title = gameName
        binding.toolbar.setNavigationOnClickListener { finish() }

        // **the title id and the version are drawn here where the grid deliberately draws neither.**
        // a cover is picked by its picture and its name and both would be noise on it; this screen is
        // about one game in particular, and these are what a person matches against a log line, a
        // save data directory or a folder on their PC.
        //
        // a row goes away whole rather than leaving its label with nothing beside it.
        fact(binding.facts.titleIdRow, binding.facts.titleId, intent.getStringExtra(EXTRA_TITLE_ID))
        fact(binding.facts.versionRow, binding.facts.version, intent.getStringExtra(EXTRA_VERSION))
        fact(binding.facts.pathRow, binding.facts.path, intent.getStringExtra(EXTRA_PATH))
        measureSize()
        binding.icon.load(icon()) {
            placeholder(R.drawable.ic_game_placeholder)
            error(R.drawable.ic_game_placeholder)
        }

        binding.sections.layoutManager =
            GridLayoutManager(this, resources.getInteger(R.integer.settings_section_columns))
        binding.sections.adapter =
            SectionAdapter(SettingsActivity.Section.perGame, perGame = true) { section ->
                // **a section is usually the app's own screen told which store to write, and User
                // data is the one that is not** — see [SettingsActivity.Section.perGameScreen]. It
                // is handed the emulator's title id rather than the config key, because what it acts
                // on are directories the emulator named.
                startActivity(
                    if (section.perGameScreen == null) {
                        Intent(this, SettingsSectionActivity::class.java)
                            .putExtra(SettingsSectionActivity.EXTRA_SECTION, section.name)
                            .putExtra(SettingsSectionActivity.EXTRA_GAME, configKey)
                    } else {
                        GameUserDataActivity.intent(this, emulatorTitleId, gameName)
                    }
                )
            }
    }

    /**
     * Measures the whole dump and fills the size row in when it lands.
     *
     * **The row is on screen before the number is**, drawn with its label and an empty value, so the
     * path under it does not jump a line down a moment after the screen opens. A measurement that
     * finds nothing takes the row away instead: a game that reads as `0 B` is a wrong answer stated
     * confidently, where a missing row is the same thing said honestly.
     *
     * **The walk is off the main thread and the result is checked against the screen still being
     * there**, since a dump is hundreds of files and a granted one is that many through a provider.
     */
    private fun measureSize() {
        val directory = intent.getStringExtra(EXTRA_DIRECTORY)
        val tree = intent.getStringExtra(EXTRA_TREE)
        val document = intent.getStringExtra(EXTRA_DOCUMENT_ID)
        binding.facts.size.text = ""
        worker.execute {
            val bytes = when {
                !directory.isNullOrEmpty() -> GameSize.of(File(directory))
                !tree.isNullOrEmpty() && !document.isNullOrEmpty() ->
                    GameSize.of(applicationContext.contentResolver, Uri.parse(tree), document)
                else -> 0L
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                fact(
                    binding.facts.sizeRow,
                    binding.facts.size,
                    if (bytes > 0) Formatter.formatShortFileSize(this, bytes) else null,
                )
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /** One row of the fact table, or no row at all where the dump does not carry that fact. */
    private fun fact(row: View, value: android.widget.TextView, text: String?) {
        value.text = text.orEmpty()
        row.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * What coil is handed for the artwork.
     *
     * **The two kinds are carried as two extras rather than as one string and a rule for reading it.**
     * A staged dump's icon is a path and a granted one's is a `content://` uri, and a launcher that
     * guessed by looking for a scheme would be right until the day a path contained one.
     */
    private fun icon(): Any? {
        intent.getStringExtra(EXTRA_ICON_URI)?.let { return Uri.parse(it) }
        intent.getStringExtra(EXTRA_ICON_PATH)?.let { return File(it) }
        return null
    }

    /**
     * **The theme is changed on a screen this one leads to**, so coming back has to notice — the same
     * reason the app's own settings scene watches for it.
     */
    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        if (Theme.recreateIfStale(this, drawnWith)) return
        SystemBars.apply(this, binding.root)
    }

    companion object {
        const val EXTRA_CONFIG_KEY = "configKey"
        const val EXTRA_NAME = "name"

        /**
         * The title id as the *list* answers it, which is what the fact table under the artwork
         * prints — a dump's own field, or the `[PPSA…]` in its directory name.
         *
         * **[EXTRA_EMULATOR_TITLE_ID] is the other one and they are both here on purpose.** This is
         * the string a person matches against a log line or a folder on their PC; that one is the
         * string the emulator names a directory with. They agree for every dump that carries the
         * field, and a screen acting on a directory must not be handed the one that guesses.
         */
        const val EXTRA_TITLE_ID = "titleId"

        /** See [Game.emulatorTitleId], and [EXTRA_TITLE_ID] on why this is a second extra. */
        const val EXTRA_EMULATOR_TITLE_ID = "emulatorTitleId"
        const val EXTRA_VERSION = "version"
        const val EXTRA_PATH = "path"

        /**
         * Where to look to measure the dump: a directory for a staged game, or the tree and document
         * id for a granted one. **Not a size**, because measuring one is a walk of hundreds of files
         * and the gesture that opens this screen is a finger held down on a cover.
         */
        const val EXTRA_DIRECTORY = "directory"
        const val EXTRA_TREE = "tree"
        const val EXTRA_DOCUMENT_ID = "documentId"
        const val EXTRA_ICON_PATH = "iconPath"
        const val EXTRA_ICON_URI = "iconUri"

        /** The scene for one game, with everything it draws already in hand. */
        fun intent(activity: AppCompatActivity, game: Game): Intent =
            Intent(activity, GameSettingsActivity::class.java)
                .putExtra(EXTRA_CONFIG_KEY, game.configKey)
                .putExtra(EXTRA_NAME, game.name)
                .putExtra(EXTRA_TITLE_ID, game.titleId)
                .putExtra(EXTRA_EMULATOR_TITLE_ID, game.emulatorTitleId)
                .putExtra(EXTRA_VERSION, game.version)
                .putExtra(EXTRA_PATH, game.ebootPath)
                .apply {
                    when (val source = game.source) {
                        is GameSource.Staged ->
                            putExtra(EXTRA_DIRECTORY, source.directory.absolutePath)
                        is GameSource.Granted -> {
                            putExtra(EXTRA_TREE, source.tree.toString())
                            putExtra(EXTRA_DOCUMENT_ID, source.documentId)
                        }
                    }
                    when (val icon = game.icon) {
                        is File -> putExtra(EXTRA_ICON_PATH, icon.absolutePath)
                        is Uri -> putExtra(EXTRA_ICON_URI, icon.toString())
                        else -> Unit
                    }
                }
    }
}
