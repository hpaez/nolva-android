package app.nolva.data

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * [PageImporter] for `content://` URIs from the Photo Picker or the system
 * file picker.
 *
 * The core imports from a file path, so the image is first streamed into a
 * private temp file (with the extension the core uses to detect the format),
 * handed to [DocumentRepository.addPage] — which copies it into managed
 * storage — and the temp file is always deleted afterwards.
 */
class ContentPageImporter(
    private val contentResolver: ContentResolver,
    private val cacheDir: File,
    private val repository: DocumentRepository,
    private val ioDispatcher: CoroutineDispatcher,
) : PageImporter {

    override suspend fun import(documentId: String, uri: String): Page {
        val temp = withContext(ioDispatcher) { copyToTemp(Uri.parse(uri)) }
        return try {
            repository.addPage(documentId, temp)
        } finally {
            withContext(ioDispatcher) { temp.delete() }
        }
    }

    private fun copyToTemp(uri: Uri): File {
        val mimeType = contentResolver.getType(uri)
        val extension = extensionFor(mimeType)
            ?: throw DocumentException.Unsupported("unsupported image type: $mimeType")

        val importDir = File(cacheDir, "imports").apply { mkdirs() }
        val temp = File.createTempFile("page-", ".$extension", importDir)
        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw DocumentException.NotFound("cannot open $uri")
            input.use { source -> temp.outputStream().use { source.copyTo(it) } }
            return temp
        } catch (e: IOException) {
            temp.delete()
            throw DocumentException.Storage("failed to read image", e)
        } catch (e: DocumentException) {
            temp.delete()
            throw e
        }
    }

    internal companion object {
        /** Maps a MIME type to the extension the core uses to detect the format. */
        fun extensionFor(mimeType: String?): String? = when (mimeType?.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/heic", "image/heif" -> "heic"
            "image/webp" -> "webp"
            else -> null
        }
    }
}
