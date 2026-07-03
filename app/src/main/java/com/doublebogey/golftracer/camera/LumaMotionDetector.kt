package com.doublebogey.golftracer.camera

import kotlin.math.abs

data class LumaFrame(
    val width: Int,
    val height: Int,
    val luma: ByteArray,
) {
    init {
        require(width > 0) { "width must be greater than 0" }
        require(height > 0) { "height must be greater than 0" }
        require(luma.size == width * height) { "luma size must equal width * height" }
    }
}

data class LumaMotionDetectorConfig(
    val lumaThreshold: Int = 180,
    val motionThreshold: Int = 24,
    val minBlobPixels: Int = 2,
    val maxBlobPixels: Int = 80,
    val maxMovingPixelFraction: Double = 0.20,
) {
    init {
        require(lumaThreshold in 0..255) { "lumaThreshold must be in 0..255" }
        require(motionThreshold in 0..255) { "motionThreshold must be in 0..255" }
        require(minBlobPixels > 0) { "minBlobPixels must be greater than 0" }
        require(maxBlobPixels >= minBlobPixels) { "maxBlobPixels must be at least minBlobPixels" }
        require(maxMovingPixelFraction > 0.0 && maxMovingPixelFraction <= 1.0) {
            "maxMovingPixelFraction must be in (0, 1]"
        }
    }
}

data class LumaMotionCandidate(
    val x: Double,
    val y: Double,
    val pixelCount: Int,
    val confidence: Double,
)

data class LumaMotionResult(
    val timestampNs: Long,
    val candidates: List<LumaMotionCandidate>,
    val matchedPixels: Int,
    val rejectedLargeBlobPixels: Int,
    val rejectedGlobalMotionPixels: Int,
    val stillCandidates: List<LumaMotionCandidate> = emptyList(),
    val stillBallDebug: StillBallDebug = StillBallDebug(),
)

class LumaMotionDetector(
    private val config: LumaMotionDetectorConfig = LumaMotionDetectorConfig(),
) {
    private var previousFrame: LumaFrame? = null

    fun analyzeFrame(frame: LumaFrame, timestampNs: Long): LumaMotionResult {
        val previous = previousFrame
        previousFrame = frame.copy(luma = frame.luma.copyOf())

        if (previous == null || previous.width != frame.width || previous.height != frame.height) {
            return LumaMotionResult(
                timestampNs = timestampNs,
                candidates = emptyList(),
                matchedPixels = 0,
                rejectedLargeBlobPixels = 0,
                rejectedGlobalMotionPixels = 0,
            )
        }

        val motionMask = BooleanArray(frame.luma.size)
        var matchedPixels = 0

        for (index in frame.luma.indices) {
            val currentLuma = frame.luma[index].toInt() and 0xFF
            val previousLuma = previous.luma[index].toInt() and 0xFF
            val isMovingPixel = abs(currentLuma - previousLuma) >= config.motionThreshold
            if (isMovingPixel) {
                motionMask[index] = true
                matchedPixels += 1
            }
        }

        if (matchedPixels.toDouble() / frame.luma.size.toDouble() > config.maxMovingPixelFraction) {
            return LumaMotionResult(
                timestampNs = timestampNs,
                candidates = emptyList(),
                matchedPixels = matchedPixels,
                rejectedLargeBlobPixels = 0,
                rejectedGlobalMotionPixels = matchedPixels,
            )
        }

        val components = findComponents(frame, motionMask)
        val candidates = mutableListOf<LumaMotionCandidate>()
        var rejectedLargeBlobPixels = 0

        for (component in components) {
            if (component.pixelCount > config.maxBlobPixels) {
                rejectedLargeBlobPixels += component.pixelCount
                continue
            }
            if (component.pixelCount < config.minBlobPixels) {
                continue
            }

            candidates += component.toCandidate(frame)
        }

        return LumaMotionResult(
            timestampNs = timestampNs,
            candidates = candidates,
            matchedPixels = matchedPixels,
            rejectedLargeBlobPixels = rejectedLargeBlobPixels,
            rejectedGlobalMotionPixels = 0,
        )
    }

    fun reset() {
        previousFrame = null
    }

    private fun findComponents(frame: LumaFrame, motionMask: BooleanArray): List<Component> {
        val visited = BooleanArray(motionMask.size)
        val components = mutableListOf<Component>()

        for (index in motionMask.indices) {
            if (!motionMask[index] || visited[index]) {
                continue
            }
            components += floodFillComponent(index, frame, motionMask, visited)
        }

        return components
    }

    private fun floodFillComponent(
        startIndex: Int,
        frame: LumaFrame,
        motionMask: BooleanArray,
        visited: BooleanArray,
    ): Component {
        val queue = ArrayDeque<Int>()
        queue.add(startIndex)
        visited[startIndex] = true

        var pixelCount = 0
        var xSum = 0.0
        var ySum = 0.0
        var lumaSum = 0.0

        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % frame.width
            val y = index / frame.width

            pixelCount += 1
            xSum += x
            ySum += y
            lumaSum += frame.luma[index].toInt() and 0xFF

            enqueueIfIncluded(index - 1, x > 0, motionMask, visited, queue)
            enqueueIfIncluded(index + 1, x < frame.width - 1, motionMask, visited, queue)
            enqueueIfIncluded(index - frame.width, y > 0, motionMask, visited, queue)
            enqueueIfIncluded(index + frame.width, y < frame.height - 1, motionMask, visited, queue)
        }

        return Component(
            pixelCount = pixelCount,
            xSum = xSum,
            ySum = ySum,
            lumaSum = lumaSum,
        )
    }

    private fun enqueueIfIncluded(
        index: Int,
        inBounds: Boolean,
        motionMask: BooleanArray,
        visited: BooleanArray,
        queue: ArrayDeque<Int>,
    ) {
        if (!inBounds || !motionMask[index] || visited[index]) {
            return
        }

        visited[index] = true
        queue.add(index)
    }

    private fun Component.toCandidate(frame: LumaFrame): LumaMotionCandidate {
        val xDenominator = (frame.width - 1).coerceAtLeast(1).toDouble()
        val yDenominator = (frame.height - 1).coerceAtLeast(1).toDouble()
        return LumaMotionCandidate(
            x = (xSum / pixelCount) / xDenominator,
            y = (ySum / pixelCount) / yDenominator,
            pixelCount = pixelCount,
            confidence = (lumaSum / pixelCount) / 255.0,
        )
    }

    private data class Component(
        val pixelCount: Int,
        val xSum: Double,
        val ySum: Double,
        val lumaSum: Double,
    )
}
