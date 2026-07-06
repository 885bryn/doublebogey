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
        val locked = state.lockedBall
        if (locked != null) {
            return listOf(locked).filter { candidate -> launchZone.contains(candidate.x, candidate.y) }
        }

        return state.acquisitionDebug.candidates
            .firstOrNull()
            ?.candidate
            ?.takeIf { candidate -> launchZone.contains(candidate.x, candidate.y) }
            ?.let(::listOf)
            .orEmpty()
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
