package com.doublebogey.golftracer.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build

class CameraNetworkController(
    context: Context,
    private val onStatus: (String) -> Unit,
) : RoleNetworkController {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var server: LocalWebSocketServer? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    override fun start() {
        if (server != null) return

        onStatus("Starting local WebSocket server...")
        val webSocketServer = LocalWebSocketServer(
            onHeartbeatSent = { heartbeat ->
                onStatus("Advertising on port " + webSocketServerPort() + " sent " + heartbeat)
            },
        )
        webSocketServer.start()
        server = webSocketServer
        registerService(webSocketServer.port)
    }

    private fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "${GolfTracerNetwork.SERVICE_NAME_PREFIX} ${Build.MODEL}"
            serviceType = GolfTracerNetwork.SERVICE_TYPE
            this.port = port
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                onStatus("Advertising ${serviceInfo.serviceName} on port $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                onStatus("NSD registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                onStatus("Stopped advertising")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                onStatus("NSD unregistration failed: $errorCode")
            }
        }

        registrationListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun webSocketServerPort(): Int = server?.port ?: 0

    override fun stop() {
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
        server?.stop()
        server = null
    }
}
