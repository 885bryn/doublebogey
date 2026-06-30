package com.doublebogey.golftracer.network

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class LocalWebSocketServer(
    private val heartbeatIntervalMs: Long = 1_000,
    private val heartbeatFactory: () -> String = {
        """{"type":"heartbeat","sentAtEpochMs":${System.currentTimeMillis()}}"""
    },
    private val onHeartbeatSent: (String) -> Unit = {},
) : Closeable {
    private val running = AtomicBoolean(false)
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val port: Int
        get() = serverSocket?.localPort ?: 0

    fun start() {
        if (!running.compareAndSet(false, true)) return

        serverSocket = ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))
        acceptThread = thread(name = "golftracer-ws-accept", isDaemon = true) {
            acceptLoop()
        }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = runCatching { serverSocket?.accept() }.getOrNull() ?: continue
            clients += socket
            thread(name = "golftracer-ws-client", isDaemon = true) {
                handleClient(socket)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val headers = readHeaders(reader)
            val key = headers["sec-websocket-key"] ?: return
            val output = socket.getOutputStream()

            output.write(
                (
                    "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: ${WebSocketProtocol.acceptKey(key)}\r\n" +
                        "\r\n"
                    ).toByteArray(Charsets.ISO_8859_1),
            )
            output.flush()

            while (running.get() && !socket.isClosed) {
                val heartbeat = heartbeatFactory()
                output.write(WebSocketProtocol.textFrame(heartbeat))
                output.flush()
                onHeartbeatSent(heartbeat)
                Thread.sleep(heartbeatIntervalMs)
            }
        } catch (_: Exception) {
            // Client disconnects and role changes are normal during M1 testing.
        } finally {
            clients -= socket
            runCatching { socket.close() }
        }
    }

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
        }
        return headers
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        clients.toList().forEach { socket ->
            runCatching { socket.close() }
        }
        clients.clear()
        serverSocket = null
    }

    override fun close() {
        stop()
    }
}
