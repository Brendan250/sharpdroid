package com.mircowuffwuff.sharpemu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import android.text.format.Formatter
import androidx.recyclerview.widget.GridLayoutManager
import coil.load
import com.mircowuffwuff.sharpemu.databinding.ActivityGameSettingsBinding
import java.io.File
import java.util.concurrent.Executors

/**
 * one game's settings: which game, and what can be set for it.
 *
 * **three blocks in both compositions -- the artwork, what the dump says about itself, and the cards.**
 * wide, they are a column beside a column; upright they are one column that scrolls as a whole, the
 * artwork leading at the width of the panel. the blocks and their order are the same either way, so
 * the two are one arrangement at two widths rather than two arrangements. see the layouts.
 *
 * **the sections behind these cards are the app's own**, not copies of them -- [SettingsSectionActivity]
 * is handed a game and writes that game's store instead of the app's. so this scene is a way in rather
 * than a second implementation, and a row added to Emulation, Graphics or Controls is offered here the
 * day it is written.
 *
 * **everything it draws travels in the intent, except the one thing that costs a walk.** the list that
 * opened it had already opened the dump to draw the row, so the name, the artwork and the identity are
 * handed over rather than read again -- which for a game inside a granted tree is a provider round trip
 * saved on every long press. the dump's *size* is the exception: it is hundreds of files either way, so
 * what travels is where to look and this screen measures it on a worker.
 *
 * **it is reached by holding a cover and by nothing else.** not exported, like every screen behind the
 * cog: what is set here decides what a launch runs.
 *
 * **and it can start the game, on the button the managers put in that corner.** somebody who came here
 * to change how a game runs is one gesture from finding out, rather than backing out to the grid to
 * tap the cover they were just holding. it is the list's own launch -- [GameLaunch] builds the intent
 * for both -- so the run is identical whichever screen started it.
 */
class GameSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameSettingsBinding

    /** the theme this screen was drawn with, so a change made behind it is noticed on the way back. */
    private lateinit var drawnWith: String

    private lateinit var configKey: String
    private lateinit var gameName: String

    /** what the emulator calls this game. see [Game.emulatorTitleId]. */
    private lateinit var emulatorTitleId: String

    /** one thread, for the one measurement this screen makes. */
    private val worker = Executors.newSingleThreadExecutor()

    /**
     * a run started from here, and why it did not start when it did not.
     *
     * **the message is said by this screen because a toast belongs to the process that posted one**,
     * which is the game list's reason exactly: a guest gets a process of its own and is ended with
     * it, so a run that gives up would post a toast and be killed before it could be read. this
     * screen is in the process that survives.
     *
     * registered at construction, as the contract requires -- this activity can be recreated while a
     * guest is in front of it, and a launcher registered later has nothing to deliver a result to.
     */
    private val run = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val why = result.data?.getStringExtra(MainActivity.ABORT_MESSAGE)
        if (!why.isNullOrEmpty()) {
            Toast.makeText(this, why, Toast.LENGTH_LONG).show()
        }
    }

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
        capArtworkAndFacts()
        binding.icon.load(icon()) {
            placeholder(R.drawable.ic_game_placeholder)
            error(R.drawable.ic_game_placeholder)
        }

        // **the same run a tap on the cover starts, from the same intent builder.** what this scene
        // sets is read by MainActivity out of the game's own store, so the launch says nothing about
        // it -- see [GameLaunch] on why putting the stored values in the intent would be wrong.
        binding.launch.setOnClickListener { launch() }
        if (source() == null) binding.launch.visibility = View.GONE

        binding.sections.layoutManager =
            GridLayoutManager(this, resources.getInteger(R.integer.settings_section_columns))
        binding.sections.adapter =
            SectionAdapter(SettingsActivity.Section.perGame, perGame = true) { section ->
                // **a section is usually the app's own screen told which store to write, and User
                // data is the one that is not** -- see [SettingsActivity.Section.perGameScreen]. it
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
     * sizes the artwork against the panel, and the facts to match it, where the layout asks for it.
     *
     * **upright the artwork leads at the width of the column, and a square sized by width is as tall
     * as the panel is wide.** on a 16:9 panel that is over half the height, which puts the first card
     * below the fold and makes a screen of settings one nobody can act on without scrolling -- and it
     * is worst on 16:9 rather than on the 21:9 panels this is drawn for, because those are the ones
     * with the least height per unit of width.
     *
     * so the side is the smaller of what the column allows and a share of the panel's height, which
     * `integers.xml` names. where the cap binds the artwork is narrower than the column and the
     * layout centres it; on the 21:9 panels this is drawn for it very nearly does not bind at all.
     *
     * **XML cannot take a minimum of two numbers**, which is the only reason this is here rather than
     * in the layout beside the view it moves. whether it applies at all is a resource -- see
     * `values/bools.xml` -- so the wide composition, which bounds the artwork by its own column, is
     * left exactly as it draws itself rather than being excluded by a test on the orientation.
     *
     * the display's own height is what the share is taken of. the window is a few dozen pixels shorter
     * once the system bars are inset, and the difference does not change what this is for: the cap is
     * a proportion chosen by eye, not a measurement something else depends on.
     */
    private fun capArtworkAndFacts() {
        if (!resources.getBoolean(R.bool.game_artwork_capped)) return
        val metrics = resources.displayMetrics
        // **the column's own insets, read rather than repeated.** they are the layout's number and
        // this is the one place that has to agree with it, so asking the parent is what keeps the
        // two from drifting the day that padding moves.
        val column = (binding.icon.parent as? View)
            ?.let { metrics.widthPixels - it.paddingStart - it.paddingEnd }
            ?: metrics.widthPixels
        val share = resources.getInteger(R.integer.game_artwork_panel_percent)
        val side = minOf(column, metrics.heightPixels * share / 100)
        binding.icon.updateLayoutParams { width = side; height = side }
        // **the facts take the artwork's width rather than the column's**, so the table stays inside
        // the edges of the picture it describes and reads as its caption. the layout centres it; only
        // the number has to come from here, and it is the number above.
        binding.facts.root.updateLayoutParams { width = side }
    }

    /**
     * measures the whole dump and fills the size row in when it lands.
     *
     * **the row is on screen before the number is**, drawn with its label and an empty value, so the
     * path under it does not jump a line down a moment after the screen opens. a measurement that
     * finds nothing takes the row away instead: a game that reads as `0 B` is a wrong answer stated
     * confidently, where a missing row is the same thing said honestly.
     *
     * **the walk is off the main thread and the result is checked against the screen still being
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

    /**
     * starts this game, exactly as a tap on its cover does.
     *
     * **the same builder the list uses**, so the two ways in produce one intent rather than two that
     * agree today -- [GameLaunch].
     */
    private fun launch() {
        val source = source() ?: return
        run.launch(GameLaunch.intent(this, source, gameName, GameLaunch.From.GAME))
    }

    /**
     * this game's source, rebuilt from what the intent already carries.
     *
     * **rebuilt rather than carried, because a [GameSource] is not something an intent can hold** --
     * the granted kind owns a content resolver. what travels is the pair of facts each kind is made
     * of, which is what the artwork and the size measurement already needed, plus the directory's
     * own name.
     *
     * **the name travels rather than being derived**, though a staged source could derive it from
     * its path: the granted kind cannot, its folder having come from the cursor that listed it and
     * never from a guess.
     *
     * null for an intent naming neither kind, which is a hand-written one -- the button is taken away
     * rather than left to start a run with nothing in it.
     */
    private fun source(): GameSource? {
        val directory = intent.getStringExtra(EXTRA_DIRECTORY)
        if (!directory.isNullOrEmpty()) return GameSource.Staged(File(directory))
        val tree = intent.getStringExtra(EXTRA_TREE)
        val document = intent.getStringExtra(EXTRA_DOCUMENT_ID)
        val folder = intent.getStringExtra(EXTRA_FOLDER)
        if (tree.isNullOrEmpty() || document.isNullOrEmpty() || folder.isNullOrEmpty()) return null
        // the application's resolver rather than this activity's, so a source outliving the screen
        // is not a leaked activity -- GameSource says so where the class is declared.
        return GameSource.Granted(Uri.parse(tree), document, folder, applicationContext.contentResolver)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /** one row of the fact table, or no row at all where the dump does not carry that fact. */
    private fun fact(row: View, value: android.widget.TextView, text: String?) {
        value.text = text.orEmpty()
        row.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * what coil is handed for the artwork.
     *
     * **the two kinds are carried as two extras rather than as one string and a rule for reading it.**
     * a staged dump's icon is a path and a granted one's is a `content://` uri, and a launcher that
     * guessed by looking for a scheme would be right until the day a path contained one.
     */
    private fun icon(): Any? {
        intent.getStringExtra(EXTRA_ICON_URI)?.let { return Uri.parse(it) }
        intent.getStringExtra(EXTRA_ICON_PATH)?.let { return File(it) }
        return null
    }

    /**
     * **the theme is changed on a screen this one leads to**, so coming back has to notice -- the same
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
         * the title id as the *list* answers it, which is what the fact table under the artwork
         * prints -- a dump's own field, or the `[PPSA…]` in its directory name.
         *
         * **[EXTRA_EMULATOR_TITLE_ID] is the other one and they are both here on purpose.** this is
         * the string a person matches against a log line or a folder on their PC; that one is the
         * string the emulator names a directory with. they agree for every dump that carries the
         * field, and a screen acting on a directory must not be handed the one that guesses.
         */
        const val EXTRA_TITLE_ID = "titleId"

        /** see [Game.emulatorTitleId], and [EXTRA_TITLE_ID] on why this is a second extra. */
        const val EXTRA_EMULATOR_TITLE_ID = "emulatorTitleId"
        const val EXTRA_VERSION = "version"
        const val EXTRA_PATH = "path"

        /**
         * where to look to measure the dump: a directory for a staged game, or the tree and document
         * id for a granted one. **not a size**, because measuring one is a walk of hundreds of files
         * and the gesture that opens this screen is a finger held down on a cover.
         */
        const val EXTRA_DIRECTORY = "directory"
        const val EXTRA_TREE = "tree"
        const val EXTRA_DOCUMENT_ID = "documentId"

        /**
         * the directory's own name, which is what a launch intent carries.
         *
         * **a staged game could derive it and a granted one could not**, its folder having come from
         * the cursor that listed the tree rather than from anything this screen can take apart. so it
         * travels for both, one rule being better than a rule with an exception in it.
         */
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_ICON_PATH = "iconPath"
        const val EXTRA_ICON_URI = "iconUri"

        /** the scene for one game, with everything it draws already in hand. */
        fun intent(activity: AppCompatActivity, game: Game): Intent =
            Intent(activity, GameSettingsActivity::class.java)
                .putExtra(EXTRA_CONFIG_KEY, game.configKey)
                .putExtra(EXTRA_NAME, game.name)
                .putExtra(EXTRA_TITLE_ID, game.titleId)
                .putExtra(EXTRA_EMULATOR_TITLE_ID, game.emulatorTitleId)
                .putExtra(EXTRA_VERSION, game.version)
                .putExtra(EXTRA_PATH, game.ebootPath)
                .putExtra(EXTRA_FOLDER, game.folder)
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
