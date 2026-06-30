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
    private var frameCount = 0L
    private var lastFps = 0.0
    private var started = false

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openRearCameraIfReady()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    fun start() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Camera permission missing")
            return
        }

        if (started) {
            return
        }

        started = true
        startBackgroundThread()

        if (textureView.isAvailable) {
            openRearCameraIfReady()
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
            onStatus("Waiting for camera preview surface")
        }
    }

    fun stop() {
        started = false
        textureView.surfaceTextureListener = null

        captureSession?.close()
        captureSession = null

        cameraDevice?.close()
        cameraDevice = null

        imageReader?.close()
        imageReader = null

        previewSurface?.release()
        previewSurface = null

        selectedMode = null
        frameCount = 0L
        lastFps = 0.0
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

    private fun openRearCameraIfReady() {
        if (!started || cameraDevice != null) {
            return
        }

        val handler = backgroundHandler
        if (handler == null) {
            onStatus("Camera background thread unavailable")
            return
        }

        val cameraConfig = findRearCameraConfig()
        if (cameraConfig == null) {
            stop()
            return
        }

        selectedMode = cameraConfig.mode
        imageReader = createImageReader(cameraConfig.mode, handler)
        onStatus("Opening rear camera ${cameraConfig.mode.statusLabel()}")

        openCamera(cameraConfig.cameraId, handler)
    }

    private fun findRearCameraConfig(): CameraConfig? {
        val rearCameraId = cameraManager.cameraIdList.firstOrNull { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }

        if (rearCameraId == null) {
            onStatus("No rear-facing camera found")
            return null
        }

        val characteristics = cameraManager.getCameraCharacteristics(rearCameraId)
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (streamMap == null) {
            onStatus("Rear camera has no stream configuration map")
            return null
        }

        val yuvSizes = streamMap.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        if (yuvSizes.isEmpty()) {
            onStatus("Rear camera has no YUV_420_888 output sizes")
            return null
        }

        val fpsRanges = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList()
            .orEmpty()
            .filter { it.lower > 0 && it.upper >= it.lower }
        if (fpsRanges.isEmpty()) {
            onStatus("Rear camera has no target FPS ranges")
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
        }

        val mode = try {
            CaptureModeSelector.select(candidates)
        } catch (exception: IllegalArgumentException) {
            onStatus("No suitable rear camera capture mode")
            return null
        }

        return CameraConfig(cameraId = rearCameraId, mode = mode)
    }

    private fun createImageReader(mode: CaptureMode, handler: Handler): ImageReader =
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
                        onStatus(
                            "Camera ${mode.statusLabel()} frames=$frameCount fps=${lastFps.formatFps()} y0=${firstLuma ?: "n/a"}",
                        )
                    } finally {
                        image.close()
                    }
                },
                handler,
            )
        }

    @SuppressLint("MissingPermission")
    private fun openCamera(cameraId: String, handler: Handler) {
        try {
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCaptureSession(camera)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        onStatus("Camera disconnected")
                        stop()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        onStatus("Camera error $error")
                        stop()
                    }
                },
                handler,
            )
        } catch (exception: RuntimeException) {
            onStatus("Failed to open camera: ${exception.message ?: exception.javaClass.simpleName}")
            stop()
        }
    }

    private fun createCaptureSession(camera: CameraDevice) {
        val handler = backgroundHandler
        val mode = selectedMode
        val texture = textureView.surfaceTexture
        val reader = imageReader
        if (handler == null || mode == null || texture == null || reader == null) {
            onStatus("Camera capture session prerequisites missing")
            stop()
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
                        captureSession = session
                        startRepeatingRequest(camera, session, preview, reader.surface, mode)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onStatus("Camera capture session configuration failed")
                        stop()
                    }
                },
                handler,
            )
        } catch (exception: RuntimeException) {
            onStatus("Failed to create capture session: ${exception.message ?: exception.javaClass.simpleName}")
            stop()
        }
    }

    private fun startRepeatingRequest(
        camera: CameraDevice,
        session: CameraCaptureSession,
        preview: Surface,
        analysis: Surface,
        mode: CaptureMode,
    ) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(preview)
                addTarget(analysis)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(mode.minFps, mode.maxFps))
            }.build()

            session.setRepeatingRequest(request, null, backgroundHandler)
            onStatus("Camera running ${mode.statusLabel()} frames=0 fps=0.0 y0=n/a")
        } catch (exception: RuntimeException) {
            onStatus("Failed to start camera capture: ${exception.message ?: exception.javaClass.simpleName}")
            stop()
        }
    }

    private fun CaptureMode.statusLabel(): String {
        val speed = if (highSpeed) " high-speed" else ""
        return "${width}x$height @ ${minFps}-${maxFps}fps$speed"
    }

    private fun Double.formatFps(): String = String.format("%.1f", this)

    private data class CameraConfig(
        val cameraId: String,
        val mode: CaptureMode,
    )

    private companion object {
        const val MAX_IMAGES = 3
    }
}
