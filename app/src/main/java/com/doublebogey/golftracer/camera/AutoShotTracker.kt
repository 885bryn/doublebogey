package com.doublebogey.golftracer.camera

import kotlin.math.hypot

enum class AutoShotTrackerStatus {
    @Deprecated("Temporary compile shim; tracker lifecycle is calibration-free")
    Calibrating,
    Searching,
    BallLocked,
    Tracking,
    Reviewing,
}

data class AutoShotTrackerConfig(
    val stableFramesRequired: Int = 1,
    val maxStableDistance: Double = 0.025,
    val maxLaunchDistance: Double = 0.30,
    val lostBallFrames: Int = 50,
    val reviewHoldNs: Long = 4_000_000_000L,
    val minLockConfidence: Double = 0.80,
) {
    init {
        require(stableFramesRequired > 0) { "stableFramesRequired must be greater than 0" }
        require(maxStableDistance > 0.0) { "maxStableDistance must be greater than 0" }
        require(maxLaunchDistance > 0.0) { "maxLaunchDistance must be greater than 0" }
        require(lostBallFrames > 0) { "lostBallFrames must be greater than 0" }
        require(reviewHoldNs >= 0L) { "reviewHoldNs must not be negative" }
        require(minLockConfidence in 0.0..1.0) { "minLockConfidence must be in 0..1" }
    }
}

data class AutoShotTrackerState(
    val status: AutoShotTrackerStatus,
    val trackingState: ShotTrackerState,
    val lockedBall: LumaMotionCandidate?,
    val acquisitionDebug: ZoneBallDebug = ZoneBallDebug(),
)

class AutoShotTracker(
    private val delegate: ShotTracker = ShotTracker(),
    private val detector: ZoneBallDetector = ZoneBallDetector(),
    private val config: AutoShotTrackerConfig = AutoShotTrackerConfig(),
) {
    var status = AutoShotTrackerStatus.Searching
        private set

    private var activeLaunchZone: LaunchZone? = null
    private var lockCandidate: LumaMotionCandidate? = null
    private var lockTimestampNs = 0L
    private var stableFrameCount = 0
    private var lostFrameCount = 0
    private var frozenTrackingState: ShotTrackerState? = null
    private var reviewStartedNs = 0L
    private var lastAcquisitionDebug = ZoneBallDebug()

    fun update(
        frame: YuvFrame,
        result: LumaMotionResult,
        launchZone: LaunchZone,
        mapper: FrameCoordinateMapper = FrameCoordinateMapper.Identity,
    ): AutoShotTrackerState {
        if (activeLaunchZone != launchZone) {
            resetAcquisition(launchZone)
        }

        if (status == AutoShotTrackerStatus.Reviewing) {
            if (result.timestampNs - reviewStartedNs >= config.reviewHoldNs) {
                resetAcquisition(launchZone)
            } else {
                return currentState()
            }
        }

        if (status == AutoShotTrackerStatus.Tracking) {
            val trackingState = delegate.update(result, launchZone)
            if (trackingState.status == ShotTrackerStatus.Finalized) {
                frozenTrackingState = trackingState
                reviewStartedNs = result.timestampNs
                status = AutoShotTrackerStatus.Reviewing
            }
            return currentState()
        }

        val stillDetection = detector.analyzeFrame(frame, launchZone, mapper)
        lastAcquisitionDebug = stillDetection.debug

        when (status) {
            AutoShotTrackerStatus.Searching -> updateBallLock(stillDetection.acceptedCandidate, launchZone, result.timestampNs)
            AutoShotTrackerStatus.BallLocked -> {
                maintainLock(stillDetection.acceptedCandidate)
                if (status == AutoShotTrackerStatus.BallLocked) {
                    maybeStartTracking(result, stillDetection.acceptedCandidate, launchZone)
                }
            }
            else -> Unit
        }

        return currentState()
    }

    fun updateFromLaunchZoneCrop(
        zoneFrame: YuvFrame,
        result: LumaMotionResult,
        launchZone: LaunchZone,
        mapper: FrameCoordinateMapper = FrameCoordinateMapper.Identity,
        cropLeftPx: Int,
        cropTopPx: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): AutoShotTrackerState {
        require(cropLeftPx >= 0) { "cropLeftPx must not be negative" }
        require(cropTopPx >= 0) { "cropTopPx must not be negative" }
        require(sourceWidth > 0) { "sourceWidth must be greater than 0" }
        require(sourceHeight > 0) { "sourceHeight must be greater than 0" }
        require(cropLeftPx + zoneFrame.width <= sourceWidth) { "zoneFrame must fit sourceWidth" }
        require(cropTopPx + zoneFrame.height <= sourceHeight) { "zoneFrame must fit sourceHeight" }

        val detectorZone = LaunchZone(left = 0.0, top = 0.0, width = 1.0, height = 1.0)
        val mapCandidate: (LumaMotionCandidate) -> LumaMotionCandidate = { candidate ->
            val cropXDenominator = (zoneFrame.width - 1).coerceAtLeast(1).toDouble()
            val cropYDenominator = (zoneFrame.height - 1).coerceAtLeast(1).toDouble()
            val sourceXDenominator = (sourceWidth - 1).coerceAtLeast(1).toDouble()
            val sourceYDenominator = (sourceHeight - 1).coerceAtLeast(1).toDouble()
            val frameX = (cropLeftPx + candidate.x * cropXDenominator) / sourceXDenominator
            val frameY = (cropTopPx + candidate.y * cropYDenominator) / sourceYDenominator
            val viewPoint = mapper.frameToView(frameX, frameY)
            candidate.copy(x = viewPoint.x, y = viewPoint.y)
        }

        if (activeLaunchZone != launchZone) {
            resetAcquisition(launchZone)
        }

        if (status == AutoShotTrackerStatus.Reviewing) {
            if (result.timestampNs - reviewStartedNs >= config.reviewHoldNs) {
                resetAcquisition(launchZone)
            } else {
                return currentState()
            }
        }

        if (status == AutoShotTrackerStatus.Tracking) {
            val trackingState = delegate.update(result, launchZone)
            if (trackingState.status == ShotTrackerStatus.Finalized) {
                frozenTrackingState = trackingState
                reviewStartedNs = result.timestampNs
                status = AutoShotTrackerStatus.Reviewing
            }
            return currentState()
        }

        val stillDetection = detector.analyzeFrame(zoneFrame, detectorZone, FrameCoordinateMapper.Identity)
            .mapCandidates(mapCandidate)
        lastAcquisitionDebug = stillDetection.debug

        when (status) {
            AutoShotTrackerStatus.Searching -> updateBallLock(stillDetection.acceptedCandidate, launchZone, result.timestampNs)
            AutoShotTrackerStatus.BallLocked -> {
                maintainLock(stillDetection.acceptedCandidate)
                if (status == AutoShotTrackerStatus.BallLocked) {
                    maybeStartTracking(result, stillDetection.acceptedCandidate, launchZone)
                }
            }
            else -> Unit
        }

        return currentState()
    }

    fun update(result: LumaMotionResult, launchZone: LaunchZone): AutoShotTrackerState {
        if (activeLaunchZone != launchZone) {
            resetAcquisition(launchZone)
        }

        if (status == AutoShotTrackerStatus.Reviewing) return currentState()

        if (status == AutoShotTrackerStatus.Tracking) {
            val trackingState = delegate.update(result, launchZone)
            if (trackingState.status == ShotTrackerStatus.Finalized) {
                frozenTrackingState = trackingState
                reviewStartedNs = result.timestampNs
                status = AutoShotTrackerStatus.Reviewing
            }
            return currentState()
        }

        if (status == AutoShotTrackerStatus.Searching) updateLegacyBallLock(result, launchZone)
        if (status == AutoShotTrackerStatus.BallLocked) maybeStartTracking(result, null, launchZone)
        return currentState()
    }

    fun resetForNextShot(): AutoShotTrackerState {
        reset()
        return currentState()
    }

    fun reset() {
        resetAcquisition(null)
    }

    private fun resetAcquisition(launchZone: LaunchZone?) {
        delegate.reset()
        detector.reset()
        activeLaunchZone = launchZone
        clearLock()
        frozenTrackingState = null
        reviewStartedNs = 0L
        status = AutoShotTrackerStatus.Searching
        lastAcquisitionDebug = ZoneBallDebug()
    }

    private fun updateBallLock(candidate: LumaMotionCandidate?, launchZone: LaunchZone, timestampNs: Long) {
        val current = candidate?.takeIf { launchZone.contains(it.x, it.y) } ?: run {
            clearLock()
            return
        }
        val previous = lockCandidate
        if (previous == null || distance(previous.x, previous.y, current.x, current.y) > config.maxStableDistance) {
            lockCandidate = current
            lockTimestampNs = timestampNs
            stableFrameCount = 1
        } else {
            lockCandidate = current
            lockTimestampNs = timestampNs
            stableFrameCount += 1
        }
        lostFrameCount = 0
        if (stableFrameCount >= config.stableFramesRequired) status = AutoShotTrackerStatus.BallLocked
    }

    private fun updateLegacyBallLock(result: LumaMotionResult, launchZone: LaunchZone) {
        val candidates = result.stillCandidates.filter { candidate ->
            launchZone.contains(candidate.x, candidate.y) && candidate.confidence >= config.minLockConfidence
        }
        if (candidates.size != 1) {
            clearLock()
            return
        }
        val current = candidates.single()
        val previous = lockCandidate
        if (previous == null || distance(previous.x, previous.y, current.x, current.y) > config.maxStableDistance) {
            lockCandidate = current
            lockTimestampNs = result.timestampNs
            stableFrameCount = 1
        } else {
            lockCandidate = current
            lockTimestampNs = result.timestampNs
            stableFrameCount += 1
        }
        if (stableFrameCount >= config.stableFramesRequired) status = AutoShotTrackerStatus.BallLocked
    }

    private fun maintainLock(candidate: LumaMotionCandidate?) {
        val locked = lockCandidate ?: return
        val nearLock = candidate?.takeIf {
            distance(locked.x, locked.y, it.x, it.y) <= config.maxStableDistance
        }
        if (nearLock == null) {
            lostFrameCount += 1
            if (lostFrameCount >= config.lostBallFrames) clearLock()
            return
        }
        lockCandidate = nearLock
        lostFrameCount = 0
    }

    private fun maybeStartTracking(
        result: LumaMotionResult,
        stillCandidate: LumaMotionCandidate?,
        launchZone: LaunchZone,
    ) {
        val locked = lockCandidate ?: return
        val stillAtLock = stillCandidate != null &&
            distance(locked.x, locked.y, stillCandidate.x, stillCandidate.y) <= config.maxStableDistance
        if (stillAtLock) return

        val launchCandidates = result.candidates.filter { candidate ->
            candidate.y < launchZone.top &&
                distance(locked.x, locked.y, candidate.x, candidate.y) <= config.maxLaunchDistance
        }
        if (launchCandidates.isEmpty()) return

        delegate.reset()
        delegate.update(
            result.copy(timestampNs = lockTimestampNs, candidates = listOf(locked), stillCandidates = emptyList()),
            launchZone,
        )
        val trackingState = delegate.update(
            result.copy(candidates = launchCandidates, stillCandidates = emptyList()),
            launchZone,
        )
        if (trackingState.status == ShotTrackerStatus.Tracking) status = AutoShotTrackerStatus.Tracking
    }

    private fun clearLock() {
        if (status == AutoShotTrackerStatus.BallLocked) status = AutoShotTrackerStatus.Searching
        lockCandidate = null
        lockTimestampNs = 0L
        stableFrameCount = 0
        lostFrameCount = 0
    }

    private fun ZoneBallDetection.mapCandidates(
        transform: (LumaMotionCandidate) -> LumaMotionCandidate,
    ): ZoneBallDetection = copy(
        acceptedCandidate = acceptedCandidate?.let(transform),
        debug = debug.copy(
            candidates = debug.candidates.map { candidateDebug ->
                candidateDebug.copy(candidate = transform(candidateDebug.candidate))
            },
        ),
    )

    private fun currentState(): AutoShotTrackerState =
        AutoShotTrackerState(
            status = status,
            trackingState = frozenTrackingState ?: delegate.currentState(),
            lockedBall = lockCandidate,
            acquisitionDebug = lastAcquisitionDebug,
        )

    private fun LaunchZone.contains(x: Double, y: Double): Boolean =
        x >= left && x <= left + width && y >= top && y <= top + height

    private fun distance(ax: Double, ay: Double, bx: Double, by: Double): Double = hypot(ax - bx, ay - by)
}

