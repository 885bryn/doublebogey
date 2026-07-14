package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShotTrackerTest {
    @Test
    fun startsTrackingWhenCandidateExitsLaunchZoneUpwardFastEnough() {
        val tracker = ShotTracker(config = ShotTrackerConfig(minStartSpeedPerSecond = 1.0))
        val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)

        val idle = tracker.update(resultAt(0L, candidate(x = 0.50, y = 0.78)), zone)
        val tracking = tracker.update(resultAt(100_000_000L, candidate(x = 0.50, y = 0.58)), zone)

        assertEquals(ShotTrackerStatus.Idle, idle.status)
        assertEquals(ShotTrackerStatus.Tracking, tracking.status)
        assertEquals(2, tracking.points.size)
        assertEquals(0.50, tracking.points.last().x)
        assertEquals(0.58, tracking.points.last().y)
    }

    @Test
    fun doesNotStartTrackingWhenCandidateLeavesThroughSideOfLaunchZone() {
        val tracker = ShotTracker(config = ShotTrackerConfig(minStartSpeedPerSecond = 1.0))
        val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)

        tracker.update(resultAt(0L, candidate(x = 0.50, y = 0.78)), zone)
        val state = tracker.update(resultAt(100_000_000L, candidate(x = 0.70, y = 0.76)), zone)

        assertEquals(ShotTrackerStatus.Idle, state.status)
        assertEquals(0, state.points.size)
    }

    @Test
    fun discardsTwoPointFalseStartInsteadOfPredictingAcrossFrame() {
        val tracker = ShotTracker(
            config = ShotTrackerConfig(
                minStartSpeedPerSecond = 1.0,
                maxBridgeFrames = 2,
            ),
        )
        val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)

        tracker.update(resultAt(0L, candidate(x = 0.50, y = 0.78)), zone)
        tracker.update(resultAt(100_000_000L, candidate(x = 0.50, y = 0.58)), zone)
        val state = tracker.update(resultAt(200_000_000L), zone)

        assertEquals(ShotTrackerStatus.Idle, state.status)
        assertEquals(0, state.points.size)
    }

    @Test
    fun doesNotStartTrackingForSlowMotionFromLaunchZone() {
        val tracker = ShotTracker(config = ShotTrackerConfig(minStartSpeedPerSecond = 1.0))
        val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)

        tracker.update(resultAt(0L, candidate(x = 0.50, y = 0.78)), zone)
        val state = tracker.update(resultAt(100_000_000L, candidate(x = 0.50, y = 0.69)), zone)

        assertEquals(ShotTrackerStatus.Idle, state.status)
        assertEquals(0, state.points.size)
    }

    @Test
    fun doesNotStartTrackingFromUnrelatedFarAwayCandidateAfterLaunchZoneNoise() {
        val tracker = ShotTracker(config = ShotTrackerConfig(minStartSpeedPerSecond = 1.0, maxStartDistance = 0.18))
        val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)

        tracker.update(resultAt(0L, candidate(x = 0.50, y = 0.78)), zone)
        val state = tracker.update(resultAt(100_000_000L, candidate(x = 0.10, y = 0.10)), zone)

        assertEquals(ShotTrackerStatus.Idle, state.status)
        assertEquals(0, state.points.size)
    }

    @Test
    fun doesNotStartTrackingWhenCandidateDidNotOriginateInLaunchZone() {
        val tracker = ShotTracker(config = ShotTrackerConfig(minStartSpeedPerSecond = 1.0))
        val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)

        tracker.update(resultAt(0L, candidate(x = 0.20, y = 0.60)), zone)
        val state = tracker.update(resultAt(100_000_000L, candidate(x = 0.20, y = 0.40)), zone)

        assertEquals(ShotTrackerStatus.Idle, state.status)
    }


    @Test
    fun rejectsCandidateThatMovesBackwardAlongLaunchPath() {
        val tracker = ShotTracker(
            config = ShotTrackerConfig(
                minStartSpeedPerSecond = 0.1,
                maxMatchDistance = 0.12,
            ),
        )
        val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)

        tracker.update(resultAt(0L, candidate(x = 0.50, y = 0.78)), zone)
        tracker.update(resultAt(100_000_000L, candidate(x = 0.50, y = 0.69)), zone)
        tracker.update(resultAt(200_000_000L, candidate(x = 0.50, y = 0.60)), zone)
        val state = tracker.update(resultAt(300_000_000L, candidate(x = 0.50, y = 0.63)), zone)

        assertEquals(ShotTrackerStatus.Tracking, state.status)
        assertEquals(4, state.points.size)
        assertTrue(state.points.last().predicted)
        assertEquals(0.51, state.points.last().y, absoluteTolerance = 0.000000001)
    }
    @Test
    fun followsCandidateNearestPredictedPositionWhileTracking() {
        val tracker = ShotTracker(config = ShotTrackerConfig(minStartSpeedPerSecond = 1.0, maxMatchDistance = 0.10))
        val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)

        tracker.update(resultAt(0L, candidate(x = 0.50, y = 0.78)), zone)
        tracker.update(resultAt(100_000_000L, candidate(x = 0.50, y = 0.58)), zone)
        val state = tracker.update(
            resultAt(
                200_000_000L,
                candidate(x = 0.51, y = 0.39),
                candidate(x = 0.85, y = 0.85),
            ),
            zone,
        )

        assertEquals(ShotTrackerStatus.Tracking, state.status)
        assertEquals(3, state.points.size)
        assertEquals(0.51, state.points.last().x)
        assertEquals(0.39, state.points.last().y)
    }

    @Test
    fun rejectsCandidateNearPredictionWhenAccelerationIsImplausible() {
        val tracker = ShotTracker(
            config = ShotTrackerConfig(
                minStartSpeedPerSecond = 1.0,
                maxMatchDistance = 0.12,
                maxBridgeFrames = 2,
                maxAccelerationPerSecondSquared = 5.0,
            ),
        )
        val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)

        tracker.update(resultAt(0L, candidate(x = 0.50, y = 0.78)), zone)
        tracker.update(resultAt(100_000_000L, candidate(x = 0.50, y = 0.58)), zone)
        tracker.update(resultAt(200_000_000L, candidate(x = 0.50, y = 0.38)), zone)
        val state = tracker.update(resultAt(300_000_000L, candidate(x = 0.60, y = 0.18)), zone)

        assertEquals(ShotTrackerStatus.Tracking, state.status)
        assertTrue(state.points.last().predicted)
        assertEquals(0.50, state.points.last().x, absoluteTolerance = 0.000000001)
        assertEquals(0.18, state.points.last().y, absoluteTolerance = 0.000000001)
    }

    @Test
    fun bridgesShortOcclusionWithPredictedPointAndReacquires() {
        val tracker = ShotTracker(
            config = ShotTrackerConfig(
                minStartSpeedPerSecond = 1.0,
                maxMatchDistance = 0.12,
                maxBridgeFrames = 2,
            ),
        )
        val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)

        tracker.update(resultAt(0L, candidate(x = 0.50, y = 0.78)), zone)
        tracker.update(resultAt(100_000_000L, candidate(x = 0.50, y = 0.58)), zone)
        tracker.update(resultAt(200_000_000L, candidate(x = 0.50, y = 0.39)), zone)
        val bridged = tracker.update(resultAt(300_000_000L), zone)
        val reacquired = tracker.update(resultAt(400_000_000L, candidate(x = 0.50, y = 0.00)), zone)

        assertEquals(ShotTrackerStatus.Tracking, bridged.status)
        assertTrue(bridged.points.last().predicted)
        assertEquals(ShotTrackerStatus.Tracking, reacquired.status)
        assertFalse(reacquired.points.last().predicted)
        assertTrue(reacquired.occlusionBridged)
    }

    @Test
    fun finalizesWhenOcclusionExceedsBridgeWindow() {
        val tracker = ShotTracker(config = ShotTrackerConfig(minStartSpeedPerSecond = 1.0, maxBridgeFrames = 1))
        val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)

        tracker.update(resultAt(0L, candidate(x = 0.50, y = 0.78)), zone)
        tracker.update(resultAt(100_000_000L, candidate(x = 0.50, y = 0.58)), zone)
        tracker.update(resultAt(200_000_000L, candidate(x = 0.50, y = 0.39)), zone)
        tracker.update(resultAt(300_000_000L), zone)
        val finalized = tracker.update(resultAt(400_000_000L), zone)

        assertEquals(ShotTrackerStatus.Finalized, finalized.status)
        assertTrue(finalized.lowConfidence)
    }

    private fun resultAt(timestampNs: Long, vararg candidates: LumaMotionCandidate): LumaMotionResult =
        LumaMotionResult(
            timestampNs = timestampNs,
            candidates = candidates.toList(),
            matchedPixels = candidates.sumOf { it.pixelCount },
            rejectedLargeBlobPixels = 0,
            rejectedGlobalMotionPixels = 0,
        )

    private fun candidate(x: Double, y: Double): LumaMotionCandidate =
        LumaMotionCandidate(
            x = x,
            y = y,
            pixelCount = 4,
            confidence = 0.95,
        )
}
