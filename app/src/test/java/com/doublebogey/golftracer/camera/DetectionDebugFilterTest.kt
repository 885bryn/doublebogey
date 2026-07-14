package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetectionDebugFilterTest {
    private val zone = LaunchZone(0.40, 0.70, 0.20, 0.20)
    private val idle = trackingState(ShotTrackerStatus.Idle)

    @Test fun searchingOverlayShowsOnlyVerifiedBestCandidate() {
        val best = candidate(0.50, 0.80)
        val runnerUp = candidate(0.51, 0.81)
        val visible = DetectionDebugFilter.visibleCandidates(
            trackerState(ZoneBallDebug(candidates = listOf(debug(best), debug(runnerUp)))), zone, idle,
        )
        assertEquals(listOf(best), visible)
    }

    @Test fun lockedOverlayShowsOnlyLockedBall() {
        val locked = candidate(0.50, 0.80)
        val visible = DetectionDebugFilter.visibleCandidates(
            trackerState(
                debug = ZoneBallDebug(candidates = listOf(debug(candidate(0.49, 0.79)))),
                status = AutoShotTrackerStatus.BallLocked,
                lockedBall = locked,
            ), zone, idle,
        )
        assertEquals(listOf(locked), visible)
    }

    @Test fun rejectedProposalNeverAppearsInOverlay() {
        val visible = DetectionDebugFilter.visibleCandidates(
            trackerState(ZoneBallDebug(topRejected = debug(candidate(0.50, 0.80)))), zone, idle,
        )
        assertTrue(visible.isEmpty())
    }

    @Test fun rawMotionResultNeverAppearsInOverlay() {
        val motion = candidate(0.50, 0.80)
        val visible = DetectionDebugFilter.visibleCandidates(
            LumaMotionResult(0L, listOf(motion), 16, 0, 0, stillCandidates = listOf(motion)), zone, idle,
        )
        assertTrue(visible.isEmpty())
    }

    @Test fun activeTrackHidesStillBallOverlay() {
        val visible = DetectionDebugFilter.visibleCandidates(
            trackerState(ZoneBallDebug(candidates = listOf(debug(candidate(0.50, 0.80))))),
            zone,
            trackingState(ShotTrackerStatus.Tracking, hasPoint = true),
        )
        assertTrue(visible.isEmpty())
    }

    private fun trackerState(
        debug: ZoneBallDebug,
        status: AutoShotTrackerStatus = AutoShotTrackerStatus.Searching,
        lockedBall: LumaMotionCandidate? = null,
    ) = AutoShotTrackerState(status, idle, lockedBall, debug)

    private fun trackingState(status: ShotTrackerStatus, hasPoint: Boolean = false) = ShotTrackerState(
        status, if (hasPoint) listOf(ShotTrackPoint(0L, 0.5, 0.8, 0.9, false)) else emptyList(), false, false,
    )

    private fun debug(candidate: LumaMotionCandidate) = ZoneBallCandidateDebug(
        candidate, 2.0, 10.0, 8.0, BallShapeMetrics(4.0, 0.8, 0.7, 0.6, 0.1, 0.2, 0.3), null,
    )

    private fun candidate(x: Double, y: Double) = LumaMotionCandidate(x, y, 16, 0.9)
}
