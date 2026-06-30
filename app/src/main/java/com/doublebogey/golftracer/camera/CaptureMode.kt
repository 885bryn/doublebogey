package com.doublebogey.golftracer.camera

data class CaptureModeOutput(
    val width: Int,
    val height: Int,
    val minFrameDurationNs: Long,
)

data class CaptureFpsRange(
    val minFps: Int,
    val maxFps: Int,
)

data class CaptureModeCandidate(
    val width: Int,
    val height: Int,
    val minFps: Int,
    val maxFps: Int,
) {
    val area: Int = width * height
}

data class CaptureMode(
    val width: Int,
    val height: Int,
    val minFps: Int,
    val maxFps: Int,
)