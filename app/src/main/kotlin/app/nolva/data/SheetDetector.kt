package app.nolva.data

/**
 * A camera frame's luminance (Y) plane, as camera APIs deliver it: rows may
 * be padded to [rowStride] bytes, and [rotationDegrees] (clockwise) makes it
 * upright.
 */
class LumaFrame(
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val luma: ByteArray,
    val rotationDegrees: Int,
) {
    /** Width / height of the frame once rotated upright, as the preview shows it. */
    val uprightAspect: Float
        get() = if (rotationDegrees % 180 == 0) width.toFloat() / height else height.toFloat() / width
}

/**
 * Finds the sheet in live camera frames, for the capture guide. Blocking and
 * CPU-bound (a few milliseconds): call it from the camera's analysis
 * thread, never the main thread.
 */
fun interface SheetDetector {
    /** Corners normalized to the upright frame, or `null` if no sheet is visible. */
    fun detect(frame: LumaFrame): CornerDetection?
}
