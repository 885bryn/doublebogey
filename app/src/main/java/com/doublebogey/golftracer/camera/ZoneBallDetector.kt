package com.doublebogey.golftracer.camera

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class ZoneBallCalibrationState {
    Uncalibrated,
    Calibrating,
    Calibrated,
}

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
) {
    init {
        require(calibrationFramesRequired > 0) { "calibrationFramesRequired must be greater than 0" }
        require(assumedZoneWidthMm > 0.0) { "assumedZoneWidthMm must be greater than 0" }
        require(ballDiameterMm > 0.0) { "ballDiameterMm must be greater than 0" }
        require(minAnnulusContrast >= 0.0) { "minAnnulusContrast must not be negative" }
        require(maxInteriorUniformity >= 0.0) { "maxInteriorUniformity must not be negative" }
        require(minEdgeCircularity in 0.0..1.0) { "minEdgeCircularity must be in 0..1" }
        require(chromaShiftTolerance >= 0.0) { "chromaShiftTolerance must not be negative" }
        require(runnerUpMargin >= 1.0) { "runnerUpMargin must be at least 1" }
        require(persistenceDecay in 0.0..1.0) { "persistenceDecay must be in 0..1" }
        require(lockThreshold > 0.0) { "lockThreshold must be greater than 0" }
        require(maxMissedLockFrames > 0) { "maxMissedLockFrames must be greater than 0" }
        require(maxCandidatesPerFrame > 0) { "maxCandidatesPerFrame must be greater than 0" }
        require(sigmaFloor > 0.0) { "sigmaFloor must be greater than 0" }
    }
}

data class ZoneBallCandidateDebug(
    val candidate: LumaMotionCandidate,
    val area: Int,
    val fillRatio: Double,
    val aspectRatio: Double,
    val meanSignificance: Double,
    val chromaShift: Double,
    val rankScore: Double,
    val meanYDelta: Double = 0.0,
    val radiusPx: Double = 0.0,
    val annulusContrast: Double = meanSignificance,
    val interiorUniformity: Double = 0.0,
    val edgeCircularity: Double = fillRatio,
    val persistenceScore: Double = 0.0,
)

data class ZoneBallDebug(
    val calibrationState: ZoneBallCalibrationState = ZoneBallCalibrationState.Uncalibrated,
    val calibrationFramesCollected: Int = 0,
    val calibrationFramesRequired: Int = 0,
    val medianSigmaY: Double = 0.0,
    val foregroundFraction: Double = 0.0,
    val backgroundStale: Boolean = false,
    val candidates: List<ZoneBallCandidateDebug> = emptyList(),
    val rejectedBySize: Int = 0,
    val rejectedByShape: Int = 0,
    val rejectedBySignificance: Int = 0,
    val rejectedByChroma: Int = 0,
    val margin: Double = 0.0,
    val expectedRadiusPx: Double = 0.0,
    val lockThreshold: Double = 0.0,
    val leadingPersistence: Double = 0.0,
) {
    val best: ZoneBallCandidateDebug?
        get() = candidates.firstOrNull()

    fun statusSummary(aeAwbLocked: Boolean = false): String {
        val bestSummary = best?.let { candidate ->
            "best={q=${candidate.rankScore.format1()} C=${candidate.annulusContrast.format1()} E=${candidate.edgeCircularity.format2()} U=${candidate.interiorUniformity.format2()} r=${candidate.radiusPx.format1()}}"
        } ?: "best=none"
        return "shot=Searching r_e=${expectedRadiusPx.format1()} cand=${candidates.size} $bestSummary persist=${leadingPersistence.format1()}/${lockThreshold.format1()} margin=${margin.format2()} aeLock=${if (aeAwbLocked) 1 else 0}"
    }

    private fun Double.format1(): String = String.format(Locale.US, "%.1f", this)
    private fun Double.format2(): String = String.format(Locale.US, "%.2f", this)
}

data class ZoneBallDetection(
    val acceptedCandidate: LumaMotionCandidate?,
    val debug: ZoneBallDebug,
)

class ZoneBallDetector(
    private val config: ZoneBallDetectorConfig = ZoneBallDetectorConfig(),
) {
    var calibrationState: ZoneBallCalibrationState = ZoneBallCalibrationState.Uncalibrated
        private set

    private var warmupFrameCount = 0
    private var activeLaunchZone: LaunchZone? = null
    private var activeFrameSize: Pair<Int, Int>? = null
    private val accumulators = mutableMapOf<CellKey, Accumulator>()
    private var lockedKey: CellKey? = null
    private var lockedCandidate: LumaMotionCandidate? = null
    private var missedLockFrames = 0

    fun startCalibration(launchZone: LaunchZone) {
        calibrationState = ZoneBallCalibrationState.Calibrating
        warmupFrameCount = 0
        activeLaunchZone = launchZone
        resetTrackingState()
    }

    fun collectCalibrationFrame(
        frame: YuvFrame,
        launchZone: LaunchZone,
        mapper: FrameCoordinateMapper = FrameCoordinateMapper.Identity,
    ): ZoneBallDetection {
        if (calibrationState != ZoneBallCalibrationState.Calibrating || activeLaunchZone != launchZone) {
            startCalibration(launchZone)
        }
        warmupFrameCount += 1
        if (warmupFrameCount >= config.calibrationFramesRequired) {
            calibrationState = ZoneBallCalibrationState.Calibrated
        }
        return ZoneBallDetection(
            acceptedCandidate = null,
            debug = debug(expectedRadiusPx = expectedRadius(mapper.viewZoneToFrameZone(launchZone).bounds(frame))),
        )
    }

    fun analyzeFrame(
        frame: YuvFrame,
        launchZone: LaunchZone,
        mapper: FrameCoordinateMapper = FrameCoordinateMapper.Identity,
    ): ZoneBallDetection {
        ensureActiveFrame(frame, launchZone)
        if (calibrationState == ZoneBallCalibrationState.Uncalibrated) {
            calibrationState = ZoneBallCalibrationState.Calibrated
            warmupFrameCount = config.calibrationFramesRequired
        }

        val frameZone = mapper.viewZoneToFrameZone(launchZone)
        val bounds = frameZone.bounds(frame)
        val expectedRadius = expectedRadius(bounds)
        val proposals = proposeCandidates(frame, bounds, mapper, expectedRadius)
        val ranked = proposals.map { it.debug }.sortedByDescending { it.rankScore }
        decayAccumulators()
        for (proposal in proposals) {
            val key = proposal.cellKey(expectedRadius)
            val accumulator = accumulators.getOrPut(key) { Accumulator(key = key) }
            accumulator.score += proposal.debug.rankScore
            accumulator.candidate = proposal.debug.candidate
            accumulator.debug = proposal.debug
        }

        val locked = updateLock(proposals, expectedRadius, frame)
        val leaders = accumulators.values.sortedByDescending { it.score }
        val leader = leaders.firstOrNull()
        val runnerUp = leaders.getOrNull(1)
        val margin = when {
            leader == null -> 0.0
            runnerUp == null || runnerUp.score == 0.0 -> Double.POSITIVE_INFINITY
            else -> leader.score / runnerUp.score
        }
        if (lockedKey == null && leader != null && leader.score >= config.lockThreshold && margin >= config.runnerUpMargin) {
            lockedKey = leader.key
            lockedCandidate = leader.candidate
            missedLockFrames = 0
        }

        val accepted = when {
            locked != null -> locked
            lockedKey != null && leader?.key == lockedKey && proposals.any { it.cellKey(expectedRadius) == lockedKey } -> leader?.candidate
            else -> null
        }
        val candidates = ranked.take(3).map { candidate ->
            val score = proposals.firstOrNull { it.debug === candidate }
                ?.cellKey(expectedRadius)
                ?.let { accumulators[it]?.score }
                ?: 0.0
            candidate.copy(persistenceScore = score)
        }

        return ZoneBallDetection(
            acceptedCandidate = accepted,
            debug = debug(
                candidates = candidates,
                margin = margin,
                expectedRadiusPx = expectedRadius,
                leadingPersistence = leader?.score ?: 0.0,
            ),
        )
    }

    fun reset() {
        calibrationState = ZoneBallCalibrationState.Uncalibrated
        warmupFrameCount = 0
        activeLaunchZone = null
        activeFrameSize = null
        resetTrackingState()
    }

    private fun ensureActiveFrame(frame: YuvFrame, launchZone: LaunchZone) {
        val frameSize = frame.width to frame.height
        if (activeFrameSize == frameSize && activeLaunchZone == launchZone) return
        activeFrameSize = frameSize
        activeLaunchZone = launchZone
        resetTrackingState()
    }

    private fun resetTrackingState() {
        accumulators.clear()
        lockedKey = null
        lockedCandidate = null
        missedLockFrames = 0
    }

    private fun proposeCandidates(
        frame: YuvFrame,
        bounds: PixelBounds,
        mapper: FrameCoordinateMapper,
        expectedRadius: Double,
    ): List<Proposal> {
        val radii = listOf(expectedRadius * 0.75, expectedRadius, expectedRadius * 1.4)
            .map { it.roundToInt().coerceAtLeast(2) }
            .distinct()
        val proposals = mutableListOf<Proposal>()
        for (radius in radii) {
            for (row in bounds.top..bounds.bottom) {
                for (column in bounds.left..bounds.right) {
                    val proposal = scoreCandidate(frame, bounds, mapper, column, row, radius.toDouble())
                    if (proposal != null) proposals += proposal
                }
            }
        }
        val selected = mutableListOf<Proposal>()
        for (proposal in proposals.sortedByDescending { it.debug.rankScore }) {
            val tooClose = selected.any { existing ->
                hypot(existing.frameX - proposal.frameX, existing.frameY - proposal.frameY) <= max(existing.radiusPx, proposal.radiusPx)
            }
            if (!tooClose) selected += proposal
            if (selected.size >= config.maxCandidatesPerFrame) break
        }
        return selected
    }

    private fun scoreCandidate(
        frame: YuvFrame,
        bounds: PixelBounds,
        mapper: FrameCoordinateMapper,
        centerX: Int,
        centerY: Int,
        radius: Double,
    ): Proposal? {
        val disc = sampleStats(frame, centerX, centerY, 0.0, radius)
        val inner = sampleStats(frame, centerX, centerY, 0.0, radius * 0.60)
        val annulus = sampleStats(frame, centerX, centerY, radius * 1.3, radius * 2.2)
        if (disc.count == 0 || annulus.count == 0 || inner.count == 0) return null

        val yDelta = disc.meanY - annulus.meanY
        val contrast = abs(yDelta) / max(annulus.sigmaY, config.sigmaFloor)
        if (contrast < config.minAnnulusContrast) return null

        val uniformity = inner.sigmaY / max(annulus.sigmaY, config.sigmaFloor)
        if (uniformity > config.maxInteriorUniformity) return null

        val circularity = edgeCircularity(frame, bounds, centerX, centerY, radius, max(8.0, annulus.sigmaY * 1.5))
        if (circularity < config.minEdgeCircularity) return null

        val chromaShift = abs(disc.meanU - annulus.meanU) + abs(disc.meanV - annulus.meanV)
        if (chromaShift > config.chromaShiftTolerance) return null

        val score = contrast * circularity * (1.0 - uniformity.coerceIn(0.0, 0.95))
        val xDenominator = (frame.width - 1).coerceAtLeast(1).toDouble()
        val yDenominator = (frame.height - 1).coerceAtLeast(1).toDouble()
        val viewCentroid = mapper.frameToView(centerX / xDenominator, centerY / yDenominator)
        val area = (PI * radius * radius).roundToInt().coerceAtLeast(1)
        return Proposal(
            frameX = centerX.toDouble(),
            frameY = centerY.toDouble(),
            radiusPx = radius,
            debug = ZoneBallCandidateDebug(
                candidate = LumaMotionCandidate(
                    x = viewCentroid.x,
                    y = viewCentroid.y,
                    pixelCount = area,
                    confidence = (score / 12.0).coerceIn(0.0, 1.0),
                ),
                area = area,
                fillRatio = circularity,
                aspectRatio = 1.0,
                meanSignificance = contrast,
                chromaShift = chromaShift,
                rankScore = score,
                meanYDelta = yDelta,
                radiusPx = radius,
                annulusContrast = contrast,
                interiorUniformity = uniformity,
                edgeCircularity = circularity,
            ),
        )
    }

    private fun sampleStats(
        frame: YuvFrame,
        centerX: Int,
        centerY: Int,
        minRadius: Double,
        maxRadius: Double,
    ): SampleStats {
        val minSquared = minRadius * minRadius
        val maxSquared = maxRadius * maxRadius
        val left = (centerX - maxRadius.roundToInt()).coerceAtLeast(0)
        val right = (centerX + maxRadius.roundToInt()).coerceAtMost(frame.width - 1)
        val top = (centerY - maxRadius.roundToInt()).coerceAtLeast(0)
        val bottom = (centerY + maxRadius.roundToInt()).coerceAtMost(frame.height - 1)
        var count = 0
        var ySum = 0.0
        var ySqSum = 0.0
        var uSum = 0.0
        var vSum = 0.0
        for (row in top..bottom) {
            for (column in left..right) {
                val dx = column - centerX
                val dy = row - centerY
                val distanceSquared = (dx * dx + dy * dy).toDouble()
                if (distanceSquared < minSquared || distanceSquared > maxSquared) continue
                val index = row * frame.width + column
                val y = frame.y[index].unsigned().toDouble()
                count += 1
                ySum += y
                ySqSum += y * y
                uSum += frame.u[index].unsigned().toDouble()
                vSum += frame.v[index].unsigned().toDouble()
            }
        }
        if (count == 0) return SampleStats()
        val meanY = ySum / count.toDouble()
        val variance = (ySqSum / count.toDouble() - meanY * meanY).coerceAtLeast(0.0)
        return SampleStats(
            count = count,
            meanY = meanY,
            sigmaY = sqrt(variance),
            meanU = uSum / count.toDouble(),
            meanV = vSum / count.toDouble(),
        )
    }

    private fun edgeCircularity(
        frame: YuvFrame,
        bounds: PixelBounds,
        centerX: Int,
        centerY: Int,
        radius: Double,
        threshold: Double,
    ): Double {
        var supported = 0
        var sampled = 0
        repeat(32) { sample ->
            val angle = sample * 2.0 * PI / 32.0
            val innerX = (centerX + cos(angle) * radius * 0.75).roundToInt()
            val innerY = (centerY + sin(angle) * radius * 0.75).roundToInt()
            val outerX = (centerX + cos(angle) * radius * 1.30).roundToInt()
            val outerY = (centerY + sin(angle) * radius * 1.30).roundToInt()
            if (!bounds.contains(innerX, innerY) || !bounds.contains(outerX, outerY)) return@repeat
            sampled += 1
            val inner = frame.y[innerY * frame.width + innerX].unsigned()
            val outer = frame.y[outerY * frame.width + outerX].unsigned()
            if (abs(inner - outer) >= threshold) supported += 1
        }
        return if (sampled == 0) 0.0 else supported.toDouble() / sampled.toDouble()
    }

    private fun decayAccumulators() {
        val iterator = accumulators.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.score *= config.persistenceDecay
            if (entry.value.score < 0.05) iterator.remove()
        }
    }

    private fun updateLock(proposals: List<Proposal>, expectedRadius: Double, frame: YuvFrame): LumaMotionCandidate? {
        val currentLock = lockedCandidate ?: return null
        val maxDistance = max(0.025, expectedRadius * 2.0 / min(frame.width, frame.height).toDouble())
        val near = proposals
            .map { it.debug.candidate }
            .filter { hypot(it.x - currentLock.x, it.y - currentLock.y) <= maxDistance }
            .maxByOrNull { it.confidence }
        if (near != null) {
            lockedCandidate = near
            missedLockFrames = 0
            return near
        }
        missedLockFrames += 1
        if (missedLockFrames >= config.maxMissedLockFrames) {
            lockedKey = null
            lockedCandidate = null
        }
        return null
    }

    private fun expectedRadius(bounds: PixelBounds): Double {
        val zoneWidthPx = bounds.width.toDouble()
        val expectedDiameter = zoneWidthPx * (config.ballDiameterMm / config.assumedZoneWidthMm)
        return (expectedDiameter / 2.0).coerceIn(2.0, 40.0)
    }

    private fun Proposal.cellKey(expectedRadius: Double): CellKey {
        val cellSize = max(1.0, expectedRadius / 2.0)
        return CellKey((frameX / cellSize).roundToInt(), (frameY / cellSize).roundToInt())
    }

    private fun debug(
        candidates: List<ZoneBallCandidateDebug> = emptyList(),
        margin: Double = 0.0,
        expectedRadiusPx: Double = 0.0,
        leadingPersistence: Double = 0.0,
    ): ZoneBallDebug = ZoneBallDebug(
        calibrationState = calibrationState,
        calibrationFramesCollected = warmupFrameCount,
        calibrationFramesRequired = config.calibrationFramesRequired,
        candidates = candidates,
        margin = margin,
        expectedRadiusPx = expectedRadiusPx,
        lockThreshold = config.lockThreshold,
        leadingPersistence = leadingPersistence,
    )

    private fun LaunchZone.bounds(frame: YuvFrame): PixelBounds {
        val maxX = frame.width - 1
        val maxY = frame.height - 1
        val leftPx = (left * maxX).toInt().coerceIn(0, maxX)
        val rightPx = ((left + width) * maxX).toInt().coerceIn(leftPx, maxX)
        val topPx = (top * maxY).toInt().coerceIn(0, maxY)
        val bottomPx = ((top + height) * maxY).toInt().coerceIn(topPx, maxY)
        return PixelBounds(left = leftPx, right = rightPx, top = topPx, bottom = bottomPx)
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF

    private data class PixelBounds(val left: Int, val right: Int, val top: Int, val bottom: Int) {
        val width: Int = right - left + 1
        fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom
    }

    private data class SampleStats(
        val count: Int = 0,
        val meanY: Double = 0.0,
        val sigmaY: Double = 0.0,
        val meanU: Double = 0.0,
        val meanV: Double = 0.0,
    )

    private data class Proposal(
        val frameX: Double,
        val frameY: Double,
        val radiusPx: Double,
        val debug: ZoneBallCandidateDebug,
    )

    private data class CellKey(val x: Int, val y: Int)

    private data class Accumulator(
        val key: CellKey,
        var score: Double = 0.0,
        var candidate: LumaMotionCandidate? = null,
        var debug: ZoneBallCandidateDebug? = null,
    )
}
