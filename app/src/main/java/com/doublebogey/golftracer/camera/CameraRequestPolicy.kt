package com.doublebogey.golftracer.camera

data class CameraRequestPolicy(
    val autoExposureLock: Boolean,
    val autoWhiteBalanceLock: Boolean,
) {
    companion object {
        fun default(): CameraRequestPolicy = CameraRequestPolicy(
            autoExposureLock = false,
            autoWhiteBalanceLock = false,
        )
    }
}
