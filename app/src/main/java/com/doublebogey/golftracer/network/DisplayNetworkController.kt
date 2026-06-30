package com.doublebogey.golftracer.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class DisplayNetworkController(
    context: Context,
    private val onStatus: (String) -> Unit,
) : RoleNetworkController {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val okHttpClient = OkHttpClient()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var webSocket: WebSocket? = null
    private var resolving = false
    private var stopped = true
    private var reconnectGeneration = 0

    override fun start() {
        if (!stopped) return
        stopped = false
        reconnectGeneration += 1
        acquireMulticastLock()
        discover()
    }

    private fun acquireMulticastLock() {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifiManager.createMulticastLock("doublebogey-nsd").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun discover() {
        if (discoveryListener != null) return
        onStatus("Searching for Camera service...")
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                onStatus("Browsing " + serviceType)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (
                    !resolving &&
                    serviceInfo.serviceType == GolfTracerNetwork.SERVICE_TYPE &&
                    serviceInfo.serviceName.startsWith(GolfTracerNetwork.SERVICE_NAME_PREFIX)
                ) {
                    resolving = true
                    onStatus("Resolving " + serviceInfo.serviceName)
                    nsdManager.resolveService(serviceInfo, resolveListener(serviceInfo.serviceName))
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                onStatus("Camera service lost")
                reconnectLater()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                onStatus("Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onStatus("Discovery failed: " + errorCode)
                runCatching { nsdManager.stopServiceDiscovery(this) }
                reconnectLater()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                onStatus("Stop discovery failed: " + errorCode)
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }
        }
        discoveryListener = listener
        nsdManager.discoverServices(GolfTracerNetwork.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolveListener(serviceName: String): NsdManager.ResolveListener =
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolving = false
                onStatus("Resolve failed: " + errorCode)
                reconnectLater()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                resolving = false
                val host = serviceInfo.host?.hostAddress
                val port = serviceInfo.port
                if (host.isNullOrBlank() || port <= 0) {
                    onStatus("Resolved Camera without usable host/port")
                    reconnectLater()
                    return
                }
                onStatus("Resolved " + serviceName + " at " + host + ":" + port)
                connectWebSocket(host, port)
            }
        }

    private fun connectWebSocket(host: String, port: Int) {
        webSocket?.cancel()
        val request = Request.Builder()
            .url("ws://" + host + ":" + port + GolfTracerNetwork.HEARTBEAT_PATH)
            .build()
        webSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    onStatus("Connected to Camera at " + host + ":" + port)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    onStatus("Connected | received " + text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    this@DisplayNetworkController.webSocket = null
                    onStatus("Socket closed: " + reason)
                    reconnectLater()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    this@DisplayNetworkController.webSocket = null
                    onStatus("Socket failed: " + (t.message ?: t.javaClass.simpleName))
                    reconnectLater()
                }
            },
        )
    }

    private fun reconnectLater() {
        if (stopped) return
        val callbackGeneration = reconnectGeneration
        mainHandler.postDelayed({
            if (!stopped && callbackGeneration == reconnectGeneration && webSocket == null) {
                restartDiscovery()
            }
        }, 1_500)
    }

    private fun restartDiscovery() {
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
        resolving = false
        discover()
    }

    override fun stop() {
        stopped = true
        reconnectGeneration += 1
        mainHandler.removeCallbacksAndMessages(null)
        resolving = false
        webSocket?.cancel()
        webSocket = null
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
        multicastLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        multicastLock = null
    }
}
