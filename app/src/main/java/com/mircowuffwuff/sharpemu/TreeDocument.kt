package com.mircowuffwuff.sharpemu

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * how a document inside a granted tree is addressed.
 *
 * **a child's document id is its parent's id plus `/name`, and that concatenation is the whole
 * rule.** resolving a path by querying for each component in turn -- which is what a `DocumentFile`
 * does and what looks obviously correct -- costs a fifth of a second per path on a dump with 816
 * files in it, because every level lists all of its children. appending is one query at any size.
 * `docs/guest-files.md` has the measurement and the assumption it rests on: the id scheme the
 * platform's own external-storage provider uses, which holds for internal storage and for an SD card
 * alike.
 *
 * it lives here rather than in either caller because both sides of the app need it and they are not
 * the same contract. [GuestFiles] answers syscalls for one mounted game directory, on guest threads,
 * per file the guest opens; [GameLibrary] enumerates a library on a worker, once per refresh. two
 * copies of these three lines would be two places to notice the day a provider disagrees with the
 * assumption above.
 */
object TreeDocument {

    /** `<parentId>/<relative>`, or the parent itself when [relative] is empty. */
    fun childId(parentId: String, relative: String): String =
        if (relative.isEmpty()) parentId else "$parentId/$relative"

    /** the document at [id], addressed through the tree that was granted. */
    fun uri(tree: Uri, id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    /** what to query to list the children of the directory at [id]. */
    fun childrenUri(tree: Uri, id: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)

    /** the tree's own document id -- the directory the user picked. */
    fun rootId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    /**
     * whether a granted tree comes from the platform's own storage provider, which is the only one
     * whose document ids [path] can read.
     *
     * **it is the guard on deriving a path for somebody to look at.** a cloud provider or a file
     * manager's own provider issues ids of whatever shape it likes, and plenty of them contain a
     * colon -- so [path] would answer confidently and wrongly rather than not at all. a caller that
     * goes on to *open* what it derived catches that by itself, since the file will not be there;
     * a caller that only prints it has nothing to catch it with.
     */
    fun isOnAVolume(tree: Uri): Boolean = tree.authority == EXTERNAL_STORAGE

    /** the platform's storage provider: internal storage and any SD card, and nothing else. */
    const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /**
     * the ordinary filesystem path a document id names, or null when the id is not shaped like one.
     *
     * **this is not how the app reads a granted document and it never becomes one.** a path derived
     * here is unreadable without `MANAGE_EXTERNAL_STORAGE`, which is an opt-in -- [AllFiles] is the
     * only caller and it asks only while the permission is held. everything else addresses a document
     * through the grant, which needs no permission at all and is how a game is reached by default.
     *
     * the rule is the other half of the id scheme above: an id is `<volume>:<relative path>`, and a
     * volume is either `primary`, which is the device's own external storage, or an SD card's id,
     * which is mounted at `/storage/<id>`. **derived rather than looked up, so the caller checks that
     * what came back is there** -- a mapping that is wrong presents as a game whose every file is
     * missing, which reads as a corrupt dump rather than as a bad path.
     */
    fun path(documentId: String): File? {
        val colon = documentId.indexOf(':')
        if (colon < 1) return null
        val volume = documentId.substring(0, colon)
        val relative = documentId.substring(colon + 1)
        val root =
            if (volume == "primary") Environment.getExternalStorageDirectory() else File("/storage", volume)
        return if (relative.isEmpty()) root else File(root, relative)
    }
}
