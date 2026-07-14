package com.doublebogey.golftracer.camera

import kotlin.math.abs

data class ZoneMotionMeterConfig(
    val motionThreshold: Int = 24,
    val quietChangedFraction: Double = 0.02,
) {
    init {
        require(motionThreshold in 0..255) { "motionThreshold must be in 0..255" }
        require(quietChangedFraction in 0.0..1.0) { "quietChangedFraction must be in 0..1" }
    }
}

data class ZoneMotionMeasurement(
    val changedFraction: Double,
    val quiet: Boolean,
)

class ZoneMotionMeter(
    private val config: ZoneMotionMeterConfig = ZoneMotionMeterConfig(),
) {
    private var previousFrame: LumaFrame? = null

    fun measure(
        frame: YuvFrame,
        launchZone: LaunchZone,
        mapper: FrameCoordinateMapper = FrameCoordinateMapper.Identity,
    ): ZoneMotionMeasurement = measure(frame.toLumaFrame(), launchZone, mapper)

    fun measure(
        frame: LumaFrame,
        launchZone: LaunchZone,
        mapper: FrameCoordinateMapper = FrameCoordinateMapper.Identity,
    ): ZoneMotionMeasurement {
        val bounds = mapper.viewZoneToFrameZone(launchZone).bounds(frame)
        val previous = previousFrame
        previousFrame = frame.copy(luma = frame.luma.copyOf())
        if (previous == null || previous.width != frame.width || previous.height != frame.height) {
            return ZoneMotionMeasurement(changedFraction = 0.0, quiet = true)
        }

        var changed = 0
        for (row in bounds.top..bounds.bottom) {
            for (column in bounds.left..bounds.right) {
                val index = row * frame.width + column
                val delta = abs(frame.luma[index].unsigned() - previous.luma[index].unsigned())
                if (delta >= config.motionThreshold) {
                    changed += 1
                }
            }
        }
        val changedFraction = changed.toDouble() / bounds.area.toDouble()
        return ZoneMotionMeasurement(
            changedFraction = changedFraction,
            quiet = changedFraction <= config.quietChangedFraction,
        )
    }

    fun reset() {
        previousFrame = null
    }

    private fun LaunchZone.bounds(frame: LumaFrame): PixelBounds {
        val maxX = frame.width - 1
        val maxY = frame.height - 1
        val leftPx = (left * maxX).toInt().coerceIn(0, maxX)
        val rightPx = ((left + width) * maxX).toInt().coerceIn(leftPx, maxX)
        val topPx = (top * maxY).toInt().coerceIn(0, maxY)
        val bottomPx = ((top + height) * maxY).toInt().coerceIn(topPx, maxY)
        return PixelBounds(left = leftPx, right = rightPx, top = topPx, bottom = bottomPx)
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF

    private data class PixelBounds(val left: Int, val right: Int, val top: Int, val bottom: Int) {
        val area: Int = (right - left + 1) * (bottom - top + 1)
    }
}
