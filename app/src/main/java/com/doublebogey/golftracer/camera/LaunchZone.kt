package com.doublebogey.golftracer.camera

data class LaunchZone(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
) {
    fun dragBy(deltaX: Double, deltaY: Double): LaunchZone {
        return copy(
            left = (left + deltaX).coerceIn(0.0, 1.0 - width),
            top = (top + deltaY).coerceIn(0.0, 1.0 - height),
        )
    }

    fun dragByPixels(deltaX: Double, deltaY: Double, viewWidth: Int, viewHeight: Int): LaunchZone {
        require(viewWidth > 0) { "viewWidth must be greater than 0" }
        require(viewHeight > 0) { "viewHeight must be greater than 0" }

        return dragBy(deltaX / viewWidth, deltaY / viewHeight)
    }

    fun isValid(): Boolean {
        return width > 0.0 &&
            height > 0.0 &&
            left >= 0.0 &&
            top >= 0.0 &&
            left + width <= 1.0 &&
            top + height <= 1.0
    }

    companion object {
        val Default = LaunchZone(left = 0.35, top = 0.68, width = 0.30, height = 0.18)

        fun fromPersisted(left: Double, top: Double, width: Double, height: Double): LaunchZone {
            val persisted = LaunchZone(left = left, top = top, width = width, height = height)
            return if (persisted.isValid()) persisted else Default
        }
    }
}
