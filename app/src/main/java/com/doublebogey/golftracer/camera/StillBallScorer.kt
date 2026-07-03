package com.doublebogey.golftracer.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class StillBallCalibrationState { Uncalibrated, Calibrating, Calibrated }

data class StillBallScorerConfig(
    val calibrationFramesRequired: Int = 30,
    val minBrightnessDelta: Int = 35,
    val neutralChromaTolerance: Int = 22,
    val minBlobPixels: Int = 4,
    val maxBlobPixels: Int = 3000,
    val minFillRatio: Double = 0.45,
    val minAspectRatio: Double = 0.55,
    val minAcceptedScore: Double = 0.62,
) {
    init {
        require(calibrationFramesRequired > 0) { "calibrationFramesRequired must be greater than 0" }
        require(minBrightnessDelta in 0..255) { "minBrightnessDelta must be in 0..255" }
        require(neutralChromaTolerance in 0..255) { "neutralChromaTolerance must be in 0..255" }
        require(minBlobPixels > 0) { "minBlobPixels must be greater than 0" }
        require(maxBlobPixels >= minBlobPixels) { "maxBlobPixels must be at least minBlobPixels" }
        require(minFillRatio in 0.0..1.0) { "minFillRatio must be in 0..1" }
        require(minAspectRatio in 0.0..1.0) { "minAspectRatio must be in 0..1" }
        require(minAcceptedScore in 0.0..1.0) { "minAcceptedScore must be in 0..1" }
    }
}

data class StillBallDebug(
    val calibrationState: StillBallCalibrationState = StillBallCalibrationState.Uncalibrated,
    val calibrationFramesCollected: Int = 0,
    val calibrationFramesRequired: Int = 0,
    val candidateCount: Int = 0,
    val rejectedByBrightness: Int = 0,
    val rejectedByChroma: Int = 0,
    val rejectedBySize: Int = 0,
    val rejectedByShape: Int = 0,
    val brightnessScore: Double = 0.0,
    val chromaScore: Double = 0.0,
    val shapeScore: Double = 0.0,
    val acceptedScore: Double = 0.0,
) {
    fun statusSummary(): String =
        "cal=$calibrationState ${calibrationFramesCollected}/${calibrationFramesRequired} score=${acceptedScore.formatScore()} b=${brightnessScore.formatScore()} c=${chromaScore.formatScore()} shape=${shapeScore.formatScore()} cand=$candidateCount rejB=$rejectedByBrightness rejC=$rejectedByChroma rejS=$rejectedByShape rejZ=$rejectedBySize"

    private fun Double.formatScore(): String = String.format(java.util.Locale.US, "%.2f", this)
}

data class StillBallDetection(val acceptedCandidate: LumaMotionCandidate?, val debug: StillBallDebug)

class StillBallScorer(private val config: StillBallScorerConfig = StillBallScorerConfig()) {
    var calibrationState: StillBallCalibrationState = StillBallCalibrationState.Uncalibrated
        private set

    private var calibrationFrameCount = 0
    private var sumY: IntArray? = null
    private var sumU: IntArray? = null
    private var sumV: IntArray? = null
    private var model: BackgroundModel? = null

    fun startCalibration() {
        calibrationState = StillBallCalibrationState.Calibrating
        calibrationFrameCount = 0
        sumY = null
        sumU = null
        sumV = null
        model = null
    }

    fun collectCalibrationFrame(frame: YuvFrame, launchZone: LaunchZone): StillBallDetection {
        if (calibrationState != StillBallCalibrationState.Calibrating) startCalibration()
        ensureCalibrationBuffers(frame)
        val ySum = requireNotNull(sumY)
        val uSum = requireNotNull(sumU)
        val vSum = requireNotNull(sumV)
        val bounds = launchZone.bounds(frame)
        for (row in bounds.top..bounds.bottom) {
            for (column in bounds.left..bounds.right) {
                val index = row * frame.width + column
                ySum[index] += frame.y[index].unsigned()
                uSum[index] += frame.u[index].unsigned()
                vSum[index] += frame.v[index].unsigned()
            }
        }
        calibrationFrameCount += 1
        if (calibrationFrameCount >= config.calibrationFramesRequired) {
            model = buildModel(frame)
            calibrationState = StillBallCalibrationState.Calibrated
        }
        return StillBallDetection(acceptedCandidate = null, debug = debug(candidateCount = 0))
    }

    fun analyzeFrame(frame: YuvFrame, launchZone: LaunchZone): StillBallDetection {
        val background = model
        if (calibrationState != StillBallCalibrationState.Calibrated || background == null) {
            return StillBallDetection(acceptedCandidate = null, debug = debug(candidateCount = 0))
        }
        if (background.width != frame.width || background.height != frame.height) {
            reset()
            return StillBallDetection(acceptedCandidate = null, debug = debug(candidateCount = 0))
        }

        val bounds = launchZone.bounds(frame)
        val mask = BooleanArray(frame.width * frame.height)
        var rejectedByBrightness = 0
        var rejectedByChroma = 0
        for (row in bounds.top..bounds.bottom) {
            for (column in bounds.left..bounds.right) {
                val index = row * frame.width + column
                val brightnessDelta = frame.y[index].unsigned() - background.y[index].unsigned()
                if (brightnessDelta < config.minBrightnessDelta) {
                    rejectedByBrightness += 1
                    continue
                }
                if (neutralChromaDistance(frame.u[index].unsigned(), frame.v[index].unsigned()) > config.neutralChromaTolerance) {
                    rejectedByChroma += 1
                    continue
                }
                mask[index] = true
            }
        }

        var rejectedBySize = 0
        var rejectedByShape = 0
        var candidateCount = 0
        var best: ScoredComponent? = null
        for (component in findComponents(frame, mask)) {
            if (component.pixelCount !in config.minBlobPixels..config.maxBlobPixels) {
                rejectedBySize += 1
                continue
            }
            val scored = component.score(frame, background)
            if (scored.fillRatio < config.minFillRatio || scored.aspectRatio < config.minAspectRatio) {
                rejectedByShape += 1
                continue
            }
            candidateCount += 1
            if (best == null || scored.score > best.score) best = scored
        }

        val accepted = best?.takeIf { it.score >= config.minAcceptedScore }
        return StillBallDetection(
            acceptedCandidate = accepted?.toCandidate(frame),
            debug = debug(
                candidateCount = candidateCount,
                rejectedByBrightness = rejectedByBrightness,
                rejectedByChroma = rejectedByChroma,
                rejectedBySize = rejectedBySize,
                rejectedByShape = rejectedByShape,
                brightnessScore = accepted?.brightnessScore ?: best?.brightnessScore ?: 0.0,
                chromaScore = accepted?.chromaScore ?: best?.chromaScore ?: 0.0,
                shapeScore = accepted?.shapeScore ?: best?.shapeScore ?: 0.0,
                acceptedScore = accepted?.score ?: 0.0,
            ),
        )
    }

    fun reset() {
        calibrationState = StillBallCalibrationState.Uncalibrated
        calibrationFrameCount = 0
        sumY = null
        sumU = null
        sumV = null
        model = null
    }

    private fun ensureCalibrationBuffers(frame: YuvFrame) {
        val existing = sumY
        if (existing != null && existing.size == frame.width * frame.height) return
        val size = frame.width * frame.height
        sumY = IntArray(size)
        sumU = IntArray(size)
        sumV = IntArray(size)
        calibrationFrameCount = 0
    }

    private fun buildModel(frame: YuvFrame): BackgroundModel {
        val ySum = requireNotNull(sumY)
        val uSum = requireNotNull(sumU)
        val vSum = requireNotNull(sumV)
        val y = ByteArray(frame.width * frame.height)
        val u = ByteArray(frame.width * frame.height)
        val v = ByteArray(frame.width * frame.height)
        for (index in y.indices) {
            y[index] = (ySum[index] / calibrationFrameCount).toByte()
            u[index] = (uSum[index] / calibrationFrameCount).toByte()
            v[index] = (vSum[index] / calibrationFrameCount).toByte()
        }
        return BackgroundModel(width = frame.width, height = frame.height, y = y, u = u, v = v)
    }

    private fun findComponents(frame: YuvFrame, mask: BooleanArray): List<Component> {
        val visited = BooleanArray(mask.size)
        val components = mutableListOf<Component>()
        for (index in mask.indices) {
            if (mask[index] && !visited[index]) components += floodFill(index, frame, mask, visited)
        }
        return components
    }

    private fun floodFill(startIndex: Int, frame: YuvFrame, mask: BooleanArray, visited: BooleanArray): Component {
        val queue = ArrayDeque<Int>()
        queue.add(startIndex)
        visited[startIndex] = true
        var pixelCount = 0
        var xSum = 0.0
        var ySum = 0.0
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % frame.width
            val y = index / frame.width
            pixelCount += 1
            xSum += x
            ySum += y
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)
            enqueueIfIncluded(index - 1, x > 0, mask, visited, queue)
            enqueueIfIncluded(index + 1, x < frame.width - 1, mask, visited, queue)
            enqueueIfIncluded(index - frame.width, y > 0, mask, visited, queue)
            enqueueIfIncluded(index + frame.width, y < frame.height - 1, mask, visited, queue)
        }
        return Component(pixelCount, xSum, ySum, minX, maxX, minY, maxY)
    }

    private fun enqueueIfIncluded(index: Int, inBounds: Boolean, mask: BooleanArray, visited: BooleanArray, queue: ArrayDeque<Int>) {
        if (!inBounds || !mask[index] || visited[index]) return
        visited[index] = true
        queue.add(index)
    }

    private fun Component.score(frame: YuvFrame, background: BackgroundModel): ScoredComponent {
        var brightnessDeltaSum = 0.0
        var chromaDistanceSum = 0.0
        for (row in minY..maxY) {
            for (column in minX..maxX) {
                val index = row * frame.width + column
                val brightnessDelta = frame.y[index].unsigned() - background.y[index].unsigned()
                val chromaDistance = neutralChromaDistance(frame.u[index].unsigned(), frame.v[index].unsigned())
                if (brightnessDelta >= config.minBrightnessDelta && chromaDistance <= config.neutralChromaTolerance) {
                    brightnessDeltaSum += brightnessDelta
                    chromaDistanceSum += chromaDistance
                }
            }
        }
        val boxWidth = maxX - minX + 1
        val boxHeight = maxY - minY + 1
        val aspectRatio = min(boxWidth, boxHeight).toDouble() / max(boxWidth, boxHeight).toDouble()
        val fillRatio = pixelCount.toDouble() / (boxWidth * boxHeight).toDouble()
        val brightnessScore = ((brightnessDeltaSum / pixelCount) / 140.0).coerceIn(0.0, 1.0)
        val chromaScore = (1.0 - (chromaDistanceSum / pixelCount) / config.neutralChromaTolerance.toDouble()).coerceIn(0.0, 1.0)
        val shapeScore = ((aspectRatio + fillRatio) / 2.0).coerceIn(0.0, 1.0)
        val score = (brightnessScore * 0.45 + chromaScore * 0.35 + shapeScore * 0.20).coerceIn(0.0, 1.0)
        return ScoredComponent(this, aspectRatio, fillRatio, brightnessScore, chromaScore, shapeScore, score)
    }

    private fun ScoredComponent.toCandidate(frame: YuvFrame): LumaMotionCandidate {
        val xDenominator = (frame.width - 1).coerceAtLeast(1).toDouble()
        val yDenominator = (frame.height - 1).coerceAtLeast(1).toDouble()
        return LumaMotionCandidate(
            x = (component.xSum / component.pixelCount) / xDenominator,
            y = (component.ySum / component.pixelCount) / yDenominator,
            pixelCount = component.pixelCount,
            confidence = score,
        )
    }

    private fun debug(
        candidateCount: Int,
        rejectedByBrightness: Int = 0,
        rejectedByChroma: Int = 0,
        rejectedBySize: Int = 0,
        rejectedByShape: Int = 0,
        brightnessScore: Double = 0.0,
        chromaScore: Double = 0.0,
        shapeScore: Double = 0.0,
        acceptedScore: Double = 0.0,
    ): StillBallDebug = StillBallDebug(
        calibrationState = calibrationState,
        calibrationFramesCollected = calibrationFrameCount,
        calibrationFramesRequired = config.calibrationFramesRequired,
        candidateCount = candidateCount,
        rejectedByBrightness = rejectedByBrightness,
        rejectedByChroma = rejectedByChroma,
        rejectedBySize = rejectedBySize,
        rejectedByShape = rejectedByShape,
        brightnessScore = brightnessScore,
        chromaScore = chromaScore,
        shapeScore = shapeScore,
        acceptedScore = acceptedScore,
    )

    private fun neutralChromaDistance(u: Int, v: Int): Int = abs(u - NEUTRAL_CHROMA) + abs(v - NEUTRAL_CHROMA)

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

    private data class PixelBounds(val left: Int, val right: Int, val top: Int, val bottom: Int)
    private data class BackgroundModel(val width: Int, val height: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray)
    private data class Component(val pixelCount: Int, val xSum: Double, val ySum: Double, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)
    private data class ScoredComponent(
        val component: Component,
        val aspectRatio: Double,
        val fillRatio: Double,
        val brightnessScore: Double,
        val chromaScore: Double,
        val shapeScore: Double,
        val score: Double,
    )

    private companion object { const val NEUTRAL_CHROMA = 128 }
}
