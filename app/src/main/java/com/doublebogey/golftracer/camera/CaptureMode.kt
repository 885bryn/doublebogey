package com.doublebogey.golftracer.camera

data class CaptureModeCandidate(
    val width: Int,
    val height: Int,
    val minFps: Int,
    val maxFps: Int,
    val highSpeed: Boolean,
) {
    val area: Int = width * height
}

data class CaptureMode(
    val width: Int,
    val height: Int,
    val minFps: Int,
    val maxFps: Int,
    val highSpeed: Boolean,
)