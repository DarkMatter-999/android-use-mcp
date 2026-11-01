package darkmatter.androiduse.app.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.security.Principal
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomTlsWebSocketFeasibilityTest {

    @Test
    fun androidKeyStoreSupportsTls13MutualTlsAndLoopbackWebSocketExchange() {
        AndroidKeyStoreTestIdentity.deleteStaleTestIdentities()
        var device: AndroidKeyStoreTestIdentity? = null
        var host: AndroidKeyStoreTestIdentity? = null
        val executor = Executors.newSingleThreadExecutor()
        var serverSocket: SSLServerSocket? = null
        try {
            val serverIdentity = AndroidKeyStoreTestIdentity.create("server").also { device = it }
            val clientIdentity = AndroidKeyStoreTestIdentity.create("client").also { host = it }
            val listener = createServerSocket(serverIdentity).also { serverSocket = it }
            assertNull("Android Keystore private key must not be exportable", serverIdentity.privateKey.encoded)
            assertNull("host test key must not be exportable", clientIdentity.privateKey.encoded)
            assertTrue("client authentication must be required", listener.needClientAuth)
            assertFalse("optional client authentication must remain disabled", listener.wantClientAuth)
            assertArrayEquals(arrayOf("TLSv1.3"), listener.enabledProtocols)

            val server = executor.submit {
                rejectClientWithoutCertificate(listener)
                serveOneWebSocket(listener, clientIdentity.certificate)
            }

            assertMissingClientCertificateRejected(listener.localPort, serverIdentity.certificate)

            createClientSocket(listener.localPort, serverIdentity.certificate, clientIdentity).use { client ->
                client.soTimeout = SOCKET_TIMEOUT_MILLIS
                client.startHandshake()
                assertEquals("TLSv1.3", client.session.protocol)

                client.outputStream.write(HTTP_UPGRADE.toByteArray(Charsets.US_ASCII))
                client.outputStream.flush()
                val response = readHttpHeaders(BufferedInputStream(client.inputStream))
                assertTrue(response.startsWith("HTTP/1.1 101 Switching Protocols\r\n"))
                assertTrue(response.contains("Sec-WebSocket-Accept: $KNOWN_ACCEPT\r\n"))

                client.outputStream.write(maskedTextFrame("hello", MASK_KEY))
                client.outputStream.flush()
                assertEquals("world", readUnmaskedTextFrame(client.inputStream))
            }

            server.get(SOCKET_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
        } finally {
            runCatching { serverSocket?.close() }
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
            runCatching { host?.close() }
            runCatching { device?.close() }
            AndroidKeyStoreTestIdentity.deleteStaleTestIdentities()
        }
    }

    private fun createServerSocket(device: AndroidKeyStoreTestIdentity): SSLServerSocket {
        val serverSocket = serverContext(device).serverSocketFactory.createServerSocket(
            0,
            2,
            InetAddress.getByName("127.0.0.1"),
        ) as SSLServerSocket
        serverSocket.enabledProtocols = arrayOf("TLSv1.3")
        serverSocket.needClientAuth = true
        serverSocket.soTimeout = SOCKET_TIMEOUT_MILLIS
        check(serverSocket.needClientAuth && !serverSocket.wantClientAuth)
        return serverSocket
    }

    private fun rejectClientWithoutCertificate(serverSocket: SSLServerSocket) {
        val socket = serverSocket.accept() as SSLSocket
        socket.soTimeout = SOCKET_TIMEOUT_MILLIS
        try {
            try {
                socket.startHandshake()
                throw AssertionError("TLS handshake accepted a client without a certificate")
            } catch (_: SSLHandshakeException) {
                // Required client authentication rejected the empty client chain.
            }
        } finally {
            socket.close()
        }
    }

    private fun assertMissingClientCertificateRejected(port: Int, deviceCertificate: X509Certificate) {
        val clientContext = SSLContext.getInstance("TLSv1.3").apply {
            init(null, arrayOf(SelfSignedTrustManager()), SecureRandom())
        }
        val socket = clientContext.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
        socket.enabledProtocols = arrayOf("TLSv1.3")
        socket.soTimeout = SOCKET_TIMEOUT_MILLIS
        try {
            try {
                socket.startHandshake()
            } catch (_: SSLHandshakeException) {
                // Client-side handshake rejection is provider-dependent for TLS 1.3;
                // the server task below asserts that the peer was rejected.
            }
        } finally {
            socket.close()
        }
    }

    private fun serveOneWebSocket(
        serverSocket: SSLServerSocket,
        expectedClientCertificate: X509Certificate,
    ) {
        val socket = serverSocket.accept() as SSLSocket
        socket.soTimeout = SOCKET_TIMEOUT_MILLIS
        try {
            socket.startHandshake()
            assertEquals("TLSv1.3", socket.session.protocol)
            val peer = socket.session.peerCertificates.single() as X509Certificate
            assertEquals(expectedClientCertificate, peer)

            val input = BufferedInputStream(socket.inputStream)
            val request = readHttpHeaders(input)
            assertTrue(request.startsWith("GET /rpc HTTP/1.1\r\n"))
            assertTrue(request.contains("Sec-WebSocket-Key: $KNOWN_KEY\r\n"))
            socket.outputStream.write(
                ("HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $KNOWN_ACCEPT\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII),
            )
            socket.outputStream.flush()

            assertEquals("hello", readMaskedTextFrame(input))
            socket.outputStream.write(byteArrayOf(0x81.toByte(), 5) + "world".toByteArray())
            socket.outputStream.flush()
        } finally {
            socket.close()
        }
    }

    private fun createClientSocket(
        port: Int,
        deviceCertificate: X509Certificate,
        host: AndroidKeyStoreTestIdentity,
    ): SSLSocket {
        val socket = clientContext(deviceCertificate, host).socketFactory
            .createSocket("127.0.0.1", port) as SSLSocket
        socket.enabledProtocols = arrayOf("TLSv1.3")
        return socket
    }

    private fun serverContext(identity: AndroidKeyStoreTestIdentity): SSLContext =
        SSLContext.getInstance("TLSv1.3").apply {
            init(
                arrayOf(FixedKeyManager(identity)),
                arrayOf(SelfSignedTrustManager()),
                SecureRandom(),
            )
        }

    private fun clientContext(
        deviceCertificate: X509Certificate,
        identity: AndroidKeyStoreTestIdentity,
    ): SSLContext = SSLContext.getInstance("TLSv1.3").apply {
        init(
            arrayOf(FixedKeyManager(identity)),
            arrayOf(SelfSignedTrustManager(deviceCertificate)),
            SecureRandom(),
        )
    }

    private fun readHttpHeaders(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val next = input.read()
            check(next >= 0) { "socket closed before the HTTP upgrade completed" }
            bytes.write(next)
            matched = when {
                matched == 0 && next == '\r'.code -> 1
                matched == 1 && next == '\n'.code -> 2
                matched == 2 && next == '\r'.code -> 3
                matched == 3 && next == '\n'.code -> 4
                next == '\r'.code -> 1
                else -> 0
            }
            check(bytes.size() <= MAX_HEADER_BYTES) { "HTTP headers exceeded test bound" }
        }
        return bytes.toString(Charsets.US_ASCII.name())
    }

    private fun readMaskedTextFrame(input: InputStream): String {
        val first = input.read()
        val second = input.read()
        check(first == 0x81) { "expected a final text frame" }
        check(second >= 0 && second and 0x80 != 0) { "client frame must be masked" }
        val length = second and 0x7f
        check(length <= MAX_FRAME_BYTES) { "test frame exceeded bound" }
        val mask = ByteArray(4) { input.read().also { check(it >= 0) }.toByte() }
        val payload = ByteArray(length) { input.read().also { check(it >= 0) }.toByte() }
        for (index in payload.indices) payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
        return payload.toString(Charsets.UTF_8)
    }

    private fun readUnmaskedTextFrame(input: InputStream): String {
        val first = input.read()
        val second = input.read()
        check(first == 0x81) { "expected a final text response frame" }
        check(second >= 0 && second and 0x80 == 0) { "server frame must not be masked" }
        val payload = ByteArray(second) { input.read().also { check(it >= 0) }.toByte() }
        return payload.toString(Charsets.UTF_8)
    }

    private fun maskedTextFrame(text: String, mask: ByteArray): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.size < 126)
        return byteArrayOf(0x81.toByte(), (0x80 or payload.size).toByte()) + mask +
            ByteArray(payload.size) { index -> (payload[index].toInt() xor mask[index % 4].toInt()).toByte() }
    }

    private class FixedKeyManager(
        private val identity: AndroidKeyStoreTestIdentity,
    ) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
            arrayOf(identity.alias)

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: java.net.Socket?,
        ): String = identity.alias

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
            arrayOf(identity.alias)

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: java.net.Socket?,
        ): String = identity.alias

        override fun getCertificateChain(alias: String?): Array<X509Certificate> =
            if (alias == identity.alias) arrayOf(identity.certificate) else emptyArray()

        override fun getPrivateKey(alias: String?) =
            if (alias == identity.alias) identity.privateKey else null

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            engine: javax.net.ssl.SSLEngine?,
        ): String = identity.alias

        override fun chooseEngineServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            engine: javax.net.ssl.SSLEngine?,
        ): String = identity.alias
    }

    private class SelfSignedTrustManager(
        private vararg val explicitlyTrusted: X509Certificate,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = validate(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = validate(chain)
        override fun getAcceptedIssuers(): Array<X509Certificate> =
            Array(explicitlyTrusted.size) { index -> explicitlyTrusted[index] }

        private fun validate(chain: Array<out X509Certificate>?) {
            if (chain == null || chain.size != 1) throw CertificateException("expected one self-signed leaf")
            val certificate = chain.single()
            certificate.checkValidity()
            certificate.verify(certificate.publicKey)
            if (explicitlyTrusted.isNotEmpty() && explicitlyTrusted.none { it == certificate }) {
                throw CertificateException("peer certificate was not the expected local identity")
            }
        }
    }

    private companion object {
        const val SOCKET_TIMEOUT_MILLIS = 5_000
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_FRAME_BYTES = 2 * 1024 * 1024
        const val KNOWN_KEY = "dGhlIHNhbXBsZSBub25jZQ=="
        const val KNOWN_ACCEPT = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        val MASK_KEY = byteArrayOf(1, 2, 3, 4)
        val HTTP_UPGRADE = "GET /rpc HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $KNOWN_KEY\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"
    }
}
