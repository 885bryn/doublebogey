package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StillBallScorerTest {
    private val zone = LaunchZone(left = 0.20, top = 0.20, width = 0.60, height = 0.60)
    private val config = StillBallScorerConfig(
        calibrationFramesRequired = 2,
        minBrightnessDelta = 35,
        neutralChromaTolerance = 18,
        minBlobPixels = 4,
        maxBlobPixels = 30,
        minFillRatio = 0.45,
        minAspectRatio = 0.55,
        minAcceptedScore = 0.60,
    )

    @Test
    fun acceptsNeutralWhiteBallOnTintedMatAfterCalibration() {
        val scorer = calibratedScorer()
        val ball = square(9, 9, 3, 3)

        val detection = scorer.analyzeFrame(frameWith(ball = ball), zone)

        val candidate = detection.acceptedCandidate
        assertTrue(candidate != null)
        assertEquals(10.0 / 19.0, candidate.x, absoluteTolerance = 0.000000001)
        assertEquals(10.0 / 19.0, candidate.y, absoluteTolerance = 0.000000001)
        assertEquals(9, candidate.pixelCount)
        assertTrue(detection.debug.brightnessScore > 0.0)
        assertTrue(detection.debug.chromaScore > 0.0)
        assertTrue(detection.debug.shapeScore > 0.0)
    }

    @Test
    fun acceptsLargerNeutralWhiteBallAfterCalibration() {
        val scorer = StillBallScorer(StillBallScorerConfig(calibrationFramesRequired = 2))
        val largerZone = LaunchZone(left = 0.20, top = 0.20, width = 0.60, height = 0.60)
        val largerBall = square(14, 14, 13, 13)
        scorer.startCalibration()
        scorer.collectCalibrationFrame(frameWithSize(width = 40, height = 40, ball = emptySet()), largerZone)
        scorer.collectCalibrationFrame(frameWithSize(width = 40, height = 40, ball = emptySet()), largerZone)

        val detection = scorer.analyzeFrame(frameWithSize(width = 40, height = 40, ball = largerBall), largerZone)

        val candidate = detection.acceptedCandidate
        assertTrue(candidate != null)
        assertEquals(169, candidate.pixelCount)
        assertTrue(candidate.confidence >= 0.62)
    }

    @Test
    fun rejectsBrightColoredArtifactByChroma() {
        val scorer = calibratedScorer()
        val artifact = square(9, 9, 3, 3)

        val detection = scorer.analyzeFrame(
            frameWith(ball = artifact, ballY = 245, ballU = 90, ballV = 190),
            zone,
        )

        assertEquals(null, detection.acceptedCandidate)
        assertTrue(detection.debug.rejectedByChroma > 0)
    }

    @Test
    fun rejectsBrightMatSeamByShape() {
        val scorer = calibratedScorer()
        val seam = (5..14).map { x -> x to 10 }.toSet()

        val detection = scorer.analyzeFrame(frameWith(ball = seam), zone)

        assertEquals(null, detection.acceptedCandidate)
        assertTrue(detection.debug.rejectedByShape > 0)
    }

    @Test
    fun doesNotAcceptBeforeCalibrationCompletes() {
        val scorer = StillBallScorer(config)
        scorer.startCalibration()
        scorer.collectCalibrationFrame(emptyMatFrame(), zone)

        val detection = scorer.analyzeFrame(frameWith(ball = square(9, 9, 3, 3)), zone)

        assertEquals(null, detection.acceptedCandidate)
        assertEquals(StillBallCalibrationState.Calibrating, detection.debug.calibrationState)
    }

    private fun calibratedScorer(): StillBallScorer {
        val scorer = StillBallScorer(config)
        scorer.startCalibration()
        scorer.collectCalibrationFrame(emptyMatFrame(), zone)
        scorer.collectCalibrationFrame(emptyMatFrame(), zone)
        return scorer
    }

    private fun emptyMatFrame(): YuvFrame = frameWith(ball = emptySet())

    private fun frameWith(
        ball: Set<Pair<Int, Int>>,
        ballY: Int = 245,
        ballU: Int = 128,
        ballV: Int = 128,
    ): YuvFrame = frameWithSize(width = 20, height = 20, ball = ball, ballY = ballY, ballU = ballU, ballV = ballV)

    private fun frameWithSize(
        width: Int,
        height: Int,
        ball: Set<Pair<Int, Int>>,
        ballY: Int = 245,
        ballU: Int = 128,
        ballV: Int = 128,
    ): YuvFrame {
        val y = ByteArray(width * height) { 112.toByte() }
        val u = ByteArray(width * height) { 82.toByte() }
        val v = ByteArray(width * height) { 148.toByte() }
        for ((x, yCoord) in ball) {
            val index = yCoord * width + x
            y[index] = ballY.toByte()
            u[index] = ballU.toByte()
            v[index] = ballV.toByte()
        }
        return YuvFrame(width = width, height = height, y = y, u = u, v = v)
    }

    private fun square(left: Int, top: Int, width: Int, height: Int): Set<Pair<Int, Int>> =
        buildSet {
            for (y in top until top + height) {
                for (x in left until left + width) {
                    add(x to y)
                }
            }
        }
}
