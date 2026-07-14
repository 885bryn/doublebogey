package com.doublebogey.golftracer.camera

import kotlin.math.hypot
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

class ZoneBallDetectorPerformanceTest {
    @Test
    fun representativeCropStaysWithinProposalAndJvmRuntimeGuards() {
        val detector = ZoneBallDetector()
        val frame = representativeCrop()
        val zone = LaunchZone(0.0, 0.0, 1.0, 1.0)

        repeat(3) { detector.analyzeFrame(frame, zone) }

        val timingsNs = mutableListOf<Long>()
        var maxProposalCount = 0
        repeat(12) {
            lateinit var result: ZoneBallDetection
            timingsNs += measureNanoTime {
                result = detector.analyzeFrame(frame, zone)
            }
            maxProposalCount = maxOf(maxProposalCount, result.debug.proposalCount)
            assertTrue(result.debug.proposalCount <= 8, "proposal count was ${result.debug.proposalCount}")
        }

        val sortedMs = timingsNs.sorted().map { it / 1_000_000.0 }
        val medianMs = (sortedMs[5] + sortedMs[6]) / 2.0
        println("ZoneBallDetector medianMs=$medianMs maxProposalCount=$maxProposalCount")
        assertTrue(medianMs < 250.0, "median detector time was $medianMs ms")
    }

    private fun representativeCrop(width: Int = 240, height: Int = 180): YuvFrame {
        val y = ByteArray(width * height)
        val u = ByteArray(width * height) { 128.toByte() }
        val v = ByteArray(width * height) { 128.toByte() }
        for (row in 0 until height) {
            for (column in 0 until width) {
                val texture = ((column * 7 + row * 11) % 9) - 4
                val marking = column in 45..48 || row in 133..136 ||
                    (column in 115..124 && row in 80..112) ||
                    (row in 92..101 && column in 105..134)
                val ball = hypot((column - 170).toDouble(), (row - 105).toDouble()) <= 7.0
                val luma = when {
                    ball -> 232
                    marking -> 190 + texture
                    else -> 104 + texture
                }
                y[row * width + column] = luma.toByte()
            }
        }
        return YuvFrame(width, height, y, u, v)
    }
}
