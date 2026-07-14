package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureModeSelectorFixedFpsTest {
    @Test
    fun prefersFixedThirtyFpsOverVariableThirtyFpsForStableExposure() {
        val selected = CaptureModeSelector.select(
            listOf(
                CaptureModeCandidate(width = 1280, height = 720, minFps = 7, maxFps = 30),
                CaptureModeCandidate(width = 1280, height = 720, minFps = 30, maxFps = 30),
            ),
        )

        assertEquals(CaptureMode(width = 1280, height = 720, minFps = 30, maxFps = 30), selected)
    }
}
