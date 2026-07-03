package com.doublebogey.golftracer

import com.doublebogey.golftracer.camera.AutoShotTrackerStatus
import com.doublebogey.golftracer.camera.ShotTrackerState
import com.doublebogey.golftracer.camera.ShotTrackerStatus
import com.doublebogey.golftracer.camera.ZoneBallCalibrationState
import com.doublebogey.golftracer.camera.ZoneBallCandidateDebug
import com.doublebogey.golftracer.camera.ZoneBallDebug
import com.doublebogey.golftracer.camera.LumaMotionCandidate
import kotlin.test.Test
import kotlin.test.assertEquals

class CameraStatusBadgeModelTest {
    @Test
    fun showsCalibrationReadyWhenBackgroundModelIsCalibrated() {
        val badges = CameraStatusBadgeModel.from(
            shotStatus = AutoShotTrackerStatus.Searching,
            trackingState = idleTrackingState(),
            acquisitionDebug = ZoneBallDebug(
                calibrationState = ZoneBallCalibrationState.Calibrated,
            ),
        )

        assertEquals("CALIBRATION READY", badges.calibration.text)
        assertEquals(CameraStatusBadgeTone.Ready, badges.calibration.tone)
    }

    @Test
    fun showsCalibrationProgressWhenModelIsStillCollectingFrames() {
        val badges = CameraStatusBadgeModel.from(
            shotStatus = AutoShotTrackerStatus.Calibrating,
            trackingState = idleTrackingState(),
            acquisitionDebug = ZoneBallDebug(
                calibrationState = ZoneBallCalibrationState.Calibrating,
                calibrationFramesCollected = 12,
                calibrationFramesRequired = 30,
            ),
        )

        assertEquals("CALIBRATING 12/30", badges.calibration.text)
        assertEquals(CameraStatusBadgeTone.Waiting, badges.calibration.tone)
    }

    @Test
    fun showsCalibrationResetNeededWhenBackgroundIsStale() {
        val badges = CameraStatusBadgeModel.from(
            shotStatus = AutoShotTrackerStatus.Searching,
            trackingState = idleTrackingState(),
            acquisitionDebug = ZoneBallDebug(
                calibrationState = ZoneBallCalibrationState.Calibrated,
                backgroundStale = true,
            ),
        )

        assertEquals("CALIBRATION RESET", badges.calibration.text)
        assertEquals(CameraStatusBadgeTone.Warning, badges.calibration.tone)
    }

    @Test
    fun showsBallDetectedWhenBestCandidateExistsBeforeLock() {
        val badges = CameraStatusBadgeModel.from(
            shotStatus = AutoShotTrackerStatus.Searching,
            trackingState = idleTrackingState(),
            acquisitionDebug = ZoneBallDebug(
                calibrationState = ZoneBallCalibrationState.Calibrated,
                candidates = listOf(candidateDebug()),
            ),
        )

        assertEquals("BALL DETECTED", badges.ball.text)
        assertEquals(CameraStatusBadgeTone.Detected, badges.ball.tone)
    }

    @Test
    fun showsBallLockedWhenTrackerHasStableStillBall() {
        val badges = CameraStatusBadgeModel.from(
            shotStatus = AutoShotTrackerStatus.BallLocked,
            trackingState = idleTrackingState(),
            acquisitionDebug = ZoneBallDebug(
                calibrationState = ZoneBallCalibrationState.Calibrated,
                candidates = listOf(candidateDebug()),
            ),
        )

        assertEquals("BALL LOCKED", badges.ball.text)
        assertEquals(CameraStatusBadgeTone.Ready, badges.ball.tone)
    }

    @Test
    fun showsBallTrackingDuringFlightTracking() {
        val badges = CameraStatusBadgeModel.from(
            shotStatus = AutoShotTrackerStatus.Tracking,
            trackingState = ShotTrackerState(
                status = ShotTrackerStatus.Tracking,
                points = emptyList(),
                occlusionBridged = false,
                lowConfidence = false,
            ),
            acquisitionDebug = ZoneBallDebug(
                calibrationState = ZoneBallCalibrationState.Calibrated,
            ),
        )

        assertEquals("BALL TRACKING", badges.ball.text)
        assertEquals(CameraStatusBadgeTone.Detected, badges.ball.tone)
    }

    @Test
    fun showsNoBallWhenCalibratedButNoCandidateExists() {
        val badges = CameraStatusBadgeModel.from(
            shotStatus = AutoShotTrackerStatus.Searching,
            trackingState = idleTrackingState(),
            acquisitionDebug = ZoneBallDebug(
                calibrationState = ZoneBallCalibrationState.Calibrated,
            ),
        )

        assertEquals("NO BALL", badges.ball.text)
        assertEquals(CameraStatusBadgeTone.Waiting, badges.ball.tone)
    }

    private fun idleTrackingState(): ShotTrackerState =
        ShotTrackerState(
            status = ShotTrackerStatus.Idle,
            points = emptyList(),
            occlusionBridged = false,
            lowConfidence = false,
        )

    private fun candidateDebug(): ZoneBallCandidateDebug =
        ZoneBallCandidateDebug(
            candidate = LumaMotionCandidate(x = 0.5, y = 0.7, pixelCount = 220, confidence = 0.8),
            area = 220,
            fillRatio = 0.8,
            aspectRatio = 0.9,
            meanSignificance = 11.0,
            chromaShift = 4.0,
            rankScore = 9.35,
        )
}
