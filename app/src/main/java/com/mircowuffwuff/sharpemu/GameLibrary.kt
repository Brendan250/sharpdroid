package com.mircowuffwuff.sharpemu

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * every game the app can see, from both places one can be, and the folders the user granted us.
 *
 * **a game is a directory holding an `eboot.bin`, and that is the whole test on either volume.** an
 * empty directory left behind by a half-finished staging run is not a game, and offering it would
 * mean the host layer reporting a failure the scan could have avoided.
 *
 * the two places are not alike and neither replaces the other:
 *
 * - **staged**, under the app's own external files, written by `scripts/stage.py`. this is the
 *   arm every measurement in the project was taken on, and it stays reachable exactly as it was
 * - **granted**, inside a directory tree the user picked. `docs/guest-files.md` describes what the
 *   guest then pays for reading one, which was measured rather than assumed: nothing above the noise
 *   floor, on either title
 *
 * **this object is the store as well as the scan**, and the store is deliberately two things at
 * once: the tree uris live in a `SharedPreferences` line, which is what remembers *which* folders and
 * in what order, and the platform's own persisted-permission list is the authority on whether each is
 * still readable. keeping only the second would mean a grant taken later for something else -- a
 * driver zip, a build zip -- appearing in the library; keeping only the first would mean offering a
 * folder whose grant the user revoked in Settings. a folder that fails that cross-check is dropped
 * and said so, once.
 *
 * **everything here talks to a content provider and none of it belongs on the main thread.**
 * [GameListActivity] runs it on a worker.
 */
object GameLibrary {

    private const val TAG = "sharpemu"

    private const val PREFS = "library"
    private const val KEY_TREES = "trees"

    /** what [add] made of a folder the user picked. */
    enum class Added {
        /** granted, stored, and picked up by the next scan. */
        OK,

        /** already in the library. picking the same folder twice is a no-op rather than a duplicate. */
        ALREADY_THERE,

        /**
         * the folder picked **is** a game rather than a folder of games.
         *
         * worth its own answer rather than an empty result: it is the likeliest way to get this
         * wrong, and it would otherwise present as a grant that was accepted and shows nothing.
         */
        IS_A_GAME,

        /** the grant could not be persisted. */
        FAILED,
    }

    /**
     * what to tell the user about an [Added], as a toast.
     *
     * **it is here rather than in a screen because two screens offer the picker** -- the folder
     * manager and the game list's empty state -- and a refusal worded differently depending on which
     * button was pressed is the first thing to drift when a new outcome is added. every outcome
     * speaks, the refusals included: a folder that was picked and then silently did not appear is
     * the one result nobody can act on.
     *
     * **[IS_A_GAME] carries no label.** it is a whole path, a toast is two lines and truncates
     * without warning, and the half that says what to do about it is the half that gets cut. [add]
     * logs the folder exactly.
     */
    fun message(context: Context, added: Added, label: String): String = when (added) {
        Added.OK -> context.getString(R.string.folder_added, label)
        Added.ALREADY_THERE -> context.getString(R.string.folder_already_there, label)
        Added.IS_A_GAME -> context.getString(R.string.folder_is_a_game)
        Added.FAILED -> context.getString(R.string.folder_failed)
    }

    // ---------------------------------------------------------------------------------------------
    // the granted folders

    /**
     * the granted folders, in the order they were added, minus any whose grant is gone.
     *
     * a grant can be revoked from android's own Settings, and it can go away when the volume it
     * points at is unmounted. neither errors here -- the folder simply stops being in the library, and
     * the count is logged so a list that lost rows says why.
     */
    fun trees(context: Context): List<Uri> {
        val stored = prefs(context).getString(KEY_TREES, "").orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }
            .map(Uri::parse)
        if (stored.isEmpty()) return emptyList()

        val held = context.applicationContext.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }
            .toSet()
        val live = stored.filter { it in held }
        if (live.size != stored.size) {
            Log.w(TAG, "[app] " + (stored.size - live.size) + " folder(s) in the library are no"
                + " longer granted to this app -- revoked, or on a volume that is not mounted."
                + " dropping them")
            store(context, live)
        }
        return live
    }

    /**
     * takes a persisted read grant on [tree] and puts it in the library.
     *
     * **the grant is taken last, and only for a folder that will produce rows.** the check above it
     * costs one provider query and runs on the grant the picker already handed this process, so a
     * folder that is refused leaves nothing behind to release.
     */
    fun add(context: Context, tree: Uri): Added {
        if (tree in trees(context)) {
            return Added.ALREADY_THERE
        }
        val resolver = context.applicationContext.contentResolver
        val rootId = TreeDocument.rootId(tree)
        if (exists(resolver, tree, TreeDocument.childId(rootId, Game.EBOOT))) {
            Log.w(TAG, "[app] " + rootId + " holds an " + Game.EBOOT + " of its own -- that is a game,"
                + " and the library wants the folder above it")
            return Added.IS_A_GAME
        }
        return try {
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            store(context, trees(context) + tree)
            Log.i(TAG, "[app] added " + rootId + " to the library")
            Added.OK
        } catch (e: Exception) {
            Log.e(TAG, "[app] could not persist the grant on " + tree, e)
            Added.FAILED
        }
    }

    /**
     * drops [tree] from the library and releases its grant.
     *
     * the release is what makes this undoable-by-picking-again rather than merely hidden: a grant the
     * app holds forever is one the user cannot see the end of, and android's own storage screen is
     * where they would go looking.
     */
    fun remove(context: Context, tree: Uri) {
        store(context, trees(context).filterNot { it == tree })
        try {
            context.applicationContext.contentResolver
                .releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // the store is already updated, which is the part that decides what the list shows. a
            // grant that cannot be released is one android has already forgotten.
            Log.w(TAG, "[app] removed " + tree + " from the library, but could not release the grant: " + e)
        }
        Log.i(TAG, "[app] removed " + TreeDocument.rootId(tree) + " from the library")
    }

    /**
     * what to call a granted folder on screen -- `primary:roms/ps5`, or an SD card's volume id.
     *
     * the document id rather than the display name, and that is a choice: the display name is the
     * last component, so two `ps5` folders on two volumes would be one label twice. this says where.
     */
    fun label(tree: Uri): String =
        try {
            TreeDocument.rootId(tree)
        } catch (e: Exception) {
            tree.toString()
        }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun store(context: Context, trees: List<Uri>) {
        prefs(context).edit()
            .putString(KEY_TREES, trees.joinToString("\n"))
            .apply()
    }

    // ---------------------------------------------------------------------------------------------
    // the scan

    /**
     * every game, staged and granted, ordered by display name.
     *
     * returns empty rather than throwing when [stagedRoot] does not exist -- a fresh install has no
     * `games/` until something writes one, and that is an empty list rather than an error.
     */
    fun scan(context: Context, stagedRoot: File): List<Game> {
        val staged = stagedRoot.listFiles()
            ?.filter { it.isDirectory && File(it, Game.EBOOT).isFile }
            ?.map { Game.read(GameSource.Staged(it)) }
            .orEmpty()
        val granted = trees(context).flatMap { scanTree(context, it) }
        Log.i(TAG, "[app] " + staged.size + " staged and " + granted.size + " granted game(s)")
        return (staged + granted).sortedBy { it.name.lowercase() }
    }

    /**
     * the games directly inside one granted folder. one level, not a search.
     *
     * **the child ids come out of the cursor that listed them rather than being built**, which is
     * free -- the listing returns them -- and is one fewer place relying on [TreeDocument]'s assumption
     * about how a provider names things. below that level the concatenation rule does apply, because
     * checking for an `eboot.bin` by listing a game directory would mean enumerating hundreds of
     * files to learn one thing.
     */
    private fun scanTree(context: Context, tree: Uri): List<Game> {
        val resolver = context.applicationContext.contentResolver
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val children = query(resolver, TreeDocument.childrenUri(tree, TreeDocument.rootId(tree)), columns)
            ?: return emptyList()

        val games = ArrayList<Game>()
        children.use {
            while (it.moveToNext()) {
                if (it.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR) continue
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                if (!exists(resolver, tree, TreeDocument.childId(id, Game.EBOOT))) continue
                games.add(Game.read(GameSource.Granted(tree, id, name, resolver)))
            }
        }
        return games
    }

    /** whether there is a document at [id]. one query, one column, and the answer is `moveToFirst`. */
    private fun exists(resolver: ContentResolver, tree: Uri, id: String): Boolean =
        query(
            resolver,
            TreeDocument.uri(tree, id),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
        )?.use { it.moveToFirst() } ?: false

    /**
     * a cursor, or null for every reason there is -- including the ordinary one.
     *
     * **a query for a document that is not there throws**, so an absent `eboot.bin` -- which is what
     * every directory in a folder of screenshots looks like -- arrives as an exception rather than as
     * an empty cursor. it is not logged for that reason: a scan of a folder the user picked by
     * mistake would otherwise be one line of alarm per subdirectory in it.
     */
    private fun query(resolver: ContentResolver, uri: Uri, columns: Array<String>): Cursor? =
        try {
            resolver.query(uri, columns, null, null, null)
        } catch (e: Exception) {
            null
        }
}
