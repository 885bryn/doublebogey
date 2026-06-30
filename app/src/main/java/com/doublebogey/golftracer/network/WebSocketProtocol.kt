package com.doublebogey.golftracer.network

import java.security.MessageDigest
import java.util.Base64

object WebSocketProtocol {
    private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    fun acceptKey(clientKey: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((clientKey.trim() + WEBSOCKET_GUID).toByteArray(Charsets.ISO_8859_1))
        return Base64.getEncoder().encodeToString(digest)
    }

    fun textFrame(message: String): ByteArray {
        val payload = message.toByteArray(Charsets.UTF_8)
        return when {
            payload.size <= 125 -> byteArrayOf(0x81.toByte(), payload.size.toByte()) + payload
            payload.size <= UShort.MAX_VALUE.toInt() ->
                byteArrayOf(0x81.toByte(), 126, (payload.size ushr 8).toByte(), payload.size.toByte()) + payload
            else -> error("M1 heartbeat payload is too large for the lightweight WebSocket server")
        }
    }
}
