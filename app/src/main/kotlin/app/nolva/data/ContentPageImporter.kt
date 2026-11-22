package app.nolva.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
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
 *
 * HEIC/HEIF photos (the default on many phones) are converted to JPEG here,
 * because the core can't decode them. Android decodes HEIF from API 28.
 */
class ContentPageImporter(
    private val contentResolver: ContentResolver,
    private val cacheDir: File,
    private val repository: DocumentRepository,
    private val ioDispatcher: CoroutineDispatcher,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
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
        val plan = importPlanFor(mimeType, sdkInt)
            ?: throw DocumentException.Unsupported("unsupported image type: $mimeType")

        val importDir = File(cacheDir, "imports").apply { mkdirs() }
        val temp = File.createTempFile("page-", ".${plan.extension}", importDir)
        try {
            when (plan) {
                is ImportPlan.Copy -> {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw DocumentException.NotFound("cannot open $uri")
                    input.use { source -> temp.outputStream().use { source.copyTo(it) } }
                }
                ImportPlan.ConvertToJpeg ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        convertToJpeg(uri, temp)
                    } else {
                        throw DocumentException.Unsupported("HEIF needs Android 9: $mimeType")
                    }
            }
            return temp
        } catch (e: IOException) {
            temp.delete()
            throw DocumentException.Storage("failed to read image", e)
        } catch (e: DocumentException) {
            temp.delete()
            throw e
        } catch (e: OutOfMemoryError) {
            temp.delete()
            throw DocumentException.Storage("image too large to convert", e)
        }
    }

    /**
     * Decodes [uri] and writes it to [target] as JPEG. ImageDecoder applies the
     * EXIF orientation, so the JPEG is already upright and carries no EXIF.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun convertToJpeg(uri: Uri, target: File) {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
                // Software bitmaps can be compressed; hardware ones can't.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: ImageDecoder.DecodeException) {
            throw DocumentException.Unsupported("cannot decode image", e)
        }
        try {
            val written = target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (!written) throw IOException("JPEG encoding failed")
        } finally {
            bitmap.recycle()
        }
    }

    /** How an imported image reaches the core. */
    internal sealed interface ImportPlan {
        /** Extension of the temp file handed to the core. */
        val extension: String

        /** The core reads this format: copy the bytes unchanged. */
        data class Copy(override val extension: String) : ImportPlan

        /** The core can't read this format: decode it here and store a JPEG. */
        data object ConvertToJpeg : ImportPlan {
            override val extension = "jpg"
        }
    }

    internal companion object {
        /** High enough that text stays crisp; HEIC photos are already lossy. */
        const val JPEG_QUALITY = 92

        /** @return how to import [mimeType] on API [sdkInt], or `null` if it can't be. */
        fun importPlanFor(mimeType: String?, sdkInt: Int): ImportPlan? = when (val ext = extensionFor(mimeType)) {
            null -> null
            "heic" -> if (sdkInt >= Build.VERSION_CODES.P) ImportPlan.ConvertToJpeg else null
            else -> ImportPlan.Copy(ext)
        }

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
