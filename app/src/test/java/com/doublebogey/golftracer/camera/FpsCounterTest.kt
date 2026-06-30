package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals

class FpsCounterTest {
    @Test
    fun reportsZeroBeforeTwoFramesExist() {
        val counter = FpsCounter()

        assertEquals(0.0, counter.recordFrame(1_000_000_000L))
    }

    @Test
    fun reportsRollingFpsFromFrameTimestamps() {
        val counter = FpsCounter()

        counter.recordFrame(1_000_000_000L)
        counter.recordFrame(1_250_000_000L)
        counter.recordFrame(1_500_000_000L)
        counter.recordFrame(1_750_000_000L)
        val fps = counter.recordFrame(2_000_000_000L)

        assertEquals(4.0, fps)
    }
}
