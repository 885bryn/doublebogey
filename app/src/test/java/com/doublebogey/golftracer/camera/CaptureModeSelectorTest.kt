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
                CaptureModeCandidate(width = 1280, height = 720, minFps = 30, maxFps = 60),
                CaptureModeCandidate(width = 640, height = 480, minFps = 30, maxFps = 120),
                CaptureModeCandidate(width = 1920, height = 1080, minFps = 30, maxFps = 30),
            ),
        )

        assertEquals(CaptureMode(width = 640, height = 480, minFps = 30, maxFps = 120), selected)
    }

    @Test
    fun choosesCandidateClosestTo720pAmongEqualFpsModes() {
        val selected = CaptureModeSelector.select(
            listOf(
                CaptureModeCandidate(width = 1920, height = 1080, minFps = 30, maxFps = 60),
                CaptureModeCandidate(width = 1280, height = 720, minFps = 30, maxFps = 60),
                CaptureModeCandidate(width = 640, height = 480, minFps = 30, maxFps = 60),
            ),
        )

        assertEquals(CaptureMode(width = 1280, height = 720, minFps = 30, maxFps = 60), selected)
    }

    @Test
    fun choosesHighestFpsBeforeResolution() {
        val selected = CaptureModeSelector.select(
            listOf(
                CaptureModeCandidate(width = 1280, height = 720, minFps = 30, maxFps = 60),
                CaptureModeCandidate(width = 1920, height = 1080, minFps = 30, maxFps = 120),
            ),
        )

        assertEquals(CaptureMode(width = 1920, height = 1080, minFps = 30, maxFps = 120), selected)
    }

    @Test
    fun rejectsEmptyCandidateListsWithClearException() {
        val exception = assertFailsWith<IllegalArgumentException> {
            CaptureModeSelector.select(emptyList())
        }

        assertTrue(exception.message!!.contains("candidate", ignoreCase = true))
    }

    @Test
    fun buildsOnlyModesFeasibleForEachOutputSizeMinFrameDuration() {
        val candidates = CaptureModeSelector.standardCandidates(
            outputs = listOf(
                CaptureModeOutput(width = 1920, height = 1080, minFrameDurationNs = 33_333_334L),
                CaptureModeOutput(width = 1280, height = 720, minFrameDurationNs = 16_666_667L),
                CaptureModeOutput(width = 640, height = 480, minFrameDurationNs = 8_333_334L),
            ),
            fpsRanges = listOf(
                CaptureFpsRange(minFps = 30, maxFps = 30),
                CaptureFpsRange(minFps = 30, maxFps = 60),
                CaptureFpsRange(minFps = 30, maxFps = 120),
            ),
        )

        assertEquals(
            listOf(
                CaptureModeCandidate(width = 1920, height = 1080, minFps = 30, maxFps = 30),
                CaptureModeCandidate(width = 1280, height = 720, minFps = 30, maxFps = 30),
                CaptureModeCandidate(width = 1280, height = 720, minFps = 30, maxFps = 60),
                CaptureModeCandidate(width = 640, height = 480, minFps = 30, maxFps = 30),
                CaptureModeCandidate(width = 640, height = 480, minFps = 30, maxFps = 60),
                CaptureModeCandidate(width = 640, height = 480, minFps = 30, maxFps = 120),
            ),
            candidates,
        )
    }

    @Test
    fun treatsUnknownMinFrameDurationAsFeasibleForDeclaredFpsRanges() {
        val candidates = CaptureModeSelector.standardCandidates(
            outputs = listOf(CaptureModeOutput(width = 1280, height = 720, minFrameDurationNs = 0L)),
            fpsRanges = listOf(CaptureFpsRange(minFps = 30, maxFps = 120)),
        )

        assertEquals(
            listOf(CaptureModeCandidate(width = 1280, height = 720, minFps = 30, maxFps = 120)),
            candidates,
        )
    }

    @Test
    fun choosesPracticalStandardModeFromPlausibleAndroidCameraModes() {
        val selected = CaptureModeSelector.select(
            listOf(
                CaptureModeCandidate(width = 3840, height = 2160, minFps = 30, maxFps = 30),
                CaptureModeCandidate(width = 1920, height = 1080, minFps = 30, maxFps = 60),
                CaptureModeCandidate(width = 1280, height = 720, minFps = 30, maxFps = 60),
                CaptureModeCandidate(width = 640, height = 480, minFps = 30, maxFps = 120),
            ),
        )

        assertEquals(CaptureMode(width = 640, height = 480, minFps = 30, maxFps = 120), selected)
    }
}