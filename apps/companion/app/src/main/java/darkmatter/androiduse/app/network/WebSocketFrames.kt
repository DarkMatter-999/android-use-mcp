package darkmatter.androiduse.app.network

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

sealed interface WebSocketMessage {
    data class Text(val value: String) : WebSocketMessage
    data class Ping(val payload: ByteArray) : WebSocketMessage {
        override fun equals(other: Any?): Boolean = other is Ping && payload.contentEquals(other.payload)
        override fun hashCode(): Int = payload.contentHashCode()
    }
    data class Pong(val payload: ByteArray) : WebSocketMessage {
        override fun equals(other: Any?): Boolean = other is Pong && payload.contentEquals(other.payload)
        override fun hashCode(): Int = payload.contentHashCode()
    }
    data class Close(val code: Int?, val reason: String) : WebSocketMessage
}

data class WebSocketUpgradeRequest(val path: String, val webSocketKey: String)

class WebSocketProtocolException(
    message: String,
    val closeCode: Int = 1002,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Bounded server-side subset of RFC 6455 used by the RPC transport. */
object WebSocketFrames {
    private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val DEFAULT_HEADER_LIMIT = 16 * 1024
    private const val MAX_FRAME_BYTES = 2 * 1024 * 1024
    private const val DEFAULT_MESSAGE_LIMIT = MAX_FRAME_BYTES

    fun acceptKey(webSocketKey: String): String {
        val decoded = try {
            Base64.getDecoder().decode(webSocketKey)
        } catch (exception: IllegalArgumentException) {
            throw WebSocketProtocolException("invalid Sec-WebSocket-Key", cause = exception)
        }
        if (decoded.size != 16 || Base64.getEncoder().encodeToString(decoded) != webSocketKey) {
            throw WebSocketProtocolException("invalid Sec-WebSocket-Key")
        }
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((webSocketKey + WEBSOCKET_GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(digest)
    }

    fun readUpgradeRequest(
        input: InputStream,
        maxHeaderBytes: Int = DEFAULT_HEADER_LIMIT,
    ): WebSocketUpgradeRequest {
        require(maxHeaderBytes >= 4)
        val bytes = ByteArrayOutputStream(minOf(maxHeaderBytes, 1024))
        var matched = 0
        while (matched < 4) {
            val next = input.read()
            if (next < 0) throw WebSocketProtocolException("connection closed during HTTP upgrade")
            bytes.write(next)
            if (bytes.size() > maxHeaderBytes) {
                throw WebSocketProtocolException("HTTP upgrade headers exceed limit", 1009)
            }
            matched = when {
                matched == 0 && next == '\r'.code -> 1
                matched == 1 && next == '\n'.code -> 2
                matched == 2 && next == '\r'.code -> 3
                matched == 3 && next == '\n'.code -> 4
                next == '\r'.code -> 1
                else -> 0
            }
        }
        val headerText = bytes.toString(Charsets.US_ASCII.name())
        if (headerText.any { it.code > 0x7f || (it.code < 0x20 && it != '\r' && it != '\n' && it != '\t') }) {
            throw WebSocketProtocolException("HTTP upgrade headers contain invalid characters")
        }
        val lines = headerText.dropLast(4).split("\r\n")
        val request = lines.firstOrNull()?.split(' ')
        if (request == null || request.size != 3 || request[0] != "GET" || request[2] != "HTTP/1.1" ||
            request[1].isEmpty() || request[1].any { it.isWhitespace() }
        ) {
            throw WebSocketProtocolException("invalid WebSocket HTTP request line")
        }
        val headers = mutableMapOf<String, MutableList<String>>()
        for (line in lines.drop(1)) {
            if (line.isEmpty() || line.startsWith(' ') || line.startsWith('\t')) {
                throw WebSocketProtocolException("invalid HTTP header line")
            }
            val colon = line.indexOf(':')
            if (colon <= 0) throw WebSocketProtocolException("invalid HTTP header line")
            val name = line.substring(0, colon).trim().lowercase()
            if (name.isEmpty() || name.any { !(it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~") }) {
                throw WebSocketProtocolException("invalid HTTP header name")
            }
            headers.getOrPut(name) { mutableListOf() }.add(line.substring(colon + 1).trim())
        }
        fun values(name: String): List<String> = headers[name.lowercase()].orEmpty()
        fun tokens(name: String): Set<String> = values(name).flatMap { it.split(',') }
            .map { it.trim() }.filter { it.isNotEmpty() }.map { it.lowercase() }.toSet()

        if ("websocket" !in tokens("upgrade") || "upgrade" !in tokens("connection")) {
            throw WebSocketProtocolException("missing WebSocket upgrade headers")
        }
        if (values("sec-websocket-version") != listOf("13")) {
            throw WebSocketProtocolException("unsupported WebSocket version")
        }
        if (values("transfer-encoding").isNotEmpty() || values("content-length").any { it != "0" }) {
            throw WebSocketProtocolException("WebSocket upgrade request must not have a body")
        }
        val keyValues = values("sec-websocket-key")
        if (keyValues.size != 1) throw WebSocketProtocolException("missing or duplicate Sec-WebSocket-Key")
        acceptKey(keyValues.single())
        return WebSocketUpgradeRequest(request[1], keyValues.single())
    }

    fun writeUpgradeResponse(output: OutputStream, webSocketKey: String) {
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: ${acceptKey(webSocketKey)}\r\n\r\n"
        output.write(response.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    /** Reads through controls until a complete text message or close is available. */
    fun readMessage(
        input: InputStream,
        maxMessageBytes: Int = DEFAULT_MESSAGE_LIMIT,
        onControlFrame: (WebSocketMessage) -> Unit = {},
    ): WebSocketMessage? {
        require(maxMessageBytes > 0)
        var fragmentedText: ByteArrayOutputStream? = null
        while (true) {
            val first = input.read()
            if (first < 0) return null
            val second = input.readOrProtocolError("truncated WebSocket frame header")
            val fin = first and 0x80 != 0
            val reserved = first and 0x70
            val opcode = first and 0x0f
            if (reserved != 0) throw WebSocketProtocolException("WebSocket extensions were not negotiated")
            if (second and 0x80 == 0) throw WebSocketProtocolException("client frame is not masked")

            val control = opcode >= 8
            val lengthMarker = second and 0x7f
            val length = when (lengthMarker) {
                in 0..125 -> lengthMarker.toLong()
                126 -> {
                    val extended = readUnsignedShort(input)
                    if (extended < 126) throw WebSocketProtocolException("non-canonical 16-bit payload length")
                    extended.toLong()
                }
                else -> {
                    val extended = readUnsignedLong(input)
                    if (extended < 65_536L) throw WebSocketProtocolException("non-canonical 64-bit payload length")
                    extended
                }
            }
            if (control && (!fin || length > 125)) {
                throw WebSocketProtocolException("invalid fragmented or oversized control frame")
            }
            if (length > maxMessageBytes || length > Int.MAX_VALUE) {
                throw WebSocketProtocolException("WebSocket message exceeds limit", 1009)
            }
            if (opcode !in setOf(0, 1, 2, 8, 9, 10)) {
                throw WebSocketProtocolException("unknown WebSocket opcode")
            }
            if (opcode == 2) throw WebSocketProtocolException("binary WebSocket messages are unsupported", 1003)

            val mask = ByteArray(4).also { readFully(input, it) }
            val payload = ByteArray(length.toInt()).also { readFully(input, it) }
            for (index in payload.indices) payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()

            when (opcode) {
                8 -> return readClose(payload)
                9 -> onControlFrame(WebSocketMessage.Ping(payload))
                10 -> onControlFrame(WebSocketMessage.Pong(payload))
                1 -> {
                    if (fragmentedText != null) throw WebSocketProtocolException("new data frame during fragmented message")
                    if (fin) return WebSocketMessage.Text(decodeUtf8(payload))
                    fragmentedText = ByteArrayOutputStream(payload.size).apply { write(payload) }
                }
                0 -> {
                    val fragments = fragmentedText
                        ?: throw WebSocketProtocolException("continuation frame without a fragmented message")
                    if (fragments.size().toLong() + payload.size > maxMessageBytes) {
                        throw WebSocketProtocolException("fragmented message exceeds limit", 1009)
                    }
                    fragments.write(payload)
                    if (fin) {
                        fragmentedText = null
                        return WebSocketMessage.Text(decodeUtf8(fragments.toByteArray()))
                    }
                }
            }
        }
    }

    fun writeText(output: OutputStream, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_FRAME_BYTES) throw WebSocketProtocolException("server message exceeds limit", 1009)
        writeFrame(output, 1, payload)
    }

    fun writePong(output: OutputStream, payload: ByteArray) {
        if (payload.size > 125) throw WebSocketProtocolException("control payload exceeds 125 bytes")
        writeFrame(output, 10, payload)
    }

    fun writeClose(output: OutputStream, code: Int, reason: String = "") {
        val reasonBytes = reason.toByteArray(Charsets.UTF_8)
        if (!isValidCloseCode(code) || reasonBytes.size > 123) {
            throw WebSocketProtocolException("invalid close frame")
        }
        writeFrame(output, 8, ByteBuffer.allocate(2 + reasonBytes.size).putShort(code.toShort()).put(reasonBytes).array())
    }

    private fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        output.write(0x80 or opcode)
        when {
            payload.size < 126 -> output.write(payload.size)
            payload.size <= 0xffff -> {
                output.write(126)
                output.write(payload.size ushr 8)
                output.write(payload.size)
            }
            else -> {
                output.write(127)
                for (shift in 56 downTo 0 step 8) output.write((payload.size.toLong() ushr shift).toInt())
            }
        }
        output.write(payload)
        output.flush()
    }

    private fun readClose(payload: ByteArray): WebSocketMessage.Close {
        if (payload.size == 1) throw WebSocketProtocolException("close frame has a one-byte payload")
        if (payload.isEmpty()) return WebSocketMessage.Close(null, "")
        val code = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        if (!isValidCloseCode(code)) throw WebSocketProtocolException("invalid WebSocket close code")
        return WebSocketMessage.Close(code, decodeUtf8(payload.copyOfRange(2, payload.size), 1007))
    }

    private fun isValidCloseCode(code: Int): Boolean = code in 3000..4999 || code in setOf(
        1000, 1001, 1002, 1003, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014,
    )

    private fun decodeUtf8(bytes: ByteArray, closeCode: Int = 1007): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (exception: Exception) {
        throw WebSocketProtocolException("invalid UTF-8 WebSocket payload", closeCode, exception)
    }

    private fun readUnsignedShort(input: InputStream): Int =
        (input.readOrProtocolError("truncated extended payload length") shl 8) or
            input.readOrProtocolError("truncated extended payload length")

    private fun readUnsignedLong(input: InputStream): Long {
        var value = 0L
        repeat(8) { index ->
            val byte = input.readOrProtocolError("truncated extended payload length")
            if (index == 0 && byte and 0x80 != 0) throw WebSocketProtocolException("payload length high bit is set")
            value = (value shl 8) or byte.toLong()
        }
        return value
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) throw EOFException("truncated WebSocket frame payload")
            offset += count
        }
    }

    private fun InputStream.readOrProtocolError(message: String): Int = read().also {
        if (it < 0) throw WebSocketProtocolException(message)
    }
}
