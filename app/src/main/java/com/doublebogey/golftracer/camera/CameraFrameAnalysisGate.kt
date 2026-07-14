package com.doublebogey.golftracer.camera

class CameraFrameAnalysisGate {
    private val monitor = Object()
    private var analysisInProgress = false
    private var paused = false

    fun tryStartAnalysis(): Boolean = synchronized(monitor) {
        if (paused || analysisInProgress) {
            false
        } else {
            analysisInProgress = true
            true
        }
    }

    fun finishAnalysis() {
        synchronized(monitor) {
            analysisInProgress = false
            monitor.notifyAll()
        }
    }

    fun pauseAndAwaitIdle() {
        awaitIdleAfterPausing(reserveAnalysis = false, rejectIfPaused = false)
    }

    fun pauseAndReserveAnalysis(): Boolean =
        awaitIdleAfterPausing(reserveAnalysis = true, rejectIfPaused = true)

    private fun awaitIdleAfterPausing(reserveAnalysis: Boolean, rejectIfPaused: Boolean): Boolean {
        var interrupted = false
        synchronized(monitor) {
            if (rejectIfPaused && paused) return false
            paused = true
            while (analysisInProgress) {
                try {
                    monitor.wait()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (reserveAnalysis) {
                analysisInProgress = true
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
        return true
    }

    fun resume() {
        synchronized(monitor) {
            paused = false
        }
    }
}

data class FrameGeneration(
    val camera: Int,
    val reader: Int,
)

class GenerationBoundDispatcher<T>(
    private val isCurrent: (T) -> Boolean,
    private val enqueue: (() -> Unit) -> Boolean,
) {
    fun dispatch(expectedGeneration: T, callback: () -> Unit): Boolean =
        enqueue {
            if (isCurrent(expectedGeneration)) {
                callback()
            }
        }
}

class LifecycleResourceCoordinator {
    private val monitor = Any()

    fun <T> mutate(block: () -> T): T = synchronized(monitor, block)

    fun publishIfCurrent(isCurrent: () -> Boolean, publish: () -> Unit): Boolean =
        mutate {
            if (isCurrent()) {
                publish()
                true
            } else {
                false
            }
        }
}

class GenerationBoundAnalysisCommandDispatcher<T>(
    private val isCurrent: (T) -> Boolean,
    private val enqueue: (() -> Unit) -> Boolean,
    private val analysisGate: CameraFrameAnalysisGate,
) {
    fun dispatch(expectedGeneration: T, command: () -> Unit): Boolean {
        if (!analysisGate.pauseAndReserveAnalysis()) return false

        val enqueued = enqueue {
            val shouldResume = isCurrent(expectedGeneration)
            try {
                if (shouldResume) command()
            } finally {
                analysisGate.finishAnalysis()
                if (shouldResume && isCurrent(expectedGeneration)) {
                    analysisGate.resume()
                }
            }
        }
        if (!enqueued) {
            analysisGate.finishAnalysis()
            if (isCurrent(expectedGeneration)) {
                analysisGate.resume()
            }
        }
        return enqueued
    }
}
