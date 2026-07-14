package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertTrue

class FieldBallFixtureTest {
    private val verifier = BallShapeVerifier()
    private val proposer = BallBlobProposer()

    @Test
    fun realRangeBallPassesShapeVerification() {
        val evaluations = evaluations("range-ball.png")

        assertTrue(evaluations.any { it.accepted && it.proposal.response > 0.0 }, evaluations.summary())
    }

    @Test
    fun realRangeMatCurveIsRejectedAsOpenOrContinuingShape() {
        val evaluations = evaluations("range-mat-curve.png")
        val openShapeRejections = setOf(
            BallShapeRejection.InsufficientClosedEdge,
            BallShapeRejection.LineContinuation,
        )

        assertTrue(evaluations.none { it.accepted }, evaluations.summary())
        val strongestProductionProposal = evaluations
            .filter { it.proposal.response > 0.0 }
            .maxBy { it.proposal.response }
        assertTrue(strongestProductionProposal.rejection in openShapeRejections, evaluations.summary())
    }

    private fun evaluations(name: String): List<BallShapeEvaluation> {
        val frame = loadImageIoFixture(name)
        val radii = (3..9).map(Int::toDouble)
        val centerX = (frame.width - 1) / 2.0
        val centerY = (frame.height - 1) / 2.0
        val proposals = proposer.propose(frame, radii) + buildList {
            for (dy in -3..3 step 3) {
                for (dx in -3..3 step 3) {
                    for (radius in radii) {
                        add(BallBlobProposal(centerX + dx, centerY + dy, radius, 0.0))
                    }
                }
            }
        }
        return proposals.distinctBy { Triple(it.centerX to it.centerY, it.radiusPx, it.response) }
            .map { verifier.verify(frame, it) }
    }

    private fun loadImageIoFixture(name: String): YuvFrame {
        val input = requireNotNull(javaClass.getResourceAsStream("/field/$name"))
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val image = requireNotNull(
            input.use { imageIo.getMethod("read", java.io.InputStream::class.java).invoke(null, it) },
        ) { "ImageIO could not decode field fixture: $name" }
        val imageClass = image.javaClass
        val width = imageClass.getMethod("getWidth").invoke(image) as Int
        val height = imageClass.getMethod("getHeight").invoke(image) as Int
        val getRgb = imageClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val y = ByteArray(width * height)
        val u = ByteArray(width * height)
        val v = ByteArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val rgb = getRgb.invoke(image, column, row) as Int
                val red = rgb shr 16 and 0xff
                val green = rgb shr 8 and 0xff
                val blue = rgb and 0xff
                val index = row * width + column
                y[index] = kotlin.math.round(0.299 * red + 0.587 * green + 0.114 * blue).toInt().toByte()
                u[index] = kotlin.math.round(-0.169 * red - 0.331 * green + 0.500 * blue + 128.0)
                    .toInt().coerceIn(0, 255).toByte()
                v[index] = kotlin.math.round(0.500 * red - 0.419 * green - 0.081 * blue + 128.0)
                    .toInt().coerceIn(0, 255).toByte()
            }
        }
        return YuvFrame(width, height, y, u, v)
    }
    private fun List<BallShapeEvaluation>.summary(): String =
        sortedByDescending { it.score }.take(12).joinToString(separator = "\n")
}
