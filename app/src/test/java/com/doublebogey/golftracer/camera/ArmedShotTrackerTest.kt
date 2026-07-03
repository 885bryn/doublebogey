package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals

class ArmedShotTrackerTest {
    @Test
    fun ignoresValidLookingLaunchMotionUntilUserArms() {
        val tracker = ArmedShotTracker(delegate = ShotTracker(config = ShotTrackerConfig(minStartSpeedPerSecond = 1.0)))
        val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)

        tracker.update(resultAt(0L, candidate(x = 0.50, y = 0.78)), zone)
        val state = tracker.update(resultAt(100_000_000L, candidate(x = 0.50, y = 0.58)), zone)

        assertEquals(ArmedShotTrackerStatus.Disarmed, state.status)
        assertEquals(ShotTrackerStatus.Idle, state.trackingState.status)
        assertEquals(0, state.trackingState.points.size)
    }

    @Test
    fun tracksValidLaunchMotionAfterUserArms() {
        val tracker = ArmedShotTracker(delegate = ShotTracker(config = ShotTrackerConfig(minStartSpeedPerSecond = 1.0)))
        val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)

        tracker.armNextShot()
        tracker.update(resultAt(0L, candidate(x = 0.50, y = 0.78)), zone)
        val state = tracker.update(resultAt(100_000_000L, candidate(x = 0.50, y = 0.58)), zone)

        assertEquals(ArmedShotTrackerStatus.Tracking, state.status)
        assertEquals(ShotTrackerStatus.Tracking, state.trackingState.status)
        assertEquals(2, state.trackingState.points.size)
    }

    @Test
    fun disarmsWhenArmWindowExpiresWithoutTrack() {
        val tracker = ArmedShotTracker(
            delegate = ShotTracker(config = ShotTrackerConfig(minStartSpeedPerSecond = 1.0)),
            armWindowNs = 100_000_000L,
        )
        val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)

        tracker.armNextShot()
        tracker.update(resultAt(0L), zone)
        val state = tracker.update(resultAt(200_000_000L), zone)

        assertEquals(ArmedShotTrackerStatus.Disarmed, state.status)
        assertEquals(0, state.trackingState.points.size)
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
