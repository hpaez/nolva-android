package app.nolva.data

/**
 * Changes to existing documents and pages. Kept separate from
 * [DocumentRepository] so screens that only read or add pages don't depend
 * on destructive operations. All functions are main-safe.
 *
 * Deleting a page is permanent. Documents go to the trash instead (see
 * [DocumentTrash]); [deleteDocument] removes one for good.
 */
interface DocumentEditor {
    /**
     * @return the renamed document, with its updated timestamp.
     * @throws DocumentException.InvalidInput if the title is blank or too long.
     * @throws DocumentException.NotFound if the document no longer exists.
     */
    suspend fun renameDocument(documentId: String, title: String): Document

    /**
     * Deletes a document for good, with its pages' images.
     *
     * @throws DocumentException.NotFound if the document no longer exists.
     */
    suspend fun deleteDocument(documentId: String)

    /**
     * Copies a document, with its pages, crops, filters and recognized
     * text, as a new document named [title].
     *
     * @throws DocumentException.InvalidInput if the title is blank or too long.
     * @throws DocumentException.NotFound if the document no longer exists.
     */
    suspend fun duplicateDocument(documentId: String, title: String): Document

    /**
     * Deletes a page; the pages after it move up one position.
     *
     * @throws DocumentException.NotFound if the page no longer exists.
     */
    suspend fun deletePage(pageId: String)

    /**
     * Moves a page to [toPosition] (zero-based; past the end means last).
     *
     * @return the document's pages in their new order.
     */
    suspend fun movePage(pageId: String, toPosition: Int): List<Page>
}
