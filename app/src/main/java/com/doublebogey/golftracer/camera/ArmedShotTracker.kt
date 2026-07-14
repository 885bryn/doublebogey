package com.doublebogey.golftracer.camera

enum class ArmedShotTrackerStatus {
    Disarmed,
    Armed,
    Tracking,
}

data class ArmedShotTrackerState(
    val status: ArmedShotTrackerStatus,
    val trackingState: ShotTrackerState,
)

class ArmedShotTracker(
    private val delegate: ShotTracker = ShotTracker(),
    private val armWindowNs: Long = DEFAULT_ARM_WINDOW_NS,
) {
    var status = ArmedShotTrackerStatus.Disarmed
        private set
    private var pendingArm = false
    private var armedUntilNs: Long? = null

    fun armNextShot(): ArmedShotTrackerState {
        delegate.reset()
        pendingArm = true
        armedUntilNs = null
        status = ArmedShotTrackerStatus.Armed
        return currentState()
    }

    fun update(result: LumaMotionResult, launchZone: LaunchZone): ArmedShotTrackerState {
        if (pendingArm) {
            pendingArm = false
            armedUntilNs = result.timestampNs + armWindowNs
        }

        if (status == ArmedShotTrackerStatus.Disarmed) {
            return currentState()
        }

        val trackingState = delegate.update(result, launchZone)
        status = when {
            trackingState.status == ShotTrackerStatus.Tracking -> ArmedShotTrackerStatus.Tracking
            trackingState.status == ShotTrackerStatus.Finalized -> {
                delegate.reset()
                armedUntilNs = null
                ArmedShotTrackerStatus.Disarmed
            }
            isArmExpired(result.timestampNs) -> {
                delegate.reset()
                armedUntilNs = null
                ArmedShotTrackerStatus.Disarmed
            }
            else -> ArmedShotTrackerStatus.Armed
        }

        return currentState()
    }

    fun currentTrackingState(): ShotTrackerState =
        currentState().trackingState

    fun reset() {
        delegate.reset()
        pendingArm = false
        armedUntilNs = null
        status = ArmedShotTrackerStatus.Disarmed
    }

    private fun isArmExpired(timestampNs: Long): Boolean =
        status == ArmedShotTrackerStatus.Armed &&
            armedUntilNs?.let { expiresAt -> timestampNs > expiresAt } == true

    private fun currentState(): ArmedShotTrackerState =
        ArmedShotTrackerState(
            status = status,
            trackingState = if (status == ArmedShotTrackerStatus.Disarmed) {
                EMPTY_TRACKING_STATE
            } else {
                delegate.currentState()
            },
        )

    private companion object {
        const val DEFAULT_ARM_WINDOW_NS = 5_000_000_000L

        val EMPTY_TRACKING_STATE = ShotTrackerState(
            status = ShotTrackerStatus.Idle,
            points = emptyList(),
            occlusionBridged = false,
            lowConfidence = false,
        )
    }
}
