package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LumaMotionDetectorTest {
    @Test
    fun firstFrameSeedsPreviousFrameWithoutCandidates() {
        val detector = LumaMotionDetector()

        val result = detector.analyzeFrame(
            frame = Frame.lumaFrame(width = 6, height = 6),
            timestampNs = 1_000_000L,
        )

        assertEquals(0, result.candidates.size)
        assertEquals(0, result.matchedPixels)
    }

    @Test
    fun detectsCentroidOfSmallBrightMovingBlob() {
        val detector = LumaMotionDetector()
        detector.analyzeFrame(Frame.lumaFrame(width = 8, height = 8), timestampNs = 1_000_000L)

        val result = detector.analyzeFrame(
            frame = Frame.lumaFrame(width = 8, height = 8, brightPixels = setOf(3 to 4, 4 to 4, 3 to 5, 4 to 5)),
            timestampNs = 2_000_000L,
        )

        assertEquals(1, result.candidates.size)
        val candidate = result.candidates.single()
        assertEquals(3.5 / 7.0, candidate.x, absoluteTolerance = 0.000000001)
        assertEquals(4.5 / 7.0, candidate.y, absoluteTolerance = 0.000000001)
        assertEquals(4, candidate.pixelCount)
        assertTrue(candidate.confidence > 0.0)
    }


    @Test
    fun detectsCentroidOfSmallDarkMovingBlob() {
        val detector = LumaMotionDetector()
        detector.analyzeFrame(Frame.lumaFrame(width = 8, height = 8, baseLuma = 170), timestampNs = 1_000_000L)

        val result = detector.analyzeFrame(
            frame = Frame.lumaFrame(
                width = 8,
                height = 8,
                baseLuma = 170,
                pixelLuma = mapOf(
                    (3 to 4) to 45,
                    (4 to 4) to 55,
                    (3 to 5) to 50,
                    (4 to 5) to 60,
                ),
            ),
            timestampNs = 2_000_000L,
        )

        assertEquals(1, result.candidates.size)
        val candidate = result.candidates.single()
        assertEquals(3.5 / 7.0, candidate.x, absoluteTolerance = 0.000000001)
        assertEquals(4.5 / 7.0, candidate.y, absoluteTolerance = 0.000000001)
        assertEquals(4, candidate.pixelCount)
        assertTrue(candidate.confidence > 0.0)
    }
    @Test
    fun rejectsStaticBrightBlobWithoutMotion() {
        val detector = LumaMotionDetector()
        val frame = Frame.lumaFrame(width = 8, height = 8, brightPixels = setOf(3 to 4, 4 to 4, 3 to 5, 4 to 5))

        detector.analyzeFrame(frame, timestampNs = 1_000_000L)
        val result = detector.analyzeFrame(frame, timestampNs = 2_000_000L)

        assertEquals(0, result.candidates.size)
    }

    @Test
    fun rejectsLargeMovingRegion() {
        val detector = LumaMotionDetector(config = LumaMotionDetectorConfig(maxBlobPixels = 6))
        detector.analyzeFrame(Frame.lumaFrame(width = 8, height = 8), timestampNs = 1_000_000L)

        val largeRegion = buildSet {
            for (y in 2..4) {
                for (x in 2..4) {
                    add(x to y)
                }
            }
        }
        val result = detector.analyzeFrame(
            frame = Frame.lumaFrame(width = 8, height = 8, brightPixels = largeRegion),
            timestampNs = 2_000_000L,
        )

        assertEquals(0, result.candidates.size)
        assertEquals(9, result.rejectedLargeBlobPixels)
    }

    @Test
    fun suppressesCandidatesWhenTooMuchOfFrameIsMoving() {
        val detector = LumaMotionDetector(config = LumaMotionDetectorConfig(maxMovingPixelFraction = 0.20))
        detector.analyzeFrame(Frame.lumaFrame(width = 10, height = 10), timestampNs = 1_000_000L)

        val movingPixels = buildSet {
            for (y in 0 until 10) {
                for (x in 0 until 10) {
                    if ((x + y) % 2 == 0) {
                        add(x to y)
                    }
                }
            }
        }
        val result = detector.analyzeFrame(
            frame = Frame.lumaFrame(width = 10, height = 10, brightPixels = movingPixels),
            timestampNs = 2_000_000L,
        )

        assertEquals(0, result.candidates.size)
        assertEquals(50, result.rejectedGlobalMotionPixels)
    }

    private object Frame {
        fun lumaFrame(
            width: Int,
            height: Int,
            baseLuma: Int = 20,
            brightPixels: Set<Pair<Int, Int>> = emptySet(),
            pixelLuma: Map<Pair<Int, Int>, Int> = emptyMap(),
        ): LumaFrame {
            val data = ByteArray(width * height) { baseLuma.toByte() }
            for ((x, y) in brightPixels) {
                data[y * width + x] = 245.toByte()
            }
            for ((location, luma) in pixelLuma) {
                val (x, y) = location
                data[y * width + x] = luma.toByte()
            }
            return LumaFrame(width = width, height = height, luma = data)
        }
    }
}
