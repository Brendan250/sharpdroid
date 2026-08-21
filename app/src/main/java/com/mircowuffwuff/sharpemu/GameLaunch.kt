package com.mircowuffwuff.sharpemu

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * The intent that starts a guest, built in one place because more than one screen starts one.
 *
 * **Which extra carries the game is the whole difference between the two sources**, and getting it
 * wrong is not a crash but a launch that reads the wrong directory — so it is written once. A screen
 * that assembled its own would be a second answer to that question, wrong the day either kind of
 * source grows a second way in.
 *
 * **Every other extra is left absent on purpose, and a settings scene existing does not change
 * that.** Absent is a real answer everywhere [MainActivity] reads one: no `sharpemu` means the most
 * recently staged build, no `driver` means the platform's own. What the user chose is merged by
 * MainActivity, which is the one place that can see a build's environment, the app's store, a game's
 * store and an overriding extra at once. A screen that put the stored values into the intent would
 * make itself and `am start` two different mergers of the same sources, and the second one would be
 * wrong the moment the first grew a row.
 */
object GameLaunch {

    private const val TAG = "sharpemu"

    /**
     * Where a guest run is started from, for the line that says one is starting.
     *
     * It names the gesture rather than the class, because what a reader of a log wants to know is
     * which of the two ways in was taken -- and a screen may be renamed without that changing.
     */
    enum class From(val label: String) {
        LIST("the game list"),
        GAME("the game's own scene"),
    }

    /**
     * The intent for [source], and the line saying so.
     *
     * **A staged game is `game`, a name under the app's own `games/`; a granted one is `safgame`
     * beside `saftree`**, naming a directory and the tree it is in. The host layer then mounts the
     * provider and hands the guest an invented path -- `docs/guest-files.md` -- instead of opening a
     * real one.
     *
     * **A granted game has a third way in, and it is this one that is the branch.** With all-files
     * access held, the same directory is an ordinary path, so it goes as `game` and the file layer
     * is never registered -- which is not a third mode of anything but the staged one, reached from
     * a folder the user picked instead of from the tooling. [AllFiles] decides, per launch, and
     * answers null for every reason including the ordinary one.
     */
    fun intent(context: Context, source: GameSource, name: String, from: From): Intent {
        val intent = Intent(context, MainActivity::class.java)
        // **what the loading screen shows, and it is sent from here because only here has it.** the
        // extras above name a directory, which is what the host layer opens; a person waiting in front
        // of a black screen wants the name and the cover they tapped. reading them on the other side
        // would mean parsing the dump a second time, on the launch's own critical path, to arrive at
        // what this screen already holds.
        //
        // **both are absent from an `am start`, and absent is a real answer**: the loading screen falls
        // back to the directory name and draws no artwork. that keeps the intent path a control arm --
        // a scripted launch is the same launch it always was.
        intent.putExtra("gamename", name)
        // a File for a staged game and a content:// uri for a granted one, as the string each is. a
        // grant belongs to the package rather than to a process, so the uri is readable in `:guest`
        // for the same reason a granted game boots there at all.
        when (val icon = source.icon) {
            is File -> intent.putExtra("gameicon", icon.absolutePath)
            is Uri -> intent.putExtra("gameicon", icon.toString())
        }
        val how = when (source) {
            is GameSource.Staged -> {
                intent.putExtra("game", source.folder)
                "staged"
            }
            is GameSource.Granted -> {
                val direct = AllFiles.pathTo(source.documentId)
                if (direct != null) {
                    intent.putExtra("game", direct.absolutePath)
                    "by path, with all-files access"
                } else {
                    intent.putExtra("safgame", source.folder)
                    // **the tree travels with it, and that is what replaces the placeholder.** a
                    // launch naming no tree takes whichever persisted grant comes first, which is
                    // exact with one granted library and a coin toss with two.
                    intent.putExtra("saftree", source.tree.toString())
                    "through " + GameLibrary.label(source.tree)
                }
            }
        }
        // the folder, because that is what the intent carries and what the host layer will name in
        // its own lines. the display name is beside it so a log and a screen can be read together,
        // and where the run was started from, since two screens now start one.
        AppLog.i(
            TAG,
            "[app] launching " + source.folder + " (" + name + ", " + how + ", from " +
                from.label + ")"
        )
        return intent
    }
}
