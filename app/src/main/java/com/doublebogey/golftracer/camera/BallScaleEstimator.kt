package com.doublebogey.golftracer.camera

import kotlin.math.roundToInt

data class BallScale(val expectedRadiusPx: Double, val radiiPx: List<Double>)

class BallScaleEstimator(
    private val assumedZoneWidthMm: Double = 1500.0,
    private val ballDiameterMm: Double = 42.7,
) {
    init {
        require(assumedZoneWidthMm > 0.0)
        require(ballDiameterMm > 0.0)
    }

    fun estimate(zoneWidthPx: Int): BallScale {
        require(zoneWidthPx > 0)
        val expected =
            (zoneWidthPx * ballDiameterMm / assumedZoneWidthMm / 2.0).coerceIn(2.0, 40.0)
        return BallScale(
            expectedRadiusPx = expected,
            radiiPx = listOf(0.75, 1.0, 1.4)
                .map { (expected * it).coerceIn(2.0, 40.0) }
                .distinctBy { (it * 100.0).roundToInt() },
        )
    }
}
