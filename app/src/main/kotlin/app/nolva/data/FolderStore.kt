package app.nolva.data

/** Folders and which documents are in them. All functions are main-safe. */
interface FolderStore {
    /** All folders, by name. */
    suspend fun listFolders(): List<Folder>

    /** @throws DocumentException.InvalidInput if the name is blank or too long. */
    suspend fun createFolder(name: String): Folder

    /**
     * @throws DocumentException.InvalidInput if the name is blank or too long.
     * @throws DocumentException.NotFound if the folder no longer exists.
     */
    suspend fun renameFolder(folderId: String, name: String): Folder

    /** Deletes a folder; its documents are kept and move to the top level. */
    suspend fun deleteFolder(folderId: String)

    /**
     * Moves documents into [folderId] (`null`: the top level).
     *
     * @return how many moved; documents that no longer exist are skipped.
     * @throws DocumentException.NotFound if the folder no longer exists.
     */
    suspend fun moveDocuments(documentIds: List<String>, folderId: String?): Int

    /** Creates an empty document in [folderId] (`null`: the top level). */
    suspend fun createDocumentIn(title: String, folderId: String?): Document
}
