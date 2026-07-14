package com.doublebogey.golftracer.camera

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class YuvFrameExtractorCropTest {
    @Test
    fun extractsYuvCropUsingSourceCoordinatesAndStrides() {
        val ySource = bytes(
            10, 11, 12, 13,
            20, 21, 22, 23,
            30, 31, 32, 33,
            40, 41, 42, 43,
        )
        val uSource = bytes(
            100, 101,
            102, 103,
        )
        val vSource = bytes(
            110, 111,
            112, 113,
        )

        val crop = YuvFrameExtractor.extractCrop(
            yBuffer = ByteBuffer.wrap(ySource),
            uBuffer = ByteBuffer.wrap(uSource),
            vBuffer = ByteBuffer.wrap(vSource),
            sourceWidth = 4,
            sourceHeight = 4,
            cropLeft = 1,
            cropTop = 1,
            cropWidth = 2,
            cropHeight = 2,
            yRowStride = 4,
            yPixelStride = 1,
            uRowStride = 2,
            uPixelStride = 1,
            vRowStride = 2,
            vPixelStride = 1,
        )

        assertEquals(2, crop.width)
        assertEquals(2, crop.height)
        assertContentEquals(bytes(21, 22, 31, 32), crop.y)
        assertContentEquals(bytes(100, 101, 102, 103), crop.u)
        assertContentEquals(bytes(110, 111, 112, 113), crop.v)
    }

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { index -> values[index].toByte() }
}
