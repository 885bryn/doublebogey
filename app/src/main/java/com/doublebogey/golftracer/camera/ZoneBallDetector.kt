package com.doublebogey.golftracer.camera

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class ZoneBallCalibrationState {
    Uncalibrated,
    Calibrating,
    Calibrated,
}

data class ZoneBallDetectorConfig(
    val calibrationFramesRequired: Int = 30,
    val minYDelta: Double = 18.0,
    val significanceMultiplier: Double = 4.0,
    val minMeanSignificance: Double = 6.0,
    val minArea: Int = 6,
    val maxArea: Int = 4000,
    val minFillRatio: Double = 0.45,
    val minAspectRatio: Double = 0.50,
    val chromaShiftTolerance: Double = 45.0,
    val staleForegroundFraction: Double = 0.35,
    val runnerUpMargin: Double = 1.5,
) {
    init {
        require(calibrationFramesRequired > 0) { "calibrationFramesRequired must be greater than 0" }
        require(minYDelta >= 0.0) { "minYDelta must not be negative" }
        require(significanceMultiplier > 0.0) { "significanceMultiplier must be greater than 0" }
        require(minMeanSignificance > 0.0) { "minMeanSignificance must be greater than 0" }
        require(minArea > 0) { "minArea must be greater than 0" }
        require(maxArea >= minArea) { "maxArea must be at least minArea" }
        require(minFillRatio in 0.0..1.0) { "minFillRatio must be in 0..1" }
        require(minAspectRatio in 0.0..1.0) { "minAspectRatio must be in 0..1" }
        require(chromaShiftTolerance >= 0.0) { "chromaShiftTolerance must not be negative" }
        require(staleForegroundFraction in 0.0..1.0) { "staleForegroundFraction must be in 0..1" }
        require(runnerUpMargin >= 1.0) { "runnerUpMargin must be at least 1" }
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
) {
    val best: ZoneBallCandidateDebug?
        get() = candidates.firstOrNull()

    fun statusSummary(aeAwbLocked: Boolean = false): String {
        val calLabel = when (calibrationState) {
            ZoneBallCalibrationState.Uncalibrated -> "None"
            ZoneBallCalibrationState.Calibrating -> "Calibrating ${calibrationFramesCollected}/${calibrationFramesRequired}"
            ZoneBallCalibrationState.Calibrated -> "OK"
        }
        val bestSummary = best?.let { candidate ->
            "best={s=${candidate.meanSignificance.format1()} area=${candidate.area} fill=${candidate.fillRatio.format2()} asp=${candidate.aspectRatio.format2()} dc=${candidate.chromaShift.format1()}}"
        } ?: "best=none"
        return "cal=$calLabel sigma=${medianSigmaY.format1()} aeLock=${if (aeAwbLocked) 1 else 0} fg=${(foregroundFraction * 100.0).format1()}% $bestSummary margin=${margin.format2()}"
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

    private var calibrationFrameCount = 0
    private var calibrationZone: LaunchZone? = null
    private var sumY: LongArray? = null
    private var sumU: LongArray? = null
    private var sumV: LongArray? = null
    private var sumSqY: LongArray? = null
    private var model: ZoneBackgroundModel? = null

    fun startCalibration(launchZone: LaunchZone) {
        calibrationState = ZoneBallCalibrationState.Calibrating
        calibrationFrameCount = 0
        calibrationZone = launchZone
        sumY = null
        sumU = null
        sumV = null
        sumSqY = null
        model = null
    }

    fun collectCalibrationFrame(frame: YuvFrame, launchZone: LaunchZone): ZoneBallDetection {
        if (calibrationState != ZoneBallCalibrationState.Calibrating || calibrationZone != launchZone) {
            startCalibration(launchZone)
        }
        ensureCalibrationBuffers(frame)
        val bounds = launchZone.bounds(frame)
        val ySum = requireNotNull(sumY)
        val uSum = requireNotNull(sumU)
        val vSum = requireNotNull(sumV)
        val ySqSum = requireNotNull(sumSqY)

        for (row in bounds.top..bounds.bottom) {
            for (column in bounds.left..bounds.right) {
                val index = row * frame.width + column
                val y = frame.y[index].unsigned()
                ySum[index] += y.toLong()
                uSum[index] += frame.u[index].unsigned().toLong()
                vSum[index] += frame.v[index].unsigned().toLong()
                ySqSum[index] += y.toLong() * y.toLong()
            }
        }

        calibrationFrameCount += 1
        if (calibrationFrameCount >= config.calibrationFramesRequired) {
            model = buildModel(frame, bounds)
            calibrationState = ZoneBallCalibrationState.Calibrated
        }
        return ZoneBallDetection(acceptedCandidate = null, debug = debug())
    }

    fun analyzeFrame(frame: YuvFrame, launchZone: LaunchZone): ZoneBallDetection {
        val background = model
        if (calibrationState != ZoneBallCalibrationState.Calibrated || background == null) {
            return ZoneBallDetection(acceptedCandidate = null, debug = debug())
        }
        if (!background.matches(frame, launchZone)) {
            reset()
            return ZoneBallDetection(acceptedCandidate = null, debug = debug())
        }

        val foreground = buildForegroundMask(frame, background)
        val foregroundFraction = foreground.count.toDouble() / background.bounds.area.toDouble()
        if (foregroundFraction > config.staleForegroundFraction) {
            return ZoneBallDetection(
                acceptedCandidate = null,
                debug = debug(
                    foregroundFraction = foregroundFraction,
                    backgroundStale = true,
                ),
            )
        }

        val components = findComponents(frame, background, foreground.mask)
        val ranked = mutableListOf<ZoneBallCandidateDebug>()
        var rejectedBySize = 0
        var rejectedByShape = 0
        var rejectedBySignificance = 0
        var rejectedByChroma = 0

        for (component in components) {
            if (component.pixelIndices.size !in config.minArea..config.maxArea) {
                rejectedBySize += 1
                continue
            }
            val candidate = component.toCandidate(frame, background)
            if (candidate.aspectRatio < config.minAspectRatio || candidate.fillRatio < config.minFillRatio) {
                rejectedByShape += 1
                continue
            }
            if (candidate.meanSignificance < config.minMeanSignificance) {
                rejectedBySignificance += 1
                continue
            }
            if (candidate.chromaShift > config.chromaShiftTolerance) {
                rejectedByChroma += 1
                continue
            }
            ranked += candidate
        }

        val candidates = ranked.sortedByDescending { it.rankScore }
        val best = candidates.firstOrNull()
        val runnerUp = candidates.getOrNull(1)
        val margin = when {
            best == null -> 0.0
            runnerUp == null || runnerUp.rankScore == 0.0 -> Double.POSITIVE_INFINITY
            else -> best.rankScore / runnerUp.rankScore
        }
        val accepted = best?.takeIf { runnerUp == null || margin >= config.runnerUpMargin }?.candidate

        return ZoneBallDetection(
            acceptedCandidate = accepted,
            debug = debug(
                foregroundFraction = foregroundFraction,
                candidates = candidates.take(3),
                rejectedBySize = rejectedBySize,
                rejectedByShape = rejectedByShape,
                rejectedBySignificance = rejectedBySignificance,
                rejectedByChroma = rejectedByChroma,
                margin = margin,
            ),
        )
    }

    fun reset() {
        calibrationState = ZoneBallCalibrationState.Uncalibrated
        calibrationFrameCount = 0
        calibrationZone = null
        sumY = null
        sumU = null
        sumV = null
        sumSqY = null
        model = null
    }

    private fun ensureCalibrationBuffers(frame: YuvFrame) {
        val size = frame.width * frame.height
        if (sumY?.size == size) return
        sumY = LongArray(size)
        sumU = LongArray(size)
        sumV = LongArray(size)
        sumSqY = LongArray(size)
        calibrationFrameCount = 0
    }

    private fun buildModel(frame: YuvFrame, bounds: PixelBounds): ZoneBackgroundModel {
        val ySum = requireNotNull(sumY)
        val uSum = requireNotNull(sumU)
        val vSum = requireNotNull(sumV)
        val ySqSum = requireNotNull(sumSqY)
        val size = frame.width * frame.height
        val meanY = DoubleArray(size)
        val meanU = DoubleArray(size)
        val meanV = DoubleArray(size)
        val sigmaY = DoubleArray(size) { MIN_SIGMA_Y }
        val zoneSigmas = mutableListOf<Double>()

        for (row in bounds.top..bounds.bottom) {
            for (column in bounds.left..bounds.right) {
                val index = row * frame.width + column
                val mean = ySum[index].toDouble() / calibrationFrameCount.toDouble()
                val meanSq = ySqSum[index].toDouble() / calibrationFrameCount.toDouble()
                val variance = (meanSq - mean * mean).coerceAtLeast(0.0)
                val sigma = sqrt(variance).coerceIn(MIN_SIGMA_Y, MAX_SIGMA_Y)
                meanY[index] = mean
                meanU[index] = uSum[index].toDouble() / calibrationFrameCount.toDouble()
                meanV[index] = vSum[index].toDouble() / calibrationFrameCount.toDouble()
                sigmaY[index] = sigma
                zoneSigmas += sigma
            }
        }

        return ZoneBackgroundModel(
            width = frame.width,
            height = frame.height,
            launchZone = requireNotNull(calibrationZone),
            bounds = bounds,
            meanY = meanY,
            meanU = meanU,
            meanV = meanV,
            sigmaY = sigmaY,
            medianSigmaY = zoneSigmas.median(),
        )
    }

    private fun buildForegroundMask(frame: YuvFrame, background: ZoneBackgroundModel): ForegroundMask {
        val raw = BooleanArray(frame.width * frame.height)
        var rawCount = 0
        for (row in background.bounds.top..background.bounds.bottom) {
            for (column in background.bounds.left..background.bounds.right) {
                val index = row * frame.width + column
                val dY = frame.y[index].unsigned().toDouble() - background.meanY[index]
                val threshold = max(config.minYDelta, config.significanceMultiplier * background.sigmaY[index])
                if (dY >= threshold) {
                    raw[index] = true
                    rawCount += 1
                }
            }
        }

        val despeckled = BooleanArray(raw.size)
        var despeckledCount = 0
        for (row in background.bounds.top..background.bounds.bottom) {
            for (column in background.bounds.left..background.bounds.right) {
                val index = row * frame.width + column
                if (!raw[index]) continue
                val neighbours =
                    included(raw, frame.width, frame.height, column - 1, row) +
                        included(raw, frame.width, frame.height, column + 1, row) +
                        included(raw, frame.width, frame.height, column, row - 1) +
                        included(raw, frame.width, frame.height, column, row + 1)
                if (neighbours >= 2) {
                    despeckled[index] = true
                    despeckledCount += 1
                }
            }
        }

        return ForegroundMask(mask = despeckled, count = despeckledCount.coerceAtLeast(if (rawCount == frame.width * frame.height) rawCount else 0))
    }

    private fun findComponents(
        frame: YuvFrame,
        background: ZoneBackgroundModel,
        mask: BooleanArray,
    ): List<Component> {
        val visited = BooleanArray(mask.size)
        val components = mutableListOf<Component>()
        for (row in background.bounds.top..background.bounds.bottom) {
            for (column in background.bounds.left..background.bounds.right) {
                val index = row * frame.width + column
                if (mask[index] && !visited[index]) {
                    components += floodFill(frame, background.bounds, mask, visited, index)
                }
            }
        }
        return components
    }

    private fun floodFill(
        frame: YuvFrame,
        bounds: PixelBounds,
        mask: BooleanArray,
        visited: BooleanArray,
        startIndex: Int,
    ): Component {
        val queue = ArrayDeque<Int>()
        val pixelIndices = mutableListOf<Int>()
        queue.add(startIndex)
        visited[startIndex] = true
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE

        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % frame.width
            val y = index / frame.width
            pixelIndices += index
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)
            enqueueIfIncluded(index - 1, x > bounds.left, mask, visited, queue)
            enqueueIfIncluded(index + 1, x < bounds.right, mask, visited, queue)
            enqueueIfIncluded(index - frame.width, y > bounds.top, mask, visited, queue)
            enqueueIfIncluded(index + frame.width, y < bounds.bottom, mask, visited, queue)
        }

        return Component(pixelIndices, minX, maxX, minY, maxY)
    }

    private fun enqueueIfIncluded(
        index: Int,
        inBounds: Boolean,
        mask: BooleanArray,
        visited: BooleanArray,
        queue: ArrayDeque<Int>,
    ) {
        if (!inBounds || !mask[index] || visited[index]) return
        visited[index] = true
        queue.add(index)
    }

    private fun Component.toCandidate(frame: YuvFrame, background: ZoneBackgroundModel): ZoneBallCandidateDebug {
        var xSum = 0.0
        var ySum = 0.0
        var significanceSum = 0.0
        var dUSum = 0.0
        var dVSum = 0.0
        for (index in pixelIndices) {
            val x = index % frame.width
            val y = index / frame.width
            xSum += x
            ySum += y
            significanceSum += (frame.y[index].unsigned().toDouble() - background.meanY[index]) / background.sigmaY[index]
            dUSum += frame.u[index].unsigned().toDouble() - background.meanU[index]
            dVSum += frame.v[index].unsigned().toDouble() - background.meanV[index]
        }
        val boxWidth = maxX - minX + 1
        val boxHeight = maxY - minY + 1
        val area = pixelIndices.size
        val fillRatio = area.toDouble() / (boxWidth * boxHeight).toDouble()
        val aspectRatio = min(boxWidth, boxHeight).toDouble() / max(boxWidth, boxHeight).toDouble()
        val meanSignificance = significanceSum / area.toDouble()
        val chromaShift = abs(dUSum / area.toDouble()) + abs(dVSum / area.toDouble())
        val rankScore = meanSignificance * ((aspectRatio + fillRatio) / 2.0)
        val xDenominator = (frame.width - 1).coerceAtLeast(1).toDouble()
        val yDenominator = (frame.height - 1).coerceAtLeast(1).toDouble()

        return ZoneBallCandidateDebug(
            candidate = LumaMotionCandidate(
                x = (xSum / area.toDouble()) / xDenominator,
                y = (ySum / area.toDouble()) / yDenominator,
                pixelCount = area,
                confidence = (rankScore / 20.0).coerceIn(0.0, 1.0),
            ),
            area = area,
            fillRatio = fillRatio,
            aspectRatio = aspectRatio,
            meanSignificance = meanSignificance,
            chromaShift = chromaShift,
            rankScore = rankScore,
        )
    }

    private fun debug(
        foregroundFraction: Double = 0.0,
        backgroundStale: Boolean = false,
        candidates: List<ZoneBallCandidateDebug> = emptyList(),
        rejectedBySize: Int = 0,
        rejectedByShape: Int = 0,
        rejectedBySignificance: Int = 0,
        rejectedByChroma: Int = 0,
        margin: Double = 0.0,
    ): ZoneBallDebug =
        ZoneBallDebug(
            calibrationState = calibrationState,
            calibrationFramesCollected = calibrationFrameCount,
            calibrationFramesRequired = config.calibrationFramesRequired,
            medianSigmaY = model?.medianSigmaY ?: 0.0,
            foregroundFraction = foregroundFraction,
            backgroundStale = backgroundStale,
            candidates = candidates,
            rejectedBySize = rejectedBySize,
            rejectedByShape = rejectedByShape,
            rejectedBySignificance = rejectedBySignificance,
            rejectedByChroma = rejectedByChroma,
            margin = margin,
        )

    private fun ZoneBackgroundModel.matches(frame: YuvFrame, launchZone: LaunchZone): Boolean =
        width == frame.width && height == frame.height && this.launchZone == launchZone

    private fun LaunchZone.bounds(frame: YuvFrame): PixelBounds {
        val maxX = frame.width - 1
        val maxY = frame.height - 1
        val leftPx = (left * maxX).toInt().coerceIn(0, maxX)
        val rightPx = ((left + width) * maxX).toInt().coerceIn(leftPx, maxX)
        val topPx = (top * maxY).toInt().coerceIn(0, maxY)
        val bottomPx = ((top + height) * maxY).toInt().coerceIn(topPx, maxY)
        return PixelBounds(left = leftPx, right = rightPx, top = topPx, bottom = bottomPx)
    }

    private fun included(mask: BooleanArray, width: Int, height: Int, x: Int, y: Int): Int =
        if (x in 0 until width && y in 0 until height && mask[y * width + x]) 1 else 0

    private fun Byte.unsigned(): Int = toInt() and 0xFF

    private fun List<Double>.median(): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private data class PixelBounds(val left: Int, val right: Int, val top: Int, val bottom: Int) {
        val area: Int = (right - left + 1) * (bottom - top + 1)
    }

    private data class ForegroundMask(val mask: BooleanArray, val count: Int)

    private data class Component(
        val pixelIndices: List<Int>,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
    )

    private data class ZoneBackgroundModel(
        val width: Int,
        val height: Int,
        val launchZone: LaunchZone,
        val bounds: PixelBounds,
        val meanY: DoubleArray,
        val meanU: DoubleArray,
        val meanV: DoubleArray,
        val sigmaY: DoubleArray,
        val medianSigmaY: Double,
    )

    private companion object {
        const val MIN_SIGMA_Y = 2.0
        const val MAX_SIGMA_Y = 12.0
    }
}
