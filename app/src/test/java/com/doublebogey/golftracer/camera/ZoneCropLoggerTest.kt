package com.doublebogey.golftracer.camera

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZoneCropLoggerTest {
    @Test
    fun writesCenteredFortyEightPixelLumaCropWithMetadata() {
        val directory = createTempDirectory(prefix = "zone-crops").toFile()
        val logger = ZoneCropLogger(directory = directory)
        val frame = gradientFrame(width = 80, height = 64)

        val entry = logger.logSample(
            frame = frame,
            centerX = 0.50,
            centerY = 0.50,
            kind = ZoneCropSampleKind.LockedBall,
            timestampNs = 123L,
            score = 4.25,
        )

        assertTrue(entry.imageFile.exists())
        assertTrue(entry.metadataFile.exists())
        assertEquals("P5", entry.imageFile.readBytes().decodeToString(endIndex = 2))
        val metadata = entry.metadataFile.readText()
        assertTrue(metadata.contains("\"kind\":\"locked_ball\""))
        assertTrue(metadata.contains("\"timestampNs\":123"))
        assertTrue(metadata.contains("\"score\":4.25"))
    }

    @Test
    fun exportsSessionDirectoryAsZip() {
        val directory = createTempDirectory(prefix = "zone-crops").toFile()
        val logger = ZoneCropLogger(directory = directory)
        logger.logSample(
            frame = gradientFrame(width = 80, height = 64),
            centerX = 0.50,
            centerY = 0.50,
            kind = ZoneCropSampleKind.RandomNegative,
            timestampNs = 456L,
        )

        val zip = File(directory.parentFile, "${directory.name}.zip")
        val exported = logger.exportSessionZip(zip)

        assertEquals(zip, exported)
        assertTrue(zip.exists())
        assertTrue(zip.length() > 0L)
    }

    private fun gradientFrame(width: Int, height: Int): YuvFrame {
        val y = ByteArray(width * height)
        val u = ByteArray(width * height) { 128.toByte() }
        val v = ByteArray(width * height) { 128.toByte() }
        for (row in 0 until height) {
            for (column in 0 until width) {
                y[row * width + column] = ((column + row) % 256).toByte()
            }
        }
        return YuvFrame(width = width, height = height, y = y, u = u, v = v)
    }
}
