package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BallCandidateTrackerTest {
    @Test
    fun confirmsRadiusFiveCandidateOnFifthDriftingHit() {
        val tracker = BallCandidateTracker()
        val candidates = listOf(
            evaluation(0.0, 0.0),
            evaluation(2.0, 1.0),
            evaluation(4.0, 2.0),
            evaluation(5.0, 3.0),
            evaluation(7.0, 3.0),
        )

        candidates.dropLast(1).forEach { candidate ->
            val result = tracker.update(listOf(candidate))
            assertNull(result.confirmed)
        }
        val result = tracker.update(listOf(candidates.last()))

        assertSame(candidates.last(), result.leader)
        assertSame(candidates.last(), result.confirmed)
        assertEquals(5, result.hits)
        assertEquals(5, result.windowSize)
        assertEquals(Double.POSITIVE_INFINITY, result.margin)
        assertFalse(result.ambiguous)
    }

    @Test
    fun confirmsFiveHitsWithinSevenFramesDespiteTwoMisses() {
        val tracker = BallCandidateTracker()
        val frames = listOf(
            listOf(evaluation(0.0, 0.0)),
            listOf(evaluation(2.0, 1.0)),
            emptyList(),
            listOf(evaluation(4.0, 2.0)),
            emptyList(),
            listOf(evaluation(5.0, 3.0)),
            listOf(evaluation(7.0, 3.0)),
        )

        val result = frames.fold(tracker.update(emptyList())) { _, frame -> tracker.update(frame) }

        assertEquals(5, result.hits)
        assertEquals(7, result.windowSize)
        assertSame(frames.last().single(), result.confirmed)
    }

    @Test
    fun deletesTrackOnThirdConsecutiveMiss() {
        val tracker = BallCandidateTracker()
        repeat(5) { tracker.update(listOf(evaluation(it.toDouble(), 0.0))) }

        repeat(2) {
            val missed = tracker.update(emptyList())
            assertNull(missed.leader)
            assertNull(missed.confirmed)
        }
        tracker.update(emptyList())
        val replacement = evaluation(5.0, 0.0)
        val result = tracker.update(listOf(replacement))

        assertSame(replacement, result.leader)
        assertNull(result.confirmed)
        assertEquals(1, result.hits)
        assertEquals(1, result.windowSize)
    }

    @Test
    fun equalCandidatesRemainAmbiguousAndNeverConfirm() {
        val tracker = BallCandidateTracker()
        var result = tracker.update(emptyList())

        repeat(7) {
            result = tracker.update(
                listOf(
                    evaluation(0.0, 0.0, score = 10.0),
                    evaluation(100.0, 0.0, score = 10.0),
                ),
            )
            assertNull(result.confirmed)
        }

        assertEquals(7, result.hits)
        assertEquals(1.0, result.margin)
        assertTrue(result.ambiguous)
    }

    @Test
    fun confirmsLeaderAtExactOnePointThreeFiveMargin() {
        val tracker = BallCandidateTracker()
        var leader = evaluation(0.0, 0.0, score = 13.5)
        var result = tracker.update(emptyList())

        repeat(5) {
            leader = evaluation(it.toDouble(), 0.0, score = 13.5)
            result = tracker.update(
                listOf(
                    leader,
                    evaluation(100.0 + it, 0.0, score = 10.0),
                ),
            )
        }

        assertSame(leader, result.confirmed)
        assertEquals(1.35, result.margin, absoluteTolerance = 1e-12)
        assertFalse(result.ambiguous)
    }

    @Test
    fun resetClearsConfirmationHistory() {
        val tracker = BallCandidateTracker()
        repeat(5) { tracker.update(listOf(evaluation(it.toDouble(), 0.0))) }

        tracker.reset()
        val result = tracker.update(listOf(evaluation(5.0, 0.0)))

        assertNull(result.confirmed)
        assertEquals(1, result.hits)
        assertEquals(1, result.windowSize)
    }

    private fun evaluation(
        centerX: Double,
        centerY: Double,
        radius: Double = 5.0,
        score: Double = 10.0,
    ) = BallShapeEvaluation(
        proposal = BallBlobProposal(centerX, centerY, radius, response = score),
        metrics = BallShapeMetrics(
            annulusContrast = 10.0,
            interiorUniformity = 0.1,
            closedEdgeCoverage = 0.8,
            radialAlignment = 0.9,
            radiusVariation = 0.1,
            lineContinuation = 0.1,
            chromaShift = 0.0,
        ),
        score = score,
        accepted = true,
        rejection = null,
    )
}
