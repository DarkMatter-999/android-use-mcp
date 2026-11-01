package darkmatter.androiduse.app.network

import darkmatter.androiduse.app.pairing.ControllerLease
import darkmatter.androiduse.app.pairing.PairingManager
import darkmatter.androiduse.app.pairing.HostRecord
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

sealed class BindMode {
    data object Loopback : BindMode()

    data class Wifi(val address: InetAddress) : BindMode() {
        init {
            require(address is Inet4Address) { "Wi-Fi listener requires a concrete IPv4 address" }
            require(!address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isMulticastAddress) {
                "Wi-Fi listener must bind one concrete unicast interface address"
            }
        }
    }
}

data class RpcEndpoint(val address: InetAddress, val port: Int, val bindMode: BindMode)

data class RpcServerLimits(
    val maxConnections: Int = 8,
    val requestWorkers: Int = 8,
    val requestQueueCapacity: Int = 128,
    val handshakeTimeoutMillis: Int = 10_000,
    val writeTimeoutMillis: Int = 10_000,
    val idleTimeoutMillis: Int = 60_000,
    val maxInflightPerConnection: Int = 32,
) {
    init {
        require(maxConnections > 0)
        require(requestWorkers > 0)
        require(requestQueueCapacity > 0)
        require(handshakeTimeoutMillis > 0)
        require(writeTimeoutMillis > 0)
        require(idleTimeoutMillis > 0)
        require(maxInflightPerConnection > 0)
    }
}

/** Bounded TLS 1.3 WebSocket listener for the versioned RPC protocol. */
class RpcServer(
    private val sslContext: SSLContext,
    private val pairingManager: PairingManager,
    private val handler: CompanionRpcHandler,
    private val limits: RpcServerLimits = RpcServerLimits(),
) : AutoCloseable {
    @Volatile
    var endpoint: RpcEndpoint? = null
        private set

    private val lifecycle = Any()
    private val running = AtomicInteger(0)
    private val sockets = ConcurrentHashMap.newKeySet<SSLSocket>()
    private val leases = ConcurrentHashMap<SSLSocket, AtomicReference<ControllerLease?>>()
    private var listener: SSLServerSocket? = null
    private var connectionPool: ThreadPoolExecutor? = null
    private var requestPool: ThreadPoolExecutor? = null
    private var writeTimeouts: ScheduledExecutorService? = null
    private val revocationListener: (HostRecord) -> Unit = { revoked ->
        sockets.toList().filter { socket ->
            runCatching {
                val cert = socket.session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate
                cert?.fingerprintSha256() == revoked.fingerprintSha256
            }.getOrDefault(false)
        }.forEach { runCatching { it.close() } }
    }

    init {
        pairingManager.addRevocationListener(revocationListener)
    }

    fun start(bindMode: BindMode, port: Int = 0): RpcEndpoint = synchronized(lifecycle) {
        check(endpoint == null) { "RPC server is already running" }
        require(port in 0..65535)
        val address = when (bindMode) {
            BindMode.Loopback -> InetAddress.getByName("127.0.0.1")
            is BindMode.Wifi -> bindMode.address
        }
        val serverSocket = sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
        try {
            serverSocket.bind(InetSocketAddress(address, port), limits.maxConnections)
            DeviceTls.configure(serverSocket)
        } catch (exception: Exception) {
            runCatching { serverSocket.close() }
            throw exception
        }

        listener = serverSocket
        connectionPool = ThreadPoolExecutor(
            limits.maxConnections,
            limits.maxConnections,
            0L,
            TimeUnit.MILLISECONDS,
            SynchronousQueue(),
            namedThreadFactory("rpc-connection"),
            ThreadPoolExecutor.AbortPolicy(),
        )
        requestPool = ThreadPoolExecutor(
            limits.requestWorkers,
            limits.requestWorkers,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(limits.requestQueueCapacity),
            namedThreadFactory("rpc-request"),
            ThreadPoolExecutor.AbortPolicy(),
        )
        writeTimeouts = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            namedThreadFactory("rpc-write-timeout"),
        )
        running.set(1)
        val published = RpcEndpoint(serverSocket.inetAddress, serverSocket.localPort, bindMode)
        endpoint = published
        Thread({ acceptLoop(serverSocket) }, "rpc-accept").apply {
            isDaemon = true
            start()
        }
        published
    }

    fun stop() {
        val currentListener: SSLServerSocket?
        synchronized(lifecycle) {
            running.set(0)
            endpoint = null
            currentListener = listener
            listener = null
        }
        runCatching { currentListener?.close() }
        sockets.toList().forEach { runCatching { it.close() } }
        connectionPool?.shutdownNow()
        requestPool?.shutdownNow()
        writeTimeouts?.shutdownNow()
        connectionPool = null
        requestPool = null
        writeTimeouts = null
        leases.clear()
    }

    override fun close() {
        stop()
        pairingManager.removeRevocationListener(revocationListener)
    }

    private fun acceptLoop(serverSocket: SSLServerSocket) {
        while (running.get() == 1) {
            val socket = try {
                serverSocket.accept() as SSLSocket
            } catch (_: Exception) {
                if (running.get() == 1) continue else break
            }
            sockets += socket
            try {
                connectionPool?.execute { serve(socket) } ?: socket.close()
            } catch (_: RejectedExecutionException) {
                sockets -= socket
                runCatching { socket.close() }
            }
        }
    }

    private fun serve(socket: SSLSocket) {
        var lease: ControllerLease? = null
        try {
            socket.soTimeout = limits.handshakeTimeoutMillis
            socket.startHandshake()
            val peer = socket.session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate
                ?: throw java.security.cert.CertificateException("missing peer certificate")
            val session = SessionIdentity(peer)
            val input = java.io.BufferedInputStream(socket.inputStream)
            val output = socket.outputStream
            val upgrade = WebSocketFrames.readUpgradeRequest(input, MAX_UPGRADE_HEADER_BYTES)
            if (upgrade.path != RPC_PATH) throw WebSocketProtocolException("unknown WebSocket endpoint", 1008)
            WebSocketFrames.writeUpgradeResponse(output, upgrade.webSocketKey)
            socket.soTimeout = limits.idleTimeoutMillis

            val leaseRef = AtomicReference<ControllerLease?>(null)
            leases[socket] = leaseRef
            val writeLock = Any()
            val inflight = AtomicInteger(0)
            while (running.get() == 1 && !socket.isClosed) {
                val message = WebSocketFrames.readMessage(input, MAX_FRAME_BYTES) { control ->
                    if (control is WebSocketMessage.Ping) {
                        synchronized(writeLock) {
                            withWriteTimeout(socket) { WebSocketFrames.writePong(output, control.payload) }
                        }
                    }
                } ?: break
                when (message) {
                    is WebSocketMessage.Close -> {
                        synchronized(writeLock) {
                            withWriteTimeout(socket) {
                                if (message.code != null) WebSocketFrames.writeClose(output, message.code, message.reason)
                                else WebSocketFrames.writeClose(output, 1000)
                            }
                        }
                        break
                    }
                    is WebSocketMessage.Text -> dispatch(
                        socket, output, writeLock, inflight, leaseRef, session, message.value,
                    )
                    is WebSocketMessage.Ping, is WebSocketMessage.Pong -> Unit
                }
            }
            lease = leaseRef.get()
        } catch (exception: WebSocketProtocolException) {
            runCatching {
                WebSocketFrames.writeClose(socket.outputStream, exception.closeCode, "protocol error")
            }
        } catch (_: Exception) {
            // The connection is closed on TLS, framing, timeout, or I/O errors; no sensitive data is logged.
        } finally {
            val connectionLease = lease ?: leases[socket]?.get()
            if (connectionLease != null) pairingManager.releaseController(connectionLease.leaseId)
            leases.remove(socket)
            sockets -= socket
            runCatching { socket.close() }
        }
    }

    private fun dispatch(
        socket: SSLSocket,
        output: java.io.OutputStream,
        writeLock: Any,
        inflight: AtomicInteger,
        leaseRef: AtomicReference<ControllerLease?>,
        session: SessionIdentity,
        text: String,
    ) {
        val parsed = RpcProtocol.parseRequest(text)
        val request = (parsed as? RpcParseResult.Success)?.value ?: run {
            runCatching { WebSocketFrames.writeClose(socket.outputStream, 1002, "invalid RPC request") }
            socket.close()
            return
        }
        if (inflight.incrementAndGet() > limits.maxInflightPerConnection) {
            inflight.decrementAndGet()
            runCatching { WebSocketFrames.writeClose(output, 1013, "too many requests") }
            socket.close()
            return
        }
        try {
            requestPool?.execute {
                try {
                    val business = request.method !in PRE_APPROVAL_METHODS
                    val approved = pairingManager.isApproved(session.fingerprintSha256)
                    var lease = leaseRef.get()
                    if (business && approved && lease == null) {
                        val acquired = pairingManager.acquireController(session.fingerprintSha256)
                        if (acquired != null) {
                            if (leaseRef.compareAndSet(null, acquired)) lease = acquired
                            else {
                                pairingManager.releaseController(acquired.leaseId)
                                lease = leaseRef.get()
                            }
                        }
                    }
                    val response = if (business && approved && lease == null) {
                        RpcResponse(
                            PROTOCOL_VERSION,
                            request.requestId,
                            error = RpcError(RpcErrorCode.PAIRING_REQUIRED, "another controller is active"),
                        )
                    } else {
                        handler.handle(request, session)
                    }
                    if (business && lease != null && !pairingManager.isControllerLeaseActive(lease)) return@execute
                    val serialized = RpcProtocol.serialize(response)
                    synchronized(writeLock) {
                        if (business && lease != null && !pairingManager.isControllerLeaseActive(lease)) return@synchronized
                        withWriteTimeout(socket) { WebSocketFrames.writeText(output, serialized) }
                    }
                } catch (_: Exception) {
                    runCatching { socket.close() }
                } finally {
                    inflight.decrementAndGet()
                }
            } ?: throw RejectedExecutionException("RPC request executor is unavailable")
        } catch (_: RejectedExecutionException) {
            inflight.decrementAndGet()
            runCatching { socket.close() }
        }
    }

    private fun withWriteTimeout(socket: Socket, write: () -> Unit) {
        val scheduler = writeTimeouts ?: throw IllegalStateException("write timeout executor is unavailable")
        SocketWriteDeadline.run(socket, scheduler, limits.writeTimeoutMillis, write)
    }

    private fun namedThreadFactory(prefix: String): ThreadFactory {
        val sequence = AtomicInteger()
        return ThreadFactory { task ->
            Thread(task, "$prefix-${sequence.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    companion object {
        private const val MAX_UPGRADE_HEADER_BYTES = 16 * 1024
        private const val RPC_PATH = "/rpc"
        private val PRE_APPROVAL_METHODS = setOf("device.status", "pairing.begin")
    }
}

private fun java.security.cert.X509Certificate.fingerprintSha256(): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(encoded)
        .joinToString("") { "%02X".format(it) }
