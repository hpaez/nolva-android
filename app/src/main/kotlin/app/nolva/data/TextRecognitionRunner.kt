package app.nolva.data

import kotlinx.coroutines.CancellationException

/**
 * Recognizes the text of every page that needs it: new pages, and pages
 * whose image changed (re-cropped or filtered) since their last recognition.
 */
class TextRecognitionRunner(
    private val store: PageTextStore,
    private val recognizer: TextRecognizer,
    private val batchSize: Int = 5,
) {
    /**
     * Works until no page is pending or [shouldStop] returns true. Each page
     * is tried at most once per run, so a page that keeps failing can't keep
     * the run going forever.
     *
     * @return how many pages got their text.
     * @throws Exception if recognition itself fails (e.g. ML Kit), so the
     *   caller can retry later.
     */
    suspend fun run(shouldStop: () -> Boolean = { false }): Int {
        val attempted = mutableSetOf<String>()
        var recognized = 0
        while (!shouldStop()) {
            val batch = store.pagesNeedingText(attempted.size + batchSize).filter { it.id !in attempted }
            if (batch.isEmpty()) break
            for (page in batch) {
                if (shouldStop()) break
                attempted += page.id
                val image = page.displayFile
                val lines = try {
                    recognizer.recognize(image)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DocumentException.Unsupported) {
                    // Unreadable image: store "no text" so it isn't retried
                    // until the page gets a new image.
                    emptyList()
                }
                try {
                    store.savePageText(page.id, image, lines)
                    recognized++
                } catch (e: DocumentException.Conflict) {
                    // Re-cropped meanwhile: it's pending again, with its new image.
                } catch (e: DocumentException.NotFound) {
                    // Deleted meanwhile.
                }
            }
        }
        return recognized
    }
}
