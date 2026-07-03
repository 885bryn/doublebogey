package com.doublebogey.golftracer.camera

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class YuvFrameExtractorTest {
    @Test
    fun expandsYuv420PlanesUsingRowAndPixelStrides() {
        val ySource = bytes(
            10, 99, 20, 98, 30, 97, 0,
            40, 96, 50, 95, 60, 94, 0,
            70, 93, 80, 92, 90, 91, 0,
            100, 89, 110, 88, 120, 87, 0,
        )
        val uSource = bytes(
            121, 77, 122, 76, 0,
            123, 75, 124, 74, 0,
        )
        val vSource = bytes(
            131, 66, 132, 65, 0,
            133, 64, 134, 63, 0,
        )

        val frame = YuvFrameExtractor.extract(
            yBuffer = ByteBuffer.wrap(ySource),
            uBuffer = ByteBuffer.wrap(uSource),
            vBuffer = ByteBuffer.wrap(vSource),
            width = 3,
            height = 4,
            yRowStride = 7,
            yPixelStride = 2,
            uRowStride = 5,
            uPixelStride = 2,
            vRowStride = 5,
            vPixelStride = 2,
        )

        assertEquals(3, frame.width)
        assertEquals(4, frame.height)
        assertContentEquals(
            bytes(
                10, 20, 30,
                40, 50, 60,
                70, 80, 90,
                100, 110, 120,
            ),
            frame.y,
        )
        assertContentEquals(
            bytes(
                121, 121, 122,
                121, 121, 122,
                123, 123, 124,
                123, 123, 124,
            ),
            frame.u,
        )
        assertContentEquals(
            bytes(
                131, 131, 132,
                131, 131, 132,
                133, 133, 134,
                133, 133, 134,
            ),
            frame.v,
        )
        assertContentEquals(frame.y, frame.toLumaFrame().luma)
    }

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { index -> values[index].toByte() }
}
