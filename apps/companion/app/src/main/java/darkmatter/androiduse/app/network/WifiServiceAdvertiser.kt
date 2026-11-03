package darkmatter.androiduse.app.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.atomic.AtomicBoolean

interface WifiServiceAdvertiser {
    fun advertise(endpoint: RpcEndpoint)
    fun stop()
}

internal interface NsdServiceRegistrar {
    interface Listener {
        fun onRegistered()
        fun onFailure(errorCode: Int)
    }

    fun register(
        serviceName: String,
        serviceType: String,
        port: Int,
        attributes: Map<String, String>,
        listener: Listener,
    ): AutoCloseable
}

/** Publishes only the active Wi-Fi RPC endpoint; discovery is not an authorization mechanism. */
class NsdWifiServiceAdvertiser internal constructor(
    private val registrar: NsdServiceRegistrar,
    private val onError: (String) -> Unit,
) : WifiServiceAdvertiser {
    private var generation = 0L
    private var registration: AutoCloseable? = null

    constructor(context: Context, onError: (String) -> Unit = {}) : this(
        AndroidNsdServiceRegistrar(context.getSystemService(NsdManager::class.java)),
        onError,
    )

    @Synchronized
    override fun advertise(endpoint: RpcEndpoint) {
        require(endpoint.bindMode is BindMode.Wifi) { "DNS-SD can advertise only a Wi-Fi endpoint" }
        stop()
        val token = ++generation
        registration = registrar.register(
            serviceName = "Android Use",
            serviceType = SERVICE_TYPE,
            port = endpoint.port,
            attributes = mapOf("path" to RPC_PATH),
            listener = object : NsdServiceRegistrar.Listener {
                override fun onRegistered() = Unit

                override fun onFailure(errorCode: Int) {
                    val report = synchronized(this@NsdWifiServiceAdvertiser) {
                        if (generation != token) return
                        runCatching { registration?.close() }
                        registration = null
                        generation++
                        true
                    }
                    if (report) onError("Wi-Fi discovery registration failed ($errorCode)")
                }
            },
        )
    }

    @Synchronized
    override fun stop() {
        generation++
        runCatching { registration?.close() }
        registration = null
    }

    private companion object {
        const val SERVICE_TYPE = "_androiduse._tcp."
        const val RPC_PATH = "/rpc"
    }
}

private class AndroidNsdServiceRegistrar(private val manager: NsdManager) : NsdServiceRegistrar {
    override fun register(
        serviceName: String,
        serviceType: String,
        port: Int,
        attributes: Map<String, String>,
        listener: NsdServiceRegistrar.Listener,
    ): AutoCloseable {
        val service = NsdServiceInfo().apply {
            setServiceName(serviceName)
            setServiceType(serviceType)
            setPort(port)
            attributes.forEach { (key, value) ->
                setAttribute(key, value.toByteArray(Charsets.UTF_8))
            }
        }
        val closed = AtomicBoolean(false)
        val stateLock = Any()
        var registered = false
        lateinit var platformListener: NsdManager.RegistrationListener
        platformListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                val unregisterNow = synchronized(stateLock) {
                    if (closed.get()) true else {
                        registered = true
                        false
                    }
                }
                if (unregisterNow) runCatching { manager.unregisterService(platformListener) }
                else listener.onRegistered()
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                listener.onFailure(errorCode)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, platformListener)
        return AutoCloseable {
            val unregisterNow = synchronized(stateLock) {
                if (!closed.compareAndSet(false, true)) false else registered.also { registered = false }
            }
            if (unregisterNow) runCatching { manager.unregisterService(platformListener) }
        }
    }
}
