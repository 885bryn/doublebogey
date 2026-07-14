package com.doublebogey.golftracer.camera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BallShapeVerifierTest {
    private val verifier = BallShapeVerifier()
    private val proposal = BallBlobProposal(CENTER, CENTER, RADIUS, 20.0)

    @Test
    fun acceptsBrightDisc() = assertAccepted(frame { x, y ->
        if (distance(x, y) <= RADIUS) Pixel(224) else BACKGROUND
    })

    @Test
    fun acceptsDarkDisc() = assertAccepted(frame { x, y ->
        if (distance(x, y) <= RADIUS) Pixel(28) else BACKGROUND
    })

    @Test
    fun acceptsDiscWithOneHundredDegreeOccludedRim() = assertAccepted(frame { x, y ->
        val angle = degrees(atan2(y - CENTER, x - CENTER))
        val occluded = angularDistance(angle, 0.0) <= 50.0
        if (distance(x, y) <= RADIUS && !occluded) Pixel(224) else BACKGROUND
    })

    @Test
    fun acceptsDiscWithAdjacentHardShadow() = assertAccepted(frame { x, y ->
        when {
            distance(x, y) <= RADIUS -> Pixel(224)
            x >= CENTER + RADIUS + 2.0 && y in 27.0..37.0 -> Pixel(45)
            else -> BACKGROUND
        }
    })

    @Test
    fun rejectsStraightStripe() = assertRejected(BallShapeRejection.InsufficientClosedEdge, frame { x, _ ->
        if (abs(x - CENTER) <= 2.5) Pixel(224) else BACKGROUND
    })

    @Test
    fun rejectsCurvedStripe() = assertRejected(BallShapeRejection.InteriorTooTextured, frame { x, y ->
        val theta = atan2(y - CENTER, x - CENTER)
        val radius = RADIUS * (1.0 + 0.42 * cos(theta))
        if (abs(distance(x, y) - radius) <= 1.3) Pixel(224) else BACKGROUND
    })

    @Test
    fun rejectsCross() = assertRejected(BallShapeRejection.InsufficientClosedEdge, frame { x, y ->
        if (abs(x - CENTER) <= 1.5 || abs(y - CENTER) <= 1.5) Pixel(224) else BACKGROUND
    })

    @Test
    fun rejectsEndpoint() = assertRejected(BallShapeRejection.InsufficientClosedEdge, frame { x, y ->
        val angle = degrees(atan2(y - CENTER, x - CENTER))
        val inCap = distance(x, y) <= RADIUS
        val continuedEdge = angularDistance(angle, 180.0) <= 80.0 &&
            distance(x, y) in 7.5..9.5
        if (inCap || continuedEdge) Pixel(224) else BACKGROUND
    })

    @Test
    fun rejectsOpenTwoHundredTwentyDegreeArc() = assertRejected(BallShapeRejection.LowContrast, frame { x, y ->
        val angle = degrees(atan2(y - CENTER, x - CENTER))
        val inArc = angularDistance(angle, 0.0) <= 110.0
        if (inArc && abs(distance(x, y) - RADIUS) <= 1.2) Pixel(224) else BACKGROUND
    })

    @Test
    fun rejectsElongatedEllipse() = assertRejected(BallShapeRejection.InsufficientClosedEdge, frame { x, y ->
        val normalized = (x - CENTER) * (x - CENTER) / 144.0 +
            (y - CENTER) * (y - CENTER) / 6.25
        if (normalized <= 1.0) Pixel(224) else BACKGROUND
    })

    @Test
    fun rejectsStronglyColoredCompactBlob() = assertRejected(BallShapeRejection.StrongChromaShift, frame { x, y ->
        if (distance(x, y) <= RADIUS) Pixel(224, 205, 45) else BACKGROUND
    })

    @Test
    fun bilinearSamplingPreservesRadiusTwoDisc() {
        val smallProposal = proposal.copy(radiusPx = 2.0)
        val result = verifier.verify(frame { x, y ->
            if (hypot(x - CENTER - 0.35, y - CENTER + 0.25) <= 2.0) Pixel(224) else BACKGROUND
        }, smallProposal)

        assertTrue(result.accepted, result.toString())
    }

    private fun assertAccepted(frame: YuvFrame) {
        val result = verifier.verify(frame, proposal)
        assertTrue(result.accepted, result.toString())
        assertEquals(null, result.rejection, result.toString())
    }

    private fun assertRejected(expected: BallShapeRejection, frame: YuvFrame) {
        val result = verifier.verify(frame, proposal)
        assertTrue(!result.accepted, result.toString())
        assertEquals(expected, result.rejection, result.toString())
    }

    private fun frame(pixelAt: (Double, Double) -> Pixel): YuvFrame {
        val y = ByteArray(SIZE * SIZE)
        val u = ByteArray(SIZE * SIZE)
        val v = ByteArray(SIZE * SIZE)
        for (row in 0 until SIZE) {
            for (column in 0 until SIZE) {
                val pixel = pixelAt(column.toDouble(), row.toDouble())
                val index = row * SIZE + column
                y[index] = pixel.y.toByte()
                u[index] = pixel.u.toByte()
                v[index] = pixel.v.toByte()
            }
        }
        return YuvFrame(SIZE, SIZE, y, u, v)
    }

    private fun distance(x: Double, y: Double): Double = hypot(x - CENTER, y - CENTER)

    private fun degrees(radians: Double): Double = radians * 180.0 / PI

    private fun angularDistance(first: Double, second: Double): Double {
        val difference = abs(first - second) % 360.0
        return minOf(difference, 360.0 - difference)
    }

    private data class Pixel(val y: Int, val u: Int = 128, val v: Int = 128)

    private companion object {
        const val SIZE = 64
        const val CENTER = 32.0
        const val RADIUS = 5.0
        val BACKGROUND = Pixel(112)
    }
}
