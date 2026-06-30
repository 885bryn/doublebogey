package com.doublebogey.golftracer.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WebSocketProtocolTest {
    @Test
    fun createsRfcWebSocketAcceptKey() {
        val acceptKey = WebSocketProtocol.acceptKey("dGhlIHNhbXBsZSBub25jZQ==")

        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", acceptKey)
    }

    @Test
    fun encodesUnmaskedServerTextFrame() {
        val frame = WebSocketProtocol.textFrame("hi")

        assertContentEquals(byteArrayOf(0x81.toByte(), 0x02, 'h'.code.toByte(), 'i'.code.toByte()), frame)
    }
}
