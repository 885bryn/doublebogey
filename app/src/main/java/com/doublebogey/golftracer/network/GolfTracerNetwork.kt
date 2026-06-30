package com.doublebogey.golftracer.network

object GolfTracerNetwork {
    const val SERVICE_TYPE = "_golftracer._tcp."
    const val SERVICE_NAME_PREFIX = "DoubleBogey Camera"
    const val HEARTBEAT_PATH = "/shot"
}

interface RoleNetworkController {
    fun start()
    fun stop()
}
