package com.doublebogey.golftracer.camera

class FpsCounter(private val maxSamples: Int = 30) {
    private val timestamps = ArrayDeque<Long>()

    fun recordFrame(timestampNs: Long): Double {
        timestamps.addLast(timestampNs)
        while (timestamps.size > maxSamples) {
            timestamps.removeFirst()
        }

        if (timestamps.size < 2) {
            return 0.0
        }

        val elapsedNs = timestamps.last() - timestamps.first()
        if (elapsedNs <= 0L) {
            return 0.0
        }

        return (timestamps.size - 1) * 1_000_000_000.0 / elapsedNs
    }

    fun reset() {
        timestamps.clear()
    }
}
