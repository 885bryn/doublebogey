package com.doublebogey.golftracer.camera

object DetectionDebugFilter {
    fun visibleCandidates(
        trackerState: AutoShotTrackerState?,
        launchZone: LaunchZone,
        trackingState: ShotTrackerState?,
    ): List<LumaMotionCandidate> {
        if (trackingState?.points?.isNotEmpty() == true) {
            return emptyList()
        }

        val state = trackerState ?: return emptyList()
        val rankedCandidates = state.acquisitionDebug.candidates.map { candidate -> candidate.candidate }
        val locked = state.lockedBall?.let(::listOf).orEmpty()
        return (locked + rankedCandidates)
            .filter { candidate -> launchZone.contains(candidate.x, candidate.y) }
            .distinctBy { candidate -> candidate.x to candidate.y }
            .take(3)
    }

    fun visibleCandidates(
        result: LumaMotionResult,
        launchZone: LaunchZone,
        trackingState: ShotTrackerState?,
    ): List<LumaMotionCandidate> {
        if (trackingState?.points?.isNotEmpty() == true) {
            return emptyList()
        }

        return result.stillCandidates
            .filter { candidate -> launchZone.contains(candidate.x, candidate.y) }
            .maxByOrNull { candidate -> candidate.confidence }
            ?.let(::listOf)
            .orEmpty()
    }

    private fun LaunchZone.contains(x: Double, y: Double): Boolean =
        x >= left && x <= left + width && y >= top && y <= top + height
}
