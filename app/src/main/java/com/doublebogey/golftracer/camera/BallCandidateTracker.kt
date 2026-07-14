package com.doublebogey.golftracer.camera

import kotlin.math.hypot
import kotlin.math.max

data class BallCandidateTrackerConfig(
    val windowSize: Int = 7,
    val hitsRequired: Int = 5,
    val maxConsecutiveMisses: Int = 3,
    val associationRadiusMultiplier: Double = 3.0,
    val minAssociationDistancePx: Double = 4.0,
    val leaderMargin: Double = 1.35,
) {
    init {
        require(windowSize > 0)
        require(hitsRequired in 1..windowSize)
        require(maxConsecutiveMisses > 0)
        require(associationRadiusMultiplier >= 0.0)
        require(minAssociationDistancePx >= 0.0)
        require(leaderMargin >= 1.0)
    }
}

data class BallCandidateTrackingResult(
    val leader: BallShapeEvaluation?,
    val confirmed: BallShapeEvaluation?,
    val hits: Int,
    val windowSize: Int,
    val margin: Double,
    val ambiguous: Boolean,
)

class BallCandidateTracker(
    private val config: BallCandidateTrackerConfig = BallCandidateTrackerConfig(),
) {
    private val tracks = ArrayList<Track>()
    private var nextTrackId = 0L

    fun update(candidates: List<BallShapeEvaluation>): BallCandidateTrackingResult {
        tracks.forEach { it.matchedThisFrame = false }

        candidates
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<BallShapeEvaluation>> { it.value.score }
                .thenBy { it.index })
            .forEach { indexedCandidate -> associate(indexedCandidate.value) }

        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()
            if (!track.matchedThisFrame) {
                track.appendHit(false, config.windowSize)
                track.consecutiveMisses += 1
                if (track.consecutiveMisses >= config.maxConsecutiveMisses) iterator.remove()
            }
        }

        val currentTracks = tracks
            .asSequence()
            .filter { it.matchedThisFrame }
            .sortedWith(compareByDescending<Track> { it.latest.score }.thenBy { it.id })
            .toList()
        val leaderTrack = currentTracks.firstOrNull()
        val leader = leaderTrack?.latest
        val runnerUp = currentTracks.getOrNull(1)?.latest
        val margin = scoreMargin(leader, runnerUp)
        val ambiguous = leader != null && margin < config.leaderMargin
        val confirmed = leader?.takeIf {
            leaderTrack.hits >= config.hitsRequired && !ambiguous
        }

        return BallCandidateTrackingResult(
            leader = leader,
            confirmed = confirmed,
            hits = leaderTrack?.hits ?: 0,
            windowSize = leaderTrack?.history?.size ?: 0,
            margin = margin,
            ambiguous = ambiguous,
        )
    }

    fun reset() {
        tracks.clear()
        nextTrackId = 0L
    }

    private fun associate(candidate: BallShapeEvaluation) {
        val proposal = candidate.proposal
        val track = tracks
            .asSequence()
            .filter { !it.matchedThisFrame }
            .map { existing ->
                val distance = hypot(
                    proposal.centerX - existing.centerX,
                    proposal.centerY - existing.centerY,
                )
                existing to distance
            }
            .filter { (existing, distance) ->
                distance <= max(
                    config.minAssociationDistancePx,
                    config.associationRadiusMultiplier * max(proposal.radiusPx, existing.radiusPx),
                )
            }
            .minWithOrNull(compareBy<Pair<Track, Double>> { it.second }.thenBy { it.first.id })
            ?.first

        if (track == null) {
            tracks += Track(
                id = nextTrackId++,
                centerX = proposal.centerX,
                centerY = proposal.centerY,
                radiusPx = proposal.radiusPx,
                latest = candidate,
            ).also {
                it.matchedThisFrame = true
                it.appendHit(true, config.windowSize)
            }
            return
        }

        track.centerX = smooth(track.centerX, proposal.centerX)
        track.centerY = smooth(track.centerY, proposal.centerY)
        track.radiusPx = smooth(track.radiusPx, proposal.radiusPx)
        track.latest = candidate
        track.matchedThisFrame = true
        track.consecutiveMisses = 0
        track.appendHit(true, config.windowSize)
    }

    private fun scoreMargin(
        leader: BallShapeEvaluation?,
        runnerUp: BallShapeEvaluation?,
    ): Double = when {
        leader == null -> 0.0
        runnerUp == null -> Double.POSITIVE_INFINITY
        runnerUp.score > 0.0 -> leader.score / runnerUp.score
        leader.score > runnerUp.score -> Double.POSITIVE_INFINITY
        else -> 1.0
    }

    private fun smooth(previous: Double, current: Double): Double =
        SMOOTHING_ALPHA * current + (1.0 - SMOOTHING_ALPHA) * previous

    private class Track(
        val id: Long,
        var centerX: Double,
        var centerY: Double,
        var radiusPx: Double,
        var latest: BallShapeEvaluation,
    ) {
        val history = ArrayDeque<Boolean>()
        var consecutiveMisses = 0
        var matchedThisFrame = false

        val hits: Int
            get() = history.count { it }

        fun appendHit(hit: Boolean, windowSize: Int) {
            history.addLast(hit)
            while (history.size > windowSize) history.removeFirst()
        }
    }

    private companion object {
        const val SMOOTHING_ALPHA = 0.55
    }
}
