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
import android.util.Range
import android.view.Surface
import android.view.TextureView
import java.util.Locale

class CameraCaptureController(
    private val context: Context,
    private val textureView: TextureView,
    private val onStatus: (String) -> Unit,
) {
    private val cameraManager: CameraManager =
        context.getSystemService(CameraManager::class.java)
    private val fpsCounter = FpsCounter()

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var selectedMode: CaptureMode? = null
    private var remainingModes = ArrayDeque<CaptureMode>()
    private var frameCount = 0L
    private var lastFps = 0.0
    private var lastStatusTimestampNs = 0L

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
        frameCount = 0L
        lastFps = 0.0
        lastStatusTimestampNs = 0L
        fpsCounter.reset()
        startBackgroundThread()

        textureView.surfaceTextureListener = surfaceTextureListener
        if (textureView.isAvailable) {
            openRearCameraIfReady(generation)
        } else {
            emitStatusFromCallingThread("Waiting for camera preview surface")
        }
    }

    fun stop() {
        generation += 1
        started = false
        textureView.surfaceTextureListener = null

        closeActiveCaptureResources()

        cameraDevice?.close()
        cameraDevice = null

        selectedMode = null
        remainingModes.clear()
        frameCount = 0L
        lastFps = 0.0
        lastStatusTimestampNs = 0L
        fpsCounter.reset()

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
        if (!isCurrent(callbackGeneration) || cameraDevice != null) {
            return
        }

        val handler = backgroundHandler
        if (handler == null) {
            emitStatusFromCallingThread("Camera background thread unavailable")
            return
        }

        val cameraConfig = findRearCameraConfig()
        if (cameraConfig == null) {
            stop()
            return
        }

        remainingModes = ArrayDeque(cameraConfig.rankedModes)
        emitStatusFromCallingThread("Opening rear camera with ${cameraConfig.rankedModes.size} candidate modes")

        openCamera(cameraConfig.cameraId, handler, callbackGeneration)
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

        val yuvSizes = streamMap.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        if (yuvSizes.isEmpty()) {
            emitStatusFromCallingThread("Rear camera has no YUV_420_888 output sizes")
            return null
        }

        val fpsRanges = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList()
            .orEmpty()
            .filter { it.lower > 0 && it.upper >= it.lower }
        if (fpsRanges.isEmpty()) {
            emitStatusFromCallingThread("Rear camera has no target FPS ranges")
            return null
        }

        val candidates = yuvSizes.flatMap { size ->
            fpsRanges.map { range ->
                CaptureModeCandidate(
                    width = size.width,
                    height = size.height,
                    minFps = range.lower,
                    maxFps = range.upper,
                    highSpeed = false,
                )
            }
        }.distinctBy { candidate ->
            ModeKey(
                width = candidate.width,
                height = candidate.height,
                minFps = candidate.minFps,
                maxFps = candidate.maxFps,
                highSpeed = candidate.highSpeed,
            )
        }

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
    private fun openCamera(cameraId: String, handler: Handler, callbackGeneration: Int) {
        try {
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (!isCurrent(callbackGeneration)) {
                            camera.close()
                            return
                        }

                        cameraDevice = camera
                        tryNextMode(camera, callbackGeneration, null)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        if (!isCurrent(callbackGeneration)) {
                            camera.close()
                            return
                        }

                        emitStatusFromCallingThread("Camera disconnected")
                        stop()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        if (!isCurrent(callbackGeneration)) {
                            camera.close()
                            return
                        }

                        emitStatusFromCallingThread("Camera error $error")
                        stop()
                    }
                },
                handler,
            )
        } catch (exception: Exception) {
            emitStatusFromCallingThread("Failed to open camera: ${exception.statusDetail()}")
            stop()
        }
    }

    private fun tryNextMode(camera: CameraDevice, callbackGeneration: Int, previousFailure: String?) {
        if (!isCurrent(callbackGeneration)) {
            camera.close()
            return
        }

        closeActiveCaptureResources()

        if (remainingModes.isEmpty()) {
            val failureDetail = previousFailure?.let { ": $it" }.orEmpty()
            emitStatusFromCallingThread("No viable rear camera capture mode remaining$failureDetail")
            stop()
            return
        }

        val mode = remainingModes.removeFirst()
        selectedMode = mode
        imageReader = createImageReader(mode)
        emitStatusFromCallingThread("Trying camera mode ${mode.statusLabel()} (${remainingModes.size} fallback modes remain)")
        createCaptureSession(camera, mode, callbackGeneration)
    }

    private fun createImageReader(mode: CaptureMode): ImageReader =
        ImageReader.newInstance(mode.width, mode.height, ImageFormat.YUV_420_888, MAX_IMAGES).apply {
            setOnImageAvailableListener(
                { reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val yPlane = image.planes.firstOrNull()
                        val yBuffer = yPlane?.buffer
                        val firstLuma = if (yBuffer != null && yBuffer.remaining() > 0) {
                            yBuffer.get(0).toInt() and 0xFF
                        } else {
                            null
                        }

                        frameCount += 1
                        lastFps = fpsCounter.recordFrame(image.timestamp)
                        maybeEmitFrameStatus(mode, image.timestamp, firstLuma)
                    } finally {
                        image.close()
                    }
                },
                backgroundHandler,
            )
        }

    private fun maybeEmitFrameStatus(mode: CaptureMode, timestampNs: Long, firstLuma: Int?) {
        if (lastStatusTimestampNs != 0L && timestampNs - lastStatusTimestampNs < STATUS_INTERVAL_NS) {
            return
        }

        lastStatusTimestampNs = timestampNs
        emitStatusFromCallingThread(
            "Camera ${mode.statusLabel()} frames=$frameCount fps=${lastFps.formatFps()} y0=${firstLuma ?: "n/a"}",
        )
    }

    private fun createCaptureSession(camera: CameraDevice, mode: CaptureMode, callbackGeneration: Int) {
        val handler = backgroundHandler
        val texture = textureView.surfaceTexture
        val reader = imageReader
        if (handler == null || texture == null || reader == null) {
            tryNextMode(camera, callbackGeneration, "capture session prerequisites missing")
            return
        }

        texture.setDefaultBufferSize(mode.width, mode.height)
        val preview = Surface(texture)
        previewSurface = preview

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
            }.build()

            session.setRepeatingRequest(request, null, backgroundHandler)
            emitStatusFromCallingThread("Camera running ${mode.statusLabel()} frames=0 fps=0.0 y0=n/a")
        } catch (exception: Exception) {
            tryNextMode(camera, callbackGeneration, "failed to start ${mode.statusLabel()}: ${exception.statusDetail()}")
        }
    }

    private fun closeActiveCaptureResources() {
        captureSession?.close()
        captureSession = null

        imageReader?.close()
        imageReader = null

        previewSurface?.release()
        previewSurface = null

        selectedMode = null
        frameCount = 0L
        lastFps = 0.0
        lastStatusTimestampNs = 0L
        fpsCounter.reset()
    }

    private fun isCurrent(callbackGeneration: Int): Boolean = started && generation == callbackGeneration

    // Camera callbacks use the background handler; Task 6's caller is responsible for UI marshaling.
    private fun emitStatusFromCallingThread(message: String) {
        onStatus(message)
    }

    private fun CaptureMode.statusLabel(): String {
        val speed = if (highSpeed) " high-speed" else ""
        return "${width}x$height @ ${minFps}-${maxFps}fps$speed"
    }

    private fun CaptureModeCandidate.matches(mode: CaptureMode): Boolean =
        width == mode.width &&
            height == mode.height &&
            minFps == mode.minFps &&
            maxFps == mode.maxFps &&
            highSpeed == mode.highSpeed

    private fun Double.formatFps(): String = String.format(Locale.US, "%.1f", this)

    private fun Exception.statusDetail(): String = message ?: javaClass.simpleName

    private data class CameraConfig(
        val cameraId: String,
        val rankedModes: List<CaptureMode>,
    )

    private data class ModeKey(
        val width: Int,
        val height: Int,
        val minFps: Int,
        val maxFps: Int,
        val highSpeed: Boolean,
    )

    private companion object {
        const val MAX_IMAGES = 3
        const val STATUS_INTERVAL_NS = 1_000_000_000L
    }
}