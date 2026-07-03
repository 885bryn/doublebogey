package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZoneBallDetectorTest {
    private val zone = LaunchZone(left = 0.0, top = 0.0, width = 1.0, height = 1.0)
    private val config = ZoneBallDetectorConfig(
        calibrationFramesRequired = 4,
        minYDelta = 18.0,
        significanceMultiplier = 4.0,
        minMeanSignificance = 6.0,
        minArea = 6,
        maxArea = 200,
        chromaShiftTolerance = 45.0,
        staleForegroundFraction = 0.35,
        runnerUpMargin = 1.5,
    )

    @Test
    fun acceptsBrightCompactBallAgainstTexturedCalibratedMat() {
        val detector = calibratedDetector()
        val frame = texturedMatFrame()
            .withDisc(centerX = 16, centerY = 16, radius = 3, y = 165, u = 104, v = 136)

        val result = detector.analyzeFrame(frame, zone)

        val accepted = assertNotNull(result.acceptedCandidate)
        assertEquals(16.0 / 31.0, accepted.x, absoluteTolerance = 0.03)
        assertEquals(16.0 / 31.0, accepted.y, absoluteTolerance = 0.03)
        assertTrue(accepted.pixelCount in 20..40)
        assertTrue(result.debug.best?.meanSignificance ?: 0.0 >= 6.0)
        assertEquals(false, result.debug.backgroundStale)
    }

    @Test
    fun keepsCalibratedBrightSeamOutOfForeground() {
        val detector = calibratedDetector()

        val result = detector.analyzeFrame(texturedMatFrame(), zone)

        assertEquals(null, result.acceptedCandidate)
        assertEquals(0, result.debug.candidates.size)
        assertTrue(result.debug.foregroundFraction < 0.02)
    }

    @Test
    fun flagsGlobalPositiveLumaShiftAsStaleBackgroundInsteadOfBall() {
        val detector = calibratedDetector()
        val shifted = texturedMatFrame(globalYShift = 25)

        val result = detector.analyzeFrame(shifted, zone)

        assertEquals(null, result.acceptedCandidate)
        assertEquals(true, result.debug.backgroundStale)
        assertTrue(result.debug.foregroundFraction > 0.35)
    }

    @Test
    fun rejectsDarkerShadowBecauseForegroundIsPositiveOnly() {
        val detector = calibratedDetector()
        val shadow = texturedMatFrame()
            .withDisc(centerX = 16, centerY = 16, radius = 4, y = 70, u = 100, v = 140)

        val result = detector.analyzeFrame(shadow, zone)

        assertEquals(null, result.acceptedCandidate)
        assertEquals(false, result.debug.backgroundStale)
    }

    @Test
    fun rejectsStronglyColoredBrightObjectByRelativeChromaShift() {
        val detector = calibratedDetector()
        val colored = texturedMatFrame()
            .withDisc(centerX = 16, centerY = 16, radius = 3, y = 170, u = 40, v = 205)

        val result = detector.analyzeFrame(colored, zone)

        assertEquals(null, result.acceptedCandidate)
        assertTrue(result.debug.rejectedByChroma > 0)
    }

    @Test
    fun withholdsAcceptanceWhenTwoSimilarCandidatesCompete() {
        val detector = calibratedDetector()
        val frame = texturedMatFrame()
            .withDisc(centerX = 10, centerY = 16, radius = 3, y = 165, u = 104, v = 136)
            .withDisc(centerX = 22, centerY = 16, radius = 3, y = 164, u = 104, v = 136)

        val result = detector.analyzeFrame(frame, zone)

        assertEquals(null, result.acceptedCandidate)
        assertTrue(result.debug.candidates.size >= 2)
        assertTrue(result.debug.margin < 1.5)
    }

    @Test
    fun findsBallInsidePortraitZoneOnLandscapeFrameWithRotation90() {
        val mapper = FrameCoordinateMapper(90)
        // Portrait view zone; on the rotated landscape buffer this covers frame
        // x in [0.25, 0.75], y in [0.25, 0.50] (see FrameCoordinateMapper).
        val viewZone = LaunchZone(left = 0.50, top = 0.25, width = 0.25, height = 0.50)
        val detector = ZoneBallDetector(config)
        detector.startCalibration(viewZone)
        repeat(config.calibrationFramesRequired) { index ->
            detector.collectCalibrationFrame(
                texturedMatFrame(width = 48, height = 32, noisePhase = index),
                viewZone,
                mapper,
            )
        }
        assertEquals(ZoneBallCalibrationState.Calibrated, detector.calibrationState)

        // Ball at frame pixel (23, 11): inside the mapped frame zone, outside the raw view-zone numbers.
        val frame = texturedMatFrame(width = 48, height = 32)
            .withDisc(centerX = 23, centerY = 11, radius = 3, y = 165, u = 104, v = 136)

        val result = detector.analyzeFrame(frame, viewZone, mapper)

        val accepted = assertNotNull(result.acceptedCandidate)
        val expectedView = mapper.frameToView(23.0 / 47.0, 11.0 / 31.0)
        assertEquals(expectedView.x, accepted.x, absoluteTolerance = 0.03)
        assertEquals(expectedView.y, accepted.y, absoluteTolerance = 0.03)
        assertTrue(accepted.x in viewZone.left..viewZone.left + viewZone.width)
        assertTrue(accepted.y in viewZone.top..viewZone.top + viewZone.height)
    }

    private fun calibratedDetector(): ZoneBallDetector {
        val detector = ZoneBallDetector(config)
        detector.startCalibration(zone)
        repeat(config.calibrationFramesRequired) { index ->
            val result = detector.collectCalibrationFrame(texturedMatFrame(noisePhase = index), zone)
            if (index < config.calibrationFramesRequired - 1) {
                assertEquals(ZoneBallCalibrationState.Calibrating, result.debug.calibrationState)
            }
        }
        assertEquals(ZoneBallCalibrationState.Calibrated, detector.calibrationState)
        return detector
    }

    private fun texturedMatFrame(
        width: Int = 32,
        height: Int = 32,
        noisePhase: Int = 0,
        globalYShift: Int = 0,
    ): YuvFrame {
        val y = ByteArray(width * height)
        val u = ByteArray(width * height)
        val v = ByteArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val index = row * width + column
                val texture = ((column * 7 + row * 11 + noisePhase) % 9) - 4
                val seam = if (column == 6 || row == 24) 20 else 0
                y[index] = (112 + texture + seam + globalYShift).coerceIn(0, 255).toByte()
                u[index] = (100 + ((column + noisePhase) % 3) - 1).toByte()
                v[index] = (140 + ((row + noisePhase) % 3) - 1).toByte()
            }
        }
        return YuvFrame(width = width, height = height, y = y, u = u, v = v)
    }

    private fun YuvFrame.withDisc(centerX: Int, centerY: Int, radius: Int, y: Int, u: Int, v: Int): YuvFrame {
        val nextY = this.y.copyOf()
        val nextU = this.u.copyOf()
        val nextV = this.v.copyOf()
        val radiusSquared = radius * radius
        for (row in 0 until height) {
            for (column in 0 until width) {
                val dx = column - centerX
                val dy = row - centerY
                if (dx * dx + dy * dy <= radiusSquared) {
                    val index = row * width + column
                    nextY[index] = y.toByte()
                    nextU[index] = u.toByte()
                    nextV[index] = v.toByte()
                }
            }
        }
        return copy(y = nextY, u = nextU, v = nextV)
    }
}
