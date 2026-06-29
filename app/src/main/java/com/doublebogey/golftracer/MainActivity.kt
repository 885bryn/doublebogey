package com.doublebogey.golftracer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val preferences by lazy {
        getSharedPreferences("doublebogey-role", Context.MODE_PRIVATE)
    }

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
        preferences.edit().putString(KEY_LAST_ROLE, role.persistedName).apply()

        setContentView(
            verticalLayout {
                addView(titleText(text = "${role.label} role", sizeSp = 28f))
                addView(bodyText(text = placeholderText(role)))
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

    private fun placeholderText(role: RoleChoice): String =
        when (role) {
            RoleChoice.Camera -> "Capture, detection, and local server will start here in later milestones."
            RoleChoice.Display -> "Discovery, connection state, and tracer rendering will start here in later milestones."
        }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_LAST_ROLE = "last-role"
        const val PERMISSIONS_REQUEST_CODE = 1001
    }
}
