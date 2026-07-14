package com.doublebogey.golftracer.camera

import kotlin.test.Test
import kotlin.test.assertEquals

class FrameCoordinateMapperTest {
    @Test
    fun identityMapsPointsUnchanged() {
        val mapper = FrameCoordinateMapper.Identity

        assertEquals(FrameCoordinateMapper.MappedPoint(0.3, 0.7), mapper.frameToView(0.3, 0.7))
        assertEquals(FrameCoordinateMapper.MappedPoint(0.3, 0.7), mapper.viewToFrame(0.3, 0.7))
    }

    @Test
    fun rotation90MapsBufferCornersToDisplayCorners() {
        val mapper = FrameCoordinateMapper(90)

        // Buffer top-left appears at display top-right after a 90° clockwise rotation.
        assertEquals(FrameCoordinateMapper.MappedPoint(1.0, 0.0), mapper.frameToView(0.0, 0.0))
        assertEquals(FrameCoordinateMapper.MappedPoint(1.0, 1.0), mapper.frameToView(1.0, 0.0))
        assertEquals(FrameCoordinateMapper.MappedPoint(0.0, 0.0), mapper.frameToView(0.0, 1.0))
        assertEquals(FrameCoordinateMapper.MappedPoint(0.0, 1.0), mapper.frameToView(1.0, 1.0))
    }

    @Test
    fun rotation270MapsBufferCornersToDisplayCorners() {
        val mapper = FrameCoordinateMapper(270)

        // Buffer top-left appears at display bottom-left after a 270° clockwise rotation.
        assertEquals(FrameCoordinateMapper.MappedPoint(0.0, 1.0), mapper.frameToView(0.0, 0.0))
        assertEquals(FrameCoordinateMapper.MappedPoint(0.0, 0.0), mapper.frameToView(1.0, 0.0))
        assertEquals(FrameCoordinateMapper.MappedPoint(1.0, 1.0), mapper.frameToView(0.0, 1.0))
        assertEquals(FrameCoordinateMapper.MappedPoint(1.0, 0.0), mapper.frameToView(1.0, 1.0))
    }

    @Test
    fun viewToFrameRoundTripsForAllRotations() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val mapper = FrameCoordinateMapper(rotation)
            val original = FrameCoordinateMapper.MappedPoint(0.37, 0.81)

            val frame = mapper.viewToFrame(original.x, original.y)
            val roundTripped = mapper.frameToView(frame.x, frame.y)

            assertEquals(original.x, roundTripped.x, absoluteTolerance = 1e-9, "x rotation=$rotation")
            assertEquals(original.y, roundTripped.y, absoluteTolerance = 1e-9, "y rotation=$rotation")
        }
    }

    @Test
    fun rotation90MapsPortraitLaunchZoneIntoLandscapeFrameZone() {
        val mapper = FrameCoordinateMapper(90)
        val viewZone = LaunchZone(left = 0.40, top = 0.60, width = 0.30, height = 0.18)

        val frameZone = mapper.viewZoneToFrameZone(viewZone)

        assertEquals(0.60, frameZone.left, absoluteTolerance = 1e-9)
        assertEquals(0.30, frameZone.top, absoluteTolerance = 1e-9)
        assertEquals(0.18, frameZone.width, absoluteTolerance = 1e-9)
        assertEquals(0.30, frameZone.height, absoluteTolerance = 1e-9)
    }

    @Test
    fun rotation180ZoneMappingIsSelfInverse() {
        val mapper = FrameCoordinateMapper(180)
        val viewZone = LaunchZone(left = 0.10, top = 0.20, width = 0.30, height = 0.40)

        val frameZone = mapper.viewZoneToFrameZone(viewZone)
        val roundTripped = mapper.viewZoneToFrameZone(frameZone)

        assertEquals(0.60, frameZone.left, absoluteTolerance = 1e-9)
        assertEquals(0.40, frameZone.top, absoluteTolerance = 1e-9)
        assertEquals(viewZone.left, roundTripped.left, absoluteTolerance = 1e-9)
        assertEquals(viewZone.top, roundTripped.top, absoluteTolerance = 1e-9)
    }

    @Test
    fun rearCameraFactoryCombinesSensorAndDisplayRotation() {
        assertEquals(90, FrameCoordinateMapper.forRearCamera(90, 0).rotationDegrees)
        assertEquals(0, FrameCoordinateMapper.forRearCamera(90, 90).rotationDegrees)
        assertEquals(180, FrameCoordinateMapper.forRearCamera(90, 270).rotationDegrees)
        assertEquals(270, FrameCoordinateMapper.forRearCamera(0, 90).rotationDegrees)
        assertEquals(270, FrameCoordinateMapper.forRearCamera(270, 0).rotationDegrees)
    }
}
