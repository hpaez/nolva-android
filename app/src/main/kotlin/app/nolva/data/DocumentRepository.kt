package app.nolva.data

import java.io.File

/** Access to the user's documents. All functions are main-safe. */
interface DocumentRepository {
    /** All documents, most recently updated first. */
    suspend fun listDocuments(): List<Document>

    /** @throws DocumentException.InvalidInput if the title is blank or too long. */
    suspend fun createDocument(title: String): Document

    /**
     * Copies [image] into Nolva's storage as the last page of the document.
     * The source file is left untouched.
     */
    suspend fun addPage(documentId: String, image: File): Page

    /** Pages of a document, ordered by position. */
    suspend fun listPages(documentId: String): List<Page>
}
