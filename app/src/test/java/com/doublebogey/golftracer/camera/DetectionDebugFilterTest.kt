package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetectionDebugFilterTest {
    @Test
    fun idleOverlayDoesNotShowRawMotionWhenStillBallMissing() {
        val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)
        val visible = DetectionDebugFilter.visibleCandidates(
            result = resultWith(
                candidate(x = 0.50, y = 0.80),
                candidate(x = 0.10, y = 0.10),
                candidate(x = 0.90, y = 0.90),
            ),
            launchZone = zone,
            trackingState = ShotTrackerState(
                status = ShotTrackerStatus.Idle,
                points = emptyList(),
                occlusionBridged = false,
                lowConfidence = false,
            ),
        )

        assertTrue(visible.isEmpty())
    }

    @Test
    fun trackingOverlayHidesRawCandidatesSoTrackIsReadable() {
        val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)
        val visible = DetectionDebugFilter.visibleCandidates(
            result = resultWith(candidate(x = 0.50, y = 0.80)),
            launchZone = zone,
            trackingState = ShotTrackerState(
                status = ShotTrackerStatus.Tracking,
                points = listOf(ShotTrackPoint(0L, 0.50, 0.80, 0.9, predicted = false)),
                occlusionBridged = false,
                lowConfidence = false,
            ),
        )

        assertTrue(visible.isEmpty())
    }

    @Test
    fun idleOverlayShowsOnlyBestStillBallCandidateWhenAvailable() {
        val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)
        val visible = DetectionDebugFilter.visibleCandidates(
            result = resultWith(
                candidate(x = 0.50, y = 0.80, confidence = 0.40),
                candidate(x = 0.51, y = 0.80, confidence = 0.30),
                stillCandidates = listOf(
                    candidate(x = 0.49, y = 0.79, confidence = 0.50),
                    candidate(x = 0.50, y = 0.80, confidence = 0.95),
                    candidate(x = 0.51, y = 0.81, confidence = 0.70),
                ),
            ),
            launchZone = zone,
            trackingState = ShotTrackerState(
                status = ShotTrackerStatus.Idle,
                points = emptyList(),
                occlusionBridged = false,
                lowConfidence = false,
            ),
        )

        assertEquals(1, visible.size)
        assertEquals(0.50, visible.single().x)
        assertEquals(0.80, visible.single().y)
    }

    @Test
    fun lockedOverlayShowsOnlyTheLockedBall() {
        val zone = LaunchZone(left = 0.40, top = 0.70, width = 0.20, height = 0.20)
        val lockedBall = candidate(x = 0.50, y = 0.80)
        val visible = DetectionDebugFilter.visibleCandidates(
            trackerState = AutoShotTrackerState(
                status = AutoShotTrackerStatus.BallLocked,
                trackingState = ShotTrackerState(
                    status = ShotTrackerStatus.Idle,
                    points = emptyList(),
                    occlusionBridged = false,
                    lowConfidence = false,
                ),
                lockedBall = lockedBall,
                acquisitionDebug = ZoneBallDebug(
                    candidates = listOf(
                        debugCandidate(candidate(x = 0.49, y = 0.79)),
                        debugCandidate(candidate(x = 0.51, y = 0.81)),
                    ),
                ),
            ),
            launchZone = zone,
            trackingState = ShotTrackerState(
                status = ShotTrackerStatus.Idle,
                points = emptyList(),
                occlusionBridged = false,
                lowConfidence = false,
            ),
        )

        assertEquals(listOf(lockedBall), visible)
    }
    private fun resultWith(
        vararg candidates: LumaMotionCandidate,
        stillCandidates: List<LumaMotionCandidate> = emptyList(),
    ): LumaMotionResult =
        LumaMotionResult(
            timestampNs = 0L,
            candidates = candidates.toList(),
            matchedPixels = candidates.sumOf { it.pixelCount },
            rejectedLargeBlobPixels = 0,
            rejectedGlobalMotionPixels = 0,
            stillCandidates = stillCandidates,
        )

    private fun debugCandidate(candidate: LumaMotionCandidate): ZoneBallCandidateDebug =
        ZoneBallCandidateDebug(
            candidate = candidate,
            area = candidate.pixelCount,
            fillRatio = 1.0,
            aspectRatio = 1.0,
            meanSignificance = 8.0,
            chromaShift = 0.0,
            rankScore = 8.0,
        )
    private fun candidate(x: Double, y: Double, confidence: Double = 0.95): LumaMotionCandidate =
        LumaMotionCandidate(
            x = x,
            y = y,
            pixelCount = 4,
            confidence = confidence,
        )
}
