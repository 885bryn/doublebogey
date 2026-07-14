package com.doublebogey.golftracer.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.util.Locale

class CameraCaptureController(
    private val context: Context,
    private val textureView: TextureView,
    private val onStatus: (String) -> Unit,
    private val launchZoneProvider: () -> LaunchZone = { LaunchZone.Default },
    private val onDetectionResult: (LumaMotionResult) -> Unit = {},
    private val onTrackingState: (ShotTrackerState) -> Unit = {},
    private val onAutoTrackingState: (AutoShotTrackerState) -> Unit = {},
) {
    private val cameraManager: CameraManager =
        context.getSystemService(CameraManager::class.java)
    private val fpsCounter = FpsCounter()
    private val detector = LumaMotionDetector()
    private val tracker = AutoShotTracker()
    private val cropLogger = ZoneCropLogger(File(context.filesDir, "debug-crops"))
    private val cameraCallbackHandler = Handler(Looper.getMainLooper())
    private val resourceCoordinator = LifecycleResourceCoordinator()
    private val callbackDispatcher = GenerationBoundDispatcher<FrameGeneration>(
        isCurrent = ::isCurrent,
        enqueue = cameraCallbackHandler::post,
    )
    private val analysisGate = CameraFrameAnalysisGate()
    private val requestPolicy = CameraRequestPolicy.default()

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var analysisThread: HandlerThread? = null
    private var analysisHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var selectedMode: CaptureMode? = null
    private var remainingModes = ArrayDeque<CaptureMode>()
    private var frameCount = 0L
    private var lastFps = 0.0
    private var lastStatusTimestampNs = 0L
    private var lastDetectionOverlayTimestampNs = 0L
    private var lastCropLogTimestampNs = 0L

    @Volatile
    private var coordinateMapper = FrameCoordinateMapper.Identity

    @Volatile
    private var readerGeneration = 0
    private var openingCamera = false

    @Volatile
    private var started = false

    @Volatile
    private var generation = 0

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openRearCameraIfReady(generation)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            stop()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    fun start() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            emitStatusFromCallingThread("Camera permission missing")
            return
        }

        val callbackGeneration = resourceCoordinator.mutate {
            if (started) {
                null
            } else {
                started = true
                generation += 1
                generation
            }
        } ?: return

        resetFrameStats()
        startBackgroundThread()
        startAnalysisThread()

        textureView.surfaceTextureListener = surfaceTextureListener
        if (textureView.isAvailable) {
            openRearCameraIfReady(callbackGeneration)
        } else {
            emitStatusFromCallingThread("Waiting for camera preview surface")
        }
    }

    fun resetShotReview() {
        val state = tracker.resetForNextShot()
        onTrackingState(state.trackingState)
        emitStatusFromCallingThread("Detection reset; searching for ball")
    }

    fun stop() {
        textureView.surfaceTextureListener = null
        resourceCoordinator.mutate {
            generation += 1
            started = false
            openingCamera = false
            closeActiveCaptureResourcesLocked(releasePreviewSurface = true)
            cameraDevice?.close()
            cameraDevice = null
            selectedMode = null
            remainingModes.clear()
        }

        stopBackgroundThread()
        stopAnalysisThread()
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) {
            return
        }

        val thread = HandlerThread("CameraCaptureController")
        thread.start()
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
    }

    private fun stopBackgroundThread() {
        val thread = backgroundThread
        backgroundThread = null
        backgroundHandler = null

        thread?.quitSafely()
        if (thread != null && Thread.currentThread() != thread) {
            try {
                thread.join()
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun startAnalysisThread() {
        if (analysisThread != null) {
            return
        }

        val thread = HandlerThread("CameraFrameAnalysis")
        thread.start()
        analysisThread = thread
        analysisHandler = Handler(thread.looper)
    }

    private fun stopAnalysisThread() {
        val thread = analysisThread
        analysisThread = null
        analysisHandler = null

        thread?.quitSafely()
        if (thread != null && Thread.currentThread() != thread) {
            try {
                thread.join()
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun openRearCameraIfReady(callbackGeneration: Int) {
        val canOpen = resourceCoordinator.mutate {
            isCurrent(callbackGeneration) && cameraDevice == null && !openingCamera
        }
        if (!canOpen) {
            return
        }

        val handler = backgroundHandler
        if (handler == null) {
            emitStatusFromCallingThread("Camera background thread unavailable")
            return
        }

        val texture = textureView.surfaceTexture
        if (texture == null) {
            emitStatusFromCallingThread("Camera preview surface unavailable")
            return
        }

        val cameraConfig = findRearCameraConfig()
        if (cameraConfig == null) {
            closeCameraResourcesFromCallback(callbackGeneration, null)
            return
        }

        val mapper = FrameCoordinateMapper.forRearCamera(
            sensorOrientationDegrees = cameraConfig.sensorOrientationDegrees,
            displayRotationDegrees = currentDisplayRotationDegrees(),
        )
        val published = resourceCoordinator.publishIfCurrent(
            isCurrent = {
                isCurrent(callbackGeneration) && cameraDevice == null && !openingCamera
            },
            publish = {
                previewTexture = texture
                remainingModes = ArrayDeque(cameraConfig.rankedModes)
                coordinateMapper = mapper
                openingCamera = true
            },
        )
        if (!published) {
            return
        }

        emitStatusFromCallingThread(
            "Opening rear camera with ${cameraConfig.rankedModes.size} candidate modes rot=${mapper.rotationDegrees}",
        )
        openCamera(cameraConfig.cameraId, callbackGeneration)
    }

    private fun findRearCameraConfig(): CameraConfig? {
        val rearCameraId = try {
            cameraManager.cameraIdList.firstOrNull { cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (exception: Exception) {
            emitStatusFromCallingThread("Camera list unavailable: ${exception.statusDetail()}")
            return null
        }

        if (rearCameraId == null) {
            emitStatusFromCallingThread("No rear-facing camera found")
            return null
        }

        val characteristics = try {
            cameraManager.getCameraCharacteristics(rearCameraId)
        } catch (exception: Exception) {
            emitStatusFromCallingThread("Rear camera characteristics unavailable: ${exception.statusDetail()}")
            return null
        }

        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (streamMap == null) {
            emitStatusFromCallingThread("Rear camera has no stream configuration map")
            return null
        }

        val yuvOutputs = streamMap.getOutputSizes(ImageFormat.YUV_420_888)?.map { size ->
            CaptureModeOutput(
                width = size.width,
                height = size.height,
                minFrameDurationNs = streamMap.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size),
            )
        }.orEmpty()
        if (yuvOutputs.isEmpty()) {
            emitStatusFromCallingThread("Rear camera has no YUV_420_888 output sizes")
            return null
        }

        val fpsRanges = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { range -> CaptureFpsRange(minFps = range.lower, maxFps = range.upper) }
            .orEmpty()
        if (fpsRanges.isEmpty()) {
            emitStatusFromCallingThread("Rear camera has no target FPS ranges")
            return null
        }

        val candidates = CaptureModeSelector.standardCandidates(yuvOutputs, fpsRanges)

        val rankedModes = rankCaptureModes(candidates)
        if (rankedModes.isEmpty()) {
            emitStatusFromCallingThread("No suitable rear camera capture modes")
            return null
        }

        return CameraConfig(
            cameraId = rearCameraId,
            rankedModes = rankedModes,
            sensorOrientationDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
        )
    }

    private fun currentDisplayRotationDegrees(): Int =
        when (textureView.display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

    private fun rankCaptureModes(candidates: List<CaptureModeCandidate>): List<CaptureMode> {
        val remaining = candidates.toMutableList()
        val rankedModes = mutableListOf<CaptureMode>()

        while (remaining.isNotEmpty()) {
            val selected = try {
                CaptureModeSelector.select(remaining)
            } catch (exception: IllegalArgumentException) {
                break
            }

            rankedModes += selected
            val selectedIndex = remaining.indexOfFirst { candidate -> candidate.matches(selected) }
            if (selectedIndex < 0) {
                break
            }
            remaining.removeAt(selectedIndex)
        }

        return rankedModes
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(cameraId: String, callbackGeneration: Int) {
        try {
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        val published = resourceCoordinator.publishIfCurrent(
                            isCurrent = { isCurrent(callbackGeneration) },
                            publish = {
                                openingCamera = false
                                cameraDevice = camera
                            },
                        )
                        if (!published) {
                            camera.close()
                            return
                        }

                        tryNextMode(camera, callbackGeneration, null)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        handleTerminalCameraCallback(camera, callbackGeneration, "Camera disconnected")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        handleTerminalCameraCallback(camera, callbackGeneration, "Camera error $error")
                    }
                },
                cameraCallbackHandler,
            )
        } catch (exception: Exception) {
            val current = resourceCoordinator.mutate {
                if (isCurrent(callbackGeneration)) {
                    openingCamera = false
                    true
                } else {
                    false
                }
            }
            if (current) {
                emitStatusFromCallingThread("Failed to open camera: ${exception.statusDetail()}")
                closeCameraResourcesFromCallback(callbackGeneration, null)
            }
        }
    }

    private fun tryNextMode(camera: CameraDevice, callbackGeneration: Int, previousFailure: String?) {
        val setup = resourceCoordinator.mutate {
            if (!isCurrent(callbackGeneration)) {
                null
            } else {
                closeActiveCaptureResourcesLocked(releasePreviewSurface = false)
                val mode = remainingModes.removeFirstOrNull()
                val texture = previewTexture
                if (mode == null || texture == null) {
                    null
                } else {
                    readerGeneration += 1
                    ModeSetup(mode, texture, readerGeneration, remainingModes.size)
                }
            }
        }
        if (setup == null) {
            if (!isCurrent(callbackGeneration)) {
                camera.close()
            } else {
                val failureDetail = previousFailure?.let { ": $it" }.orEmpty()
                closeCameraResourcesFromCallback(
                    callbackGeneration,
                    "No viable rear camera capture mode remaining$failureDetail",
                )
            }
            return
        }

        setup.texture.setDefaultBufferSize(setup.mode.width, setup.mode.height)
        val newPreviewSurface = Surface(setup.texture)
        val newImageReader = createImageReader(setup.mode, callbackGeneration, setup.readerGeneration)
        val frameGeneration = FrameGeneration(callbackGeneration, setup.readerGeneration)
        val published = resourceCoordinator.publishIfCurrent(
            isCurrent = { isCurrent(frameGeneration) },
            publish = {
                selectedMode = setup.mode
                previewSurface = newPreviewSurface
                imageReader = newImageReader
            },
        )
        if (!published) {
            newImageReader.setOnImageAvailableListener(null, null)
            newImageReader.close()
            newPreviewSurface.release()
            if (!isCurrent(callbackGeneration)) {
                camera.close()
            }
            return
        }

        emitStatusFromCallingThread(
            "Trying camera mode ${setup.mode.statusLabel()} (${setup.remainingModeCount} fallback modes remain)",
        )
        createCaptureSession(
            camera = camera,
            mode = setup.mode,
            frameGeneration = frameGeneration,
            preview = newPreviewSurface,
            reader = newImageReader,
        )
    }

    private fun createImageReader(
        mode: CaptureMode,
        callbackGeneration: Int,
        callbackReaderGeneration: Int,
    ): ImageReader =
        ImageReader.newInstance(mode.width, mode.height, ImageFormat.YUV_420_888, MAX_IMAGES).apply {
            setOnImageAvailableListener(
                { reader ->
                    if (!isCurrent(FrameGeneration(callbackGeneration, callbackReaderGeneration))) {
                        return@setOnImageAvailableListener
                    }

                    val image = try {
                        reader.acquireLatestImage()
                    } catch (exception: IllegalStateException) {
                        return@setOnImageAvailableListener
                    } ?: return@setOnImageAvailableListener

                    if (!analysisGate.tryStartAnalysis()) {
                        image.close()
                        return@setOnImageAvailableListener
                    }

                    val handler = analysisHandler
                    if (handler == null) {
                        try {
                            image.close()
                        } finally {
                            analysisGate.finishAnalysis()
                        }
                        return@setOnImageAvailableListener
                    }

                    val posted = handler.post {
                        try {
                            try {
                                analyzeImage(image, mode, callbackGeneration, callbackReaderGeneration)
                            } catch (exception: Exception) {
                                dispatchAnalysisCallback(
                                    FrameGeneration(callbackGeneration, callbackReaderGeneration),
                                ) {
                                    onStatus("Analysis frame failed: ${exception.statusDetail()}")
                                }
                            }
                        } finally {
                            try {
                                image.close()
                            } finally {
                                analysisGate.finishAnalysis()
                            }
                        }
                    }
                    if (!posted) {
                        try {
                            image.close()
                        } finally {
                            analysisGate.finishAnalysis()
                        }
                    }
                },
                backgroundHandler,
            )
        }
    private fun analyzeImage(
        image: Image,
        mode: CaptureMode,
        callbackGeneration: Int,
        callbackReaderGeneration: Int,
    ) {
        val frameGeneration = FrameGeneration(callbackGeneration, callbackReaderGeneration)
        if (!isCurrent(frameGeneration)) {
            return
        }

        val yPlane = image.planes.getOrNull(0)
        val uPlane = image.planes.getOrNull(1)
        val vPlane = image.planes.getOrNull(2)
        val yBuffer = yPlane?.buffer
        val uBuffer = uPlane?.buffer
        val vBuffer = vPlane?.buffer
        val firstLuma = if (yBuffer != null && yBuffer.remaining() > 0) {
            yBuffer.get(0).toInt() and 0xFF
        } else {
            null
        }
        val launchZone = launchZoneProvider()
        var autoTrackingState: AutoShotTrackerState? = null
        val detectionResult = if (
            yPlane != null && uPlane != null && vPlane != null &&
            yBuffer != null && uBuffer != null && vBuffer != null
        ) {
            val mapper = coordinateMapper
            val lumaFrame = YuvFrameExtractor.extractLuma(
                yBuffer = yBuffer,
                width = image.width,
                height = image.height,
                yRowStride = yPlane.rowStride,
                yPixelStride = yPlane.pixelStride,
            )
            val motionResult = detector.analyzeFrame(lumaFrame, image.timestamp, mapper)
            val crop = mapper.viewZoneToFrameZone(launchZone).cropBounds(image.width, image.height)
            val zoneFrame = YuvFrameExtractor.extractCrop(
                yBuffer = yBuffer,
                uBuffer = uBuffer,
                vBuffer = vBuffer,
                sourceWidth = image.width,
                sourceHeight = image.height,
                cropLeft = crop.left,
                cropTop = crop.top,
                cropWidth = crop.width,
                cropHeight = crop.height,
                yRowStride = yPlane.rowStride,
                yPixelStride = yPlane.pixelStride,
                uRowStride = uPlane.rowStride,
                uPixelStride = uPlane.pixelStride,
                vRowStride = vPlane.rowStride,
                vPixelStride = vPlane.pixelStride,
            )
            autoTrackingState = tracker.updateFromLaunchZoneCrop(
                zoneFrame = zoneFrame,
                result = motionResult,
                launchZone = launchZone,
                mapper = mapper,
                cropLeftPx = crop.left,
                cropTopPx = crop.top,
                sourceWidth = image.width,
                sourceHeight = image.height,
            )
            maybeLogZoneCrop(
                timestampNs = image.timestamp,
                zoneFrame = zoneFrame,
                state = autoTrackingState,
                mapper = mapper,
                crop = crop,
                sourceWidth = image.width,
                sourceHeight = image.height,
            )
            motionResult
        } else {
            null
        }
        val trackingState = autoTrackingState?.trackingState
        val visibleDetectionResult = detectionResult?.copy(
            candidates = DetectionDebugFilter.visibleCandidates(
                trackerState = autoTrackingState,
                launchZone = launchZone,
                trackingState = trackingState,
            ),
        )

        frameCount += 1
        lastFps = fpsCounter.recordFrame(image.timestamp)
        if (visibleDetectionResult != null) {
            maybeEmitDetectionResult(image.timestamp, visibleDetectionResult, frameGeneration)
        }
        if (trackingState != null) {
            maybeEmitTrackingState(image.timestamp, trackingState, frameGeneration)
        }
        if (autoTrackingState != null) {
            dispatchAnalysisCallback(frameGeneration) { onAutoTrackingState(autoTrackingState) }
        }
        maybeEmitFrameStatus(mode, image.timestamp, firstLuma, detectionResult, autoTrackingState, frameGeneration)
    }

    private fun maybeEmitDetectionResult(
        timestampNs: Long,
        result: LumaMotionResult,
        frameGeneration: FrameGeneration,
    ) {
        if (lastDetectionOverlayTimestampNs != 0L && timestampNs - lastDetectionOverlayTimestampNs < DETECTION_OVERLAY_INTERVAL_NS) {
            return
        }

        lastDetectionOverlayTimestampNs = timestampNs
        dispatchAnalysisCallback(frameGeneration) { onDetectionResult(result) }
    }

    private fun maybeEmitTrackingState(
        timestampNs: Long,
        state: ShotTrackerState,
        frameGeneration: FrameGeneration,
    ) {
        if (lastDetectionOverlayTimestampNs != timestampNs) {
            return
        }

        dispatchAnalysisCallback(frameGeneration) { onTrackingState(state) }
    }

    private fun maybeEmitFrameStatus(
        mode: CaptureMode,
        timestampNs: Long,
        firstLuma: Int?,
        detectionResult: LumaMotionResult?,
        autoTrackingState: AutoShotTrackerState?,
        frameGeneration: FrameGeneration,
    ) {
        if (lastStatusTimestampNs != 0L && timestampNs - lastStatusTimestampNs < STATUS_INTERVAL_NS) {
            return
        }

        lastStatusTimestampNs = timestampNs
        val candidateCount = detectionResult?.candidates?.size ?: 0
        val stillCandidateCount = autoTrackingState?.acquisitionDebug?.candidates?.size ?: 0
        val matchedPixels = detectionResult?.matchedPixels ?: 0
        val trackingState = autoTrackingState?.trackingState
        val trackingLabel = trackingState?.status ?: ShotTrackerStatus.Idle
        val trackPointCount = trackingState?.points?.size ?: 0
        val shotLabel = autoTrackingState?.status ?: tracker.status
        val stillDebug = autoTrackingState?.acquisitionDebug?.statusSummary(
            aeAwbLocked = requestPolicy.autoExposureLock || requestPolicy.autoWhiteBalanceLock,
        ) ?: "cal=n/a"
        val firstLumaLabel = firstLuma?.toString() ?: "n/a"
        val statusMessage =
            "Camera " + mode.statusLabel() + " rot=" + coordinateMapper.rotationDegrees +
                " frames=" + frameCount +
                " fps=" + lastFps.formatFps() +
                " y0=" + firstLumaLabel +
                " candidates=" + candidateCount +
                " still=" + stillCandidateCount +
                " matched=" + matchedPixels +
                " shot=" + shotLabel +
                " track=" + trackingLabel +
                " points=" + trackPointCount +
                " ball={" + stillDebug + "}"
        dispatchAnalysisCallback(frameGeneration) { onStatus(statusMessage) }
    }

    private fun createCaptureSession(
        camera: CameraDevice,
        mode: CaptureMode,
        frameGeneration: FrameGeneration,
        preview: Surface,
        reader: ImageReader,
    ) {
        val handler = backgroundHandler
        if (handler == null || !isCurrent(frameGeneration)) {
            if (isCurrent(frameGeneration)) {
                tryNextMode(camera, frameGeneration.camera, "capture session prerequisites missing")
            }
            return
        }

        try {
            camera.createCaptureSession(
                listOf(preview, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        val published = resourceCoordinator.publishIfCurrent(
                            isCurrent = {
                                isCurrent(frameGeneration) &&
                                    imageReader === reader &&
                                    previewSurface === preview
                            },
                            publish = {
                                captureSession = session
                                analysisGate.resume()
                            },
                        )
                        if (!published) {
                            session.close()
                            return
                        }

                        startRepeatingRequest(
                            camera,
                            session,
                            preview,
                            reader.surface,
                            mode,
                            frameGeneration,
                        )
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (isCurrent(frameGeneration)) {
                            tryNextMode(
                                camera,
                                frameGeneration.camera,
                                "session configuration failed for ${mode.statusLabel()}",
                            )
                        }
                    }
                },
                handler,
            )
        } catch (exception: Exception) {
            if (isCurrent(frameGeneration)) {
                tryNextMode(
                    camera,
                    frameGeneration.camera,
                    "failed to create session for ${mode.statusLabel()}: ${exception.statusDetail()}",
                )
            }
        }
    }

    private fun startRepeatingRequest(
        camera: CameraDevice,
        session: CameraCaptureSession,
        preview: Surface,
        analysis: Surface,
        mode: CaptureMode,
        frameGeneration: FrameGeneration,
    ) {
        if (!isCurrent(frameGeneration)) {
            session.close()
            return
        }

        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(preview)
                addTarget(analysis)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(mode.minFps, mode.maxFps))
                set(CaptureRequest.CONTROL_AE_LOCK, requestPolicy.autoExposureLock)
                set(CaptureRequest.CONTROL_AWB_LOCK, requestPolicy.autoWhiteBalanceLock)
            }.build()

            session.setRepeatingRequest(request, null, backgroundHandler)
            emitStatusFromCallingThread("Camera running ${mode.statusLabel()} frames=0 fps=0.0 y0=n/a")
        } catch (exception: Exception) {
            if (isCurrent(frameGeneration)) {
                tryNextMode(
                    camera,
                    frameGeneration.camera,
                    "failed to start ${mode.statusLabel()}: ${exception.statusDetail()}",
                )
            }
        }
    }

    private fun handleTerminalCameraCallback(
        camera: CameraDevice,
        callbackGeneration: Int,
        status: String,
    ) {
        val handled = resourceCoordinator.mutate {
            if (!isCurrent(callbackGeneration)) {
                false
            } else {
                openingCamera = false
                closeActiveCaptureResourcesLocked(releasePreviewSurface = true)
                if (cameraDevice !== camera) {
                    cameraDevice?.close()
                }
                cameraDevice = null
                camera.close()
                selectedMode = null
                remainingModes.clear()
                true
            }
        }
        if (!handled) {
            camera.close()
            return
        }
        emitStatusFromCallingThread(status)
    }

    private fun closeCameraResourcesFromCallback(callbackGeneration: Int, status: String?) {
        val handled = resourceCoordinator.mutate {
            if (!isCurrent(callbackGeneration)) {
                false
            } else {
                closeActiveCaptureResourcesLocked(releasePreviewSurface = true)
                cameraDevice?.close()
                cameraDevice = null
                selectedMode = null
                remainingModes.clear()
                true
            }
        }
        if (handled) {
            status?.let(::emitStatusFromCallingThread)
        }
    }

    private fun closeActiveCaptureResourcesLocked(releasePreviewSurface: Boolean) {
        captureSession?.close()
        captureSession = null

        val reader = imageReader
        reader?.setOnImageAvailableListener(null, null)
        readerGeneration += 1
        analysisGate.pauseAndAwaitIdle()
        reader?.close()
        imageReader = null

        previewSurface?.release()
        previewSurface = null

        if (releasePreviewSurface) {
            previewTexture = null
        }

        selectedMode = null
        resetFrameStats()
    }

    private fun resetFrameStats() {
        frameCount = 0L
        lastFps = 0.0
        lastStatusTimestampNs = 0L
        lastDetectionOverlayTimestampNs = 0L
        lastCropLogTimestampNs = 0L
        fpsCounter.reset()
        detector.reset()
        tracker.reset()
    }

    private fun isCurrent(callbackGeneration: Int): Boolean = started && generation == callbackGeneration

    private fun isCurrent(frameGeneration: FrameGeneration): Boolean =
        isCurrent(frameGeneration.camera) && readerGeneration == frameGeneration.reader

    private fun emitStatusFromCallingThread(message: String) {
        onStatus(message)
    }

    private fun dispatchAnalysisCallback(frameGeneration: FrameGeneration, callback: () -> Unit) {
        callbackDispatcher.dispatch(frameGeneration, callback)
    }

    private fun CaptureMode.statusLabel(): String = "${width}x$height @ ${minFps}-${maxFps}fps standard"

    private fun CaptureModeCandidate.matches(mode: CaptureMode): Boolean =
        width == mode.width &&
            height == mode.height &&
            minFps == mode.minFps &&
            maxFps == mode.maxFps

    private fun Double.formatFps(): String = String.format(Locale.US, "%.1f", this)

    private fun Exception.statusDetail(): String = message ?: javaClass.simpleName

    private fun maybeLogZoneCrop(
        timestampNs: Long,
        zoneFrame: YuvFrame,
        state: AutoShotTrackerState?,
        mapper: FrameCoordinateMapper,
        crop: PixelCrop,
        sourceWidth: Int,
        sourceHeight: Int,
    ) {
        if (state == null) return
        if (lastCropLogTimestampNs != 0L && timestampNs - lastCropLogTimestampNs < CROP_LOG_INTERVAL_NS) return

        val sample = ZoneCropSampleSelector.select(
            lockedBall = state.lockedBall,
            debug = state.acquisitionDebug,
        )
        val center = sample.candidate
            ?.toCropPoint(mapper, crop, sourceWidth, sourceHeight)
            ?: if (sample.kind == ZoneCropSampleKind.RandomNegative) 0.5 to 0.5 else return
        runCatching {
            cropLogger.logSample(
                frame = zoneFrame,
                centerX = center.first,
                centerY = center.second,
                kind = sample.kind,
                timestampNs = timestampNs,
                score = sample.score,
                rejection = sample.shape?.rejection,
                closedEdgeCoverage = sample.shape?.metrics?.closedEdgeCoverage,
                radialAlignment = sample.shape?.metrics?.radialAlignment,
                radiusVariation = sample.shape?.metrics?.radiusVariation,
                lineContinuation = sample.shape?.metrics?.lineContinuation,
            )
            lastCropLogTimestampNs = timestampNs
        }
    }

    private fun LumaMotionCandidate.toCropPoint(
        mapper: FrameCoordinateMapper,
        crop: PixelCrop,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Pair<Double, Double>? {
        val framePoint = mapper.viewToFrame(x, y)
        val sourceX = framePoint.x * (sourceWidth - 1).coerceAtLeast(1)
        val sourceY = framePoint.y * (sourceHeight - 1).coerceAtLeast(1)
        val cropX = (sourceX - crop.left) / (crop.width - 1).coerceAtLeast(1).toDouble()
        val cropY = (sourceY - crop.top) / (crop.height - 1).coerceAtLeast(1).toDouble()
        if (cropX !in 0.0..1.0 || cropY !in 0.0..1.0) return null
        return cropX to cropY
    }

    private fun LaunchZone.cropBounds(frameWidth: Int, frameHeight: Int): PixelCrop {
        val maxX = frameWidth - 1
        val maxY = frameHeight - 1
        val leftPx = (left * maxX).toInt().coerceIn(0, maxX)
        val rightPx = ((left + width) * maxX).toInt().coerceIn(leftPx, maxX)
        val topPx = (top * maxY).toInt().coerceIn(0, maxY)
        val bottomPx = ((top + height) * maxY).toInt().coerceIn(topPx, maxY)
        return PixelCrop(
            left = leftPx,
            top = topPx,
            width = rightPx - leftPx + 1,
            height = bottomPx - topPx + 1,
        )
    }

    private data class PixelCrop(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )

    private data class ModeSetup(
        val mode: CaptureMode,
        val texture: SurfaceTexture,
        val readerGeneration: Int,
        val remainingModeCount: Int,
    )
    private data class CameraConfig(
        val cameraId: String,
        val rankedModes: List<CaptureMode>,
        val sensorOrientationDegrees: Int,
    )

    private companion object {
        const val MAX_IMAGES = 3
        const val STATUS_INTERVAL_NS = 1_000_000_000L
        const val DETECTION_OVERLAY_INTERVAL_NS = 33_333_333L
        const val CROP_LOG_INTERVAL_NS = 1_000_000_000L
    }
}
