package com.doublebogey.golftracer.camera

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

class BallScaleEstimatorTest {
    private val estimator = BallScaleEstimator(1500.0, 42.7)

    @Test
    fun derivesExpectedRadiusAndThreeScales() {
        val scale = estimator.estimate(240)

        assertEquals(3.416, scale.expectedRadiusPx, 0.01)
        assertEquals(
            listOf(2.562, 3.416, 4.782),
            scale.radiiPx.map { (it * 1000).roundToInt() / 1000.0 },
        )
    }

    @Test
    fun clampsUnsupportedExtremes() {
        assertEquals(2.0, estimator.estimate(20).expectedRadiusPx)
        assertEquals(40.0, estimator.estimate(4000).expectedRadiusPx)
    }
}
