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
    fun writesShapeDiagnosticsUsingStableExactJsonKeys() {
        val directory = createTempDirectory(prefix = "zone-crops").toFile()
        val logger = ZoneCropLogger(directory = directory)

        val entry = logger.logSample(
            frame = gradientFrame(width = 80, height = 64),
            centerX = 0.25,
            centerY = 0.75,
            kind = ZoneCropSampleKind.RejectedShape,
            timestampNs = 789L,
            score = 3.5,
            rejection = BallShapeRejection.LineContinuation,
            closedEdgeCoverage = 0.625,
            radialAlignment = 0.8125,
            radiusVariation = 0.125,
            lineContinuation = 0.4375,
        )

        assertEquals(
            "{\"timestampNs\":789,\"kind\":\"rejected_shape\",\"centerX\":0.25," +
                "\"centerY\":0.75,\"score\":3.5,\"rejection\":\"LineContinuation\"," +
                "\"closedEdgeCoverage\":0.625,\"radialAlignment\":0.8125," +
                "\"radiusVariation\":0.125,\"lineContinuation\":0.4375}\n",
            entry.metadataFile.readText(),
        )
    }

    @Test
    fun exposesAllStableSampleKindWireNames() {
        assertEquals("locked_ball", ZoneCropSampleKind.LockedBall.wireName)
        assertEquals("verified_candidate", ZoneCropSampleKind.VerifiedCandidate.wireName)
        assertEquals("rejected_shape", ZoneCropSampleKind.RejectedShape.wireName)
        assertEquals("ambiguous", ZoneCropSampleKind.Ambiguous.wireName)
        assertEquals("random_negative", ZoneCropSampleKind.RandomNegative.wireName)
    }

    @Test
    fun omitsNonFiniteDiagnosticsInsteadOfWritingInvalidJson() {
        val directory = createTempDirectory(prefix = "zone-crops").toFile()
        val logger = ZoneCropLogger(directory = directory)
        val entry = logger.logSample(
            frame = gradientFrame(width = 80, height = 64), centerX = 0.5, centerY = 0.5,
            kind = ZoneCropSampleKind.RejectedShape, timestampNs = 790L,
            score = Double.POSITIVE_INFINITY,
            rejection = BallShapeRejection.InsufficientClosedEdge,
            closedEdgeCoverage = Double.NaN, radialAlignment = 0.75,
            radiusVariation = Double.NEGATIVE_INFINITY, lineContinuation = 0.25,
        )
        assertEquals(
            "{\"timestampNs\":790,\"kind\":\"rejected_shape\",\"centerX\":0.5," +
                "\"centerY\":0.5,\"rejection\":\"InsufficientClosedEdge\"," +
                "\"radialAlignment\":0.75,\"lineContinuation\":0.25}\n",
            entry.metadataFile.readText(),
        )
    }

    @Test
    fun lockedSampleOmitsShapeFromDifferentAmbiguousLeader() {
        val locked = candidate(x = 0.20, y = 0.70)
        val differentLeader = candidateDebug(candidate(x = 0.65, y = 0.75))
        val sample = ZoneCropSampleSelector.select(
            lockedBall = locked,
            debug = ZoneBallDebug(candidates = listOf(differentLeader), ambiguous = true),
        )
        assertEquals(ZoneCropSampleKind.LockedBall, sample.kind)
        assertEquals(locked, sample.candidate)
        assertEquals(null, sample.shape)
    }
    @Test
    fun lockedSampleKeepsShapeForLeaderAtSamePointWithinTolerance() {
        val locked = candidate(x = 0.20, y = 0.70)
        val matchingLeader = candidateDebug(candidate(x = 0.22, y = 0.70))
        val sample = ZoneCropSampleSelector.select(
            lockedBall = locked,
            debug = ZoneBallDebug(candidates = listOf(matchingLeader), ambiguous = true),
        )
        assertEquals(matchingLeader, sample.shape)
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

    private fun candidate(x: Double, y: Double) =
        LumaMotionCandidate(x = x, y = y, pixelCount = 16, confidence = 0.9)

    private fun candidateDebug(candidate: LumaMotionCandidate) = ZoneBallCandidateDebug(
        candidate = candidate,
        radiusPx = 4.0,
        proposalResponse = 8.0,
        rankScore = 7.0,
        metrics = BallShapeMetrics(10.0, 0.1, 0.9, 0.9, 0.1, 0.0, 2.0),
        rejection = null,
    )
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
