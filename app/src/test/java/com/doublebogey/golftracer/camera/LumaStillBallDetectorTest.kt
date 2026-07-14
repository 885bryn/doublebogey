package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LumaStillBallDetectorTest {
    @Test
    fun detectsCompactBrightStillBallInsideLaunchZone() {
        val detector = LumaStillBallDetector()
        val zone = LaunchZone(left = 0.25, top = 0.25, width = 0.50, height = 0.50)
        val frame = frameWithBrightBlob(width = 12, height = 12, blob = setOf(5 to 6, 6 to 6, 5 to 7, 6 to 7))

        val candidates = detector.analyzeFrame(frame, zone)

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals(5.5 / 11.0, candidate.x, absoluteTolerance = 0.000000001)
        assertEquals(6.5 / 11.0, candidate.y, absoluteTolerance = 0.000000001)
        assertTrue(candidate.confidence > 0.0)
    }

    @Test
    fun ignoresDarkTurfContrastInsideLaunchZone() {
        val detector = LumaStillBallDetector()
        val zone = LaunchZone(left = 0.25, top = 0.25, width = 0.50, height = 0.50)
        val frame = frameWithDarkBlob(width = 12, height = 12, blob = setOf(5 to 6, 6 to 6, 5 to 7, 6 to 7))

        val candidates = detector.analyzeFrame(frame, zone)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun ignoresStillBallOutsideLaunchZone() {
        val detector = LumaStillBallDetector()
        val zone = LaunchZone(left = 0.60, top = 0.60, width = 0.25, height = 0.25)
        val frame = frameWithBrightBlob(width = 12, height = 12, blob = setOf(2 to 2, 3 to 2, 2 to 3, 3 to 3))

        val candidates = detector.analyzeFrame(frame, zone)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun mergesNearbyStillBallFragmentsIntoOneCandidate() {
        val detector = LumaStillBallDetector(
            config = LumaStillBallDetectorConfig(fragmentMergeDistance = 0.20),
        )
        val zone = LaunchZone(left = 0.20, top = 0.20, width = 0.60, height = 0.60)
        val frame = frameWithBrightBlob(
            width = 20,
            height = 20,
            blob = setOf(
                8 to 10,
                8 to 11,
                11 to 10,
                11 to 11,
            ),
        )

        val candidates = detector.analyzeFrame(frame, zone)

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals(9.5 / 19.0, candidate.x, absoluteTolerance = 0.000000001)
        assertEquals(10.5 / 19.0, candidate.y, absoluteTolerance = 0.000000001)
        assertEquals(4, candidate.pixelCount)
    }

    @Test
    fun prefersCompactBrightBallOverLargerDarkTurfFragment() {
        val detector = LumaStillBallDetector()
        val zone = LaunchZone(left = 0.20, top = 0.20, width = 0.60, height = 0.60)
        val darkTurfFragment = setOf(5 to 8, 6 to 8, 5 to 9, 6 to 9, 5 to 10, 6 to 10)
        val brightBall = setOf(12 to 9, 13 to 9, 12 to 10, 13 to 10)
        val frame = frameWithBlobLuma(
            width = 20,
            height = 20,
            pixelLuma = darkTurfFragment.associateWith { 40 } + brightBall.associateWith { 245 },
        )

        val candidates = detector.analyzeFrame(frame, zone)

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals(12.5 / 19.0, candidate.x, absoluteTolerance = 0.000000001)
        assertEquals(9.5 / 19.0, candidate.y, absoluteTolerance = 0.000000001)
        assertEquals(4, candidate.pixelCount)
    }

    private fun frameWithDarkBlob(width: Int, height: Int, blob: Set<Pair<Int, Int>>): LumaFrame {
        return frameWithBlobLuma(width, height, blob.associateWith { 40 })
    }

    private fun frameWithBrightBlob(width: Int, height: Int, blob: Set<Pair<Int, Int>>): LumaFrame {
        return frameWithBlobLuma(width, height, blob.associateWith { 245 })
    }

    private fun frameWithBlobLuma(
        width: Int,
        height: Int,
        pixelLuma: Map<Pair<Int, Int>, Int>,
    ): LumaFrame {
        val data = ByteArray(width * height) { 170.toByte() }
        for ((location, luma) in pixelLuma) {
            val (x, y) = location
            data[y * width + x] = luma.toByte()
        }
        return LumaFrame(width = width, height = height, luma = data)
    }
}
