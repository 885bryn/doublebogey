package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CaptureModeSelectorTest {
    @Test
    fun choosesHighestFpsCandidate() {
        val selected = CaptureModeSelector.select(
            listOf(
                CaptureModeCandidate(width = 1280, height = 720, minFps = 30, maxFps = 60, highSpeed = false),
                CaptureModeCandidate(width = 640, height = 480, minFps = 30, maxFps = 120, highSpeed = false),
                CaptureModeCandidate(width = 1920, height = 1080, minFps = 30, maxFps = 30, highSpeed = false),
            ),
        )

        assertEquals(CaptureMode(width = 640, height = 480, minFps = 30, maxFps = 120, highSpeed = false), selected)
    }

    @Test
    fun prefersHighSpeedModeWhenFpsTies() {
        val selected = CaptureModeSelector.select(
            listOf(
                CaptureModeCandidate(width = 1280, height = 720, minFps = 30, maxFps = 120, highSpeed = false),
                CaptureModeCandidate(width = 1920, height = 1080, minFps = 30, maxFps = 120, highSpeed = true),
            ),
        )

        assertEquals(CaptureMode(width = 1920, height = 1080, minFps = 30, maxFps = 120, highSpeed = true), selected)
    }

    @Test
    fun choosesCandidateClosestTo720pAmongEqualFpsModes() {
        val selected = CaptureModeSelector.select(
            listOf(
                CaptureModeCandidate(width = 1920, height = 1080, minFps = 30, maxFps = 60, highSpeed = false),
                CaptureModeCandidate(width = 1280, height = 720, minFps = 30, maxFps = 60, highSpeed = false),
                CaptureModeCandidate(width = 640, height = 480, minFps = 30, maxFps = 60, highSpeed = false),
            ),
        )

        assertEquals(CaptureMode(width = 1280, height = 720, minFps = 30, maxFps = 60, highSpeed = false), selected)
    }

    @Test
    fun fallsBackToBestStandardModeWhenNoHighSpeedCandidateExists() {
        val selected = CaptureModeSelector.select(
            listOf(
                CaptureModeCandidate(width = 1280, height = 720, minFps = 30, maxFps = 60, highSpeed = false),
                CaptureModeCandidate(width = 1920, height = 1080, minFps = 30, maxFps = 120, highSpeed = false),
            ),
        )

        assertEquals(CaptureMode(width = 1920, height = 1080, minFps = 30, maxFps = 120, highSpeed = false), selected)
    }

    @Test
    fun rejectsEmptyCandidateListsWithClearException() {
        val exception = assertFailsWith<IllegalArgumentException> {
            CaptureModeSelector.select(emptyList())
        }

        assertTrue(exception.message!!.contains("candidate", ignoreCase = true))
    }
    @Test
    fun choosesPracticalHighSpeedModeFromPlausibleAndroidCameraModes() {
        val selected = CaptureModeSelector.select(
            listOf(
                CaptureModeCandidate(width = 3840, height = 2160, minFps = 30, maxFps = 30, highSpeed = false),
                CaptureModeCandidate(width = 1920, height = 1080, minFps = 30, maxFps = 60, highSpeed = false),
                CaptureModeCandidate(width = 1280, height = 720, minFps = 30, maxFps = 60, highSpeed = false),
                CaptureModeCandidate(width = 1920, height = 1080, minFps = 120, maxFps = 120, highSpeed = true),
                CaptureModeCandidate(width = 1280, height = 720, minFps = 120, maxFps = 120, highSpeed = true),
                CaptureModeCandidate(width = 640, height = 480, minFps = 240, maxFps = 240, highSpeed = true),
            ),
        )

        assertEquals(CaptureMode(width = 640, height = 480, minFps = 240, maxFps = 240, highSpeed = true), selected)
    }
}
