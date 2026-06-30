package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun calculatesFpsFromLastSamplesOnlyWhenWindowIsFull() {
        val counter = FpsCounter(maxSamples = 3)

        counter.recordFrame(0L)
        counter.recordFrame(500_000_000L)
        counter.recordFrame(1_000_000_000L)
        val fps = counter.recordFrame(2_000_000_000L)

        assertEquals(1.333333333, fps, absoluteTolerance = 0.000000001)
    }

    @Test
    fun resetClearsRecordedFrameHistory() {
        val counter = FpsCounter()

        counter.recordFrame(1_000_000_000L)
        counter.recordFrame(2_000_000_000L)
        counter.reset()

        assertEquals(0.0, counter.recordFrame(3_000_000_000L))
    }

    @Test
    fun reportsZeroWhenElapsedTimeIsNotPositive() {
        val counter = FpsCounter()

        counter.recordFrame(2_000_000_000L)
        val fps = counter.recordFrame(1_000_000_000L)

        assertEquals(0.0, fps)
    }

    @Test
    fun rejectsNonPositiveMaxSamples() {
        val exception = assertFailsWith<IllegalArgumentException> {
            FpsCounter(maxSamples = 0)
        }

        assertTrue(exception.message!!.contains("maxSamples"))
    }
}