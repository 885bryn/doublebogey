package com.doublebogey.golftracer.camera

import kotlin.math.abs

object CaptureModeSelector {
    private const val TargetArea = 1280 * 720

    fun select(candidates: List<CaptureModeCandidate>): CaptureMode {
        require(candidates.isNotEmpty()) { "Capture mode candidate list must not be empty." }

        return candidates
            .maxWith(
                compareBy<CaptureModeCandidate> { it.maxFps }
                    .thenBy { it.highSpeed }
                    .thenBy { -abs(it.area - TargetArea) },
            )
            .toCaptureMode()
    }

    private fun CaptureModeCandidate.toCaptureMode(): CaptureMode = CaptureMode(
        width = width,
        height = height,
        minFps = minFps,
        maxFps = maxFps,
        highSpeed = highSpeed,
    )
}

