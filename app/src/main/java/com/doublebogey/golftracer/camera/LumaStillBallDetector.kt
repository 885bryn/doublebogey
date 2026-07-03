package com.doublebogey.golftracer.camera

import kotlin.math.hypot

data class LumaStillBallDetectorConfig(
    val contrastThreshold: Int = 45,
    val minBlobPixels: Int = 2,
    val maxBlobPixels: Int = 90,
    val fragmentMergeDistance: Double = 0.08,
) {
    init {
        require(contrastThreshold in 0..255) { "contrastThreshold must be in 0..255" }
        require(minBlobPixels > 0) { "minBlobPixels must be greater than 0" }
        require(maxBlobPixels >= minBlobPixels) { "maxBlobPixels must be at least minBlobPixels" }
        require(fragmentMergeDistance > 0.0) { "fragmentMergeDistance must be greater than 0" }
    }
}

class LumaStillBallDetector(
    private val config: LumaStillBallDetectorConfig = LumaStillBallDetectorConfig(),
) {
    fun analyzeFrame(frame: LumaFrame, launchZone: LaunchZone): List<LumaMotionCandidate> {
        val mask = BooleanArray(frame.luma.size)
        if (frame.width <= BACKGROUND_SAMPLE_RADIUS * 2 || frame.height <= BACKGROUND_SAMPLE_RADIUS * 2) {
            return emptyList()
        }

        val minX = BACKGROUND_SAMPLE_RADIUS
        val maxX = frame.width - BACKGROUND_SAMPLE_RADIUS - 1
        val minY = BACKGROUND_SAMPLE_RADIUS
        val maxY = frame.height - BACKGROUND_SAMPLE_RADIUS - 1
        val left = (launchZone.left * (frame.width - 1)).toInt().coerceIn(minX, maxX)
        val right = ((launchZone.left + launchZone.width) * (frame.width - 1)).toInt().coerceIn(minX, maxX)
        val top = (launchZone.top * (frame.height - 1)).toInt().coerceIn(minY, maxY)
        val bottom = ((launchZone.top + launchZone.height) * (frame.height - 1)).toInt().coerceIn(minY, maxY)

        for (y in top..bottom) {
            for (x in left..right) {
                val index = y * frame.width + x
                val center = frame.luma[index].toInt() and 0xFF
                val localBackground = localBackground(frame, x, y)
                if (center - localBackground >= config.contrastThreshold) {
                    mask[index] = true
                }
            }
        }

        val fragments = findComponents(frame, mask)
            .filter { component -> component.pixelCount in config.minBlobPixels..config.maxBlobPixels }
            .map { component -> component.toCandidate(frame) }

        return mergeFragments(fragments)
            .maxWithOrNull(compareBy<LumaMotionCandidate> { candidate -> candidate.pixelCount }.thenBy { candidate -> candidate.confidence })
            ?.let(::listOf)
            .orEmpty()
    }

    private fun findComponents(frame: LumaFrame, mask: BooleanArray): List<Component> {
        val visited = BooleanArray(mask.size)
        val components = mutableListOf<Component>()
        for (index in mask.indices) {
            if (mask[index] && !visited[index]) {
                components += floodFill(index, frame, mask, visited)
            }
        }
        return components
    }

    private fun floodFill(
        startIndex: Int,
        frame: LumaFrame,
        mask: BooleanArray,
        visited: BooleanArray,
    ): Component {
        val queue = ArrayDeque<Int>()
        queue.add(startIndex)
        visited[startIndex] = true

        var pixelCount = 0
        var xSum = 0.0
        var ySum = 0.0
        var contrastSum = 0.0

        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % frame.width
            val y = index / frame.width
            val center = frame.luma[index].toInt() and 0xFF
            val localBackground = localBackground(frame, x, y)

            pixelCount += 1
            xSum += x
            ySum += y
            contrastSum += (center - localBackground).coerceAtLeast(0)

            enqueueIfIncluded(index - 1, x > 0, mask, visited, queue)
            enqueueIfIncluded(index + 1, x < frame.width - 1, mask, visited, queue)
            enqueueIfIncluded(index - frame.width, y > 0, mask, visited, queue)
            enqueueIfIncluded(index + frame.width, y < frame.height - 1, mask, visited, queue)
        }

        return Component(
            pixelCount = pixelCount,
            xSum = xSum,
            ySum = ySum,
            contrastSum = contrastSum,
        )
    }

    private fun enqueueIfIncluded(
        index: Int,
        inBounds: Boolean,
        mask: BooleanArray,
        visited: BooleanArray,
        queue: ArrayDeque<Int>,
    ) {
        if (!inBounds || !mask[index] || visited[index]) {
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
            confidence = ((contrastSum / pixelCount) / 255.0).coerceIn(0.0, 1.0),
        )
    }

    private fun mergeFragments(fragments: List<LumaMotionCandidate>): List<LumaMotionCandidate> {
        val clusters = mutableListOf<CandidateCluster>()
        for (fragment in fragments) {
            val cluster = clusters.firstOrNull { existing ->
                distance(existing.x, existing.y, fragment.x, fragment.y) <= config.fragmentMergeDistance
            }
            if (cluster == null) {
                clusters += CandidateCluster.from(fragment)
            } else {
                cluster.add(fragment)
            }
        }
        return clusters.map { cluster -> cluster.toCandidate() }
    }

    private fun distance(ax: Double, ay: Double, bx: Double, by: Double): Double =
        hypot(ax - bx, ay - by)

    private fun localBackground(frame: LumaFrame, x: Int, y: Int): Int {
        val radius = BACKGROUND_SAMPLE_RADIUS
        return (
            lumaAt(frame, x - radius, y) +
                lumaAt(frame, x + radius, y) +
                lumaAt(frame, x, y - radius) +
                lumaAt(frame, x, y + radius) +
                lumaAt(frame, x - radius, y - radius) +
                lumaAt(frame, x + radius, y - radius) +
                lumaAt(frame, x - radius, y + radius) +
                lumaAt(frame, x + radius, y + radius)
            ) / 8
    }

    private fun lumaAt(frame: LumaFrame, x: Int, y: Int): Int =
        frame.luma[y * frame.width + x].toInt() and 0xFF

    private class CandidateCluster private constructor(
        private var xSum: Double,
        private var ySum: Double,
        private var confidenceSum: Double,
        private var totalPixels: Int,
    ) {
        val pixelCount: Int
            get() = totalPixels
        val x: Double
            get() = xSum / totalPixels
        val y: Double
            get() = ySum / totalPixels

        fun add(candidate: LumaMotionCandidate) {
            xSum += candidate.x * candidate.pixelCount
            ySum += candidate.y * candidate.pixelCount
            confidenceSum += candidate.confidence * candidate.pixelCount
            totalPixels += candidate.pixelCount
        }

        fun toCandidate(): LumaMotionCandidate =
            LumaMotionCandidate(
                x = x,
                y = y,
                pixelCount = pixelCount,
                confidence = confidenceSum / pixelCount,
            )

        companion object {
            fun from(candidate: LumaMotionCandidate): CandidateCluster =
                CandidateCluster(
                    xSum = candidate.x * candidate.pixelCount,
                    ySum = candidate.y * candidate.pixelCount,
                    confidenceSum = candidate.confidence * candidate.pixelCount,
                    totalPixels = candidate.pixelCount,
                )
        }
    }

    private companion object {
        const val BACKGROUND_SAMPLE_RADIUS = 2
    }

    private data class Component(
        val pixelCount: Int,
        val xSum: Double,
        val ySum: Double,
        val contrastSum: Double,
    )
}