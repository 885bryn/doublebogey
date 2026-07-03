package com.doublebogey.golftracer.camera

import kotlin.math.hypot

data class ShotTrackerConfig(
    val minStartSpeedPerSecond: Double = 1.0,
    val maxStartDistance: Double = 0.30,
    val maxMatchDistance: Double = 0.12,
    val maxBridgeFrames: Int = 4,
    val minObservedPointsBeforeBridge: Int = 3,
) {
    init {
        require(minStartSpeedPerSecond > 0.0) { "minStartSpeedPerSecond must be greater than 0" }
        require(maxStartDistance > 0.0) { "maxStartDistance must be greater than 0" }
        require(maxMatchDistance > 0.0) { "maxMatchDistance must be greater than 0" }
        require(maxBridgeFrames >= 0) { "maxBridgeFrames must not be negative" }
        require(minObservedPointsBeforeBridge >= 2) { "minObservedPointsBeforeBridge must be at least 2" }
    }
}

enum class ShotTrackerStatus {
    Idle,
    Tracking,
    Finalized,
}

data class ShotTrackPoint(
    val timestampNs: Long,
    val x: Double,
    val y: Double,
    val confidence: Double,
    val predicted: Boolean,
)

data class ShotTrackerState(
    val status: ShotTrackerStatus,
    val points: List<ShotTrackPoint>,
    val occlusionBridged: Boolean,
    val lowConfidence: Boolean,
)

class ShotTracker(
    private val config: ShotTrackerConfig = ShotTrackerConfig(),
) {
    private var previousIdleCandidate: TimedCandidate? = null
    private val trackPoints = mutableListOf<ShotTrackPoint>()
    private var status = ShotTrackerStatus.Idle
    private var bridgeFrames = 0
    private var occlusionBridged = false
    private var lowConfidence = false

    fun update(result: LumaMotionResult, launchZone: LaunchZone): ShotTrackerState {
        return when (status) {
            ShotTrackerStatus.Idle -> updateIdle(result, launchZone)
            ShotTrackerStatus.Tracking -> updateTracking(result)
            ShotTrackerStatus.Finalized -> currentState()
        }
    }

    fun reset() {
        previousIdleCandidate = null
        trackPoints.clear()
        status = ShotTrackerStatus.Idle
        bridgeFrames = 0
        occlusionBridged = false
        lowConfidence = false
    }

    private fun updateIdle(result: LumaMotionResult, launchZone: LaunchZone): ShotTrackerState {
        val launchZoneCandidates = result.candidates.filter { candidate ->
            launchZone.contains(candidate.x, candidate.y)
        }
        val current = result.candidates.maxByOrNull { candidate -> candidate.confidence }
        val currentInZone = launchZoneCandidates.maxByOrNull { candidate -> candidate.confidence }
        val previous = previousIdleCandidate

        if (current != null && previous != null && shouldStart(previous, current, result.timestampNs, launchZone)) {
            status = ShotTrackerStatus.Tracking
            trackPoints.clear()
            trackPoints += previous.toTrackPoint()
            trackPoints += current.toTrackPoint(result.timestampNs, predicted = false)
            bridgeFrames = 0
            return currentState()
        }

        previousIdleCandidate = currentInZone?.let { TimedCandidate(result.timestampNs, it) }
        return currentState()
    }

    private fun updateTracking(result: LumaMotionResult): ShotTrackerState {
        val predicted = predictNext(result.timestampNs)
        val plausibleCandidates = result.candidates.filter { candidate -> continuesObservedTrajectory(candidate) }
        val match = predicted?.let { prediction ->
            plausibleCandidates
                .map { candidate -> candidate to distance(candidate.x, candidate.y, prediction.x, prediction.y) }
                .filter { (_, matchDistance) -> matchDistance <= config.maxMatchDistance }
                .minByOrNull { (_, matchDistance) -> matchDistance }
                ?.first
        } ?: plausibleCandidates.maxByOrNull { candidate -> candidate.confidence }

        if (match != null) {
            trackPoints += match.toTrackPoint(result.timestampNs, predicted = false)
            if (bridgeFrames > 0) {
                occlusionBridged = true
            }
            bridgeFrames = 0
            return currentState()
        }

        val observedPointCount = trackPoints.count { point -> !point.predicted }
        if (observedPointCount < config.minObservedPointsBeforeBridge) {
            reset()
            return currentState()
        }

        if (predicted != null && bridgeFrames < config.maxBridgeFrames) {
            bridgeFrames += 1
            trackPoints += predicted.copy(predicted = true)
            return currentState()
        }

        status = ShotTrackerStatus.Finalized
        lowConfidence = bridgeFrames > 0
        return currentState()
    }

    private fun shouldStart(
        previous: TimedCandidate,
        current: LumaMotionCandidate,
        currentTimestampNs: Long,
        launchZone: LaunchZone,
    ): Boolean {
        val elapsedSeconds = (currentTimestampNs - previous.timestampNs) / 1_000_000_000.0
        if (elapsedSeconds <= 0.0) {
            return false
        }

        val startedInZone = launchZone.contains(previous.candidate.x, previous.candidate.y)
        val exitedThroughTop = current.y < launchZone.top
        val movingUpward = current.y < previous.candidate.y
        val travelDistance = distance(previous.candidate.x, previous.candidate.y, current.x, current.y)
        val speed = travelDistance / elapsedSeconds
        return startedInZone &&
            exitedThroughTop &&
            movingUpward &&
            travelDistance <= config.maxStartDistance &&
            speed >= config.minStartSpeedPerSecond
    }

    private fun continuesObservedTrajectory(candidate: LumaMotionCandidate): Boolean {
        val observed = trackPoints.filterNot { point -> point.predicted }
        if (observed.size < 2) {
            return true
        }

        val previous = observed[observed.lastIndex - 1]
        val current = observed.last()
        val velocityX = current.x - previous.x
        val velocityY = current.y - previous.y
        val candidateStepX = candidate.x - current.x
        val candidateStepY = candidate.y - current.y
        val velocityMagnitude = distance(0.0, 0.0, velocityX, velocityY)
        val candidateStepMagnitude = distance(0.0, 0.0, candidateStepX, candidateStepY)
        if (velocityMagnitude == 0.0 || candidateStepMagnitude == 0.0) {
            return true
        }

        return velocityX * candidateStepX + velocityY * candidateStepY > 0.0
    }

    private fun predictNext(timestampNs: Long): ShotTrackPoint? {
        val observed = trackPoints.filterNot { point -> point.predicted }
        if (observed.size < 2) {
            return trackPoints.lastOrNull()?.copy(timestampNs = timestampNs)
        }

        val previous = observed[observed.lastIndex - 1]
        val current = observed.last()
        val sourceDeltaNs = current.timestampNs - previous.timestampNs
        if (sourceDeltaNs <= 0L) {
            return current.copy(timestampNs = timestampNs)
        }

        val targetDelta = ((timestampNs - current.timestampNs).coerceAtLeast(0L)).toDouble() / sourceDeltaNs.toDouble()
        return current.copy(
            timestampNs = timestampNs,
            x = current.x + (current.x - previous.x) * targetDelta,
            y = current.y + (current.y - previous.y) * targetDelta,
            predicted = true,
        )
    }

    fun currentState(): ShotTrackerState =
        ShotTrackerState(
            status = status,
            points = trackPoints.toList(),
            occlusionBridged = occlusionBridged,
            lowConfidence = lowConfidence,
        )

    private fun LaunchZone.contains(x: Double, y: Double): Boolean =
        x >= left && x <= left + width && y >= top && y <= top + height

    private fun TimedCandidate.toTrackPoint(): ShotTrackPoint =
        candidate.toTrackPoint(timestampNs, predicted = false)

    private fun LumaMotionCandidate.toTrackPoint(timestampNs: Long, predicted: Boolean): ShotTrackPoint =
        ShotTrackPoint(
            timestampNs = timestampNs,
            x = x,
            y = y,
            confidence = confidence,
            predicted = predicted,
        )

    private fun distance(ax: Double, ay: Double, bx: Double, by: Double): Double =
        hypot(ax - bx, ay - by)

    private data class TimedCandidate(
        val timestampNs: Long,
        val candidate: LumaMotionCandidate,
    )
}
