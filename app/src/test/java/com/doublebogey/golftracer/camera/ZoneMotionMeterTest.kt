package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZoneMotionMeterTest {
    @Test
    fun reportsQuietZoneWhenOnlySmallNoiseChanges() {
        val meter = ZoneMotionMeter(ZoneMotionMeterConfig(motionThreshold = 24))
        meter.measure(frame(y = 100), zone)

        val motion = meter.measure(frame(y = 106), zone)

        assertEquals(0.0, motion.changedFraction)
        assertEquals(true, motion.quiet)
    }

    @Test
    fun reportsNonQuietZoneWhenLargeObjectChangesManyPixels() {
        val meter = ZoneMotionMeter(ZoneMotionMeterConfig(motionThreshold = 24, quietChangedFraction = 0.02))
        meter.measure(frame(y = 100), zone)

        val motion = meter.measure(frame(y = 100, brightBlock = true), zone)

        assertTrue(motion.changedFraction > 0.02)
        assertEquals(false, motion.quiet)
    }

    private val zone = LaunchZone(left = 0.0, top = 0.0, width = 1.0, height = 1.0)

    private fun frame(y: Int, brightBlock: Boolean = false): YuvFrame {
        val width = 20
        val height = 20
        val luma = ByteArray(width * height) { y.toByte() }
        if (brightBlock) {
            for (row in 5 until 12) {
                for (column in 5 until 12) {
                    luma[row * width + column] = 180.toByte()
                }
            }
        }
        return YuvFrame(
            width = width,
            height = height,
            y = luma,
            u = ByteArray(width * height) { 100.toByte() },
            v = ByteArray(width * height) { 140.toByte() },
        )
    }
}
