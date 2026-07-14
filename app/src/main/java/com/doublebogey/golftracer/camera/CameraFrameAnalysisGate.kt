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
        var interrupted = false
        synchronized(monitor) {
            paused = true
            while (analysisInProgress) {
                try {
                    monitor.wait()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
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
