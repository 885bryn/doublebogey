package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutoShotTrackerLifecycleTest {
    private val zone = LaunchZone(left = 0.0, top = 0.60, width = 1.0, height = 0.40)
    private val detectorConfig = ZoneBallDetectorConfig(
        calibrationFramesRequired = 2,
        lockThreshold = 8.0,
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
            resultAt(165L, motionCandidates = listOf(candidate(x = 16.0 / 31.0, y = 0.24))),
            zone,
        )
        val reviewing = tracker.update(emptyMatFrame(), resultAt(198L), zone)

        assertEquals(AutoShotTrackerStatus.Reviewing, reviewing.status, reviewing.toString())
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
    fun globalLumaShiftDoesNotForceBackgroundRecalibration() {
        val tracker = tracker()
        tracker.update(emptyMatFrame(), resultAt(0L), zone)
        tracker.update(emptyMatFrame(), resultAt(33L), zone)

        val shifted = tracker.update(emptyMatFrame(globalYShift = 25), resultAt(66L), zone)

        assertEquals(AutoShotTrackerStatus.Searching, shifted.status)
        assertEquals(false, shifted.acquisitionDebug.backgroundStale)
    }

    @Test
    fun locksBallOnRotatedLandscapeFrameUsingPortraitZone() {
        val mapper = FrameCoordinateMapper(90)
        val viewZone = LaunchZone(left = 0.50, top = 0.25, width = 0.25, height = 0.50)
        val tracker = tracker()

        tracker.update(landscapeMatFrame(), resultAt(0L), viewZone, mapper)
        tracker.update(landscapeMatFrame(), resultAt(33L), viewZone, mapper)
        tracker.update(landscapeBallFrame(), resultAt(66L), viewZone, mapper)
        val locked = tracker.update(landscapeBallFrame(), resultAt(99L), viewZone, mapper)

        assertEquals(AutoShotTrackerStatus.BallLocked, locked.status, locked.acquisitionDebug.statusSummary())
        val ball = assertNotNull(locked.lockedBall)
        assertEquals(1.0 - 11.0 / 31.0, ball.x, absoluteTolerance = 0.03)
        assertEquals(23.0 / 47.0, ball.y, absoluteTolerance = 0.03)
    }

    @Test
    fun croppedLaunchZoneAcquisitionReportsFullViewCoordinates() {
        val mapper = FrameCoordinateMapper(90)
        val viewZone = LaunchZone(left = 0.50, top = 0.25, width = 0.25, height = 0.50)
        val tracker = tracker()
        val sourceWidth = 480
        val sourceHeight = 320
        val cropLeft = 120
        val cropTop = 80
        val cropWidth = 240
        val cropHeight = 80
        val matFrame = emptyMatFrame(width = sourceWidth, height = sourceHeight)
        val ballFrame = matFrame.withDisc(centerX = 240, centerY = 120, radius = 4, y = 170, u = 104, v = 136)

        tracker.updateFromLaunchZoneCrop(
            zoneFrame = matFrame.crop(cropLeft, cropTop, cropWidth, cropHeight),
            result = resultAt(0L),
            launchZone = viewZone,
            mapper = mapper,
            cropLeftPx = cropLeft,
            cropTopPx = cropTop,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
        )
        tracker.updateFromLaunchZoneCrop(
            zoneFrame = matFrame.crop(cropLeft, cropTop, cropWidth, cropHeight),
            result = resultAt(33L),
            launchZone = viewZone,
            mapper = mapper,
            cropLeftPx = cropLeft,
            cropTopPx = cropTop,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
        )
        var locked = tracker.updateFromLaunchZoneCrop(
            zoneFrame = ballFrame.crop(cropLeft, cropTop, cropWidth, cropHeight),
            result = resultAt(66L),
            launchZone = viewZone,
            mapper = mapper,
            cropLeftPx = cropLeft,
            cropTopPx = cropTop,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
        )
        repeat(8) { index ->
            locked = tracker.updateFromLaunchZoneCrop(
                zoneFrame = ballFrame.crop(cropLeft, cropTop, cropWidth, cropHeight),
                result = resultAt(99L + index * 33L),
                launchZone = viewZone,
                mapper = mapper,
                cropLeftPx = cropLeft,
                cropTopPx = cropTop,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
            )
        }

        assertEquals(AutoShotTrackerStatus.BallLocked, locked.status, locked.acquisitionDebug.statusSummary())
        val ball = assertNotNull(locked.lockedBall)
        assertEquals(1.0 - 120.0 / 319.0, ball.x, absoluteTolerance = 0.03)
        assertEquals(240.0 / 479.0, ball.y, absoluteTolerance = 0.03)
    }

    private fun landscapeMatFrame(): YuvFrame = emptyMatFrame(width = 48, height = 32)

    private fun landscapeBallFrame(): YuvFrame =
        landscapeMatFrame().withDisc(centerX = 23, centerY = 11, radius = 3, y = 170, u = 104, v = 136)

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

    private fun YuvFrame.crop(left: Int, top: Int, cropWidth: Int, cropHeight: Int): YuvFrame {
        val nextY = ByteArray(cropWidth * cropHeight)
        val nextU = ByteArray(cropWidth * cropHeight)
        val nextV = ByteArray(cropWidth * cropHeight)
        var outputIndex = 0
        for (row in 0 until cropHeight) {
            for (column in 0 until cropWidth) {
                val sourceIndex = (top + row) * width + left + column
                nextY[outputIndex] = y[sourceIndex]
                nextU[outputIndex] = u[sourceIndex]
                nextV[outputIndex] = v[sourceIndex]
                outputIndex += 1
            }
        }
        return YuvFrame(width = cropWidth, height = cropHeight, y = nextY, u = nextU, v = nextV)
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

