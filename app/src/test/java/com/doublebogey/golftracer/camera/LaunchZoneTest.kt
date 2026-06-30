package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchZoneTest {
    @Test
    fun defaultValuesMatchInitialLaunchZone() {
        val zone = LaunchZone.Default

        assertEquals(0.35, zone.left)
        assertEquals(0.68, zone.top)
        assertEquals(0.30, zone.width)
        assertEquals(0.18, zone.height)
    }

    @Test
    fun dragClampsLaunchZoneInsideUnitRectangle() {
        val zone = LaunchZone(left = 0.35, top = 0.68, width = 0.30, height = 0.18)

        assertZoneEquals(LaunchZone(left = 0.70, top = 0.82, width = 0.30, height = 0.18), zone.dragBy(0.80, 0.80))
        assertZoneEquals(LaunchZone(left = 0.0, top = 0.0, width = 0.30, height = 0.18), zone.dragBy(-0.80, -0.80))
    }

    @Test
    fun invalidPersistedValuesRestoreDefaultLaunchZone() {
        assertEquals(LaunchZone.Default, LaunchZone.fromPersisted(left = -0.01, top = 0.68, width = 0.30, height = 0.18))
        assertEquals(LaunchZone.Default, LaunchZone.fromPersisted(left = 0.80, top = 0.68, width = 0.30, height = 0.18))
        assertEquals(LaunchZone.Default, LaunchZone.fromPersisted(left = 0.35, top = 0.68, width = 0.0, height = 0.18))
        assertEquals(LaunchZone.Default, LaunchZone.fromPersisted(left = 0.35, top = 0.68, width = 0.30, height = 1.01))
    }

    @Test
    fun validPersistedValuesAreRestored() {
        val zone = LaunchZone.fromPersisted(left = 0.10, top = 0.20, width = 0.30, height = 0.40)

        assertZoneEquals(LaunchZone(left = 0.10, top = 0.20, width = 0.30, height = 0.40), zone)
    }

    @Test
    fun reportsWhetherZoneFitsInsideUnitRectangle() {
        assertTrue(LaunchZone(left = 0.10, top = 0.20, width = 0.30, height = 0.40).isValid())
        assertFalse(LaunchZone(left = 0.80, top = 0.20, width = 0.30, height = 0.40).isValid())
        assertFalse(LaunchZone(left = 0.10, top = 0.20, width = -0.01, height = 0.40).isValid())
    }

    @Test
    fun dragByPixelsConvertsViewDeltasToUnitDeltas() {
        val zone = LaunchZone(left = 0.35, top = 0.68, width = 0.30, height = 0.18)

        val dragged = zone.dragByPixels(deltaX = 128.0, deltaY = -72.0, viewWidth = 1280, viewHeight = 720)

        assertZoneEquals(LaunchZone(left = 0.45, top = 0.58, width = 0.30, height = 0.18), dragged)
    }

    private fun assertZoneEquals(expected: LaunchZone, actual: LaunchZone) {
        assertEquals(expected.left, actual.left, absoluteTolerance = 0.000000001)
        assertEquals(expected.top, actual.top, absoluteTolerance = 0.000000001)
        assertEquals(expected.width, actual.width, absoluteTolerance = 0.000000001)
        assertEquals(expected.height, actual.height, absoluteTolerance = 0.000000001)
    }
}
