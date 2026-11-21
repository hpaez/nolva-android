package app.nolva.data

import app.nolva.core.CameraFrame
import app.nolva.core.NolvaException
import app.nolva.core.detectCornersInFrame

/** [SheetDetector] backed by the Rust core's live frame detection. */
class CoreSheetDetector : SheetDetector {
    override fun detect(frame: LumaFrame): CornerDetection? = try {
        detectCornersInFrame(
            CameraFrame(
                width = frame.width.toUInt(),
                height = frame.height.toUInt(),
                rowStride = frame.rowStride.toUInt(),
                luma = frame.luma,
                rotationDegrees = frame.rotationDegrees.toUInt(),
            ),
        )?.let { CornerDetection(it.corners.toCorners(), it.confidence) }
    } catch (e: NolvaException) {
        // A malformed frame only means no guide for that frame.
        null
    }
}
