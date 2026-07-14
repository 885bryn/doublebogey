package com.doublebogey.golftracer.camera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class BallShapeRejection {
    LowContrast,
    InteriorTooTextured,
    InsufficientClosedEdge,
    PoorRadialAlignment,
    RadiusInconsistent,
    LineContinuation,
    StrongChromaShift,
}

data class BallShapeMetrics(
    val annulusContrast: Double,
    val interiorUniformity: Double,
    val closedEdgeCoverage: Double,
    val radialAlignment: Double,
    val radiusVariation: Double,
    val lineContinuation: Double,
    val chromaShift: Double,
)

data class BallShapeEvaluation(
    val proposal: BallBlobProposal,
    val metrics: BallShapeMetrics,
    val score: Double,
    val accepted: Boolean,
    val rejection: BallShapeRejection?,
)

data class BallShapeVerifierConfig(
    val angularSectors: Int = 32,
    val minAnnulusContrast: Double = 2.0,
    val maxInteriorUniformity: Double = 0.95,
    val minClosedEdgeCoverage: Double = 0.56,
    val minRadialAlignment: Double = 0.60,
    val maxRadiusVariation: Double = 0.35,
    val maxLineContinuation: Double = 0.35,
    val chromaShiftTolerance: Double = 45.0,
    val sigmaFloor: Double = 6.0,
) {
    init {
        require(angularSectors > 0)
        require(minAnnulusContrast >= 0.0)
        require(maxInteriorUniformity >= 0.0)
        require(minClosedEdgeCoverage in 0.0..1.0)
        require(minRadialAlignment in 0.0..1.0)
        require(maxRadiusVariation >= 0.0)
        require(maxLineContinuation in 0.0..1.0)
        require(chromaShiftTolerance >= 0.0)
        require(sigmaFloor > 0.0)
    }
}

class BallShapeVerifier(
    private val config: BallShapeVerifierConfig = BallShapeVerifierConfig(),
) {
    fun verify(frame: YuvFrame, proposal: BallBlobProposal): BallShapeEvaluation {
        val regionStats = measureRegions(frame, proposal)
        val annulusContrast = abs(regionStats.interiorY.mean - regionStats.annulusY.mean)
        val interiorUniformity = regionStats.interiorY.standardDeviation /
            max(config.sigmaFloor, abs(regionStats.interiorY.mean - regionStats.annulusY.mean))
        val chromaShift = hypot(
            regionStats.interiorU.mean - regionStats.annulusU.mean,
            regionStats.interiorV.mean - regionStats.annulusV.mean,
        )

        val edgeStats = measureEdges(frame, proposal, regionStats.annulusY.standardDeviation)
        val metrics = BallShapeMetrics(
            annulusContrast = annulusContrast,
            interiorUniformity = interiorUniformity,
            closedEdgeCoverage = edgeStats.coverage,
            radialAlignment = edgeStats.radialAlignment,
            radiusVariation = edgeStats.radiusVariation,
            lineContinuation = edgeStats.lineContinuation,
            chromaShift = chromaShift,
        )
        val score = annulusContrast * edgeStats.coverage * edgeStats.radialAlignment *
            (1.0 - interiorUniformity.coerceIn(0.0, 0.95)) *
            (1.0 - edgeStats.lineContinuation.coerceIn(0.0, 0.95))
        val rejection = when {
            annulusContrast < config.minAnnulusContrast -> BallShapeRejection.LowContrast
            interiorUniformity > config.maxInteriorUniformity -> BallShapeRejection.InteriorTooTextured
            edgeStats.coverage < config.minClosedEdgeCoverage -> BallShapeRejection.InsufficientClosedEdge
            edgeStats.radialAlignment < config.minRadialAlignment -> BallShapeRejection.PoorRadialAlignment
            edgeStats.radiusVariation > config.maxRadiusVariation -> BallShapeRejection.RadiusInconsistent
            edgeStats.lineContinuation > config.maxLineContinuation -> BallShapeRejection.LineContinuation
            chromaShift > config.chromaShiftTolerance -> BallShapeRejection.StrongChromaShift
            else -> null
        }
        return BallShapeEvaluation(
            proposal = proposal,
            metrics = metrics,
            score = score,
            accepted = rejection == null,
            rejection = rejection,
        )
    }

    private fun measureRegions(frame: YuvFrame, proposal: BallBlobProposal): RegionStats {
        val interiorY = RunningStats()
        val interiorU = RunningStats()
        val interiorV = RunningStats()
        val annulusY = RunningStats()
        val annulusU = RunningStats()
        val annulusV = RunningStats()
        val outerRadius = 2.2 * proposal.radiusPx
        val left = floor(proposal.centerX - outerRadius).toInt().coerceAtLeast(0)
        val right = floor(proposal.centerX + outerRadius).toInt().coerceAtMost(frame.width - 1)
        val top = floor(proposal.centerY - outerRadius).toInt().coerceAtLeast(0)
        val bottom = floor(proposal.centerY + outerRadius).toInt().coerceAtMost(frame.height - 1)

        for (y in top..bottom) {
            for (x in left..right) {
                val radius = hypot(x - proposal.centerX, y - proposal.centerY) / proposal.radiusPx
                val index = y * frame.width + x
                when {
                    radius <= 0.60 -> {
                        interiorY.add(unsigned(frame.y[index]))
                        interiorU.add(unsigned(frame.u[index]))
                        interiorV.add(unsigned(frame.v[index]))
                    }
                    radius in 1.3..2.2 -> {
                        annulusY.add(unsigned(frame.y[index]))
                        annulusU.add(unsigned(frame.u[index]))
                        annulusV.add(unsigned(frame.v[index]))
                    }
                }
            }
        }
        return RegionStats(interiorY, interiorU, interiorV, annulusY, annulusU, annulusV)
    }

    private fun measureEdges(
        frame: YuvFrame,
        proposal: BallBlobProposal,
        annulusSigma: Double,
    ): EdgeStats {
        val supportThreshold = max(8.0, 1.5 * annulusSigma)
        val supportedRadii = DoubleArray(config.angularSectors)
        var supported = 0
        var alignmentSum = 0.0
        var continuationCount = 0

        for (sector in 0 until config.angularSectors) {
            val theta = 2.0 * PI * sector / config.angularSectors
            val radialX = cos(theta)
            val radialY = sin(theta)
            var bestMagnitude = 0.0
            var bestAlignment = 0.0
            var bestRadius = 0.0
            for (radiusFactor in RIM_RADIUS_FACTORS) {
                val gradient = gradient(
                    frame,
                    proposal.centerX + radialX * proposal.radiusPx * radiusFactor,
                    proposal.centerY + radialY * proposal.radiusPx * radiusFactor,
                )
                if (gradient.magnitude > bestMagnitude) {
                    bestMagnitude = gradient.magnitude
                    bestAlignment = abs(gradient.x * radialX + gradient.y * radialY) /
                        max(gradient.magnitude, 1e-6)
                    bestRadius = proposal.radiusPx * radiusFactor
                }
            }
            if (bestMagnitude >= supportThreshold && bestAlignment >= MIN_SUPPORT_ALIGNMENT) {
                supportedRadii[supported] = bestRadius
                supported += 1
                alignmentSum += bestAlignment
                if (continuesBeyondRim(frame, proposal, radialX, radialY, bestMagnitude)) {
                    continuationCount += 1
                }
            }
        }

        val coverage = supported.toDouble() / config.angularSectors
        val radialAlignment = if (supported == 0) 0.0 else alignmentSum / supported
        val radiusVariation = coefficientOfVariation(supportedRadii, supported)
        val lineContinuation = continuationCount.toDouble() / config.angularSectors
        return EdgeStats(coverage, radialAlignment, radiusVariation, lineContinuation)
    }

    private fun continuesBeyondRim(
        frame: YuvFrame,
        proposal: BallBlobProposal,
        radialX: Double,
        radialY: Double,
        rimMagnitude: Double,
    ): Boolean = CONTINUATION_RADIUS_FACTORS.any { radiusFactor ->
        gradient(
            frame,
            proposal.centerX + radialX * proposal.radiusPx * radiusFactor,
            proposal.centerY + radialY * proposal.radiusPx * radiusFactor,
        ).magnitude >= CONTINUATION_MAGNITUDE_RATIO * rimMagnitude
    }

    private fun gradient(frame: YuvFrame, x: Double, y: Double): Gradient {
        val gx = (sample(frame.y, frame.width, frame.height, x + 1.0, y) -
            sample(frame.y, frame.width, frame.height, x - 1.0, y)) / 2.0
        val gy = (sample(frame.y, frame.width, frame.height, x, y + 1.0) -
            sample(frame.y, frame.width, frame.height, x, y - 1.0)) / 2.0
        return Gradient(gx, gy, hypot(gx, gy))
    }

    private fun sample(
        plane: ByteArray,
        width: Int,
        height: Int,
        x: Double,
        y: Double,
    ): Double {
        val boundedX = x.coerceIn(0.0, (width - 1).toDouble())
        val boundedY = y.coerceIn(0.0, (height - 1).toDouble())
        val x0 = floor(boundedX).toInt()
        val y0 = floor(boundedY).toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val dx = boundedX - x0
        val dy = boundedY - y0
        val top = unsigned(plane[y0 * width + x0]) * (1.0 - dx) +
            unsigned(plane[y0 * width + x1]) * dx
        val bottom = unsigned(plane[y1 * width + x0]) * (1.0 - dx) +
            unsigned(plane[y1 * width + x1]) * dx
        return top * (1.0 - dy) + bottom * dy
    }

    private fun coefficientOfVariation(values: DoubleArray, size: Int): Double {
        if (size == 0) return Double.POSITIVE_INFINITY
        var mean = 0.0
        for (index in 0 until size) mean += values[index]
        mean /= size
        if (mean <= 1e-6) return Double.POSITIVE_INFINITY
        var squaredDeviation = 0.0
        for (index in 0 until size) {
            val deviation = values[index] - mean
            squaredDeviation += deviation * deviation
        }
        return sqrt(squaredDeviation / size) / mean
    }

    private fun unsigned(value: Byte): Double = (value.toInt() and 0xff).toDouble()

    private data class Gradient(val x: Double, val y: Double, val magnitude: Double)

    private data class EdgeStats(
        val coverage: Double,
        val radialAlignment: Double,
        val radiusVariation: Double,
        val lineContinuation: Double,
    )

    private data class RegionStats(
        val interiorY: RunningStats,
        val interiorU: RunningStats,
        val interiorV: RunningStats,
        val annulusY: RunningStats,
        val annulusU: RunningStats,
        val annulusV: RunningStats,
    )

    private class RunningStats {
        var count: Int = 0
            private set
        var mean: Double = 0.0
            private set
        private var sumSquaredDifferences: Double = 0.0

        val standardDeviation: Double
            get() = if (count == 0) 0.0 else sqrt(sumSquaredDifferences / count)

        fun add(value: Double) {
            count += 1
            val delta = value - mean
            mean += delta / count
            sumSquaredDifferences += delta * (value - mean)
        }
    }

    private companion object {
        const val MIN_SUPPORT_ALIGNMENT = 0.55
        const val CONTINUATION_MAGNITUDE_RATIO = 0.70
        val RIM_RADIUS_FACTORS = doubleArrayOf(0.65, 0.85, 1.05, 1.25, 1.45)
        val CONTINUATION_RADIUS_FACTORS = doubleArrayOf(1.7, 2.1, 2.5)
    }
}
