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
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.view.Surface
import android.view.TextureView
import java.util.Locale

class CameraCaptureController(
    private val context: Context,
    private val textureView: TextureView,
    // Status is delivered on the calling callback thread. Task 6's caller will marshal to UI.
    private val onStatus: (String) -> Unit,
    private val launchZoneProvider: () -> LaunchZone = { LaunchZone.Default },
    // Detection results are delivered on the camera callback thread. The caller marshals to UI.
    private val onDetectionResult: (LumaMotionResult) -> Unit = {},
    private val onTrackingState: (ShotTrackerState) -> Unit = {},
) {
    private val cameraManager: CameraManager =
        context.getSystemService(CameraManager::class.java)
    private val fpsCounter = FpsCounter()
    private val detector = LumaMotionDetector()
    private val tracker = AutoShotTracker()
    private val cameraCallbackHandler = Handler(Looper.getMainLooper())

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
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

        if (started) {
            return
        }

        started = true
        generation += 1
        resetFrameStats()
        startBackgroundThread()

        textureView.surfaceTextureListener = surfaceTextureListener
        if (textureView.isAvailable) {
            openRearCameraIfReady(generation)
        } else {
            emitStatusFromCallingThread("Waiting for camera preview surface")
        }
    }

    fun resetShotReview() {
        val state = tracker.resetForNextShot()
        onTrackingState(state.trackingState)
        emitStatusFromCallingThread("Shot review cleared; calibrating empty launch zone")
    }

    fun stop() {
        generation += 1
        started = false
        openingCamera = false
        textureView.surfaceTextureListener = null

        closeActiveCaptureResources(releasePreviewSurface = true)

        cameraDevice?.close()
        cameraDevice = null

        selectedMode = null
        remainingModes.clear()
        resetFrameStats()

        stopBackgroundThread()
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

    private fun openRearCameraIfReady(callbackGeneration: Int) {
        if (!isCurrent(callbackGeneration) || cameraDevice != null || openingCamera) {
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

        previewTexture = texture
        remainingModes = ArrayDeque(cameraConfig.rankedModes)
        openingCamera = true
        emitStatusFromCallingThread("Opening rear camera with ${cameraConfig.rankedModes.size} candidate modes")

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

        return CameraConfig(cameraId = rearCameraId, rankedModes = rankedModes)
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
                        if (!isCurrent(callbackGeneration)) {
                            camera.close()
                            return
                        }

                        openingCamera = false
                        cameraDevice = camera
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
            openingCamera = false
            emitStatusFromCallingThread("Failed to open camera: ${exception.statusDetail()}")
            closeCameraResourcesFromCallback(callbackGeneration, null)
        }
    }

    private fun tryNextMode(camera: CameraDevice, callbackGeneration: Int, previousFailure: String?) {
        if (!isCurrent(callbackGeneration)) {
            camera.close()
            return
        }

        closeActiveCaptureResources(releasePreviewSurface = false)

        if (remainingModes.isEmpty()) {
            val failureDetail = previousFailure?.let { ": $it" }.orEmpty()
            closeCameraResourcesFromCallback(callbackGeneration, "No viable rear camera capture mode remaining$failureDetail")
            return
        }

        val mode = remainingModes.removeFirst()
        val texture = previewTexture
        if (texture == null) {
            closeCameraResourcesFromCallback(callbackGeneration, "preview texture unavailable for ${mode.statusLabel()}")
            return
        }

        texture.setDefaultBufferSize(mode.width, mode.height)
        val newPreviewSurface = Surface(texture)
        val callbackReaderGeneration = readerGeneration + 1
        val newImageReader = createImageReader(mode, callbackGeneration, callbackReaderGeneration)

        if (!isCurrent(callbackGeneration)) {
            newImageReader.setOnImageAvailableListener(null, null)
            newImageReader.close()
            newPreviewSurface.release()
            camera.close()
            return
        }

        selectedMode = mode
        readerGeneration = callbackReaderGeneration
        previewSurface = newPreviewSurface
        imageReader = newImageReader
        emitStatusFromCallingThread("Trying camera mode ${mode.statusLabel()} (${remainingModes.size} fallback modes remain)")
        createCaptureSession(camera, mode, callbackGeneration)
    }

    private fun createImageReader(
        mode: CaptureMode,
        callbackGeneration: Int,
        callbackReaderGeneration: Int,
    ): ImageReader =
        ImageReader.newInstance(mode.width, mode.height, ImageFormat.YUV_420_888, MAX_IMAGES).apply {
            setOnImageAvailableListener(
                { reader ->
                    if (!isCurrent(callbackGeneration) || callbackReaderGeneration != readerGeneration) {
                        return@setOnImageAvailableListener
                    }

                    val image = try {
                        reader.acquireLatestImage()
                    } catch (exception: IllegalStateException) {
                        return@setOnImageAvailableListener
                    } ?: return@setOnImageAvailableListener

                    try {
                        if (!isCurrent(callbackGeneration) || callbackReaderGeneration != readerGeneration) {
                            return@setOnImageAvailableListener
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
                            val yuvFrame = YuvFrameExtractor.extract(
                                yBuffer = yBuffer,
                                uBuffer = uBuffer,
                                vBuffer = vBuffer,
                                width = image.width,
                                height = image.height,
                                yRowStride = yPlane.rowStride,
                                yPixelStride = yPlane.pixelStride,
                                uRowStride = uPlane.rowStride,
                                uPixelStride = uPlane.pixelStride,
                                vRowStride = vPlane.rowStride,
                                vPixelStride = vPlane.pixelStride,
                            )
                            val motionResult = detector.analyzeFrame(yuvFrame.toLumaFrame(), image.timestamp)
                            autoTrackingState = tracker.update(yuvFrame, motionResult, launchZone)
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
                            maybeEmitDetectionResult(image.timestamp, visibleDetectionResult)
                        }
                        if (trackingState != null) {
                            maybeEmitTrackingState(image.timestamp, trackingState)
                        }
                        maybeEmitFrameStatus(mode, image.timestamp, firstLuma, detectionResult, autoTrackingState)
                    } finally {
                        image.close()
                    }
                },
                backgroundHandler,
            )
        }


    private fun maybeEmitDetectionResult(timestampNs: Long, result: LumaMotionResult) {
        if (lastDetectionOverlayTimestampNs != 0L && timestampNs - lastDetectionOverlayTimestampNs < DETECTION_OVERLAY_INTERVAL_NS) {
            return
        }

        lastDetectionOverlayTimestampNs = timestampNs
        onDetectionResult(result)
    }

    private fun maybeEmitTrackingState(timestampNs: Long, state: ShotTrackerState) {
        if (lastDetectionOverlayTimestampNs != timestampNs) {
            return
        }

        onTrackingState(state)
    }

    private fun maybeEmitFrameStatus(
        mode: CaptureMode,
        timestampNs: Long,
        firstLuma: Int?,
        detectionResult: LumaMotionResult?,
        autoTrackingState: AutoShotTrackerState?,
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
        val stillDebug = autoTrackingState?.acquisitionDebug?.statusSummary(aeAwbLocked = true) ?: "cal=n/a"
        val firstLumaLabel = firstLuma?.toString() ?: "n/a"
        emitStatusFromCallingThread(
            "Camera " + mode.statusLabel() + " frames=" + frameCount +
                " fps=" + lastFps.formatFps() +
                " y0=" + firstLumaLabel +
                " candidates=" + candidateCount +
                " still=" + stillCandidateCount +
                " matched=" + matchedPixels +
                " shot=" + shotLabel +
                " track=" + trackingLabel +
                " points=" + trackPointCount +
                " ball={" + stillDebug + "}",
        )
    }

    private fun createCaptureSession(camera: CameraDevice, mode: CaptureMode, callbackGeneration: Int) {
        val handler = backgroundHandler
        val preview = previewSurface
        val reader = imageReader
        if (handler == null || preview == null || reader == null) {
            tryNextMode(camera, callbackGeneration, "capture session prerequisites missing")
            return
        }

        try {
            camera.createCaptureSession(
                listOf(preview, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!isCurrent(callbackGeneration)) {
                            session.close()
                            return
                        }

                        captureSession = session
                        startRepeatingRequest(camera, session, preview, reader.surface, mode, callbackGeneration)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (!isCurrent(callbackGeneration)) {
                            return
                        }

                        tryNextMode(camera, callbackGeneration, "session configuration failed for ${mode.statusLabel()}")
                    }
                },
                handler,
            )
        } catch (exception: Exception) {
            tryNextMode(camera, callbackGeneration, "failed to create session for ${mode.statusLabel()}: ${exception.statusDetail()}")
        }
    }

    private fun startRepeatingRequest(
        camera: CameraDevice,
        session: CameraCaptureSession,
        preview: Surface,
        analysis: Surface,
        mode: CaptureMode,
        callbackGeneration: Int,
    ) {
        if (!isCurrent(callbackGeneration)) {
            session.close()
            return
        }

        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(preview)
                addTarget(analysis)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(mode.minFps, mode.maxFps))
                set(CaptureRequest.CONTROL_AE_LOCK, true)
                set(CaptureRequest.CONTROL_AWB_LOCK, true)
            }.build()

            session.setRepeatingRequest(request, null, backgroundHandler)
            emitStatusFromCallingThread("Camera running ${mode.statusLabel()} frames=0 fps=0.0 y0=n/a")
        } catch (exception: Exception) {
            tryNextMode(camera, callbackGeneration, "failed to start ${mode.statusLabel()}: ${exception.statusDetail()}")
        }
    }

    private fun handleTerminalCameraCallback(
        camera: CameraDevice,
        callbackGeneration: Int,
        status: String,
    ) {
        if (!isCurrent(callbackGeneration)) {
            camera.close()
            return
        }

        openingCamera = false
        status.let(::emitStatusFromCallingThread)
        closeActiveCaptureResources(releasePreviewSurface = true)
        if (cameraDevice !== camera) {
            cameraDevice?.close()
        }
        cameraDevice = null
        camera.close()
        selectedMode = null
        remainingModes.clear()
        resetFrameStats()
    }

    private fun closeCameraResourcesFromCallback(callbackGeneration: Int, status: String?) {
        if (!isCurrent(callbackGeneration)) {
            return
        }

        status?.let(::emitStatusFromCallingThread)
        closeActiveCaptureResources(releasePreviewSurface = true)
        cameraDevice?.close()
        cameraDevice = null
        selectedMode = null
        remainingModes.clear()
        resetFrameStats()
    }

    private fun closeActiveCaptureResources(releasePreviewSurface: Boolean) {
        captureSession?.close()
        captureSession = null

        readerGeneration += 1
        imageReader?.let { reader ->
            reader.setOnImageAvailableListener(null, null)
            reader.close()
        }
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
        fpsCounter.reset()
        detector.reset()
        tracker.reset()
    }

    private fun isCurrent(callbackGeneration: Int): Boolean = started && generation == callbackGeneration

    private fun emitStatusFromCallingThread(message: String) {
        onStatus(message)
    }

    private fun CaptureMode.statusLabel(): String = "${width}x$height @ ${minFps}-${maxFps}fps standard"

    private fun CaptureModeCandidate.matches(mode: CaptureMode): Boolean =
        width == mode.width &&
            height == mode.height &&
            minFps == mode.minFps &&
            maxFps == mode.maxFps

    private fun Double.formatFps(): String = String.format(Locale.US, "%.1f", this)

    private fun Exception.statusDetail(): String = message ?: javaClass.simpleName

    private data class CameraConfig(
        val cameraId: String,
        val rankedModes: List<CaptureMode>,
    )

    private companion object {
        const val MAX_IMAGES = 3
        const val STATUS_INTERVAL_NS = 1_000_000_000L
        const val DETECTION_OVERLAY_INTERVAL_NS = 33_333_333L
    }
}

