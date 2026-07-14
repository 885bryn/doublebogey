package com.doublebogey.golftracer.camera

import java.util.Locale
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt

data class ZoneBallDetectorConfig(
    val assumedZoneWidthMm: Double = 1500.0,
    val ballDiameterMm: Double = 42.7,
    val proposer: BallBlobProposerConfig = BallBlobProposerConfig(),
    val verifier: BallShapeVerifierConfig = BallShapeVerifierConfig(),
    val tracker: BallCandidateTrackerConfig = BallCandidateTrackerConfig(),
) {
    init {
        require(assumedZoneWidthMm > 0.0)
        require(ballDiameterMm > 0.0)
    }
}
data class ZoneBallCandidateDebug(
    val candidate: LumaMotionCandidate,
    val radiusPx: Double,
    val proposalResponse: Double,
    val rankScore: Double,
    val metrics: BallShapeMetrics,
    val rejection: BallShapeRejection?,
)
data class ZoneBallDebug(
    val candidates: List<ZoneBallCandidateDebug> = emptyList(),
    val topRejected: ZoneBallCandidateDebug? = null,
    val proposalCount: Int = 0,
    val rejectedCount: Int = 0,
    val expectedRadiusPx: Double = 0.0,
    val confirmationHits: Int = 0,
    val confirmationWindow: Int = 7,
    val margin: Double = 0.0,
    val ambiguous: Boolean = false,
) {
    val best: ZoneBallCandidateDebug? get() = candidates.firstOrNull()

    @Suppress("UNUSED_PARAMETER")
    fun statusSummary(aeAwbLocked: Boolean = false): String {
        val best = best
        return "shot=Searching r_e=${expectedRadiusPx.f1()} prop=$proposalCount verified=${candidates.size} " +
            "score=${best?.rankScore.f1()} closed=${best?.metrics?.closedEdgeCoverage.f2()} " +
            "radial=${best?.metrics?.radialAlignment.f2()} line=${best?.metrics?.lineContinuation.f2()} " +
            "rv=${best?.metrics?.radiusVariation.f2()} r=${best?.radiusPx.f1()} " +
            "confirm=$confirmationHits/$confirmationWindow margin=${margin.f2()} ambiguous=${if (ambiguous) 1 else 0}"
    }

    private fun Double?.f1() = String.format(Locale.US, "%.1f", this ?: 0.0)
    private fun Double?.f2() = String.format(Locale.US, "%.2f", this ?: 0.0)
}
data class ZoneBallDetection(val acceptedCandidate: LumaMotionCandidate?, val debug: ZoneBallDebug)

class ZoneBallDetector(private val config: ZoneBallDetectorConfig = ZoneBallDetectorConfig()) {
    private var activeContext: DetectionContext? = null
    private val scaleEstimator = BallScaleEstimator(config.assumedZoneWidthMm, config.ballDiameterMm)
    private val proposer = BallBlobProposer(
        config.proposer.copy(maxProposals = min(8, config.proposer.maxProposals)),
    )
    private val verifier = BallShapeVerifier(config.verifier)
    private val tracker = BallCandidateTracker(config.tracker)

    fun analyzeFrame(
        frame: YuvFrame,
        launchZone: LaunchZone,
        mapper: FrameCoordinateMapper = FrameCoordinateMapper.Identity,
    ): ZoneBallDetection {
        require(launchZone.isValid()) { "launchZone must be valid" }
        val frameZone = mapper.viewZoneToFrameZone(launchZone)
        val bounds = frameZone.bounds(frame)
        val context = DetectionContext(frame.width, frame.height, bounds, frameZone, mapper)
        if (activeContext != context) {
            activeContext = context
            tracker.reset()
        }
        val scale = scaleEstimator.estimate(bounds.width)
        val padding = kotlin.math.ceil(scale.radiiPx.max() * 2.2).toInt()
        val analysisBounds = bounds.expand(frame, padding)
        val zoneFrame = frame.crop(analysisBounds)
        val centerBounds = frameZone.centerBounds(frame)
        val allowedCenterRegion = centerBounds?.relativeTo(analysisBounds)
        val proposals = if (allowedCenterRegion == null) {
            emptyList()
        } else {
            proposer.propose(zoneFrame, scale.radiiPx, allowedCenterRegion).filter { proposal ->
                frameZone.contains(
                    x = (analysisBounds.left + proposal.centerX) / (frame.width - 1).coerceAtLeast(1),
                    y = (analysisBounds.top + proposal.centerY) / (frame.height - 1).coerceAtLeast(1),
                )
            }
        }
        val evaluations = proposals.map { verifier.verify(zoneFrame, it) }
        val accepted = evaluations.filter { it.accepted }
        val tracking = tracker.update(accepted)
        val acceptedDebug = accepted
            .sortedByDescending { it.score }
            .map { it.toDebug(frame, analysisBounds, mapper) }
        val topRejected = evaluations
            .filterNot { it.accepted }
            .maxByOrNull { it.score }
            ?.toDebug(frame, analysisBounds, mapper)

        return ZoneBallDetection(
            acceptedCandidate = tracking.confirmed?.toCandidate(frame, analysisBounds, mapper),
            debug = ZoneBallDebug(
                candidates = acceptedDebug,
                topRejected = topRejected,
                proposalCount = proposals.size,
                rejectedCount = evaluations.count { !it.accepted },
                expectedRadiusPx = scale.expectedRadiusPx,
                confirmationHits = tracking.hits,
                confirmationWindow = config.tracker.windowSize,
                margin = tracking.margin,
                ambiguous = tracking.ambiguous,
            ),
        )
    }

    fun reset() {
        activeContext = null
        tracker.reset()
    }

    private fun BallShapeEvaluation.toDebug(frame: YuvFrame, bounds: PixelBounds, mapper: FrameCoordinateMapper) =
        ZoneBallCandidateDebug(
            candidate = toCandidate(frame, bounds, mapper),
            radiusPx = proposal.radiusPx,
            proposalResponse = proposal.response,
            rankScore = score,
            metrics = metrics,
            rejection = rejection,
        )

    private fun BallShapeEvaluation.toCandidate(
        frame: YuvFrame,
        bounds: PixelBounds,
        mapper: FrameCoordinateMapper,
    ): LumaMotionCandidate {
        val frameX = (bounds.left + proposal.centerX) / (frame.width - 1).coerceAtLeast(1).toDouble()
        val frameY = (bounds.top + proposal.centerY) / (frame.height - 1).coerceAtLeast(1).toDouble()
        val view = mapper.frameToView(frameX, frameY)
        return LumaMotionCandidate(
            x = view.x,
            y = view.y,
            pixelCount = (PI * proposal.radiusPx * proposal.radiusPx).roundToInt(),
            confidence = (score / 12.0).coerceIn(0.0, 1.0),
        )
    }

    private fun LaunchZone.bounds(frame: YuvFrame): PixelBounds {
        val maxX = frame.width - 1
        val maxY = frame.height - 1
        val leftPx = (left * maxX).toInt().coerceIn(0, maxX)
        val rightPx = ((left + width) * maxX).toInt().coerceIn(leftPx, maxX)
        val topPx = (top * maxY).toInt().coerceIn(0, maxY)
        val bottomPx = ((top + height) * maxY).toInt().coerceIn(topPx, maxY)
        return PixelBounds(leftPx, rightPx, topPx, bottomPx)
    }

    private fun LaunchZone.centerBounds(frame: YuvFrame): PixelBounds? {
        val maxX = frame.width - 1
        val maxY = frame.height - 1
        val leftPx = kotlin.math.ceil(left * maxX).toInt().coerceIn(0, maxX)
        val rightPx = kotlin.math.floor((left + width) * maxX).toInt().coerceIn(0, maxX)
        val topPx = kotlin.math.ceil(top * maxY).toInt().coerceIn(0, maxY)
        val bottomPx = kotlin.math.floor((top + height) * maxY).toInt().coerceIn(0, maxY)
        return if (leftPx <= rightPx && topPx <= bottomPx) {
            PixelBounds(leftPx, rightPx, topPx, bottomPx)
        } else {
            null
        }
    }

    private fun LaunchZone.contains(x: Double, y: Double): Boolean =
        x >= left && x <= left + width && y >= top && y <= top + height

    private fun PixelBounds.relativeTo(container: PixelBounds) = BallBlobProposalRegion(
        left = left - container.left,
        top = top - container.top,
        right = right - container.left,
        bottom = bottom - container.top,
    )

    private fun PixelBounds.expand(frame: YuvFrame, padding: Int) = PixelBounds(
        left = (left - padding).coerceAtLeast(0),
        right = (right + padding).coerceAtMost(frame.width - 1),
        top = (top - padding).coerceAtLeast(0),
        bottom = (bottom + padding).coerceAtMost(frame.height - 1),
    )

    private fun YuvFrame.crop(bounds: PixelBounds): YuvFrame {
        if (bounds.left == 0 && bounds.top == 0 && bounds.width == width && bounds.height == height) return this
        val yy = ByteArray(bounds.width * bounds.height)
        val uu = ByteArray(yy.size)
        val vv = ByteArray(yy.size)
        for (row in 0 until bounds.height) {
            val source = (bounds.top + row) * width + bounds.left
            val destination = row * bounds.width
            y.copyInto(yy, destination, source, source + bounds.width)
            u.copyInto(uu, destination, source, source + bounds.width)
            v.copyInto(vv, destination, source, source + bounds.width)
        }
        return YuvFrame(bounds.width, bounds.height, yy, uu, vv)
    }

    private data class PixelBounds(val left: Int, val right: Int, val top: Int, val bottom: Int) {
        val width = right - left + 1
        val height = bottom - top + 1
    }

    private data class DetectionContext(
        val frameWidth: Int,
        val frameHeight: Int,
        val bounds: PixelBounds,
        val frameZone: LaunchZone,
        val mapper: FrameCoordinateMapper,
    )
}
