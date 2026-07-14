package com.doublebogey.golftracer.camera

import kotlin.math.hypot
import kotlin.test.*

class ZoneBallDetectorTest {
    private val fullZone = LaunchZone(0.0, 0.0, 1.0, 1.0)

    @Test fun patternedEmptyMatNeverLocksOverTwentyFrames() {
        val result = ZoneBallDetector().analyzeRepeatedly(patternedMatFrame(), times = 20)
        assertNull(result.acceptedCandidate)
        assertNotNull(result.debug.topRejected)
        assertTrue(result.debug.candidates.isEmpty())
        assertTrue(result.debug.proposalCount <= 8)
    }

    @Test fun brightAndDarkBallsLockOnFifthHit() {
        listOf(224, 28).forEach { luma ->
            val detector = ZoneBallDetector()
            val frame = patternedMatFrame().withDisc(48, 32, 2, luma)
            repeat(4) {
                val result = detector.analyzeFrame(frame, fullZone)
                assertNull(result.acceptedCandidate, "luma=$luma frame=$it debug=${result.debug}")
                assertNotNull(result.debug.best, "luma=$luma frame=$it debug=${result.debug}")
            }
            val locked = detector.analyzeFrame(frame, fullZone)
            assertNotNull(locked.acceptedCandidate, "luma=$luma debug=${locked.debug}")
            assertEquals(5, locked.debug.confirmationHits)
            assertEquals(7, locked.debug.confirmationWindow)
        }
    }

    @Test fun threeRadiusHandheldDriftLocks() {
        val detector = ZoneBallDetector()
        var result = detector.analyzeFrame(patternedMatFrame(), fullZone)
        listOf(44 to 30, 46 to 31, 48 to 32, 50 to 33, 52 to 33).forEach { (x, y) ->
            result = detector.analyzeFrame(patternedMatFrame().withDisc(x, y, 2, 224), fullZone)
        }
        assertNotNull(result.acceptedCandidate, result.debug.toString())
        assertEquals(5, result.debug.confirmationHits)
        assertTrue(result.debug.confirmationWindow <= 7)
    }

    @Test fun twoBallsRemainAmbiguous() {
        val frame = patternedMatFrame().withDisc(30, 32, 2, 224).withDisc(66, 32, 2, 224)
        val result = ZoneBallDetector().analyzeRepeatedly(frame, times = 7)
        assertNull(result.acceptedCandidate)
        assertTrue(result.debug.ambiguous, result.debug.toString())
        assertTrue(result.debug.candidates.size >= 2)
    }

    @Test fun proposalCountNeverExceedsEight() {
        var frame = patternedMatFrame()
        for (y in 12..52 step 10) for (x in 12..84 step 12) frame = frame.withDisc(x, y, 2, 224)
        val result = ZoneBallDetector().analyzeFrame(frame, fullZone)
        assertTrue(result.debug.proposalCount <= 8)
        assertEquals(result.debug.proposalCount - result.debug.candidates.size, result.debug.rejectedCount)
    }

    @Test fun rejectedProposalAppearsOnlyAsTopRejected() {
        val result = ZoneBallDetector().analyzeFrame(patternedMatFrame(), fullZone)
        assertNotNull(assertNotNull(result.debug.topRejected).rejection)
        assertTrue(result.debug.candidates.none { it.rejection != null })
    }

    @Test fun resetClearsConfirmation() {
        val detector = ZoneBallDetector()
        val frame = patternedMatFrame().withDisc(48, 32, 2, 224)
        assertNotNull(detector.analyzeRepeatedly(frame, times = 5).acceptedCandidate)
        detector.reset()
        val result = detector.analyzeFrame(frame, fullZone)
        assertNull(result.acceptedCandidate)
        assertEquals(1, result.debug.confirmationHits)
        assertEquals(7, result.debug.confirmationWindow)
    }

    @Test fun verifiedLeaderAppearsAsBestBeforeLock() {
        val result = ZoneBallDetector().analyzeFrame(patternedMatFrame().withDisc(48, 32, 2, 224), fullZone)
        assertNull(result.acceptedCandidate)
        val best = assertNotNull(result.debug.best)
        assertNull(best.rejection)
        assertTrue(best.rankScore > 0.0)
        assertEquals(7, result.debug.confirmationWindow)
    }

    @Test fun mapsFullFrameZoneCropThroughRotation() {
        val mapper = FrameCoordinateMapper(90)
        val zone = LaunchZone(0.50, 0.25, 0.25, 0.50)
        val frame = patternedMatFrame(128, 96).withDisc(63, 35, 2, 224)
        val result = ZoneBallDetector().analyzeRepeatedly(frame, zone, mapper, 5)
        val accepted = assertNotNull(result.acceptedCandidate, result.debug.toString())
        val expected = mapper.frameToView(63.0 / 127.0, 35.0 / 95.0)
        assertEquals(expected.x, accepted.x, 0.025)
        assertEquals(expected.y, accepted.y, 0.025)
        assertTrue(accepted.x in zone.left..zone.left + zone.width)
        assertTrue(accepted.y in zone.top..zone.top + zone.height)
    }

    @Test fun compatibilityCalibrationOnlyWarmsUp() {
        val detector = ZoneBallDetector(ZoneBallDetectorConfig(calibrationFramesRequired = 2))
        val frame = patternedMatFrame().withDisc(48, 32, 2, 224)
        detector.startCalibration(fullZone)
        detector.collectCalibrationFrame(frame, fullZone)
        detector.collectCalibrationFrame(frame, fullZone)
        val result = detector.analyzeRepeatedly(frame, times = 5)
        assertEquals(ZoneBallCalibrationState.Calibrated, detector.calibrationState)
        assertFalse(result.debug.backgroundStale)
        assertNotNull(result.acceptedCandidate)
    }

    @Test fun statusSummaryReportsPipelineMetricsWithoutCompatibilityClaims() {
        val result = ZoneBallDetector().analyzeFrame(patternedMatFrame().withDisc(48, 32, 2, 224), fullZone)
        val summary = result.debug.statusSummary(aeAwbLocked = true)
        listOf("r_e=", "prop=", "verified=", "score=", "closed=", "radial=", "line=", "rv=", "r=", "confirm=", "margin=", "ambiguous=")
            .forEach { assertTrue(it in summary, summary) }
        assertFalse("calibr" in summary.lowercase(), summary)
        assertFalse("aeLock" in summary, summary)
    }

    private fun ZoneBallDetector.analyzeRepeatedly(frame: YuvFrame, zone: LaunchZone = fullZone, mapper: FrameCoordinateMapper = FrameCoordinateMapper.Identity, times: Int): ZoneBallDetection {
        var result = analyzeFrame(frame, zone, mapper)
        repeat(times - 1) { result = analyzeFrame(frame, zone, mapper) }
        return result
    }

    private fun patternedMatFrame(width: Int = 96, height: Int = 64) = frame(width, height) { x, y ->
        Pixel((112 + ((x * 7 + y * 11) % 7) - 3 + if (x == width / 5 || y == height * 3 / 4) 18 else 0).coerceIn(0, 255))
    }
    private fun flatFrame(width: Int = 96, height: Int = 64) = frame(width, height) { _, _ -> Pixel(112) }
    private fun frame(width: Int, height: Int, at: (Int, Int) -> Pixel): YuvFrame {
        val yy = ByteArray(width * height); val uu = ByteArray(width * height); val vv = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width) at(x, y).also { p -> val i = y * width + x; yy[i] = p.y.toByte(); uu[i] = p.u.toByte(); vv[i] = p.v.toByte() }
        return YuvFrame(width, height, yy, uu, vv)
    }
    private fun YuvFrame.withDisc(cx: Int, cy: Int, radius: Int, luma: Int) = mapPixels { x, y, p -> if (hypot((x - cx).toDouble(), (y - cy).toDouble()) <= radius) Pixel(luma) else p }
    private fun YuvFrame.withStripe(cx: Int, halfWidth: Int, luma: Int) = mapPixels { x, _, p -> if (x in cx - halfWidth..cx + halfWidth) Pixel(luma) else p }
    private fun YuvFrame.mapPixels(f: (Int, Int, Pixel) -> Pixel): YuvFrame {
        val yy = y.copyOf(); val uu = u.copyOf(); val vv = v.copyOf()
        for (row in 0 until height) for (column in 0 until width) { val i = row * width + column; val p = f(column, row, Pixel(y[i].toInt() and 255, u[i].toInt() and 255, v[i].toInt() and 255)); yy[i] = p.y.toByte(); uu[i] = p.u.toByte(); vv[i] = p.v.toByte() }
        return copy(y = yy, u = uu, v = vv)
    }
    private data class Pixel(val y: Int, val u: Int = 128, val v: Int = 128)
}