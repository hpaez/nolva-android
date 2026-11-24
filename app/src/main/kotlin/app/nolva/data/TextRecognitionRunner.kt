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
                if (recognize(page)) recognized++
            }
        }
        return recognized
    }

    /**
     * Recognizes the pending pages of one document right away, e.g. before
     * exporting it, without waiting for the background job.
     *
     * @return how many pages got their text.
     */
    suspend fun runForDocument(documentId: String): Int {
        val pending = store.pagesNeedingText(DOCUMENT_SCAN_LIMIT).filter { it.documentId == documentId }
        return pending.count { recognize(it) }
    }

    /** @return whether the page's text was stored. */
    private suspend fun recognize(page: Page): Boolean {
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
        return try {
            store.savePageText(page.id, image, lines)
            true
        } catch (e: DocumentException.Conflict) {
            false // Re-cropped meanwhile: it's pending again, with its new image.
        } catch (e: DocumentException.NotFound) {
            false // Deleted meanwhile.
        }
    }

    private companion object {
        /** Pending pages looked at to find one document's; far above real backlogs. */
        const val DOCUMENT_SCAN_LIMIT = 10_000
    }
}
