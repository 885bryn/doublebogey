package com.doublebogey.golftracer.camera

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val DEFAULT_CROP_SIZE = 48

enum class ZoneCropSampleKind(val wireName: String) {
    LockedBall("locked_ball"),
    VerifiedCandidate("verified_candidate"),
    RejectedShape("rejected_shape"),
    Ambiguous("ambiguous"),
    RandomNegative("random_negative"),
}

data class ZoneCropLogEntry(
    val imageFile: File,
    val metadataFile: File,
)

internal data class ZoneCropSampleSelection(
    val candidate: LumaMotionCandidate?,
    val kind: ZoneCropSampleKind,
    val score: Double?,
    val shape: ZoneBallCandidateDebug? = null,
)

internal object ZoneCropSampleSelector {
    private const val LOCK_MATCH_TOLERANCE = 0.025

    fun select(
        lockedBall: LumaMotionCandidate?,
        debug: ZoneBallDebug,
    ): ZoneCropSampleSelection {
        val verified = debug.best
        val rejected = debug.topRejected
        return when {
            lockedBall != null -> ZoneCropSampleSelection(
                candidate = lockedBall,
                kind = ZoneCropSampleKind.LockedBall,
                score = lockedBall.confidence,
                shape = verified?.takeIf { leader ->
                    hypot(leader.candidate.x - lockedBall.x, leader.candidate.y - lockedBall.y) <=
                        LOCK_MATCH_TOLERANCE
                },
            )
            verified != null && debug.ambiguous -> ZoneCropSampleSelection(
                candidate = verified.candidate,
                kind = ZoneCropSampleKind.Ambiguous,
                score = verified.rankScore,
                shape = verified,
            )
            verified != null -> ZoneCropSampleSelection(
                candidate = verified.candidate,
                kind = ZoneCropSampleKind.VerifiedCandidate,
                score = verified.rankScore,
                shape = verified,
            )
            rejected != null -> ZoneCropSampleSelection(
                candidate = rejected.candidate,
                kind = ZoneCropSampleKind.RejectedShape,
                score = rejected.rankScore,
                shape = rejected,
            )
            else -> ZoneCropSampleSelection(
                candidate = null,
                kind = ZoneCropSampleKind.RandomNegative,
                score = null,
            )
        }
    }
}

class ZoneCropLogger(
    private val directory: File,
    private val cropSize: Int = DEFAULT_CROP_SIZE,
) {
    init {
        require(cropSize > 0) { "cropSize must be greater than 0" }
    }

    fun logSample(
        frame: YuvFrame,
        centerX: Double,
        centerY: Double,
        kind: ZoneCropSampleKind,
        timestampNs: Long,
        score: Double? = null,
        rejection: BallShapeRejection? = null,
        closedEdgeCoverage: Double? = null,
        radialAlignment: Double? = null,
        radiusVariation: Double? = null,
        lineContinuation: Double? = null,
    ): ZoneCropLogEntry {
        require(centerX.isFinite()) { "centerX must be finite" }
        require(centerY.isFinite()) { "centerY must be finite" }
        directory.mkdirs()

        val baseName = "${timestampNs}_${kind.wireName}_${nextSequence()}"
        val imageFile = File(directory, "$baseName.pgm")
        val metadataFile = File(directory, "$baseName.json")
        writePgm(frame, centerX.coerceIn(0.0, 1.0), centerY.coerceIn(0.0, 1.0), imageFile)
        metadataFile.writeText(
            metadata(
                timestampNs, kind, centerX, centerY, score, rejection,
                closedEdgeCoverage, radialAlignment, radiusVariation, lineContinuation,
            ),
        )
        return ZoneCropLogEntry(imageFile = imageFile, metadataFile = metadataFile)
    }

    fun exportSessionZip(destinationFile: File): File {
        destinationFile.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destinationFile))).use { zip ->
            directory.listFiles()
                .orEmpty()
                .filter { file -> file.isFile }
                .sortedBy { file -> file.name }
                .forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    FileInputStream(file).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        return destinationFile
    }

    private fun writePgm(frame: YuvFrame, centerX: Double, centerY: Double, output: File) {
        val centerPx = centerX * (frame.width - 1).coerceAtLeast(1)
        val centerPy = centerY * (frame.height - 1).coerceAtLeast(1)
        val radius = cropSize / 2.0
        BufferedOutputStream(FileOutputStream(output)).use { stream ->
            stream.write("P5\n$cropSize $cropSize\n255\n".toByteArray(Charsets.US_ASCII))
            for (row in 0 until cropSize) {
                val sourceY = (centerPy + row - radius + 0.5).roundToInt().coerceIn(0, frame.height - 1)
                for (column in 0 until cropSize) {
                    val sourceX = (centerPx + column - radius + 0.5).roundToInt().coerceIn(0, frame.width - 1)
                    stream.write(frame.y[sourceY * frame.width + sourceX].toInt() and 0xFF)
                }
            }
        }
    }

    private fun metadata(
        timestampNs: Long,
        kind: ZoneCropSampleKind,
        centerX: Double,
        centerY: Double,
        score: Double?,
        rejection: BallShapeRejection?,
        closedEdgeCoverage: Double?,
        radialAlignment: Double?,
        radiusVariation: Double?,
        lineContinuation: Double?,
    ): String {
        val optionalFields = buildString {
            score?.takeIf(Double::isFinite)?.let { append(",\"score\":${it.formatJson()}") }
            rejection?.let { append(",\"rejection\":\"${it.name}\"") }
            closedEdgeCoverage?.takeIf(Double::isFinite)?.let { append(",\"closedEdgeCoverage\":${it.formatJson()}") }
            radialAlignment?.takeIf(Double::isFinite)?.let { append(",\"radialAlignment\":${it.formatJson()}") }
            radiusVariation?.takeIf(Double::isFinite)?.let { append(",\"radiusVariation\":${it.formatJson()}") }
            lineContinuation?.takeIf(Double::isFinite)?.let { append(",\"lineContinuation\":${it.formatJson()}") }
        }
        return "{\"timestampNs\":$timestampNs,\"kind\":\"${kind.wireName}\",\"centerX\":${centerX.formatJson()},\"centerY\":${centerY.formatJson()}$optionalFields}\n"
    }

    private fun Double.formatJson(): String = String.format(Locale.US, "%.6f", this).trimEnd('0').trimEnd('.')

    private fun nextSequence(): Int = sequence++

    private var sequence = 0
}
