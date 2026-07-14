package com.doublebogey.golftracer.camera

object DetectionDebugFilter {
    fun visibleCandidates(
        trackerState: AutoShotTrackerState?,
        launchZone: LaunchZone,
        trackingState: ShotTrackerState?,
    ): List<LumaMotionCandidate> {
        if (trackingState?.points?.isNotEmpty() == true) return emptyList()
        val state = trackerState ?: return emptyList()
        val candidate = state.lockedBall ?: state.acquisitionDebug.best?.candidate
        return candidate?.takeIf { launchZone.contains(it.x, it.y) }?.let(::listOf).orEmpty()
    }

    @Suppress("UNUSED_PARAMETER")
    fun visibleCandidates(
        result: LumaMotionResult,
        launchZone: LaunchZone,
        trackingState: ShotTrackerState?,
    ): List<LumaMotionCandidate> = emptyList()

    private fun LaunchZone.contains(x: Double, y: Double): Boolean =
        x >= left && x <= left + width && y >= top && y <= top + height
}
