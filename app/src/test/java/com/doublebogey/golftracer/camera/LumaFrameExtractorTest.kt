package com.doublebogey.golftracer.camera

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class LumaFrameExtractorTest {
    @Test
    fun copiesContiguousYPlane() {
        val frame = LumaFrameExtractor.extract(
            buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6)),
            width = 3,
            height = 2,
            rowStride = 3,
            pixelStride = 1,
        )

        assertEquals(3, frame.width)
        assertEquals(2, frame.height)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), frame.luma)
    }

    @Test
    fun skipsRowPaddingAndPixelStride() {
        val source = byteArrayOf(
            10, 99, 20, 98, 30, 97, 0,
            40, 96, 50, 95, 60, 94, 0,
        )

        val frame = LumaFrameExtractor.extract(
            buffer = ByteBuffer.wrap(source),
            width = 3,
            height = 2,
            rowStride = 7,
            pixelStride = 2,
        )

        assertContentEquals(byteArrayOf(10, 20, 30, 40, 50, 60), frame.luma)
    }
}
