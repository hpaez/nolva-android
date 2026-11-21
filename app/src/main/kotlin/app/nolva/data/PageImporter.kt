package app.nolva.data

/** Adds pages to a document from external sources (gallery, files...). */
fun interface PageImporter {
    /**
     * Imports the image at [uri] (a `content://` URI as a string) as the last
     * page of the document. The source is left untouched.
     *
     * @throws DocumentException.Unsupported if the image format isn't supported.
     */
    suspend fun import(documentId: String, uri: String): Page
}
