package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutoShotTrackerTest {
    private val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)

    @Test
    fun locksSingleStableStillBallInsideLaunchZone() {
        val tracker = AutoShotTracker(config = AutoShotTrackerConfig(stableFramesRequired = 3))

        tracker.update(resultAt(0L, stillCandidates = listOf(candidate(x = 0.50, y = 0.80))), zone)
        tracker.update(resultAt(33_000_000L, stillCandidates = listOf(candidate(x = 0.505, y = 0.795))), zone)
        val state = tracker.update(resultAt(66_000_000L, stillCandidates = listOf(candidate(x = 0.502, y = 0.798))), zone)

        assertEquals(AutoShotTrackerStatus.BallLocked, state.status)
        val lockedBall = assertNotNull(state.lockedBall)
        assertEquals(0.502, lockedBall.x)
        assertEquals(ShotTrackerStatus.Idle, state.trackingState.status)
    }

    @Test
    fun ignoresLowConfidenceStillBallCandidatesForLock() {
        val tracker = AutoShotTracker(
            config = AutoShotTrackerConfig(stableFramesRequired = 2, minLockConfidence = 0.80),
        )

        tracker.update(resultAt(0L, stillCandidates = listOf(candidate(x = 0.50, y = 0.80, confidence = 0.60))), zone)
        val state = tracker.update(
            resultAt(33_000_000L, stillCandidates = listOf(candidate(x = 0.50, y = 0.80, confidence = 0.60))),
            zone,
        )

        assertEquals(AutoShotTrackerStatus.Searching, state.status)
        assertEquals(null, state.lockedBall)
    }

    @Test
    fun startsTrackingWhenMotionLaunchesFromLockedBall() {
        val tracker = AutoShotTracker(
            delegate = ShotTracker(config = ShotTrackerConfig(minStartSpeedPerSecond = 1.0)),
            config = AutoShotTrackerConfig(stableFramesRequired = 2, maxLaunchDistance = 0.20),
        )

        tracker.update(resultAt(0L, stillCandidates = listOf(candidate(x = 0.50, y = 0.80))), zone)
        tracker.update(resultAt(33_000_000L, stillCandidates = listOf(candidate(x = 0.50, y = 0.80))), zone)
        val state = tracker.update(resultAt(100_000_000L, motionCandidates = listOf(candidate(x = 0.50, y = 0.62))), zone)

        assertEquals(AutoShotTrackerStatus.Tracking, state.status)
        assertEquals(ShotTrackerStatus.Tracking, state.trackingState.status)
        assertEquals(2, state.trackingState.points.size)
        assertEquals(0.50, state.trackingState.points.first().x)
        assertEquals(0.80, state.trackingState.points.first().y)
    }

    @Test
    fun ignoresLaunchMotionThatDoesNotOriginateNearLockedBall() {
        val tracker = AutoShotTracker(
            delegate = ShotTracker(config = ShotTrackerConfig(minStartSpeedPerSecond = 1.0)),
            config = AutoShotTrackerConfig(stableFramesRequired = 2, maxLaunchDistance = 0.12),
        )

        tracker.update(resultAt(0L, stillCandidates = listOf(candidate(x = 0.50, y = 0.80))), zone)
        tracker.update(resultAt(33_000_000L, stillCandidates = listOf(candidate(x = 0.50, y = 0.80))), zone)
        val state = tracker.update(resultAt(100_000_000L, motionCandidates = listOf(candidate(x = 0.65, y = 0.62))), zone)

        assertEquals(AutoShotTrackerStatus.BallLocked, state.status)
        assertEquals(ShotTrackerStatus.Idle, state.trackingState.status)
        assertTrue(state.trackingState.points.isEmpty())
    }

    @Test
    fun freezesCompletedShotUntilResetForNextShot() {
        val tracker = AutoShotTracker(
            delegate = ShotTracker(
                config = ShotTrackerConfig(
                    minStartSpeedPerSecond = 1.0,
                    maxBridgeFrames = 0,
                ),
            ),
            config = AutoShotTrackerConfig(stableFramesRequired = 2),
        )

        tracker.update(resultAt(0L, stillCandidates = listOf(candidate(x = 0.50, y = 0.80))), zone)
        tracker.update(resultAt(33_000_000L, stillCandidates = listOf(candidate(x = 0.50, y = 0.80))), zone)
        tracker.update(resultAt(100_000_000L, motionCandidates = listOf(candidate(x = 0.50, y = 0.62))), zone)
        tracker.update(resultAt(200_000_000L, motionCandidates = listOf(candidate(x = 0.50, y = 0.44))), zone)
        val reviewing = tracker.update(resultAt(300_000_000L), zone)
        val frozenCount = reviewing.trackingState.points.size

        val ignored = tracker.update(
            resultAt(
                400_000_000L,
                stillCandidates = listOf(candidate(x = 0.52, y = 0.82)),
                motionCandidates = listOf(candidate(x = 0.52, y = 0.60)),
            ),
            zone,
        )

        assertEquals(AutoShotTrackerStatus.Reviewing, reviewing.status)
        assertEquals(AutoShotTrackerStatus.Reviewing, ignored.status)
        assertEquals(frozenCount, ignored.trackingState.points.size)
        assertTrue(ignored.trackingState.points.isNotEmpty())

        val reset = tracker.resetForNextShot()

        assertEquals(AutoShotTrackerStatus.Searching, reset.status)
        assertEquals(0, reset.trackingState.points.size)
    }

    @Test
    fun newTrackerStartsSearchingAndDefaultConfigLocksConfirmedCandidateImmediately() {
        val tracker = AutoShotTracker()

        assertEquals(AutoShotTrackerStatus.Searching, tracker.status)

        val locked = tracker.update(
            resultAt(0L, stillCandidates = listOf(candidate(x = 0.50, y = 0.80))),
            zone,
        )

        assertEquals(AutoShotTrackerStatus.BallLocked, locked.status)
    }

    @Test
    fun resultOnlyZoneChangeRestartsStableConfirmation() {
        val tracker = AutoShotTracker(
            config = AutoShotTrackerConfig(stableFramesRequired = 2),
        )
        val movedZone = zone.copy(left = 0.41)

        val firstZone = tracker.update(
            resultAt(0L, stillCandidates = listOf(candidate(x = 0.50, y = 0.80))),
            zone,
        )
        val firstMovedZone = tracker.update(
            resultAt(33_000_000L, stillCandidates = listOf(candidate(x = 0.51, y = 0.80))),
            movedZone,
        )

        assertEquals(AutoShotTrackerStatus.Searching, firstZone.status)
        assertEquals(AutoShotTrackerStatus.Searching, firstMovedZone.status)
        assertEquals(0.51, firstMovedZone.lockedBall?.x)

        val locked = tracker.update(
            resultAt(66_000_000L, stillCandidates = listOf(candidate(x = 0.51, y = 0.80))),
            movedZone,
        )

        assertEquals(AutoShotTrackerStatus.BallLocked, locked.status)
        assertEquals(0.51, locked.lockedBall?.x)
    }

    @Test
    fun resultOnlyReviewRearmsAtExactHoldBoundaryWithFreshConfirmation() {
        val tracker = AutoShotTracker(
            delegate = ShotTracker(
                config = ShotTrackerConfig(
                    minStartSpeedPerSecond = 1.0,
                    maxBridgeFrames = 0,
                ),
            ),
            config = AutoShotTrackerConfig(
                stableFramesRequired = 2,
                reviewHoldNs = 100_000_000L,
            ),
        )

        tracker.update(resultAt(0L, stillCandidates = listOf(candidate(x = 0.50, y = 0.80))), zone)
        tracker.update(resultAt(33_000_000L, stillCandidates = listOf(candidate(x = 0.50, y = 0.80))), zone)
        tracker.update(
            resultAt(100_000_000L, motionCandidates = listOf(candidate(x = 0.50, y = 0.62))),
            zone,
        )
        tracker.update(
            resultAt(200_000_000L, motionCandidates = listOf(candidate(x = 0.50, y = 0.44))),
            zone,
        )
        val reviewing = tracker.update(resultAt(300_000_000L), zone)
        val frozenCount = reviewing.trackingState.points.size

        val beforeExpiry = tracker.update(
            resultAt(399_999_999L, stillCandidates = listOf(candidate(x = 0.52, y = 0.82))),
            zone,
        )

        assertEquals(AutoShotTrackerStatus.Reviewing, beforeExpiry.status)
        assertEquals(frozenCount, beforeExpiry.trackingState.points.size)
        assertEquals(0.50, beforeExpiry.lockedBall?.x)

        val rearmed = tracker.update(
            resultAt(400_000_000L, stillCandidates = listOf(candidate(x = 0.52, y = 0.82))),
            zone,
        )

        assertEquals(AutoShotTrackerStatus.Searching, rearmed.status)
        assertEquals(ShotTrackerStatus.Idle, rearmed.trackingState.status)
        assertTrue(rearmed.trackingState.points.isEmpty())
        assertEquals(0.52, rearmed.lockedBall?.x)

        val locked = tracker.update(
            resultAt(433_000_000L, stillCandidates = listOf(candidate(x = 0.52, y = 0.82))),
            zone,
        )

        assertEquals(AutoShotTrackerStatus.BallLocked, locked.status)
        assertEquals(0.52, locked.lockedBall?.x)
    }

    private fun resultAt(
        timestampNs: Long,
        motionCandidates: List<LumaMotionCandidate> = emptyList(),
        stillCandidates: List<LumaMotionCandidate> = emptyList(),
    ): LumaMotionResult =
        LumaMotionResult(
            timestampNs = timestampNs,
            candidates = motionCandidates,
            matchedPixels = motionCandidates.sumOf { it.pixelCount },
            rejectedLargeBlobPixels = 0,
            rejectedGlobalMotionPixels = 0,
            stillCandidates = stillCandidates,
        )

    private fun candidate(x: Double, y: Double, confidence: Double = 0.95): LumaMotionCandidate =
        LumaMotionCandidate(
            x = x,
            y = y,
            pixelCount = 4,
            confidence = confidence,
        )
}

