package com.doublebogey.golftracer

import com.doublebogey.golftracer.camera.AutoShotTrackerStatus
import com.doublebogey.golftracer.camera.BallShapeMetrics
import com.doublebogey.golftracer.camera.LumaMotionCandidate
import com.doublebogey.golftracer.camera.ShotTrackerState
import com.doublebogey.golftracer.camera.ShotTrackerStatus
import com.doublebogey.golftracer.camera.ZoneBallCandidateDebug
import com.doublebogey.golftracer.camera.ZoneBallDebug
import kotlin.test.Test
import kotlin.test.assertEquals

class CameraStatusBadgeModelTest {
    @Test fun startingStateShowsDetectorStartingAndNoBall() {
        val badges = CameraStatusBadgeModel.starting()
        assertEquals(CameraStatusBadge("DETECTOR STARTING", CameraStatusBadgeTone.Waiting), badges.detector)
        assertEquals(CameraStatusBadge("NO BALL", CameraStatusBadgeTone.Waiting), badges.ball)
    }

    @Test fun searchingWithoutCandidateShowsDetectorReadyAndNoBall() {
        val badges = badges(AutoShotTrackerStatus.Searching)
        assertEquals(CameraStatusBadge("DETECTOR READY", CameraStatusBadgeTone.Ready), badges.detector)
        assertEquals(CameraStatusBadge("NO BALL", CameraStatusBadgeTone.Waiting), badges.ball)
    }

    @Test fun verifiedLeaderBeforeLockShowsCandidate() {
        val badges = badges(AutoShotTrackerStatus.Searching, ZoneBallDebug(candidates = listOf(candidateDebug())))
        assertEquals(CameraStatusBadge("CANDIDATE", CameraStatusBadgeTone.Detected), badges.ball)
    }

    @Test fun stableStillBallShowsBallLocked() {
        assertEquals(
            CameraStatusBadge("BALL LOCKED", CameraStatusBadgeTone.Ready),
            badges(AutoShotTrackerStatus.BallLocked).ball,
        )
    }

    @Test fun flightTrackingShowsBallTracking() {
        val badges = CameraStatusBadgeModel.from(
            AutoShotTrackerStatus.Tracking,
            trackingState(ShotTrackerStatus.Tracking),
            ZoneBallDebug(),
        )
        assertEquals(CameraStatusBadge("BALL TRACKING", CameraStatusBadgeTone.Detected), badges.ball)
    }

    private fun badges(status: AutoShotTrackerStatus, debug: ZoneBallDebug = ZoneBallDebug()) =
        CameraStatusBadgeModel.from(status, trackingState(ShotTrackerStatus.Idle), debug)

    private fun trackingState(status: ShotTrackerStatus) = ShotTrackerState(status, emptyList(), false, false)

    private fun candidateDebug() = ZoneBallCandidateDebug(
        candidate = LumaMotionCandidate(0.5, 0.8, 16, 0.8),
        radiusPx = 2.0,
        proposalResponse = 12.0,
        rankScore = 9.35,
        metrics = BallShapeMetrics(4.0, 0.8, 0.7, 0.6, 0.1, 0.2, 0.3),
        rejection = null,
    )
}
