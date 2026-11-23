package app.nolva.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * [TextRecognizer] backed by ML Kit's Latin-script model, bundled in the app
 * (covers Spanish and English).
 *
 * The image is decoded and turned upright here instead of letting ML Kit
 * read the file, so the bounds are relative to exactly the pixels the core
 * and the viewer use.
 */
class MlKitTextRecognizer(private val ioDispatcher: CoroutineDispatcher) : TextRecognizer {

    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(image: File): List<RecognizedLine> {
        val bitmap = withContext(ioDispatcher) { decodeUpright(image) }
            ?: throw DocumentException.Unsupported("cannot decode image")
        try {
            val result = client.process(InputImage.fromBitmap(bitmap, 0)).await()
            val width = bitmap.width.toFloat()
            val height = bitmap.height.toFloat()
            return result.textBlocks.flatMapIndexed { blockIndex, block ->
                block.lines.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    RecognizedLine(
                        block = blockIndex,
                        text = line.text,
                        bounds = TextRect(box.left / width, box.top / height, box.right / width, box.bottom / height),
                    )
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    internal companion object {
        /**
         * Longest side ML Kit gets. A 12 MP photo fits; bigger images are
         * downsampled, which keeps memory bounded and loses no legible text.
         */
        const val MAX_SIDE = 4096

        /** Power-of-two decoder subsampling so the longest side is at most [maxSide]. */
        fun sampleSizeFor(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
            var sample = 1
            while (maxOf(width, height) / sample > maxSide) sample *= 2
            return sample
        }

        /** Decodes [file] (downsampled if huge) with its EXIF orientation applied. */
        fun decodeUpright(file: File): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }
            val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
            val orientation = try {
                ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (e: IOException) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val matrix = orientationMatrix(orientation) ?: return decoded
            val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (upright !== decoded) decoded.recycle()
            return upright
        }

        /** The transform that undoes an EXIF orientation, or `null` if none is needed. */
        private fun orientationMatrix(orientation: Int): Matrix? = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f) }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { setScale(1f, -1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-90f) }
            else -> null
        }
    }
}

/** Suspends until the ML Kit task finishes. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
