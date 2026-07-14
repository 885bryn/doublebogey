package com.doublebogey.golftracer

import com.doublebogey.golftracer.camera.AutoShotTrackerStatus
import com.doublebogey.golftracer.camera.ShotTrackerState
import com.doublebogey.golftracer.camera.ShotTrackerStatus
import com.doublebogey.golftracer.camera.ZoneBallDebug

enum class CameraStatusBadgeTone { Waiting, Ready, Detected, Warning }

data class CameraStatusBadge(val text: String, val tone: CameraStatusBadgeTone)

data class CameraStatusBadgeModel(
    val detector: CameraStatusBadge,
    val ball: CameraStatusBadge,
) {
    companion object {
        fun from(
            shotStatus: AutoShotTrackerStatus,
            trackingState: ShotTrackerState,
            acquisitionDebug: ZoneBallDebug,
        ) = CameraStatusBadgeModel(
            detector = CameraStatusBadge("DETECTOR READY", CameraStatusBadgeTone.Ready),
            ball = ballBadge(shotStatus, trackingState, acquisitionDebug),
        )

        fun starting() = CameraStatusBadgeModel(
            detector = CameraStatusBadge("DETECTOR STARTING", CameraStatusBadgeTone.Waiting),
            ball = CameraStatusBadge("NO BALL", CameraStatusBadgeTone.Waiting),
        )

        private fun ballBadge(
            shotStatus: AutoShotTrackerStatus,
            trackingState: ShotTrackerState,
            debug: ZoneBallDebug,
        ) = when {
            shotStatus == AutoShotTrackerStatus.BallLocked ->
                CameraStatusBadge("BALL LOCKED", CameraStatusBadgeTone.Ready)
            shotStatus == AutoShotTrackerStatus.Tracking || trackingState.status == ShotTrackerStatus.Tracking ->
                CameraStatusBadge("BALL TRACKING", CameraStatusBadgeTone.Detected)
            debug.best != null -> CameraStatusBadge("CANDIDATE", CameraStatusBadgeTone.Detected)
            else -> CameraStatusBadge("NO BALL", CameraStatusBadgeTone.Waiting)
        }
    }
}
