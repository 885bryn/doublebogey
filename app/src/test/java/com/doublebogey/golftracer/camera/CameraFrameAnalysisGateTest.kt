package com.doublebogey.golftracer.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CameraFrameAnalysisGateTest {
    @Test
    fun dropsFramesWhileAnalysisIsInProgress() {
        val gate = CameraFrameAnalysisGate()

        assertTrue(gate.tryStartAnalysis())
        assertFalse(gate.tryStartAnalysis())

        gate.finishAnalysis()

        assertTrue(gate.tryStartAnalysis())
    }

    @Test
    fun pauseWaitsUntilActiveAnalysisFinishes() {
        val gate = CameraFrameAnalysisGate()
        val pauseFinished = CountDownLatch(1)
        assertTrue(gate.tryStartAnalysis())

        val pausingThread = Thread {
            gate.pauseAndAwaitIdle()
            pauseFinished.countDown()
        }
        pausingThread.start()

        assertTrue(pausingThread.awaitState(Thread.State.WAITING))
        assertFalse(pauseFinished.await(0, TimeUnit.MILLISECONDS))
        gate.finishAnalysis()
        assertTrue(pauseFinished.await(1, TimeUnit.SECONDS))
        pausingThread.join()
    }

    @Test
    fun pauseRestoresInterruptedFlagAfterAnalysisFinishes() {
        val gate = CameraFrameAnalysisGate()
        val restored = AtomicBoolean(false)
        val pauseFinished = CountDownLatch(1)
        assertTrue(gate.tryStartAnalysis())

        val pausingThread = Thread {
            Thread.currentThread().interrupt()
            gate.pauseAndAwaitIdle()
            restored.set(Thread.currentThread().isInterrupted)
            pauseFinished.countDown()
        }
        pausingThread.start()

        assertTrue(pausingThread.awaitState(Thread.State.WAITING))
        gate.finishAnalysis()
        assertTrue(pauseFinished.await(1, TimeUnit.SECONDS))
        assertTrue(restored.get())
        pausingThread.join()
    }

    @Test
    fun pausedGateRejectsNewAnalysisUntilResumed() {
        val gate = CameraFrameAnalysisGate()

        gate.pauseAndAwaitIdle()
        assertFalse(gate.tryStartAnalysis())

        gate.resume()
        assertTrue(gate.tryStartAnalysis())
    }

    @Test
    fun finishAnalysisReleasesGateAfterSimulatedFailure() {
        val gate = CameraFrameAnalysisGate()
        assertTrue(gate.tryStartAnalysis())

        assertFailsWith<IllegalStateException> {
            try {
                error("simulated analysis failure")
            } finally {
                gate.finishAnalysis()
            }
        }

        assertTrue(gate.tryStartAnalysis())
    }

    @Test
    fun generationBoundDeliveryDropsQueuedCallbackAfterCameraGenerationChanges() {
        var current = FrameGeneration(camera = 4, reader = 9)
        val queued = mutableListOf<() -> Unit>()
        val dispatcher = GenerationBoundDispatcher<FrameGeneration>(
            isCurrent = { expected -> current == expected },
            enqueue = { callback ->
                queued += callback
                true
            },
        )
        var delivered = false

        assertTrue(dispatcher.dispatch(current) { delivered = true })
        current = FrameGeneration(camera = 5, reader = 10)
        queued.single().invoke()

        assertFalse(delivered)
    }

    @Test
    fun generationBoundDeliveryDropsQueuedCallbackAfterReaderChangesInSameCameraGeneration() {
        var current = FrameGeneration(camera = 7, reader = 2)
        val queued = mutableListOf<() -> Unit>()
        val dispatcher = GenerationBoundDispatcher<FrameGeneration>(
            isCurrent = { expected -> current == expected },
            enqueue = { callback ->
                queued += callback
                true
            },
        )
        var delivered = false

        assertTrue(dispatcher.dispatch(current) { delivered = true })
        current = FrameGeneration(camera = 7, reader = 3)
        queued.single().invoke()

        assertFalse(delivered)
    }

    @Test
    fun generationBoundDeliveryDeliversCurrentFrameGenerationCallback() {
        val current = FrameGeneration(camera = 7, reader = 3)
        val queued = mutableListOf<() -> Unit>()
        val dispatcher = GenerationBoundDispatcher<FrameGeneration>(
            isCurrent = { expected -> expected == current },
            enqueue = { callback ->
                queued += callback
                true
            },
        )
        var delivered = false

        assertTrue(dispatcher.dispatch(current) { delivered = true })
        queued.single().invoke()

        assertTrue(delivered)
    }

    @Test
    fun publicationBeforeStopIsVisibleToCleanup() {
        val coordinator = LifecycleResourceCoordinator()
        val publicationEntered = CountDownLatch(1)
        val allowPublicationToFinish = CountDownLatch(1)
        val cleanupFinished = CountDownLatch(1)
        var current = true
        var published: String? = null
        var cleaned: String? = null

        val publicationThread = Thread {
            coordinator.publishIfCurrent({ current }) {
                published = "reader"
                publicationEntered.countDown()
                assertTrue(allowPublicationToFinish.await(1, TimeUnit.SECONDS))
            }
        }
        publicationThread.start()
        assertTrue(publicationEntered.await(1, TimeUnit.SECONDS))

        val stopThread = Thread {
            coordinator.mutate {
                current = false
                cleaned = published
                published = null
            }
            cleanupFinished.countDown()
        }
        stopThread.start()
        assertFalse(cleanupFinished.await(0, TimeUnit.MILLISECONDS))

        allowPublicationToFinish.countDown()
        publicationThread.join()
        stopThread.join()
        assertTrue(cleaned == "reader")
        assertTrue(published == null)
    }

    @Test
    fun stopBeforePublicationRejectsTheLocalResource() {
        val coordinator = LifecycleResourceCoordinator()
        val stopEntered = CountDownLatch(1)
        val allowStopToFinish = CountDownLatch(1)
        val publicationFinished = CountDownLatch(1)
        var current = true
        var published: String? = null
        var accepted = true

        val stopThread = Thread {
            coordinator.mutate {
                current = false
                stopEntered.countDown()
                assertTrue(allowStopToFinish.await(1, TimeUnit.SECONDS))
            }
        }
        stopThread.start()
        assertTrue(stopEntered.await(1, TimeUnit.SECONDS))

        val publicationThread = Thread {
            accepted = coordinator.publishIfCurrent({ current }) { published = "reader" }
            publicationFinished.countDown()
        }
        publicationThread.start()
        assertFalse(publicationFinished.await(0, TimeUnit.MILLISECONDS))

        allowStopToFinish.countDown()
        stopThread.join()
        publicationThread.join()
        assertFalse(accepted)
        assertTrue(published == null)
    }


    @Test
    fun resetReservationWaitsForQueuedFrameThenBlocksCompetingFrameUntilResetPublishes() {
        val generation = FrameGeneration(camera = 3, reader = 8)
        val gate = CameraFrameAnalysisGate()
        val queued = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        val scheduled = AtomicBoolean(false)
        val dispatcher = GenerationBoundAnalysisCommandDispatcher<FrameGeneration>(
            isCurrent = { expected -> expected == generation },
            enqueue = { command -> queued.add(command) },
            analysisGate = gate,
        )
        assertTrue(gate.tryStartAnalysis())

        val resetThread = Thread {
            scheduled.set(
                dispatcher.dispatch(generation) {
                    events += "reset mutation"
                    events += "tracking publication"
                    events += "auto publication"
                    events += "status publication"
                },
            )
        }
        resetThread.start()
        assertTrue(resetThread.awaitState(Thread.State.WAITING))

        events += "frame mutation"
        assertFalse(gate.tryStartAnalysis())
        gate.finishAnalysis()
        resetThread.join()

        assertTrue(scheduled.get())
        assertFalse(gate.tryStartAnalysis(), "a competing frame must not jump ahead of the reserved reset")
        queued.single().invoke()

        assertTrue(
            events == listOf(
                "frame mutation",
                "reset mutation",
                "tracking publication",
                "auto publication",
                "status publication",
            ),
            events.toString(),
        )
        assertTrue(gate.tryStartAnalysis(), "current reset resumes frame admission after publication")
        gate.finishAnalysis()
    }

    @Test
    fun analysisCommandQueuedForStoppedGenerationIsDroppedAndLeavesGatePaused() {
        var current = FrameGeneration(camera = 3, reader = 8)
        val gate = CameraFrameAnalysisGate()
        val queued = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        val dispatcher = GenerationBoundAnalysisCommandDispatcher<FrameGeneration>(
            isCurrent = { expected -> expected == current },
            enqueue = { command -> queued.add(command) },
            analysisGate = gate,
        )

        assertTrue(dispatcher.dispatch(current) { events += "reset and publish" })
        current = FrameGeneration(camera = 4, reader = 9)
        queued.single().invoke()

        assertTrue(events.isEmpty())
        assertFalse(gate.tryStartAnalysis(), "stale command must not resume a stopped generation")
    }

    @Test
    fun pausedAnalysisGateRejectsResetReservationBeforeItCanQueue() {
        val generation = FrameGeneration(camera = 3, reader = 8)
        val gate = CameraFrameAnalysisGate()
        val queued = mutableListOf<() -> Unit>()
        val dispatcher = GenerationBoundAnalysisCommandDispatcher<FrameGeneration>(
            isCurrent = { true },
            enqueue = { command -> queued.add(command) },
            analysisGate = gate,
        )
        gate.pauseAndAwaitIdle()

        assertFalse(dispatcher.dispatch(generation) { error("must not mutate") })
        assertTrue(queued.isEmpty())
    }
    private fun Thread.awaitState(expected: Thread.State): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (System.nanoTime() < deadline) {
            if (state == expected) return true
            Thread.yield()
        }
        return state == expected
    }
}
