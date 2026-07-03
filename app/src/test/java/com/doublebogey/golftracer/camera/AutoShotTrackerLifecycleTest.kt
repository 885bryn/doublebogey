package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutoShotTrackerLifecycleTest {
    private val zone = LaunchZone(left = 0.0, top = 0.60, width = 1.0, height = 0.40)
    private val detectorConfig = ZoneBallDetectorConfig(
        calibrationFramesRequired = 2,
        minYDelta = 18.0,
        significanceMultiplier = 4.0,
        minMeanSignificance = 6.0,
        minArea = 6,
        maxArea = 200,
    )

    @Test
    fun calibratesLocksLaunchesReviewsThenAutomaticallyRearms() {
        val tracker = tracker(reviewHoldNs = 100L)

        assertEquals(AutoShotTrackerStatus.Calibrating, tracker.update(emptyMatFrame(), resultAt(0L), zone).status)
        assertEquals(AutoShotTrackerStatus.Searching, tracker.update(emptyMatFrame(), resultAt(33L), zone).status)
        assertEquals(AutoShotTrackerStatus.Searching, tracker.update(ballFrame(), resultAt(66L), zone).status)
        val locked = tracker.update(ballFrame(), resultAt(99L), zone)

        assertEquals(AutoShotTrackerStatus.BallLocked, locked.status)
        assertNotNull(locked.lockedBall)

        val launched = tracker.update(
            emptyMatFrame(),
            resultAt(132L, motionCandidates = listOf(candidate(x = 16.0 / 31.0, y = 0.52))),
            zone,
        )

        assertEquals(AutoShotTrackerStatus.Tracking, launched.status)
        assertEquals(ShotTrackerStatus.Tracking, launched.trackingState.status)
        assertEquals(2, launched.trackingState.points.size)

        tracker.update(
            emptyMatFrame(),
            resultAt(165L, motionCandidates = listOf(candidate(x = 16.0 / 31.0, y = 0.42))),
            zone,
        )
        val reviewing = tracker.update(emptyMatFrame(), resultAt(198L), zone)

        assertEquals(AutoShotTrackerStatus.Reviewing, reviewing.status)
        assertTrue(reviewing.trackingState.points.isNotEmpty())

        val rearmed = tracker.update(emptyMatFrame(), resultAt(331L), zone)

        assertEquals(AutoShotTrackerStatus.Searching, rearmed.status)
        assertEquals(ShotTrackerStatus.Idle, rearmed.trackingState.status)
        assertEquals(null, rearmed.lockedBall)
    }

    @Test
    fun restartsCalibrationWhenZoneMotionOccursDuringCalibration() {
        val tracker = tracker()

        tracker.update(emptyMatFrame(), resultAt(0L), zone)
        val moving = tracker.update(ballFrame(), resultAt(33L), zone)

        assertEquals(AutoShotTrackerStatus.Calibrating, moving.status)
        assertEquals(0, moving.acquisitionDebug.calibrationFramesCollected)
    }

    @Test
    fun recalibratesWhenLaunchZoneChanges() {
        val tracker = tracker()
        tracker.update(emptyMatFrame(), resultAt(0L), zone)
        tracker.update(emptyMatFrame(), resultAt(33L), zone)

        val movedZone = zone.copy(left = 0.05, width = 0.90)
        val state = tracker.update(emptyMatFrame(), resultAt(66L), movedZone)

        assertEquals(AutoShotTrackerStatus.Calibrating, state.status)
        assertEquals(1, state.acquisitionDebug.calibrationFramesCollected)
    }

    @Test
    fun unlocksWhenTheStillBallDisappearsForTooLong() {
        val tracker = tracker(lostBallFrames = 2)
        calibrateAndLock(tracker)

        assertEquals(AutoShotTrackerStatus.BallLocked, tracker.update(emptyMatFrame(), resultAt(132L), zone).status)
        val unlocked = tracker.update(emptyMatFrame(), resultAt(165L), zone)

        assertEquals(AutoShotTrackerStatus.Searching, unlocked.status)
        assertEquals(null, unlocked.lockedBall)
    }

    @Test
    fun ignoresBackswingMotionWhileStillBallRemainsAtTheLock() {
        val tracker = tracker()
        calibrateAndLock(tracker)

        val state = tracker.update(
            ballFrame(),
            resultAt(132L, motionCandidates = listOf(candidate(x = 16.0 / 31.0, y = 0.52))),
            zone,
        )

        assertEquals(AutoShotTrackerStatus.BallLocked, state.status)
        assertEquals(ShotTrackerStatus.Idle, state.trackingState.status)
    }

    @Test
    fun staleBackgroundReturnsToCalibration() {
        val tracker = tracker()
        tracker.update(emptyMatFrame(), resultAt(0L), zone)
        tracker.update(emptyMatFrame(), resultAt(33L), zone)

        val stale = tracker.update(emptyMatFrame(globalYShift = 25), resultAt(66L), zone)

        assertEquals(AutoShotTrackerStatus.Calibrating, stale.status)
        assertEquals(true, stale.acquisitionDebug.backgroundStale)
    }

    private fun tracker(
        lostBallFrames: Int = 50,
        reviewHoldNs: Long = 4_000_000_000L,
    ): AutoShotTracker =
        AutoShotTracker(
            delegate = ShotTracker(
                config = ShotTrackerConfig(
                    minStartSpeedPerSecond = 1.0,
                    maxBridgeFrames = 0,
                ),
            ),
            detector = ZoneBallDetector(detectorConfig),
            motionMeter = ZoneMotionMeter(),
            config = AutoShotTrackerConfig(
                stableFramesRequired = 2,
                maxLaunchDistance = 0.30,
                lostBallFrames = lostBallFrames,
                reviewHoldNs = reviewHoldNs,
            ),
        )

    private fun calibrateAndLock(tracker: AutoShotTracker) {
        tracker.update(emptyMatFrame(), resultAt(0L), zone)
        tracker.update(emptyMatFrame(), resultAt(33L), zone)
        tracker.update(ballFrame(), resultAt(66L), zone)
        val locked = tracker.update(ballFrame(), resultAt(99L), zone)
        assertEquals(AutoShotTrackerStatus.BallLocked, locked.status)
    }

    private fun resultAt(
        timestampNs: Long,
        motionCandidates: List<LumaMotionCandidate> = emptyList(),
    ): LumaMotionResult =
        LumaMotionResult(
            timestampNs = timestampNs,
            candidates = motionCandidates,
            matchedPixels = motionCandidates.sumOf { it.pixelCount },
            rejectedLargeBlobPixels = 0,
            rejectedGlobalMotionPixels = 0,
        )

    private fun candidate(x: Double, y: Double, confidence: Double = 0.95): LumaMotionCandidate =
        LumaMotionCandidate(x = x, y = y, pixelCount = 8, confidence = confidence)

    private fun ballFrame(): YuvFrame =
        emptyMatFrame().withDisc(centerX = 16, centerY = 25, radius = 3, y = 170, u = 104, v = 136)

    private fun emptyMatFrame(width: Int = 32, height: Int = 32, globalYShift: Int = 0): YuvFrame {
        val y = ByteArray(width * height)
        val u = ByteArray(width * height)
        val v = ByteArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val index = row * width + column
                val seam = if (column == 6 || row == 24) 20 else 0
                y[index] = (112 + seam + globalYShift).coerceIn(0, 255).toByte()
                u[index] = 100.toByte()
                v[index] = 140.toByte()
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

