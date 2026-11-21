package app.nolva.ui.capture

import app.nolva.data.CornerDetection
import app.nolva.data.Corners
import app.nolva.data.NormalizedPoint
import kotlin.math.abs
import kotlin.math.max

/** The sheet's outline to draw over the camera preview. */
data class LiveGuide(
    /** Normalized to the upright camera frame. */
    val corners: Corners,
    /** Width / height of the upright frame the corners refer to. */
    val frameAspect: Float,
    /** `0..1`: how far along the sheet is to being held still long enough to capture. */
    val steadiness: Float,
)

/** Below this the outline is too unreliable to show. */
internal const val GUIDE_MIN_CONFIDENCE = 0.6f

/** Auto-capture needs a clean four-sided outline, not a rough guess. */
internal const val AUTO_CAPTURE_MIN_CONFIDENCE = 0.8f

/** Corners moving less than this (fraction of the frame) count as still. */
internal const val STEADY_TOLERANCE = 0.02f

/** How long the sheet must stay still before an automatic capture. */
internal const val STEADY_DURATION_MS = 1_000L

/** Keeps the guide through brief detection dropouts, so it doesn't flicker. */
internal const val GUIDE_HOLD_MS = 400L

/** After a capture, the sheet must move this much (or leave) to arm the next one. */
internal const val REARM_MOVEMENT = 0.15f

/**
 * Decides, frame by frame, what guide to show and when to capture
 * automatically. Pure logic with an injected clock, so it is testable
 * without a camera. Not thread-safe: feed it from one thread.
 */
class AutoCaptureTracker {

    data class Result(val guide: LiveGuide?, val capture: Boolean)

    /** Where the current still period started. */
    private var anchor: Corners? = null
    private var steadySince = 0L
    private var lastSeenAt: Long? = null
    private var lastGuide: LiveGuide? = null

    /** Outline of the last automatic capture; no new one until the sheet changes. */
    private var capturedOutline: Corners? = null

    fun onFrame(detection: CornerDetection?, frameAspect: Float, nowMs: Long, autoCapture: Boolean): Result {
        if (detection == null || detection.confidence < GUIDE_MIN_CONFIDENCE) {
            anchor = null
            val sinceSeen = lastSeenAt?.let { nowMs - it }
            if (sinceSeen == null || sinceSeen > GUIDE_HOLD_MS) {
                // The sheet left the frame: the next one may be captured.
                capturedOutline = null
                lastGuide = null
            }
            return Result(lastGuide?.copy(steadiness = 0f), capture = false)
        }

        lastSeenAt = nowMs
        val corners = detection.corners
        capturedOutline?.let { if (distance(corners, it) > REARM_MOVEMENT) capturedOutline = null }

        val still = anchor?.let { distance(corners, it) <= STEADY_TOLERANCE } ?: false
        if (!still) {
            anchor = corners
            steadySince = nowMs
        }

        val eligible = autoCapture &&
            detection.confidence >= AUTO_CAPTURE_MIN_CONFIDENCE &&
            capturedOutline == null
        val heldFor = nowMs - steadySince
        val capture = eligible && heldFor >= STEADY_DURATION_MS
        val steadiness = if (eligible) (heldFor.toFloat() / STEADY_DURATION_MS).coerceIn(0f, 1f) else 0f
        if (capture) {
            capturedOutline = corners
            anchor = null
        }

        val guide = LiveGuide(corners, frameAspect, steadiness)
        lastGuide = guide
        return Result(guide, capture)
    }

    private fun distance(a: Corners, b: Corners): Float =
        listOf(
            a.topLeft to b.topLeft,
            a.topRight to b.topRight,
            a.bottomRight to b.bottomRight,
            a.bottomLeft to b.bottomLeft,
        ).maxOf { (p, q) -> p.chebyshev(q) }

    private fun NormalizedPoint.chebyshev(other: NormalizedPoint) = max(abs(x - other.x), abs(y - other.y))
}
