package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZoneBallDetectorTest {
    private val zone = LaunchZone(left = 0.0, top = 0.0, width = 1.0, height = 1.0)
    private val config = ZoneBallDetectorConfig(
        calibrationFramesRequired = 1,
        runnerUpMargin = 1.5,
    )

    @Test
    fun locksBrightBallFromLocalContrastWithoutCalibration() {
        val detector = ZoneBallDetector(config)
        val frame = texturedMatFrame()
            .withDisc(centerX = 16, centerY = 16, radius = 3, y = 172, u = 104, v = 136)

        val result = detector.analyzeRepeatedly(frame, times = 4)

        val accepted = assertNotNull(result.acceptedCandidate)
        assertEquals(16.0 / 31.0, accepted.x, absoluteTolerance = 0.04)
        assertEquals(16.0 / 31.0, accepted.y, absoluteTolerance = 0.04)
        assertTrue(result.debug.best?.rankScore ?: 0.0 > 0.0)
        assertEquals(false, result.debug.backgroundStale)
    }

    @Test
    fun locksDarkBallFromSignInvariantLocalContrast() {
        val detector = ZoneBallDetector(config)
        val frame = texturedMatFrame()
            .withDisc(centerX = 16, centerY = 16, radius = 3, y = 62, u = 108, v = 132)

        val result = detector.analyzeRepeatedly(frame, times = 4)

        val accepted = assertNotNull(result.acceptedCandidate)
        assertEquals(16.0 / 31.0, accepted.x, absoluteTolerance = 0.04)
        assertEquals(16.0 / 31.0, accepted.y, absoluteTolerance = 0.04)
    }

    @Test
    fun calibrationWarmupDoesNotBakeTheBallIntoTheBackground() {
        val detector = ZoneBallDetector(config)
        val frameWithBall = texturedMatFrame()
            .withDisc(centerX = 16, centerY = 16, radius = 3, y = 172, u = 104, v = 136)

        detector.startCalibration(zone)
        detector.collectCalibrationFrame(frameWithBall, zone)
        val result = detector.analyzeRepeatedly(frameWithBall, times = 4)

        val accepted = assertNotNull(result.acceptedCandidate)
        assertEquals(16.0 / 31.0, accepted.x, absoluteTolerance = 0.04)
        assertEquals(ZoneBallCalibrationState.Calibrated, detector.calibrationState)
    }

    @Test
    fun withholdsAcceptanceUntilOneLocationPersists() {
        val detector = ZoneBallDetector(config)
        val frame = texturedMatFrame()
            .withDisc(centerX = 16, centerY = 16, radius = 3, y = 172, u = 104, v = 136)

        val first = detector.analyzeFrame(frame, zone)
        val locked = detector.analyzeRepeatedly(frame, times = 4)

        assertEquals(null, first.acceptedCandidate)
        assertNotNull(locked.acceptedCandidate)
        assertTrue(locked.debug.margin >= 1.5)
    }

    @Test
    fun refusesToLockWhenTwoPersistentBallsCompete() {
        val detector = ZoneBallDetector(config)
        val frame = texturedMatFrame()
            .withDisc(centerX = 10, centerY = 16, radius = 3, y = 172, u = 104, v = 136)
            .withDisc(centerX = 22, centerY = 16, radius = 3, y = 172, u = 104, v = 136)

        val result = detector.analyzeRepeatedly(frame, times = 8)

        assertEquals(null, result.acceptedCandidate)
        assertTrue(result.debug.candidates.size >= 2)
        assertTrue(result.debug.margin < 1.5)
    }

    @Test
    fun maintainsLockedBallThroughBriefOcclusionThenUnlocksAfterMisses() {
        val detector = ZoneBallDetector(config)
        val frame = texturedMatFrame()
            .withDisc(centerX = 16, centerY = 16, radius = 3, y = 172, u = 104, v = 136)
        val empty = texturedMatFrame()

        val locked = detector.analyzeRepeatedly(frame, times = 4)
        val briefMiss = detector.analyzeFrame(empty, zone)
        val reacquired = detector.analyzeFrame(frame, zone)
        detector.analyzeFrame(empty, zone)
        val unlocked = detector.analyzeFrame(empty, zone)

        assertNotNull(locked.acceptedCandidate)
        assertEquals(null, briefMiss.acceptedCandidate)
        assertNotNull(reacquired.acceptedCandidate)
        assertEquals(null, unlocked.acceptedCandidate)
    }

    @Test
    fun findsBallInsidePortraitZoneOnLandscapeFrameWithRotation90() {
        val mapper = FrameCoordinateMapper(90)
        val viewZone = LaunchZone(left = 0.50, top = 0.25, width = 0.25, height = 0.50)
        val detector = ZoneBallDetector(config)
        val frame = texturedMatFrame(width = 48, height = 32)
            .withDisc(centerX = 23, centerY = 11, radius = 3, y = 172, u = 104, v = 136)

        val result = detector.analyzeRepeatedly(frame, viewZone, mapper, times = 4)

        val accepted = assertNotNull(result.acceptedCandidate)
        val expectedView = mapper.frameToView(23.0 / 47.0, 11.0 / 31.0)
        assertEquals(expectedView.x, accepted.x, absoluteTolerance = 0.04)
        assertEquals(expectedView.y, accepted.y, absoluteTolerance = 0.04)
        assertTrue(accepted.x in viewZone.left..viewZone.left + viewZone.width)
        assertTrue(accepted.y in viewZone.top..viewZone.top + viewZone.height)
    }

    private fun ZoneBallDetector.analyzeRepeatedly(
        frame: YuvFrame,
        launchZone: LaunchZone = zone,
        mapper: FrameCoordinateMapper = FrameCoordinateMapper.Identity,
        times: Int,
    ): ZoneBallDetection {
        var result = analyzeFrame(frame, launchZone, mapper)
        repeat(times - 1) {
            result = analyzeFrame(frame, launchZone, mapper)
        }
        return result
    }

    private fun texturedMatFrame(
        width: Int = 32,
        height: Int = 32,
        noisePhase: Int = 0,
    ): YuvFrame {
        val y = ByteArray(width * height)
        val u = ByteArray(width * height)
        val v = ByteArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val index = row * width + column
                val texture = ((column * 7 + row * 11 + noisePhase) % 9) - 4
                val seam = if (column == 6 || row == 24) 20 else 0
                y[index] = (112 + texture + seam).coerceIn(0, 255).toByte()
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
