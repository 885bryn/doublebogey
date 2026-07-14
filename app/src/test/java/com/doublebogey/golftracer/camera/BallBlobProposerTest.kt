package com.doublebogey.golftracer.camera

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BallBlobProposerTest {
    private val proposer = BallBlobProposer()
    private val radiiPx = listOf(3.0, 4.0, 5.6)

    @Test
    fun proposesBrightDisc() {
        val proposals = proposer.propose(texturedFrame(listOf(Disc(48, 32, 4, 220))), radiiPx)

        val best = assertNotNull(proposals.firstOrNull())
        assertEquals(48.0, best.centerX, 2.0)
        assertEquals(32.0, best.centerY, 2.0)
        assertTrue(proposals.size <= 8)
    }

    @Test
    fun proposesDarkDiscWithSignInvariantResponse() {
        val proposals = proposer.propose(texturedFrame(listOf(Disc(48, 32, 4, 24))), radiiPx)

        val best = assertNotNull(proposals.firstOrNull())
        assertEquals(48.0, best.centerX, 2.0)
        assertEquals(32.0, best.centerY, 2.0)
        assertTrue(best.response > 0.0)
    }

    @Test
    fun returnsNoProposalsForFlatFrame() {
        val frame = yuvFrame(ByteArray(WIDTH * HEIGHT) { 112.toByte() })

        assertTrue(proposer.propose(frame, radiiPx).isEmpty())
    }

    @Test
    fun capsNineSeparatedDiscsAtEightProposals() {
        val centers = listOf(
            16 to 16, 48 to 16, 80 to 16,
            16 to 32, 48 to 32, 80 to 32,
            16 to 48, 48 to 48, 80 to 48,
        )
        val discs = centers.mapIndexed { index, (x, y) ->
            Disc(x, y, radius = 4, luma = if (index % 2 == 0) 220 else 24)
        }

        val proposals = proposer.propose(texturedFrame(discs), radiiPx)

        assertEquals(8, proposals.size)
    }

    private fun texturedFrame(discs: List<Disc>): YuvFrame {
        val luma = ByteArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val disc = discs.firstOrNull { hypot((x - it.x).toDouble(), (y - it.y).toDouble()) <= it.radius }
                val value = disc?.luma ?: 112 + ((x * 17 + y * 13) % 5) - 2
                luma[y * WIDTH + x] = value.toByte()
            }
        }
        return yuvFrame(luma)
    }

    private fun yuvFrame(luma: ByteArray): YuvFrame = YuvFrame(
        width = WIDTH,
        height = HEIGHT,
        y = luma,
        u = ByteArray(WIDTH * HEIGHT) { 128.toByte() },
        v = ByteArray(WIDTH * HEIGHT) { 128.toByte() },
    )

    private data class Disc(val x: Int, val y: Int, val radius: Int, val luma: Int)

    private companion object {
        const val WIDTH = 96
        const val HEIGHT = 64
    }
}
