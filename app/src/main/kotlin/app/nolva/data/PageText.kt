package app.nolva.data

import java.io.File

/** Rectangle in normalized coordinates (`0..1`) of a page image, origin at the top left. */
data class TextRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** One line of recognized text. */
data class RecognizedLine(
    /** Block (paragraph or column) in reading order; a block's lines are consecutive. */
    val block: Int,
    val text: String,
    val bounds: TextRect,
)

/** Text recognized in a page's current image. */
data class PageText(
    val pageId: String,
    /** Lines separated by newlines, blocks by a blank line. */
    val text: String,
    val lines: List<RecognizedLine>,
)

/** A page whose text matches a search. */
data class TextMatch(
    val documentId: String,
    val pageId: String,
    /** Zero-based position of the page in its document. */
    val pagePosition: Int,
    /** Short excerpt around the match. */
    val snippet: String,
)

/** Stores recognized text, kept by the core in step with each page's image. Main-safe. */
interface PageTextStore {
    /** Up to [limit] pages whose text has to be (re)recognized, newest first. */
    suspend fun pagesNeedingText(limit: Int): List<Page>

    /**
     * Stores the text recognized in [image], the page's [Page.displayFile].
     *
     * @throws DocumentException.Conflict if the page shows another image by
     *   now; the page stays pending.
     */
    suspend fun savePageText(pageId: String, image: File, lines: List<RecognizedLine>): PageText

    /** The page's text, or `null` while recognition is pending. */
    suspend fun getPageText(pageId: String): PageText?

    /**
     * Pages whose text contains every word of [query] (prefixes match; case
     * and accents are ignored), best match first.
     */
    suspend fun searchText(query: String): List<TextMatch>
}

/** Recognizes text in an image file, on the device. */
fun interface TextRecognizer {
    /**
     * @return the lines in reading order, with bounds relative to the image
     *   as displayed (EXIF orientation applied).
     * @throws DocumentException.Unsupported if the image can't be decoded.
     */
    suspend fun recognize(image: File): List<RecognizedLine>
}
