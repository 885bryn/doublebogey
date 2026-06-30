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

    private fun showRoleSelector(lastRole: RoleChoice?) {
        stopActiveController()
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
        preferences.edit().putString(KEY_LAST_ROLE, role.persistedName).apply()

        lateinit var statusText: TextView
        val controller = DisplayNetworkController(this) { status ->
            runOnUiThread {
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

        controller.start()
    }

    private fun showCameraScreen() {
        stopActiveController()
        preferences.edit().putString(KEY_LAST_ROLE, RoleChoice.Camera.persistedName).apply()

        val textureView = TextureView(this)
        val overlayView = LaunchZoneOverlayView(this).apply {
            launchZone = readLaunchZone()
            onLaunchZoneChanged = ::persistLaunchZone
        }
        val cameraStatusText = overlayText("Starting camera...")
        val networkStatusText = overlayText("Starting network...")
        val networkController = CameraNetworkController(this) { status ->
            runOnUiThread {
                networkStatusText.text = status
            }
        }
        val cameraController = CameraCaptureController(this, textureView) { status ->
            runOnUiThread {
                cameraStatusText.text = status
            }
        }

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
                    cameraStatusText,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.START,
                    ).apply {
                        setMargins(12.dp, 12.dp, 12.dp, 12.dp)
                    },
                )
                addView(
                    networkStatusText,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.END,
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

        networkController.start()
        cameraController.start()
    }

    private fun stopActiveController() {
        activeCameraController?.stop()
        activeCameraController = null
        activeNetworkController?.stop()
        activeNetworkController = null
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
