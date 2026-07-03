package com.doublebogey.golftracer

import com.doublebogey.golftracer.camera.AutoShotTrackerStatus
import com.doublebogey.golftracer.camera.ShotTrackerState
import com.doublebogey.golftracer.camera.ShotTrackerStatus
import com.doublebogey.golftracer.camera.ZoneBallCalibrationState
import com.doublebogey.golftracer.camera.ZoneBallDebug

enum class CameraStatusBadgeTone {
    Waiting,
    Ready,
    Detected,
    Warning,
}

data class CameraStatusBadge(
    val text: String,
    val tone: CameraStatusBadgeTone,
)

data class CameraStatusBadgeModel(
    val calibration: CameraStatusBadge,
    val ball: CameraStatusBadge,
) {
    companion object {
        fun from(
            shotStatus: AutoShotTrackerStatus,
            trackingState: ShotTrackerState,
            acquisitionDebug: ZoneBallDebug,
        ): CameraStatusBadgeModel =
            CameraStatusBadgeModel(
                calibration = calibrationBadge(acquisitionDebug),
                ball = ballBadge(shotStatus, trackingState, acquisitionDebug),
            )

        fun starting(): CameraStatusBadgeModel =
            CameraStatusBadgeModel(
                calibration = CameraStatusBadge("CALIBRATION WAITING", CameraStatusBadgeTone.Waiting),
                ball = CameraStatusBadge("NO BALL", CameraStatusBadgeTone.Waiting),
            )

        private fun calibrationBadge(debug: ZoneBallDebug): CameraStatusBadge =
            when {
                debug.backgroundStale -> CameraStatusBadge("CALIBRATION RESET", CameraStatusBadgeTone.Warning)
                debug.calibrationState == ZoneBallCalibrationState.Calibrated ->
                    CameraStatusBadge("CALIBRATION READY", CameraStatusBadgeTone.Ready)
                debug.calibrationState == ZoneBallCalibrationState.Calibrating ->
                    CameraStatusBadge(
                        "CALIBRATING ${debug.calibrationFramesCollected}/${debug.calibrationFramesRequired}",
                        CameraStatusBadgeTone.Waiting,
                    )
                else -> CameraStatusBadge("CALIBRATION WAITING", CameraStatusBadgeTone.Waiting)
            }

        private fun ballBadge(
            shotStatus: AutoShotTrackerStatus,
            trackingState: ShotTrackerState,
            debug: ZoneBallDebug,
        ): CameraStatusBadge =
            when {
                shotStatus == AutoShotTrackerStatus.BallLocked ->
                    CameraStatusBadge("BALL LOCKED", CameraStatusBadgeTone.Ready)
                shotStatus == AutoShotTrackerStatus.Tracking || trackingState.status == ShotTrackerStatus.Tracking ->
                    CameraStatusBadge("BALL TRACKING", CameraStatusBadgeTone.Detected)
                debug.best != null -> CameraStatusBadge("BALL DETECTED", CameraStatusBadgeTone.Detected)
                else -> CameraStatusBadge("NO BALL", CameraStatusBadgeTone.Waiting)
            }
    }
}
