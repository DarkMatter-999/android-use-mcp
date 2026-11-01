package darkmatter.androiduse.app.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketFramesTest {

    @Test
    fun computesRfc6455AcceptKeyVector() {
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            WebSocketFrames.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="),
        )
    }

    @Test
    fun validatesHttp11WebSocketUpgradeAndEnforcesHeaderBound() {
        val request = WebSocketFrames.readUpgradeRequest(
            ByteArrayInputStream(validUpgrade().toByteArray(Charsets.US_ASCII)),
            maxHeaderBytes = 1024,
        )
        assertEquals("/rpc", request.path)
        assertEquals("dGhlIHNhbXBsZSBub25jZQ==", request.webSocketKey)

        assertProtocolError { WebSocketFrames.readUpgradeRequest(ByteArrayInputStream("POST /rpc HTTP/1.1\r\n\r\n".toByteArray()), 1024) }
        assertProtocolError { WebSocketFrames.readUpgradeRequest(ByteArrayInputStream("GET /rpc HTTP/1.0\r\n\r\n".toByteArray()), 1024) }
        assertProtocolError { WebSocketFrames.readUpgradeRequest(ByteArrayInputStream("GET /rpc HTTP/1.1\r\n\r\n".toByteArray()), 1024) }
        assertProtocolError {
            WebSocketFrames.readUpgradeRequest(
                ByteArrayInputStream(validUpgrade().replace("Version: 13", "Version: 12").toByteArray()),
                1024,
            )
        }
        assertProtocolError {
            WebSocketFrames.readUpgradeRequest(ByteArrayInputStream(validUpgrade().toByteArray()), maxHeaderBytes = 16)
        }
    }

    @Test
    fun rejectsUnmaskedClientFramesNonCanonicalLengthsAndLengthOverflow() {
        assertProtocolError { WebSocketFrames.readMessage(ByteArrayInputStream(byteArrayOf(0x81.toByte(), 1, 65)), 64) }
        assertProtocolError { WebSocketFrames.readMessage(maskedFrame(1, byteArrayOf(65), forceLength126 = true), 64) }
        val highBit = byteArrayOf(
            0x82.toByte(), 0xff.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0,
        )
        assertProtocolError { WebSocketFrames.readMessage(ByteArrayInputStream(highBit), 64) }
        val nonCanonical64 = byteArrayOf(
            0x81.toByte(), 0xff.toByte(), 0, 0, 0, 0, 0, 0, 0, 1,
        )
        assertProtocolError { WebSocketFrames.readMessage(ByteArrayInputStream(nonCanonical64), 64) }
        assertProtocolError { WebSocketFrames.readMessage(maskedFrame(2, ByteArray(65)), 64) }
    }

    @Test
    fun readsMaskedTextAndWritesUnmaskedServerText() {
        val text = WebSocketFrames.readMessage(maskedFrame(1, "hello".toByteArray()), 64)
        assertEquals(WebSocketMessage.Text("hello"), text)

        val output = ByteArrayOutputStream()
        WebSocketFrames.writeText(output, "world")
        val bytes = output.toByteArray()
        assertEquals(0x81, bytes[0].toInt() and 0xff)
        assertEquals(5, bytes[1].toInt() and 0xff)
        assertFalse(bytes[1].toInt() and 0x80 != 0)
        assertEquals("world", String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_8))
    }

    @Test
    fun assemblesFragmentsAndRejectsInvalidContinuationSequencesAndUtf8() {
        val fragmented = ByteArrayInputStream(
            maskedFrame(1, "hel".toByteArray(), fin = false).readBytes() +
                maskedFrame(0, "lo".toByteArray()).readBytes(),
        )
        assertEquals(WebSocketMessage.Text("hello"), WebSocketFrames.readMessage(fragmented, 64))

        assertProtocolError { WebSocketFrames.readMessage(maskedFrame(0, "orphan".toByteArray()), 64) }
        val invalidUtf8 = maskedFrame(1, byteArrayOf(0xc3.toByte(), 0x28))
        assertProtocolError { WebSocketFrames.readMessage(invalidUtf8, 64) }
        val tooLargeFragment = ByteArrayInputStream(
            maskedFrame(1, ByteArray(4), fin = false).readBytes() +
                maskedFrame(0, ByteArray(4)).readBytes(),
        )
        assertProtocolError { WebSocketFrames.readMessage(tooLargeFragment, 7) }
    }

    @Test
    fun dispatchesPingAndPongAndReturnsCloseFrame() {
        val control = mutableListOf<WebSocketMessage>()
        val input = ByteArrayInputStream(
            maskedFrame(9, byteArrayOf(1, 2)).readBytes() +
                maskedFrame(10, byteArrayOf(3)).readBytes() +
                maskedFrame(8, ByteBuffer.allocate(2).putShort(1000).array()).readBytes() +
                maskedFrame(1, "after-close".toByteArray()).readBytes(),
        )
        val close = WebSocketFrames.readMessage(input, 64, control::add)

        assertEquals(listOf(WebSocketMessage.Ping(byteArrayOf(1, 2)), WebSocketMessage.Pong(byteArrayOf(3))), control)
        assertEquals(WebSocketMessage.Close(1000, ""), close)
        assertNull(WebSocketFrames.readMessage(ByteArrayInputStream(byteArrayOf()), 64))
    }

    @Test
    fun rejectsInvalidControlFrameConstraintsAndFragmentedMessageInterleaving() {
        assertProtocolError { WebSocketFrames.readMessage(maskedFrame(9, ByteArray(126)), 256) }
        assertProtocolError { WebSocketFrames.readMessage(maskedFrame(9, byteArrayOf(1), fin = false), 64) }
        assertProtocolError { WebSocketFrames.readMessage(maskedFrame(8, byteArrayOf(0, 1)), 64) }
        assertProtocolError {
            WebSocketFrames.readMessage(maskedFrame(8, byteArrayOf(0x03, 0xe8.toByte(), 0xc3.toByte())), 64)
        }
        assertProtocolError {
            WebSocketFrames.readMessage(
                ByteArrayInputStream(
                    maskedFrame(1, "a".toByteArray(), fin = false).readBytes() +
                        maskedFrame(1, "b".toByteArray()).readBytes(),
                ),
                64,
            )
        }
    }

    private fun validUpgrade(): String =
        "GET /rpc HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: keep-alive, Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"

    private fun maskedFrame(
        opcode: Int,
        payload: ByteArray,
        fin: Boolean = true,
        forceLength126: Boolean = false,
    ): ByteArrayInputStream {
        val mask = byteArrayOf(1, 2, 3, 4)
        val output = ByteArrayOutputStream()
        output.write((if (fin) 0x80 else 0) or opcode)
        if (forceLength126 || payload.size >= 126) {
            output.write(0x80 or 126)
            output.write((payload.size ushr 8) and 0xff)
            output.write(payload.size and 0xff)
        } else {
            output.write(0x80 or payload.size)
        }
        output.write(mask)
        output.write(ByteArray(payload.size) { index -> (payload[index].toInt() xor mask[index % 4].toInt()).toByte() })
        return ByteArrayInputStream(output.toByteArray())
    }

    private fun assertProtocolError(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected WebSocket protocol rejection")
        } catch (_: WebSocketProtocolException) {
            // Malformed and oversized peer input must fail closed.
        }
    }
}
