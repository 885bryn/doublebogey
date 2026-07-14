package com.doublebogey.golftracer.camera

import java.util.Arrays
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class BallBlobProposal(
    val centerX: Double,
    val centerY: Double,
    val radiusPx: Double,
    val response: Double,
)

data class BallBlobProposerConfig(
    val maxProposals: Int = 8,
    val minNormalizedResponse: Double = 6.0,
    val nmsRadiusMultiplier: Double = 1.0,
) {
    init {
        require(maxProposals >= 0)
        require(minNormalizedResponse >= 0.0)
        require(nmsRadiusMultiplier >= 0.0)
    }
}

class BallBlobProposer(
    private val config: BallBlobProposerConfig = BallBlobProposerConfig(),
) {
    fun propose(frame: YuvFrame, radiiPx: List<Double>): List<BallBlobProposal> {
        val proposalLimit = min(config.maxProposals, MAX_DOWNSTREAM_PROPOSALS)
        if (proposalLimit == 0 || radiiPx.isEmpty()) return emptyList()

        val pixelCount = frame.width * frame.height
        val innerHorizontal = DoubleArray(pixelCount)
        val innerBlur = DoubleArray(pixelCount)
        val outerHorizontal = DoubleArray(pixelCount)
        val outerBlur = DoubleArray(pixelCount)
        val sampleResponses = DoubleArray(((frame.width + 3) / 4) * ((frame.height + 3) / 4))
        val maximaIndices = IntArray(pixelCount)
        val candidates = ArrayList<BallBlobProposal>()

        for (radiusPx in radiiPx) {
            if (!radiusPx.isFinite() || radiusPx <= 0.0) continue

            val innerKernel = gaussianKernel(max(0.8, 0.55 * radiusPx))
            val outerKernel = gaussianKernel(max(1.2, 1.10 * radiusPx))
            convolveHorizontal(frame.y, frame.width, frame.height, innerKernel, innerHorizontal)
            convolveVertical(innerHorizontal, frame.width, frame.height, innerKernel, innerBlur)
            convolveHorizontal(frame.y, frame.width, frame.height, outerKernel, outerHorizontal)
            convolveVertical(outerHorizontal, frame.width, frame.height, outerKernel, outerBlur)

            val scaleNormalization = sqrt(radiusPx)
            var pixelIndex = 0
            while (pixelIndex < pixelCount) {
                innerHorizontal[pixelIndex] =
                    abs(innerBlur[pixelIndex] - outerBlur[pixelIndex]) * scaleNormalization
                pixelIndex += 1
            }

            val sampleCount = sampleEveryFourthPixel(
                innerHorizontal,
                frame.width,
                frame.height,
                sampleResponses,
            )
            val medianResponse = medianInPlace(sampleResponses, sampleCount)
            val threshold = max(config.minNormalizedResponse, 3.0 * medianResponse)
            val border = ceil(2.2 * radiusPx).toInt()
            val maximaCount = findLocalMaxima(
                responses = innerHorizontal,
                width = frame.width,
                height = frame.height,
                border = border,
                threshold = threshold,
                maximaIndices = maximaIndices,
            )

            var maximum = 0
            while (maximum < maximaCount) {
                val index = maximaIndices[maximum]
                candidates.add(
                    BallBlobProposal(
                        centerX = (index % frame.width).toDouble(),
                        centerY = (index / frame.width).toDouble(),
                        radiusPx = radiusPx,
                        response = innerHorizontal[index],
                    ),
                )
                maximum += 1
            }
        }

        candidates.sortWith(
            compareByDescending<BallBlobProposal> { it.response }
                .thenBy { it.centerY }
                .thenBy { it.centerX }
                .thenBy { it.radiusPx },
        )

        val selected = ArrayList<BallBlobProposal>(proposalLimit)
        for (candidate in candidates) {
            var suppressed = false
            var selectedIndex = 0
            while (selectedIndex < selected.size && !suppressed) {
                val existing = selected[selectedIndex]
                val dx = existing.centerX - candidate.centerX
                val dy = existing.centerY - candidate.centerY
                val suppressionRadius =
                    config.nmsRadiusMultiplier * max(existing.radiusPx, candidate.radiusPx)
                suppressed = dx * dx + dy * dy <= suppressionRadius * suppressionRadius
                selectedIndex += 1
            }
            if (!suppressed) {
                selected.add(candidate)
                if (selected.size == proposalLimit) break
            }
        }
        return selected
    }

    private fun gaussianKernel(sigma: Double): DoubleArray {
        val radius = ceil(3.0 * sigma).toInt()
        val kernel = DoubleArray(radius * 2 + 1)
        val denominator = 2.0 * sigma * sigma
        var sum = 0.0
        var offset = -radius
        while (offset <= radius) {
            val weight = exp(-(offset * offset).toDouble() / denominator)
            kernel[offset + radius] = weight
            sum += weight
            offset += 1
        }
        var index = 0
        while (index < kernel.size) {
            kernel[index] /= sum
            index += 1
        }
        return kernel
    }

    private fun convolveHorizontal(
        source: ByteArray,
        width: Int,
        height: Int,
        kernel: DoubleArray,
        destination: DoubleArray,
    ) {
        val kernelRadius = kernel.size / 2
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                var value = 0.0
                var offset = -kernelRadius
                while (offset <= kernelRadius) {
                    val sourceX = (x + offset).coerceIn(0, width - 1)
                    value += (source[y * width + sourceX].toInt() and 0xff) *
                        kernel[offset + kernelRadius]
                    offset += 1
                }
                destination[y * width + x] = value
                x += 1
            }
            y += 1
        }
    }

    private fun convolveVertical(
        source: DoubleArray,
        width: Int,
        height: Int,
        kernel: DoubleArray,
        destination: DoubleArray,
    ) {
        val kernelRadius = kernel.size / 2
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                var value = 0.0
                var offset = -kernelRadius
                while (offset <= kernelRadius) {
                    val sourceY = (y + offset).coerceIn(0, height - 1)
                    value += source[sourceY * width + x] * kernel[offset + kernelRadius]
                    offset += 1
                }
                destination[y * width + x] = value
                x += 1
            }
            y += 1
        }
    }

    private fun sampleEveryFourthPixel(
        responses: DoubleArray,
        width: Int,
        height: Int,
        samples: DoubleArray,
    ): Int {
        var sampleCount = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                samples[sampleCount] = responses[y * width + x]
                sampleCount += 1
                x += 4
            }
            y += 4
        }
        return sampleCount
    }

    private fun medianInPlace(values: DoubleArray, size: Int): Double {
        if (size == 0) return 0.0
        Arrays.sort(values, 0, size)
        val middle = size / 2
        return if (size % 2 == 0) {
            (values[middle - 1] + values[middle]) / 2.0
        } else {
            values[middle]
        }
    }

    private fun findLocalMaxima(
        responses: DoubleArray,
        width: Int,
        height: Int,
        border: Int,
        threshold: Double,
        maximaIndices: IntArray,
    ): Int {
        if (border >= width - border || border >= height - border) return 0

        var maximaCount = 0
        var y = border
        while (y < height - border) {
            var x = border
            while (x < width - border) {
                val index = y * width + x
                val response = responses[index]
                if (response > threshold && isLocalMaximum(responses, width, index, response)) {
                    maximaIndices[maximaCount] = index
                    maximaCount += 1
                }
                x += 1
            }
            y += 1
        }
        return maximaCount
    }

    private fun isLocalMaximum(
        responses: DoubleArray,
        width: Int,
        index: Int,
        response: Double,
    ): Boolean {
        var dy = -1
        while (dy <= 1) {
            var dx = -1
            while (dx <= 1) {
                if (dx != 0 || dy != 0) {
                    val neighborIndex = index + dy * width + dx
                    val neighbor = responses[neighborIndex]
                    if (neighbor > response || (neighbor == response && neighborIndex < index)) return false
                }
                dx += 1
            }
            dy += 1
        }
        return true
    }

    private companion object {
        const val MAX_DOWNSTREAM_PROPOSALS = 8
    }
}
