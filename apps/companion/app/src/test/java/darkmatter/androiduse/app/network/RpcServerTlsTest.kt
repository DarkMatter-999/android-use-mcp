package darkmatter.androiduse.app.network

import java.net.InetAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import darkmatter.androiduse.app.pairing.HostRecord
import darkmatter.androiduse.app.pairing.ControllerLease
import darkmatter.androiduse.app.pairing.PairingManager
import darkmatter.androiduse.app.pairing.PairingStore
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class RpcServerTlsTest {
    private val serverIdentity = TestTlsIdentity.load(javaClass, "server-fixture")
    private val clientIdentity = TestTlsIdentity.load(javaClass, "client-fixture")

    @Test
    fun serverSocketEnablesOnlyTls13AndRequiresClientAuthentication() {
        val socket = SSLContext.getDefault().serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        try {
            DeviceTls.configure(socket)

            assertArrayEquals(arrayOf("TLSv1.3"), socket.enabledProtocols)
            assertTrue(socket.needClientAuth)
            assertFalse(socket.wantClientAuth)
        } finally {
            socket.close()
        }
    }

    @Test
    fun tls13MutualTlsAndRpcWebSocketExchangeSucceeds() {
        val server = newServer(serverIdentity.sslContext(clientIdentity.certificate, includeClientIdentity = true))
        val endpoint = server.start(BindMode.Loopback)
        try {
            (clientIdentity.sslContext(serverIdentity.certificate, includeClientIdentity = true)
                .socketFactory.createSocket() as SSLSocket)
                .use { socket ->
                    socket.connect(InetSocketAddress(endpoint.address, endpoint.port), SOCKET_TIMEOUT_MILLIS)
                    socket.enabledProtocols = arrayOf("TLSv1.3")
                    socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                    socket.startHandshake()
                    assertEquals("TLSv1.3", socket.session.protocol)
                    socket.outputStream.write(UPGRADE.toByteArray(Charsets.US_ASCII))
                    socket.outputStream.flush()
                    val headers = readHeaders(socket.inputStream)
                    assertTrue(headers.startsWith("HTTP/1.1 101 Switching Protocols\r\n"))

                    val request = RpcProtocol.serialize(
                        RpcRequest(PROTOCOL_VERSION, REQUEST_ID, "device.status", JsonObject(emptyMap())),
                    )
                    socket.outputStream.write(maskedTextFrame(request))
                    socket.outputStream.flush()
                    val responseText = readServerText(socket.inputStream)
                    val response = RpcProtocol.parseResponse(responseText)
                    assertTrue(response is RpcParseResult.Success)
                    assertEquals("ok", (response as RpcParseResult.Success).value.result?.toString()?.trim('"'))
                }
        } finally {
            server.close()
        }
    }

    @Test
    fun approvedControllerLifecycleIsReportedAndClearedOnSocketClose() {
        val manager = newPairingManager()
        val challenge = manager.begin()
        manager.submit(challenge.code, HOST_ID, "Test Host", clientIdentity.certificate)
        manager.approve(HOST_ID)
        val connected = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val observed = mutableListOf<String?>()
        lateinit var server: RpcServer
        server = newServer(
            serverIdentity.sslContext(clientIdentity.certificate, includeClientIdentity = true),
            manager = manager,
            sessionStopRequested = { server.stop() },
            sessionChanged = { _, record ->
                synchronized(observed) { observed += record?.hostName }
                if (record == null) disconnected.countDown() else connected.countDown()
            },
        )
        val endpoint = server.start(BindMode.Loopback)
        try {
            val socket = connectWebSocket(endpoint)
            socket.outputStream.write(maskedTextFrame(RpcProtocol.serialize(
                RpcRequest(PROTOCOL_VERSION, REQUEST_ID, "session.start", JsonObject(emptyMap())),
            )))
            socket.outputStream.flush()
            assertTrue(connected.await(SOCKET_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS))
            assertEquals("Test Host", synchronized(observed) { observed.first() })
            assertTrue(RpcProtocol.parseResponse(readServerText(socket.inputStream)) is RpcParseResult.Success)
            socket.outputStream.write(maskedTextFrame(RpcProtocol.serialize(
                RpcRequest(PROTOCOL_VERSION, "22222222-2222-4222-8222-222222222222", "session.stop", JsonObject(emptyMap())),
            )))
            socket.outputStream.flush()
            assertTrue(RpcProtocol.parseResponse(readServerText(socket.inputStream)) is RpcParseResult.Success)
            assertTrue(disconnected.await(SOCKET_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS))
            assertEquals(null, server.endpoint)
            assertTrue(runCatching { connectWebSocket(endpoint) }.isFailure)
            socket.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun queuedBusinessRequestDoesNotDispatchAfterConnectionClose() {
        val manager = newPairingManager()
        val challenge = manager.begin()
        manager.submit(challenge.code, HOST_ID, "Test Host", clientIdentity.certificate)
        manager.approve(HOST_ID)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val dispatches = AtomicInteger()
        val server = newServer(
            serverIdentity.sslContext(clientIdentity.certificate, includeClientIdentity = true),
            limits = RpcServerLimits(requestWorkers = 1, requestQueueCapacity = 2, idleTimeoutMillis = 2_000),
            manager = manager,
            businessDispatcher = { request, _ ->
                if (dispatches.incrementAndGet() == 1) {
                    firstEntered.countDown()
                    check(releaseFirst.await(SOCKET_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS))
                } else {
                    secondEntered.countDown()
                }
                RpcResponse(PROTOCOL_VERSION, request.requestId)
            },
        )
        val endpoint = server.start(BindMode.Loopback)
        try {
            val socket = connectWebSocket(endpoint)
            socket.outputStream.write(maskedTextFrame(RpcProtocol.serialize(
                RpcRequest(PROTOCOL_VERSION, REQUEST_ID, "session.start", JsonObject(emptyMap())),
            )))
            socket.outputStream.flush()
            assertTrue(firstEntered.await(SOCKET_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS))
            socket.outputStream.write(maskedTextFrame(RpcProtocol.serialize(
                RpcRequest(PROTOCOL_VERSION, "22222222-2222-4222-8222-222222222222", "session.start", JsonObject(emptyMap())),
            )))
            socket.outputStream.flush()
            socket.outputStream.write(byteArrayOf(0x88.toByte(), 0x80.toByte(), 1, 2, 3, 4))
            socket.outputStream.flush()
            readServerClose(socket.inputStream)
            releaseFirst.countDown()
            assertFalse(secondEntered.await(300, TimeUnit.MILLISECONDS))
            assertEquals(1, dispatches.get())
            socket.close()
        } finally {
            releaseFirst.countDown()
            server.close()
        }
    }

    @Test
    fun tls13ListenerRejectsClientWithoutCertificateBeforeWebSocketUpgrade() {
        val server = newServer(serverIdentity.sslContext(clientIdentity.certificate, includeClientIdentity = true))
        val endpoint = server.start(BindMode.Loopback)
        try {
            val noIdentity = clientIdentity.sslContext(serverIdentity.certificate, includeClientIdentity = false)
            val socket = noIdentity.socketFactory.createSocket() as SSLSocket
            try {
                socket.connect(InetSocketAddress(endpoint.address, endpoint.port), SOCKET_TIMEOUT_MILLIS)
                socket.enabledProtocols = arrayOf("TLSv1.3")
                socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                runCatching { socket.startHandshake() }
                runCatching {
                    socket.outputStream.write(UPGRADE.toByteArray(Charsets.US_ASCII))
                    socket.outputStream.flush()
                }
                val response = runCatching { readHeaders(socket.inputStream) }.getOrDefault("")
                assertFalse(response.startsWith("HTTP/1.1 101"))
            } finally {
                socket.close()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun listenerRejectsTlsDowngradePlaintextAndWrongClientCertificate() {
        val server = newServer(serverIdentity.sslContext(clientIdentity.certificate, includeClientIdentity = true))
        val endpoint = server.start(BindMode.Loopback)
        try {
            val tls12 = clientIdentity.sslContext(serverIdentity.certificate, includeClientIdentity = true)
                .socketFactory.createSocket() as SSLSocket
            tls12.use { socket ->
                socket.connect(InetSocketAddress(endpoint.address, endpoint.port), SOCKET_TIMEOUT_MILLIS)
                socket.enabledProtocols = arrayOf("TLSv1.2")
                socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                assertTrue(runCatching { socket.startHandshake() }.isFailure)
            }

            val wrongIdentity = serverIdentity.sslContext(serverIdentity.certificate, includeClientIdentity = true)
                .socketFactory.createSocket() as SSLSocket
            wrongIdentity.use { socket ->
                socket.connect(InetSocketAddress(endpoint.address, endpoint.port), SOCKET_TIMEOUT_MILLIS)
                socket.enabledProtocols = arrayOf("TLSv1.3")
                socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                runCatching { socket.startHandshake() }
                runCatching {
                    socket.outputStream.write(UPGRADE.toByteArray(Charsets.US_ASCII))
                    socket.outputStream.flush()
                }
                assertFalse(runCatching { readHeaders(socket.inputStream) }.getOrDefault("").startsWith("HTTP/1.1 101"))
            }

            Socket().use { plaintext ->
                plaintext.connect(InetSocketAddress(endpoint.address, endpoint.port), SOCKET_TIMEOUT_MILLIS)
                plaintext.soTimeout = SOCKET_TIMEOUT_MILLIS
                plaintext.outputStream.write(UPGRADE.toByteArray(Charsets.US_ASCII))
                plaintext.outputStream.flush()
                val firstByte = plaintext.inputStream.read()
                assertTrue(firstByte < 0 || firstByte != 'H'.code)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun onlyRpcWebSocketPathIsAccepted() {
        val server = newServer(serverIdentity.sslContext(clientIdentity.certificate, includeClientIdentity = true))
        val endpoint = server.start(BindMode.Loopback)
        try {
            val socket = clientIdentity.sslContext(serverIdentity.certificate, includeClientIdentity = true)
                .socketFactory.createSocket() as SSLSocket
            socket.use {
                it.connect(InetSocketAddress(endpoint.address, endpoint.port), SOCKET_TIMEOUT_MILLIS)
                it.enabledProtocols = arrayOf("TLSv1.3")
                it.soTimeout = SOCKET_TIMEOUT_MILLIS
                it.startHandshake()
                it.outputStream.write(UPGRADE.replace("GET /rpc", "GET /other").toByteArray(Charsets.US_ASCII))
                it.outputStream.flush()
                assertFalse(runCatching { readHeaders(it.inputStream) }.getOrDefault("").startsWith("HTTP/1.1 101"))
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun slowTlsHandshakeAndHttpUpgradeAreClosedWithinConfiguredDeadline() {
        val limits = RpcServerLimits(handshakeTimeoutMillis = 250, idleTimeoutMillis = 2_000)
        val server = newServer(
            serverIdentity.sslContext(clientIdentity.certificate, includeClientIdentity = true),
            limits,
        )
        val endpoint = server.start(BindMode.Loopback)
        try {
            Socket().use { slowTls ->
                slowTls.connect(InetSocketAddress(endpoint.address, endpoint.port), SOCKET_TIMEOUT_MILLIS)
                slowTls.soTimeout = SOCKET_TIMEOUT_MILLIS
                readUntilEof(slowTls.inputStream)
            }

            (clientIdentity.sslContext(serverIdentity.certificate, includeClientIdentity = true)
                .socketFactory.createSocket() as SSLSocket).use { slowUpgrade ->
                slowUpgrade.connect(InetSocketAddress(endpoint.address, endpoint.port), SOCKET_TIMEOUT_MILLIS)
                slowUpgrade.enabledProtocols = arrayOf("TLSv1.3")
                slowUpgrade.soTimeout = SOCKET_TIMEOUT_MILLIS
                slowUpgrade.startHandshake()
                slowUpgrade.outputStream.write("GET /rpc HTTP/1.1\r\n".toByteArray(Charsets.US_ASCII))
                slowUpgrade.outputStream.flush()
                readUntilEof(slowUpgrade.inputStream)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun tricklingHttpUpgradeCannotExtendTheAbsoluteUpgradeDeadline() {
        val server = newServer(
            serverIdentity.sslContext(clientIdentity.certificate, includeClientIdentity = true),
            RpcServerLimits(handshakeTimeoutMillis = 250, idleTimeoutMillis = 3_000),
        )
        val endpoint = server.start(BindMode.Loopback)
        try {
            (clientIdentity.sslContext(serverIdentity.certificate, includeClientIdentity = true)
                .socketFactory.createSocket() as SSLSocket).use { socket ->
                socket.connect(InetSocketAddress(endpoint.address, endpoint.port), SOCKET_TIMEOUT_MILLIS)
                socket.enabledProtocols = arrayOf("TLSv1.3")
                socket.soTimeout = 4_000
                socket.startHandshake()
                val startedAt = System.nanoTime()
                val trickle = Thread {
                    "GET /rpc HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n".forEach { character ->
                        runCatching {
                            socket.outputStream.write(character.code)
                            socket.outputStream.flush()
                            Thread.sleep(60)
                        }
                    }
                }.apply { start() }
                readUntilEof(socket.inputStream)
                val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                trickle.join(1_000)
                assertTrue("upgrade deadline took $elapsedMillis ms", elapsedMillis < 800)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun saturatedHandshakeExecutorImmediatelyClosesAdditionalAcceptedSocket() {
        val server = newServer(
            serverIdentity.sslContext(clientIdentity.certificate, includeClientIdentity = true),
            RpcServerLimits(maxConnections = 1, handshakeTimeoutMillis = 5_000),
        )
        val endpoint = server.start(BindMode.Loopback)
        try {
            (clientIdentity.sslContext(serverIdentity.certificate, includeClientIdentity = true)
                .socketFactory.createSocket() as SSLSocket).use { first ->
                first.connect(InetSocketAddress(endpoint.address, endpoint.port), SOCKET_TIMEOUT_MILLIS)
                first.enabledProtocols = arrayOf("TLSv1.3")
                first.soTimeout = SOCKET_TIMEOUT_MILLIS
                first.startHandshake()
                first.outputStream.write(UPGRADE.toByteArray(Charsets.US_ASCII))
                first.outputStream.flush()
                assertTrue(readHeaders(first.inputStream).startsWith("HTTP/1.1 101"))
                Socket().use { second ->
                    second.connect(InetSocketAddress(endpoint.address, endpoint.port), SOCKET_TIMEOUT_MILLIS)
                    second.soTimeout = SOCKET_TIMEOUT_MILLIS
                    assertEquals(-1, second.inputStream.read())
                }
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun idleWebSocketSessionClosesAfterConfiguredTimeout() {
        val server = newServer(
            serverIdentity.sslContext(clientIdentity.certificate, includeClientIdentity = true),
            RpcServerLimits(idleTimeoutMillis = 250),
        )
        val endpoint = server.start(BindMode.Loopback)
        try {
            (clientIdentity.sslContext(serverIdentity.certificate, includeClientIdentity = true)
                .socketFactory.createSocket() as SSLSocket).use { socket ->
                socket.connect(InetSocketAddress(endpoint.address, endpoint.port), SOCKET_TIMEOUT_MILLIS)
                socket.enabledProtocols = arrayOf("TLSv1.3")
                socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                socket.startHandshake()
                socket.outputStream.write(UPGRADE.toByteArray(Charsets.US_ASCII))
                socket.outputStream.flush()
                assertTrue(readHeaders(socket.inputStream).startsWith("HTTP/1.1 101"))
                readUntilEof(socket.inputStream)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun revocationBeforeResponseCommitSuppressesResponseAndFutureBusinessCallsRequirePairing() {
        val manager = newPairingManager()
        val challenge = manager.begin()
        manager.submit(challenge.code, HOST_ID, "Test Host", clientIdentity.certificate)
        manager.approve(HOST_ID)
        val dispatchEntered = CountDownLatch(1)
        val resumeDispatch = CountDownLatch(1)
        val server = newServer(
            serverIdentity.sslContext(clientIdentity.certificate, includeClientIdentity = true),
            manager = manager,
            businessDispatcher = { request, _ ->
                dispatchEntered.countDown()
                check(resumeDispatch.await(SOCKET_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS))
                RpcResponse(PROTOCOL_VERSION, request.requestId, result = JsonPrimitive("must-not-commit"))
            },
        )
        val endpoint = server.start(BindMode.Loopback)
        try {
            val first = connectWebSocket(endpoint)
            first.outputStream.write(maskedTextFrame(RpcProtocol.serialize(
                RpcRequest(PROTOCOL_VERSION, REQUEST_ID, "session.start", JsonObject(emptyMap())),
            )))
            first.outputStream.flush()
            assertTrue(dispatchEntered.await(SOCKET_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS))

            manager.revoke(HOST_ID)
            resumeDispatch.countDown()
            val postRevokeFrame = runCatching { readServerText(first.inputStream) }.getOrNull()
            assertEquals(null, postRevokeFrame)
            first.close()

            connectWebSocket(endpoint).use { nextConnection ->
                nextConnection.outputStream.write(maskedTextFrame(RpcProtocol.serialize(
                    RpcRequest(PROTOCOL_VERSION, REQUEST_ID, "session.start", JsonObject(emptyMap())),
                )))
                nextConnection.outputStream.flush()
                val response = RpcProtocol.parseResponse(readServerText(nextConnection.inputStream))
                assertTrue(response is RpcParseResult.Success)
                assertEquals(
                    RpcErrorCode.PAIRING_REQUIRED,
                    (response as RpcParseResult.Success).value.error?.code,
                )
            }
        } finally {
            resumeDispatch.countDown()
            server.close()
        }
    }

    @Test
    fun maximumInflightRequestsClosesConnectionWhenBoundIsExceeded() {
        val manager = newPairingManager()
        val challenge = manager.begin()
        manager.submit(challenge.code, HOST_ID, "Test Host", clientIdentity.certificate)
        manager.approve(HOST_ID)
        val dispatchEntered = CountDownLatch(1)
        val resumeDispatch = CountDownLatch(1)
        val server = newServer(
            serverIdentity.sslContext(clientIdentity.certificate, includeClientIdentity = true),
            limits = RpcServerLimits(maxInflightPerConnection = 1, idleTimeoutMillis = 2_000),
            manager = manager,
            businessDispatcher = { request, _ ->
                dispatchEntered.countDown()
                check(resumeDispatch.await(SOCKET_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS))
                RpcResponse(PROTOCOL_VERSION, request.requestId)
            },
        )
        val endpoint = server.start(BindMode.Loopback)
        try {
            connectWebSocket(endpoint).use { socket ->
                val request = RpcProtocol.serialize(
                    RpcRequest(PROTOCOL_VERSION, REQUEST_ID, "session.start", JsonObject(emptyMap())),
                )
                socket.outputStream.write(maskedTextFrame(request))
                socket.outputStream.flush()
                assertTrue(dispatchEntered.await(SOCKET_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS))
                socket.outputStream.write(maskedTextFrame(request))
                socket.outputStream.flush()
                assertEquals(0x88, socket.inputStream.read())
            }
        } finally {
            resumeDispatch.countDown()
            server.close()
        }
    }

    @Test
    fun saturatedRequestWorkerQueueClosesConnectionInsteadOfQueueingUnboundedWork() {
        val manager = newPairingManager()
        val challenge = manager.begin()
        manager.submit(challenge.code, HOST_ID, "Test Host", clientIdentity.certificate)
        manager.approve(HOST_ID)
        val dispatchEntered = CountDownLatch(1)
        val resumeDispatch = CountDownLatch(1)
        val server = newServer(
            serverIdentity.sslContext(clientIdentity.certificate, includeClientIdentity = true),
            limits = RpcServerLimits(requestWorkers = 1, requestQueueCapacity = 1, maxInflightPerConnection = 8),
            manager = manager,
            businessDispatcher = { request, _ ->
                dispatchEntered.countDown()
                check(resumeDispatch.await(SOCKET_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS))
                RpcResponse(PROTOCOL_VERSION, request.requestId)
            },
        )
        val endpoint = server.start(BindMode.Loopback)
        try {
            connectWebSocket(endpoint).use { socket ->
                val text = RpcProtocol.serialize(
                    RpcRequest(PROTOCOL_VERSION, REQUEST_ID, "session.start", JsonObject(emptyMap())),
                )
                repeat(3) {
                    socket.outputStream.write(maskedTextFrame(text))
                    socket.outputStream.flush()
                    if (it == 0) assertTrue(dispatchEntered.await(SOCKET_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS))
                }
                readUntilEof(socket.inputStream)
            }
        } finally {
            resumeDispatch.countDown()
            server.close()
        }
    }

    private fun newServer(
        context: SSLContext,
        limits: RpcServerLimits = RpcServerLimits(idleTimeoutMillis = 2_000),
        manager: PairingManager = newPairingManager(),
        businessDispatcher: (RpcRequest, SessionIdentity) -> RpcResponse = { request, _ ->
            RpcResponse(PROTOCOL_VERSION, request.requestId)
        },
        sessionChanged: (ControllerLease, HostRecord?) -> Unit = { _, _ -> },
        sessionStopRequested: () -> Unit = {},
    ): RpcServer {
        val handler = CompanionRpcHandler(
            manager,
            { JsonPrimitive("ok") },
            businessDispatcher = businessDispatcher,
        )
        return RpcServer(context, manager, handler, limits, sessionChanged, sessionStopRequested)
    }

    private fun newPairingManager(): PairingManager {
        val store = object : PairingStore {
            private var records = emptyList<HostRecord>()
            override fun load(): List<HostRecord> = records
            override fun save(records: List<HostRecord>) {
                this.records = records.toList()
            }
        }
        return PairingManager(store)
    }

    private fun connectWebSocket(endpoint: RpcEndpoint): SSLSocket =
        (clientIdentity.sslContext(serverIdentity.certificate, includeClientIdentity = true)
            .socketFactory.createSocket() as SSLSocket).apply {
            connect(InetSocketAddress(endpoint.address, endpoint.port), SOCKET_TIMEOUT_MILLIS)
            enabledProtocols = arrayOf("TLSv1.3")
            soTimeout = SOCKET_TIMEOUT_MILLIS
            startHandshake()
            outputStream.write(UPGRADE.toByteArray(Charsets.US_ASCII))
            outputStream.flush()
            check(readHeaders(inputStream).startsWith("HTTP/1.1 101"))
        }

    private fun readHeaders(input: InputStream): String {
        val output = ByteArrayOutputStream()
        var state = 0
        while (state < 4) {
            val byte = input.read()
            if (byte < 0) break
            output.write(byte)
            state = when {
                state == 0 && byte == 13 -> 1
                state == 1 && byte == 10 -> 2
                state == 2 && byte == 13 -> 3
                state == 3 && byte == 10 -> 4
                byte == 13 -> 1
                else -> 0
            }
            check(output.size() <= 16 * 1024)
        }
        return output.toString(Charsets.US_ASCII.name())
    }

    private fun maskedTextFrame(text: String): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.size < 126)
        val mask = byteArrayOf(1, 2, 3, 4)
        return byteArrayOf(0x81.toByte(), (0x80 or payload.size).toByte()) + mask +
            ByteArray(payload.size) { index -> (payload[index].toInt() xor mask[index % 4].toInt()).toByte() }
    }

    private fun readServerText(input: InputStream): String {
        val first = input.read()
        val second = input.read()
        check(first == 0x81 && second >= 0 && second and 0x80 == 0)
        val marker = second and 0x7f
        val length = when (marker) {
            126 -> (input.read().also { check(it >= 0) } shl 8) or input.read().also { check(it >= 0) }
            127 -> error("test RPC response exceeded 65535 bytes")
            else -> marker
        }
        return ByteArray(length) { input.read().also { check(it >= 0) }.toByte() }
            .toString(Charsets.UTF_8)
    }

    private fun readServerClose(input: InputStream) {
        val first = input.read()
        val second = input.read()
        assertEquals(0x88, first)
        assertTrue(second >= 0 && second and 0x80 == 0)
        repeat(second and 0x7f) { assertTrue(input.read() >= 0) }
    }

    private fun readUntilEof(input: InputStream): Int {
        val buffer = ByteArray(1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            check(total <= 64 * 1024) { "unexpectedly large pre-upgrade data" }
        }
    }

    private companion object {
        const val SOCKET_TIMEOUT_MILLIS = 5_000
        const val HOST_ID = "11111111-1111-4111-8111-111111111111"
        const val REQUEST_ID = "00000000-0000-4000-8000-000000000000"
        val UPGRADE = "GET /rpc HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"
    }
}
