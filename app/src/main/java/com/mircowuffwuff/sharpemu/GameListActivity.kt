package com.mircowuffwuff.sharpemu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.color.MaterialColors
// the colour roles are Material's own attributes, and this module's R does not carry them: a
// non-transitive R class holds what the module itself declares and nothing a library does.
import com.google.android.material.R as MaterialR
import com.mircowuffwuff.sharpemu.databinding.ActivityGameListBinding
import java.util.concurrent.Executors

/**
 * The app's first screen: the games that are on the device, and a tap to run one.
 *
 * **This is the launcher activity and [MainActivity] is not.** MainActivity stays exported and keeps
 * every one of its intent extras, so `am start -n <id>/com.mircowuffwuff.sharpemu.MainActivity --es
 * game ...` and each script that builds that component name are unaffected. That is on purpose rather
 * than by accident: the intent path is the control arm — a game that boots by intent and not by tap
 * says the fault is this screen's.
 *
 * **A game is here from one of two places and the row does not say which**, because nothing about
 * playing it differs. What differs is the intent: a staged game is a path and a granted one is a
 * directory inside a tree, which the host layer answers for file by file. [GameLibrary] finds both
 * and [launch] is where the two part ways.
 *
 * **A tap runs a game and holding it configures one** — [launch] and [configure]. The two gestures
 * take the same row and part ways immediately: one builds an argument vector, and the other opens a
 * store.
 */
class GameListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameListBinding
    private lateinit var adapter: GameAdapter
    private val scanner = Executors.newSingleThreadExecutor()

    /**
     * The theme the settings scene last stored, as this screen was drawn with it.
     *
     * **Kept so that coming back from settings can notice.** A theme is resolved while the view
     * hierarchy is inflated, so a screen that was already inflated cannot be repainted — it has to
     * be recreated, and recreating unconditionally in `onResume` would restart this activity every
     * time the user came back from anywhere.
     */
    private lateinit var drawnWith: String

    /**
     * The directory picker the empty state's button opens.
     *
     * **The empty state goes straight to the picker rather than to the folder manager**, because on
     * this screen there is nothing to manage: a person looking at "no games yet" wants to point at
     * their library, not to visit a list that is also empty and press a second button. The manager
     * stays where it is for everything after the first folder.
     *
     * **What that costs is one duplicated picker and not one duplicated rule** — [GameLibrary.add]
     * decides, and [GameLibrary.message] says, so a folder that is itself a game is refused in the
     * same words here as there.
     *
     * Registered at construction, which the contract requires: the activity can be recreated while
     * the picker is in front of it, and a launcher registered later than `onCreate` has nothing to
     * deliver the result to.
     */
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        // null is the back button, which is not an error and not worth a word.
        if (tree != null) addFolder(tree)
    }

    /**
     * A run, and why it did not start when it did not.
     *
     * **The message is said here rather than by the screen that produced it, because a toast belongs
     * to the process that posted one.** A guest gets a process of its own and is ended with it, so a
     * run that gives up posts a toast and is killed a few hundred milliseconds later — the platform
     * cancels it with the process, and what a person sees is a flicker they cannot read. This screen
     * is in the process that survives, which is what makes the message readable at all.
     *
     * A run that ends normally carries no message and says nothing. Registered at construction for
     * the reason [picker] is.
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
        binding = ActivityGameListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // targetSdk 35 draws every window edge to edge, so without this the toolbar sits under the
        // status bar -- and the Fullscreen mode row picks the other behaviour. see SystemBars.
        SystemBars.apply(this, binding.root)

        adapter = GameAdapter(emptyList(), this::launch, this::configure)
        // **a grid of covers rather than a column of rows.** the count comes from integers.xml, so
        // the orientation qualifier answers it and a rotation re-reads it without anything watching
        // — the activity is recreated, which is what makes a resource the right place for it.
        binding.games.layoutManager =
            GridLayoutManager(this, resources.getInteger(R.integer.game_columns))
        binding.games.adapter = adapter

        // the cog is a view in the toolbar rather than a menu item, so that where it sits is this
        // layout's business — see activity_game_list.xml.
        binding.settings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // no initial uri: the picker opens wherever it was left, which is what the folder manager's
        // own button does.
        binding.addFolder.setOnClickListener { picker.launch(null) }

        binding.swipe.setOnRefreshListener { refresh() }
        // **the spinner is a white disc with a black arrow until it is told otherwise**, which is
        // the one thing on this screen that does not follow the theme. it has no attributes for
        // either colour, so the roles are set here.
        //
        // **the disc is the accent and the arrow is the surface it would otherwise have sat on**,
        // which is the louder of the two arrangements: the spinner is a small thing on a wide screen
        // and it appears only while something is happening, so it reads as an event rather than as
        // another surface. surfaceContainerHigh rather than onPrimary for the arrow, because what
        // is wanted is the popups' own fill drawn on the accent instead of under it.
        binding.swipe.setProgressBackgroundColorSchemeColor(
            MaterialColors.getColor(binding.swipe, MaterialR.attr.colorPrimary)
        )
        binding.swipe.setColorSchemeColors(
            MaterialColors.getColor(binding.swipe, MaterialR.attr.colorSurfaceContainerHigh)
        )
        // **without this the gesture would fire mid-list.** SwipeRefreshLayout decides by asking its
        // direct child whether it can scroll up, and its direct child is the frame holding both the
        // list and the empty label — a frame never scrolls, so the answer would always be "no" and a
        // drag anywhere in a scrolled list would refresh instead of scrolling.
        binding.swipe.setOnChildScrollUpCallback { _, _ -> binding.games.canScrollVertically(-1) }
    }

    /**
     * Rescans on every return to the screen.
     *
     * A game arrives while the app is open — adb writes the directory from a PC, or a file manager
     * finishes a copy into a granted folder — and the pull gesture is the deliberate answer rather
     * than the only one. Looking again whenever this screen comes forward costs a scan nobody waited
     * for and saves the gesture being mandatory.
     */
    override fun onResume() {
        super.onResume()
        // the theme is chosen on another screen and applies to this one. only a change costs a
        // recreate, so coming back from anywhere else is the ordinary path it has always been.
        if (Theme.recreateIfStale(this, drawnWith)) return
        SystemBars.apply(this, binding.root)
        refresh()
    }

    override fun onDestroy() {
        scanner.shutdown()
        super.onDestroy()
    }

    /**
     * Puts a folder picked from the empty state into the library, then rescans.
     *
     * On the scanner, because taking a grant queries a content provider — and on *that* thread in
     * particular so the rescan below cannot start before the folder is stored.
     *
     * **The spinner is shown rather than nothing**, since the grant is a provider round trip on a
     * screen whose only content is the message saying there is none.
     */
    private fun addFolder(tree: Uri) {
        binding.swipe.isRefreshing = true
        scanner.execute {
            val added = GameLibrary.add(this, tree)
            val label = GameLibrary.label(tree)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(this, GameLibrary.message(this, added, label), Toast.LENGTH_LONG)
                    .show()
                refresh()
            }
        }
    }

    /**
     * Scans on a worker and draws the result on the main thread.
     *
     * **The scan opens and parses a file per game, and asks a content provider for the granted ones**,
     * so it is off the main thread — a directory on external storage is one whose reads can be slow
     * for reasons that have nothing to do with how many games there are, and a provider query is a
     * binder round trip on top of that.
     *
     * One thread, so two scans cannot race and hand the adapter their results out of order — which
     * the pull gesture starting one while `onResume` already did is exactly how to arrange. The
     * finished check is what keeps a scan that outlived the screen from touching a destroyed one.
     */
    private fun refresh() {
        // the same directory MainActivity resolves a `--es game` name against, through the same
        // accessor. two spellings of one path is how a list ends up offering something that cannot
        // then be launched.
        val root = AppStorage.games(getExternalFilesDir(null)!!)

        scanner.execute {
            val games = GameLibrary.scan(this, root)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                show(games)
            }
        }
    }

    private fun show(games: List<Game>) {
        binding.swipe.isRefreshing = false
        adapter.submit(games)
        binding.empty.visibility = if (games.isEmpty()) View.VISIBLE else View.GONE
        binding.games.visibility = if (games.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Starts the guest.
     *
     * **Which extra carries the game is the whole difference between the two sources.** A staged game
     * is `game`, a name under the app's own `games/`; a granted one is `safgame` beside `saftree`,
     * naming a directory and the tree it is in. The host layer then mounts the provider and hands the
     * guest an invented path — `docs/guest-files.md` — instead of opening a real one.
     *
     * **A granted game has a third way in, and it is this one that is the branch.** With all-files
     * access held, the same directory is an ordinary path, so it goes as `game` and the file layer is
     * never registered — which is not a third mode of anything but the staged one, reached from a
     * folder the user picked instead of from the tooling. [AllFiles] decides, per launch, and answers
     * null for every reason including the ordinary one.
     *
     * **Every other extra is left absent on purpose, and a settings scene existing does not change
     * that.** Absent is a real answer everywhere MainActivity reads one — no `sharpemu` means the
     * most recently staged build, no `driver` means the platform's own. What the user chose is
     * merged by MainActivity, which is the one place that can see both a stored value and an extra
     * that overrides it; a tap that put the stored values in the intent would make this screen and
     * `am start` two different mergers of the same three sources, and the second one would be wrong
     * the moment the first grew a row.
     */
    /**
     * Opens one game's settings.
     *
     * **Everything that scene draws is already in hand**, because this row was built by opening the
     * dump — so the artwork, the name and the identity are handed over rather than read again. The
     * identity in particular is worth not re-reading: for a game inside a granted tree it is a
     * content provider round trip, and this is a gesture that happens while a finger is held down.
     */
    private fun configure(game: Game) {
        startActivity(GameSettingsActivity.intent(this, game))
    }

    private fun launch(game: Game) {
        val intent = Intent(this, MainActivity::class.java)
        val source = game.source
        val how = when (source) {
            is GameSource.Staged -> {
                intent.putExtra("game", game.folder)
                "staged"
            }
            is GameSource.Granted -> {
                val direct = AllFiles.pathTo(source.documentId)
                if (direct != null) {
                    intent.putExtra("game", direct.absolutePath)
                    "by path, with all-files access"
                } else {
                    intent.putExtra("safgame", game.folder)
                    // **the tree travels with it, and that is what replaces the placeholder.** until
                    // there was a picker, MainActivity took whichever persisted grant came first —
                    // fine with one and a coin toss with two.
                    intent.putExtra("saftree", source.tree.toString())
                    "through " + GameLibrary.label(source.tree)
                }
            }
        }
        // the folder, because that is what the intent carries and what the host layer will name in
        // its own lines. the display name is beside it so a log and a screen can be read together.
        Log.i(TAG, "[app] launching " + game.folder + " (" + game.name + ", " + how + ")")
        // for a result, and the result is only ever a refusal to explain — see [run]. it changes
        // nothing about the launch itself: the extras are the same ones `am start` carries, which is
        // what keeps the intent path the control arm it is described as above.
        run.launch(intent)
    }

    private companion object {
        const val TAG = "sharpemu"
    }
}
