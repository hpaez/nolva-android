package app.nolva.data

import java.time.Duration

/**
 * The trash: deleted documents stay here, out of the list, search and text
 * recognition, until restored, deleted for good, or [RETENTION] passes.
 * All functions are main-safe.
 */
interface DocumentTrash {
    /** @throws DocumentException.NotFound if the document no longer exists. */
    suspend fun moveToTrash(documentId: String)

    /**
     * Takes a document out of the trash, as it was.
     *
     * @throws DocumentException.NotFound if it was deleted for good meanwhile.
     */
    suspend fun restore(documentId: String): Document

    /** Trashed documents, most recently trashed first. */
    suspend fun listTrash(): List<Document>

    /** Deletes every trashed document for good. @return how many. */
    suspend fun emptyTrash(): Int

    /** Deletes documents trashed more than [RETENTION] ago. @return how many. */
    suspend fun purgeExpired(): Int

    companion object {
        /** How long documents stay in the trash; mirrors the core. */
        val RETENTION: Duration = Duration.ofDays(30)
    }
}
