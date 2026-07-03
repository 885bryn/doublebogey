package com.doublebogey.golftracer.camera

import java.nio.ByteBuffer

object LumaFrameExtractor {
    fun extract(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): LumaFrame {
        require(rowStride > 0) { "rowStride must be greater than 0" }
        require(pixelStride > 0) { "pixelStride must be greater than 0" }

        val duplicate = buffer.duplicate()
        val luma = ByteArray(width * height)
        var outputIndex = 0

        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                luma[outputIndex] = duplicate.get(rowStart + x * pixelStride)
                outputIndex += 1
            }
        }

        return LumaFrame(width = width, height = height, luma = luma)
    }
}
