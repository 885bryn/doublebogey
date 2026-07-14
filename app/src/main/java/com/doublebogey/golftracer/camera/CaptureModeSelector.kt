package com.doublebogey.golftracer.camera

import kotlin.math.abs

object CaptureModeSelector {
    private const val TargetArea = 1280 * 720
    private const val NanosPerSecond = 1_000_000_000L

    fun standardCandidates(
        outputs: List<CaptureModeOutput>,
        fpsRanges: List<CaptureFpsRange>,
    ): List<CaptureModeCandidate> = outputs.flatMap { output ->
        val maxFeasibleFps = output.maxFeasibleFps()
        fpsRanges
            .filter { range -> range.minFps > 0 && range.maxFps >= range.minFps }
            .filter { range -> maxFeasibleFps == null || range.maxFps <= maxFeasibleFps }
            .map { range ->
                CaptureModeCandidate(
                    width = output.width,
                    height = output.height,
                    minFps = range.minFps,
                    maxFps = range.maxFps,
                )
            }
    }.distinct()

    fun select(candidates: List<CaptureModeCandidate>): CaptureMode {
        require(candidates.isNotEmpty()) { "Capture mode candidate list must not be empty." }

        return candidates
            .maxWith(
                compareBy<CaptureModeCandidate> { it.maxFps }
                    .thenBy { if (it.minFps == 30 && it.maxFps == 30) 1 else 0 }
                    .thenBy { -abs(it.area - TargetArea) },
            )
            .toCaptureMode()
    }

    private fun CaptureModeOutput.maxFeasibleFps(): Int? {
        if (minFrameDurationNs <= 0L) {
            return null
        }
        return ((NanosPerSecond + minFrameDurationNs - 1L) / minFrameDurationNs).toInt()
    }

    private fun CaptureModeCandidate.toCaptureMode(): CaptureMode = CaptureMode(
        width = width,
        height = height,
        minFps = minFps,
        maxFps = maxFps,
    )
}
