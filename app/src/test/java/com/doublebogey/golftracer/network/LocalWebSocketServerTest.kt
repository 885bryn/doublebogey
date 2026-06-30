package com.doublebogey.golftracer.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class LocalWebSocketServerTest {
    @Test
    fun sendsHeartbeatToWebSocketClient() {
        val server = LocalWebSocketServer(heartbeatIntervalMs = 100)
        val client = OkHttpClient()
        val latch = CountDownLatch(1)
        var receivedMessage: String? = null

        try {
            server.start()

            client.newWebSocket(
                Request.Builder().url("ws://127.0.0.1:${server.port}/shot").build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        receivedMessage = text
                        latch.countDown()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        latch.countDown()
                    }
                },
            )

            assertTrue(latch.await(2, TimeUnit.SECONDS), "server should send one heartbeat")
            assertNotNull(receivedMessage)
            assertTrue(receivedMessage!!.contains("heartbeat"))
        } finally {
            server.stop()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
