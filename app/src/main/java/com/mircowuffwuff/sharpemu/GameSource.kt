package com.mircowuffwuff.sharpemu

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.InputStream

/**
 * Where a game's files are, and the two ways that can be answered.
 *
 * **[Game] was built on `java.io.File` and a game inside a granted tree has none.** A directory the
 * user granted is not a path — it is a tree, and everything under it is a document reached through a
 * content provider — so the identity a row shows has to come from `DocumentsContract` instead of
 * from the filesystem. This is that difference, and it is the only place in the app that knows about
 * it: one scan, one adapter, one row.
 *
 * It is deliberately narrow. A source answers **what the directory is called**, **what to hand coil
 * for the artwork** and **how to open `param.json`**, and nothing else — the guest never reads a file
 * through here. A granted game's files reach the guest through [GuestFiles], on the other side of the
 * JNI boundary, and `docs/guest-files.md` describes that path.
 *
 * @see GameLibrary for where both kinds are enumerated.
 */
sealed class GameSource {

    /** The directory's own name, e.g. `Dreaming Sarah [PPSA02929]`. What the launch intent carries. */
    abstract val folder: String

    /**
     * What coil is handed for `sce_sys/icon0.png` — a [File], a `content://` [Uri], or null.
     *
     * Coil loads either kind natively, so a granted dump's artwork needs no decoding of ours and no
     * copy. It is resolved when the game is scanned rather than when a row binds, because a staged
     * source stats the file to answer and a bind happens on the main thread.
     */
    abstract val icon: Any?

    /** `sce_sys/param.json`, or null when there is none. The caller closes it. */
    abstract fun openParam(): InputStream?

    /** A game staged into the app's own external files by `scripts/stage-game.ps1`. */
    class Staged(val directory: File) : GameSource() {

        override val folder: String get() = directory.name

        override val icon: Any? = File(directory, Game.ICON).takeIf { it.isFile }

        override fun openParam(): InputStream? =
            File(directory, Game.PARAM).takeIf { it.isFile }?.inputStream()
    }

    /**
     * A game inside a tree the user granted, addressed as a document.
     *
     * [tree] travels with it because the app can hold more than one grant, and the launch intent has
     * to name which one. A launch that names none takes whichever persisted permission comes first,
     * which is exact with one granted library and a coin toss with two.
     *
     * The resolver is the application's, so a source outliving the screen that produced it is not a
     * leaked activity.
     */
    class Granted(
        val tree: Uri,
        /** The game directory's document id, from the cursor that listed it — never guessed. */
        val documentId: String,
        override val folder: String,
        private val resolver: ContentResolver,
    ) : GameSource() {

        // no query, deliberately: an absent icon resolves to the same placeholder a failed decode
        // does, so checking first would be a provider round trip per game to learn nothing the
        // drawing does not already handle.
        override val icon: Any = TreeDocument.uri(tree, TreeDocument.childId(documentId, Game.ICON))

        override fun openParam(): InputStream? =
            try {
                resolver.openInputStream(
                    TreeDocument.uri(tree, TreeDocument.childId(documentId, Game.PARAM))
                )
            } catch (e: Exception) {
                // absent is ordinary — a dump with no sce_sys/ is a game that boots perfectly well —
                // and the provider answers that by throwing. Game.read logs what it could not read.
                null
            }
    }
}
