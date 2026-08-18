package com.mircowuffwuff.sharpemu

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * How much of a volume a game takes, counted the two ways a game can be reached.
 *
 * **It is not on [GameSource] and it is not carried by [Game], because it costs a walk.** A dump is
 * hundreds of files, and the scan that builds the game list opens one of them per game already; a
 * size measured there would be paid for every game on every refresh, to answer a question one screen
 * asks about one game. So the screen that wants it measures it, on a worker, once.
 *
 * **Neither walk is cheap and the granted one is not the same kind of expensive.** A staged directory
 * is a filesystem walk; a granted one is a content provider query per directory, each a binder round
 * trip returning every child at once. The second is why this may not be called from the main thread.
 */
object GameSize {

    private const val TAG = "sharpemu"

    /** Every byte under [directory], following it down. */
    fun of(directory: File): Long {
        if (directory.isFile) return directory.length()
        // null for a path that is not a directory and for one that cannot be read; both are nothing
        // to add, which is what an unreadable game should measure as rather than a wrong number.
        return directory.listFiles()?.sumOf { of(it) } ?: 0L
    }

    /**
     * Every byte under the document at [documentId], following it down.
     *
     * **One query per directory rather than one per file**, which is the same reason
     * [TreeDocument] appends ids instead of resolving them a component at a time: a query returns
     * every child with its size and its kind, so a dump of hundreds of files costs a handful of
     * round trips rather than hundreds.
     *
     * A directory's own size column means nothing, so it is a kind to recurse into rather than a
     * number to add.
     */
    fun of(resolver: ContentResolver, tree: Uri, documentId: String): Long {
        var total = 0L
        val children = TreeDocument.childrenUri(tree, documentId)
        try {
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val mime = cursor.getString(1)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        total += of(resolver, tree, id)
                    } else {
                        total += if (cursor.isNull(2)) 0L else cursor.getLong(2)
                    }
                }
            }
        } catch (e: Exception) {
            // a grant that has gone, or a provider that refuses a listing. the screen shows no size
            // rather than a partial one presented as a total.
            Log.w(TAG, "[app] could not measure " + documentId + ": " + e)
            return 0L
        }
        return total
    }
}
