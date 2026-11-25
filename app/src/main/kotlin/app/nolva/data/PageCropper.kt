package app.nolva.data

/**
 * Detection, non-destructive cropping, rotation and filters of pages. Kept separate from
 * [DocumentRepository] so screens that only list documents don't depend on
 * image processing. All functions are main-safe but CPU-heavy (up to ~1 s
 * for a 12 MP photo).
 */
interface PageCropper {
    suspend fun getPage(pageId: String): Page

    /** The sheet found in the page's original photo, or `null` if none. */
    suspend fun detectCorners(pageId: String): CornerDetection?

    /**
     * Straightens the page to [corners] and stores the result as its
     * processed image. The original is kept, so this can be repeated.
     *
     * @throws DocumentException.InvalidInput if the corners don't form a
     *   valid outline (e.g. crossed).
     */
    suspend fun cropPage(pageId: String, corners: Corners): Page

    /**
     * Renders the page with [filter], on top of its crop if it has one.
     * Filters start from the original, so they can be switched freely.
     */
    suspend fun applyFilter(pageId: String, filter: PageFilter): Page

    /** Turns the page a quarter, keeping its crop and filter. */
    suspend fun rotatePage(pageId: String, clockwise: Boolean): Page

    /**
     * Sets crop, filter and rotation at once, e.g. to undo or redo an edit.
     * Nothing is rendered if the page already looks like that, so it is
     * safe to repeat.
     */
    suspend fun setRendering(pageId: String, rendering: PageRendering): Page
}
