package com.doublebogey.golftracer.camera

import java.nio.ByteBuffer

data class YuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
) {
    init {
        require(width > 0) { "width must be greater than 0" }
        require(height > 0) { "height must be greater than 0" }
        require(y.size == width * height) { "y size must equal width * height" }
        require(u.size == width * height) { "u size must equal width * height" }
        require(v.size == width * height) { "v size must equal width * height" }
    }

    fun toLumaFrame(): LumaFrame =
        LumaFrame(width = width, height = height, luma = y.copyOf())
}

object YuvFrameExtractor {
    fun extract(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        yPixelStride: Int,
        uRowStride: Int,
        uPixelStride: Int,
        vRowStride: Int,
        vPixelStride: Int,
    ): YuvFrame {
        require(width > 0) { "width must be greater than 0" }
        require(height > 0) { "height must be greater than 0" }
        require(yRowStride > 0) { "yRowStride must be greater than 0" }
        require(yPixelStride > 0) { "yPixelStride must be greater than 0" }
        require(uRowStride > 0) { "uRowStride must be greater than 0" }
        require(uPixelStride > 0) { "uPixelStride must be greater than 0" }
        require(vRowStride > 0) { "vRowStride must be greater than 0" }
        require(vPixelStride > 0) { "vPixelStride must be greater than 0" }

        val yPlane = yBuffer.duplicate()
        val uPlane = uBuffer.duplicate()
        val vPlane = vBuffer.duplicate()
        val y = ByteArray(width * height)
        val u = ByteArray(width * height)
        val v = ByteArray(width * height)

        var outputIndex = 0
        for (row in 0 until height) {
            val yRowStart = row * yRowStride
            val chromaRow = row / 2
            val uRowStart = chromaRow * uRowStride
            val vRowStart = chromaRow * vRowStride
            for (column in 0 until width) {
                val chromaColumn = column / 2
                y[outputIndex] = yPlane.get(yRowStart + column * yPixelStride)
                u[outputIndex] = uPlane.get(uRowStart + chromaColumn * uPixelStride)
                v[outputIndex] = vPlane.get(vRowStart + chromaColumn * vPixelStride)
                outputIndex += 1
            }
        }

        return YuvFrame(width = width, height = height, y = y, u = u, v = v)
    }

    fun extractCrop(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        sourceWidth: Int,
        sourceHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        yRowStride: Int,
        yPixelStride: Int,
        uRowStride: Int,
        uPixelStride: Int,
        vRowStride: Int,
        vPixelStride: Int,
    ): YuvFrame {
        require(sourceWidth > 0) { "sourceWidth must be greater than 0" }
        require(sourceHeight > 0) { "sourceHeight must be greater than 0" }
        require(cropWidth > 0) { "cropWidth must be greater than 0" }
        require(cropHeight > 0) { "cropHeight must be greater than 0" }
        require(cropLeft >= 0 && cropTop >= 0) { "crop origin must not be negative" }
        require(cropLeft + cropWidth <= sourceWidth) { "crop must fit source width" }
        require(cropTop + cropHeight <= sourceHeight) { "crop must fit source height" }

        val yPlane = yBuffer.duplicate()
        val uPlane = uBuffer.duplicate()
        val vPlane = vBuffer.duplicate()
        val y = ByteArray(cropWidth * cropHeight)
        val u = ByteArray(cropWidth * cropHeight)
        val v = ByteArray(cropWidth * cropHeight)

        var outputIndex = 0
        for (row in 0 until cropHeight) {
            val sourceRow = cropTop + row
            val yRowStart = sourceRow * yRowStride
            val chromaRow = sourceRow / 2
            val uRowStart = chromaRow * uRowStride
            val vRowStart = chromaRow * vRowStride
            for (column in 0 until cropWidth) {
                val sourceColumn = cropLeft + column
                val chromaColumn = sourceColumn / 2
                y[outputIndex] = yPlane.get(yRowStart + sourceColumn * yPixelStride)
                u[outputIndex] = uPlane.get(uRowStart + chromaColumn * uPixelStride)
                v[outputIndex] = vPlane.get(vRowStart + chromaColumn * vPixelStride)
                outputIndex += 1
            }
        }

        return YuvFrame(width = cropWidth, height = cropHeight, y = y, u = u, v = v)
    }
}
