package com.doublebogey.golftracer.camera

import kotlin.math.abs
import kotlin.math.min

/**
 * Maps normalized coordinates between the raw analysis frame (sensor buffer orientation)
 * and view space (what the user sees: the TextureView content, launch zone, and overlay).
 *
 * The camera buffer is delivered in sensor orientation (typically landscape) while the
 * preview is displayed rotated upright. The launch zone is dragged in view space, so the
 * detectors must translate it into frame space before scanning pixels, and translate
 * detected centroids back into view space for the trackers and overlay.
 *
 * [rotationDegrees] is the clockwise rotation the display applies to the sensor buffer:
 * `(sensorOrientation - displayRotationDegrees + 360) % 360` for a back-facing camera.
 * Front-camera mirroring is not handled; the app only uses the rear camera.
 */
data class FrameCoordinateMapper(val rotationDegrees: Int) {
    init {
        require(rotationDegrees in 0..270 && rotationDegrees % 90 == 0) {
            "rotationDegrees must be one of 0, 90, 180, 270"
        }
    }

    data class MappedPoint(val x: Double, val y: Double)

    fun frameToView(x: Double, y: Double): MappedPoint =
        when (rotationDegrees) {
            0 -> MappedPoint(x, y)
            90 -> MappedPoint(1.0 - y, x)
            180 -> MappedPoint(1.0 - x, 1.0 - y)
            else -> MappedPoint(y, 1.0 - x)
        }

    fun viewToFrame(x: Double, y: Double): MappedPoint =
        when (rotationDegrees) {
            0 -> MappedPoint(x, y)
            90 -> MappedPoint(y, 1.0 - x)
            180 -> MappedPoint(1.0 - x, 1.0 - y)
            else -> MappedPoint(1.0 - y, x)
        }

    fun viewZoneToFrameZone(zone: LaunchZone): LaunchZone {
        val cornerA = viewToFrame(zone.left, zone.top)
        val cornerB = viewToFrame(zone.left + zone.width, zone.top + zone.height)
        return LaunchZone(
            left = min(cornerA.x, cornerB.x),
            top = min(cornerA.y, cornerB.y),
            width = abs(cornerA.x - cornerB.x),
            height = abs(cornerA.y - cornerB.y),
        )
    }

    companion object {
        val Identity = FrameCoordinateMapper(0)

        fun forRearCamera(sensorOrientationDegrees: Int, displayRotationDegrees: Int): FrameCoordinateMapper =
            FrameCoordinateMapper(((sensorOrientationDegrees - displayRotationDegrees) % 360 + 360) % 360)
    }
}
