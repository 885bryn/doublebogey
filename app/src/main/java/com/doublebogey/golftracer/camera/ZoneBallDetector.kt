package com.doublebogey.golftracer.camera

import java.util.Locale
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt

/** Temporary source-compatibility for AutoShotTracker and CameraStatusBadgeModel. */
enum class ZoneBallCalibrationState { Uncalibrated, Calibrating, Calibrated }

data class ZoneBallDetectorConfig(
    val calibrationFramesRequired: Int = 1,
    val assumedZoneWidthMm: Double = 1500.0,
    val ballDiameterMm: Double = 42.7,
    val minAnnulusContrast: Double = 2.5,
    val maxInteriorUniformity: Double = 0.95,
    val minEdgeCircularity: Double = 0.45,
    val chromaShiftTolerance: Double = 45.0,
    val runnerUpMargin: Double = 1.5,
    val persistenceDecay: Double = 0.90,
    val lockThreshold: Double = 12.0,
    val maxMissedLockFrames: Int = 2,
    val maxCandidatesPerFrame: Int = 8,
    val sigmaFloor: Double = 6.0,
    val proposer: BallBlobProposerConfig = BallBlobProposerConfig(),
    val verifier: BallShapeVerifierConfig = BallShapeVerifierConfig(),
    val tracker: BallCandidateTrackerConfig = BallCandidateTrackerConfig(),
) {
    init {
        require(calibrationFramesRequired > 0)
        require(assumedZoneWidthMm > 0.0)
        require(ballDiameterMm > 0.0)
        require(minAnnulusContrast >= 0.0)
        require(maxInteriorUniformity >= 0.0)
        require(minEdgeCircularity in 0.0..1.0)
        require(chromaShiftTolerance >= 0.0)
        require(runnerUpMargin >= 1.0)
        require(persistenceDecay in 0.0..1.0)
        require(lockThreshold > 0.0)
        require(maxMissedLockFrames > 0)
        require(maxCandidatesPerFrame > 0)
        require(sigmaFloor > 0.0)
    }
}

data class ZoneBallCandidateDebug(
    val candidate: LumaMotionCandidate,
    val radiusPx: Double = 0.0,
    val proposalResponse: Double = 0.0,
    val rankScore: Double = 0.0,
    val metrics: BallShapeMetrics = EMPTY_SHAPE_METRICS,
    val rejection: BallShapeRejection? = null,
    // Temporary source-compatibility fields for Tasks 6-7 callers.
    val area: Int = candidate.pixelCount,
    val fillRatio: Double = metrics.closedEdgeCoverage,
    val aspectRatio: Double = 1.0,
    val meanSignificance: Double = metrics.annulusContrast,
    val chromaShift: Double = metrics.chromaShift,
    val meanYDelta: Double = 0.0,
    val annulusContrast: Double = metrics.annulusContrast,
    val interiorUniformity: Double = metrics.interiorUniformity,
    val edgeCircularity: Double = metrics.closedEdgeCoverage,
    val persistenceScore: Double = 0.0,
) {
    companion object {
        private val EMPTY_SHAPE_METRICS = BallShapeMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }
}

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
    // Temporary non-stale source compatibility for Tasks 6-7 callers.
    val calibrationState: ZoneBallCalibrationState = ZoneBallCalibrationState.Uncalibrated,
    val calibrationFramesCollected: Int = 0,
    val calibrationFramesRequired: Int = 0,
    val medianSigmaY: Double = 0.0,
    val foregroundFraction: Double = 0.0,
    val backgroundStale: Boolean = false,
    val rejectedBySize: Int = 0,
    val rejectedByShape: Int = 0,
    val rejectedBySignificance: Int = 0,
    val rejectedByChroma: Int = 0,
    val lockThreshold: Double = 0.0,
    val leadingPersistence: Double = 0.0,
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
    var calibrationState: ZoneBallCalibrationState = ZoneBallCalibrationState.Uncalibrated
        private set

    private var warmupFrameCount = 0
    private var activeContext: DetectionContext? = null
    private val scaleEstimator = BallScaleEstimator(config.assumedZoneWidthMm, config.ballDiameterMm)
    private val proposer = BallBlobProposer(
        config.proposer.copy(maxProposals = min(8, min(config.proposer.maxProposals, config.maxCandidatesPerFrame))),
    )
    private val verifier = BallShapeVerifier(config.verifier)
    private val tracker = BallCandidateTracker(config.tracker)

    @Suppress("UNUSED_PARAMETER")
    fun startCalibration(launchZone: LaunchZone) {
        calibrationState = ZoneBallCalibrationState.Calibrating
        warmupFrameCount = 0
        activeContext = null
        tracker.reset()
    }

    @Suppress("UNUSED_PARAMETER")
    fun collectCalibrationFrame(
        frame: YuvFrame,
        launchZone: LaunchZone,
        mapper: FrameCoordinateMapper = FrameCoordinateMapper.Identity,
    ): ZoneBallDetection {
        if (calibrationState != ZoneBallCalibrationState.Calibrating) startCalibration(launchZone)
        warmupFrameCount += 1
        if (warmupFrameCount >= config.calibrationFramesRequired) calibrationState = ZoneBallCalibrationState.Calibrated
        return ZoneBallDetection(null, compatibilityDebug())
    }

    fun analyzeFrame(
        frame: YuvFrame,
        launchZone: LaunchZone,
        mapper: FrameCoordinateMapper = FrameCoordinateMapper.Identity,
    ): ZoneBallDetection {
        require(launchZone.isValid()) { "launchZone must be valid" }
        if (calibrationState == ZoneBallCalibrationState.Uncalibrated) calibrationState = ZoneBallCalibrationState.Calibrated

        val frameZone = mapper.viewZoneToFrameZone(launchZone)
        val bounds = frameZone.bounds(frame)
        val context = DetectionContext(frame.width, frame.height, bounds, mapper)
        if (activeContext != context) {
            activeContext = context
            tracker.reset()
        }
        val scale = scaleEstimator.estimate(bounds.width)
        val padding = kotlin.math.ceil(scale.radiiPx.max() * 2.2).toInt()
        val analysisBounds = bounds.expand(frame, padding)
        val zoneFrame = frame.crop(analysisBounds)
        val proposals = proposer.propose(zoneFrame, scale.radiiPx).filter { proposal ->
            val sourceX = analysisBounds.left + proposal.centerX
            val sourceY = analysisBounds.top + proposal.centerY
            sourceX in bounds.left.toDouble()..bounds.right.toDouble() &&
                sourceY in bounds.top.toDouble()..bounds.bottom.toDouble()
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
                calibrationState = calibrationState,
                calibrationFramesCollected = warmupFrameCount,
                calibrationFramesRequired = config.calibrationFramesRequired,
            ),
        )
    }

    fun reset() {
        calibrationState = ZoneBallCalibrationState.Uncalibrated
        warmupFrameCount = 0
        activeContext = null
        tracker.reset()
    }

    private fun compatibilityDebug() = ZoneBallDebug(
        calibrationState = calibrationState,
        calibrationFramesCollected = warmupFrameCount,
        calibrationFramesRequired = config.calibrationFramesRequired,
        confirmationWindow = config.tracker.windowSize,
    )

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
        val mapper: FrameCoordinateMapper,
    )
}