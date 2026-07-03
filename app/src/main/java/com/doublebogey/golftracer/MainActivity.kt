package com.doublebogey.golftracer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.doublebogey.golftracer.camera.CameraCaptureController
import com.doublebogey.golftracer.camera.LaunchZone
import com.doublebogey.golftracer.camera.LaunchZoneOverlayView
import com.doublebogey.golftracer.network.CameraNetworkController
import com.doublebogey.golftracer.network.DisplayNetworkController
import com.doublebogey.golftracer.network.RoleNetworkController

class MainActivity : Activity() {
    private val preferences by lazy {
        getSharedPreferences("doublebogey-role", Context.MODE_PRIVATE)
    }

    private var activeNetworkController: RoleNetworkController? = null
    private var activeCameraController: CameraCaptureController? = null
    private var currentRole: RoleChoice? = null
    private var uiGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMvpPermissions()
        showRoleSelector(RoleChoice.fromPersisted(preferences.getString(KEY_LAST_ROLE, null)))
    }

    private fun requestMvpPermissions() {
        val permissions = buildList {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CAMERA)
            }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != PERMISSIONS_REQUEST_CODE || currentRole != RoleChoice.Camera) {
            return
        }

        val cameraPermissionIndex = permissions.indexOf(Manifest.permission.CAMERA)
        val cameraGranted = cameraPermissionIndex >= 0 &&
            grantResults.getOrNull(cameraPermissionIndex) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            activeCameraController?.start()
        }
    }

    private fun showRoleSelector(lastRole: RoleChoice?) {
        stopActiveController()
        currentRole = null
        setContentView(
            verticalLayout {
                addView(
                    titleText(
                        text = "DoubleBogey",
                        sizeSp = 30f,
                    ),
                )
                addView(
                    bodyText(
                        text = lastRole?.let { "Last role: ${it.label}" } ?: "Choose this phone's role",
                    ),
                )
                addView(roleButton(RoleChoice.Camera))
                addView(roleButton(RoleChoice.Display))
            },
        )
    }

    private fun showRoleScreen(role: RoleChoice) {
        if (role == RoleChoice.Camera) {
            showCameraScreen()
            return
        }

        stopActiveController()
        currentRole = role
        val screenGeneration = nextUiGeneration()
        preferences.edit().putString(KEY_LAST_ROLE, role.persistedName).apply()

        lateinit var statusText: TextView
        val controller = DisplayNetworkController(this) { status ->
            updateIfCurrent(screenGeneration) {
                statusText.text = status
            }
        }
        activeNetworkController = controller

        setContentView(
            verticalLayout {
                addView(titleText(text = role.label + " role", sizeSp = 28f))
                addView(bodyText(text = placeholderText(role)))
                statusText = bodyText(text = "Starting network...")
                addView(statusText)
                addView(
                    Button(this@MainActivity).apply {
                        text = "Change role"
                        setOnClickListener {
                            showRoleSelector(role)
                        }
                    },
                )
            },
        )

        startActiveControllers()
    }

    private fun showCameraScreen() {
        stopActiveController()
        currentRole = RoleChoice.Camera
        val screenGeneration = nextUiGeneration()
        preferences.edit().putString(KEY_LAST_ROLE, RoleChoice.Camera.persistedName).apply()

        val textureView = TextureView(this)
        val overlayView = LaunchZoneOverlayView(this).apply {
            launchZone = readLaunchZone()
            onLaunchZoneChanged = ::persistLaunchZone
        }
        val cameraStatusText = overlayText("Starting camera...").apply {
            gravity = Gravity.START
        }
        val networkStatusText = overlayText("Starting network...").apply {
            gravity = Gravity.END
        }
        val networkController = CameraNetworkController(this) { status ->
            updateIfCurrent(screenGeneration) {
                networkStatusText.text = status
            }
        }
        val cameraController = CameraCaptureController(
            context = this,
            textureView = textureView,
            onStatus = { status ->
                updateIfCurrent(screenGeneration) {
                    cameraStatusText.text = status
                }
            },
            launchZoneProvider = {
                overlayView.launchZone
            },
            onDetectionResult = { result ->
                updateIfCurrent(screenGeneration) {
                    overlayView.detectionCandidates = result.candidates
                }
            },
            onTrackingState = { state ->
                updateIfCurrent(screenGeneration) {
                    overlayView.trackPoints = if (state.points.isNotEmpty()) {
                        state.points
                    } else {
                        emptyList()
                    }
                }
            },
        )

        activeNetworkController = networkController
        activeCameraController = cameraController

        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(
                    textureView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    overlayView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            cameraStatusText,
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                        addView(
                            networkStatusText,
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                topMargin = 4.dp
                            },
                        )
                    },
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP,
                    ).apply {
                        setMargins(12.dp, 12.dp, 12.dp, 12.dp)
                    },
                )
                addView(
                    Button(this@MainActivity).apply {
                        text = "Change role"
                        setOnClickListener {
                            showRoleSelector(RoleChoice.Camera)
                        }
                    },
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.START,
                    ).apply {
                        setMargins(12.dp, 12.dp, 12.dp, 12.dp)
                    },
                )
                addView(
                    Button(this@MainActivity).apply {
                        text = "Calibrate mat"
                        setOnClickListener {
                            overlayView.detectionCandidates = emptyList()
                            overlayView.trackPoints = emptyList()
                            activeCameraController?.resetShotReview()
                        }
                    },
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                    ).apply {
                        setMargins(12.dp, 12.dp, 12.dp, 12.dp)
                    },
                )
                addView(
                    Button(this@MainActivity).apply {
                        text = "Reset box"
                        setOnClickListener {
                            overlayView.launchZone = LaunchZone.Default
                            persistLaunchZone(LaunchZone.Default)
                        }
                    },
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.END,
                    ).apply {
                        setMargins(12.dp, 12.dp, 12.dp, 12.dp)
                    },
                )
            },
        )

        startActiveControllers()
    }

    private fun startActiveControllers() {
        activeNetworkController?.start()
        if (currentRole == RoleChoice.Camera) {
            activeCameraController?.start()
        }
    }

    private fun stopActiveControllersForLifecycle() {
        activeCameraController?.stop()
        activeNetworkController?.stop()
    }

    private fun stopActiveController() {
        invalidateUiGeneration()
        stopActiveControllersForLifecycle()
        activeCameraController = null
        activeNetworkController = null
    }

    override fun onResume() {
        super.onResume()
        startActiveControllers()
    }

    override fun onPause() {
        stopActiveControllersForLifecycle()
        super.onPause()
    }

    override fun onStop() {
        stopActiveControllersForLifecycle()
        super.onStop()
    }

    override fun onDestroy() {
        stopActiveController()
        super.onDestroy()
    }
    private fun roleButton(role: RoleChoice): Button =
        Button(this).apply {
            text = role.label
            textSize = 20f
            minHeight = 64.dp
            setOnClickListener { showRoleScreen(role) }
        }

    private fun verticalLayout(build: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24.dp, 24.dp, 24.dp, 24.dp)
            build()
        }

    private fun titleText(text: String, sizeSp: Float): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

    private fun bodyText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 16.dp, 0, 24.dp)
        }

    private fun overlayText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setPadding(8.dp, 6.dp, 8.dp, 6.dp)
        }

    private fun updateIfCurrent(screenGeneration: Int, update: () -> Unit) {
        runOnUiThread {
            if (screenGeneration == uiGeneration) {
                update()
            }
        }
    }

    private fun nextUiGeneration(): Int {
        uiGeneration += 1
        return uiGeneration
    }

    private fun invalidateUiGeneration() {
        uiGeneration += 1
    }

    private fun readLaunchZone(): LaunchZone =
        LaunchZone.fromPersisted(
            left = preferences.getFloat(KEY_LAUNCH_ZONE_LEFT, LaunchZone.Default.left.toFloat()).toDouble(),
            top = preferences.getFloat(KEY_LAUNCH_ZONE_TOP, LaunchZone.Default.top.toFloat()).toDouble(),
            width = preferences.getFloat(KEY_LAUNCH_ZONE_WIDTH, LaunchZone.Default.width.toFloat()).toDouble(),
            height = preferences.getFloat(KEY_LAUNCH_ZONE_HEIGHT, LaunchZone.Default.height.toFloat()).toDouble(),
        )

    private fun persistLaunchZone(zone: LaunchZone) {
        preferences.edit()
            .putFloat(KEY_LAUNCH_ZONE_LEFT, zone.left.toFloat())
            .putFloat(KEY_LAUNCH_ZONE_TOP, zone.top.toFloat())
            .putFloat(KEY_LAUNCH_ZONE_WIDTH, zone.width.toFloat())
            .putFloat(KEY_LAUNCH_ZONE_HEIGHT, zone.height.toFloat())
            .apply()
    }

    private fun placeholderText(role: RoleChoice): String =
        when (role) {
            RoleChoice.Camera -> "Capture, detection, and local server will start here in later milestones."
            RoleChoice.Display -> "Discovery, connection state, and tracer rendering will start here in later milestones."
        }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_LAST_ROLE = "last-role"
        const val KEY_LAUNCH_ZONE_LEFT = "launch-zone-left"
        const val KEY_LAUNCH_ZONE_TOP = "launch-zone-top"
        const val KEY_LAUNCH_ZONE_WIDTH = "launch-zone-width"
        const val KEY_LAUNCH_ZONE_HEIGHT = "launch-zone-height"
        const val PERMISSIONS_REQUEST_CODE = 1001
    }
}
