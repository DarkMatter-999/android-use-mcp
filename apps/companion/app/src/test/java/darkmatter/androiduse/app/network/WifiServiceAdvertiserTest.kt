package darkmatter.androiduse.app.network

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiServiceAdvertiserTest {
    @Test
    fun `advertises the wifi endpoint metadata and unregisters idempotently`() {
        val registrar = FakeNsdRegistrar()
        val advertiser = NsdWifiServiceAdvertiser(registrar) { }

        advertiser.advertise(endpoint())
        val registration = registrar.registrations.single()

        assertEquals("_androiduse._tcp", registration.serviceType.removeSuffix("."))
        assertEquals(43419, registration.port)
        assertEquals(mapOf("path" to "/rpc"), registration.attributes)
        registration.listener.onRegistered()
        advertiser.stop()
        advertiser.stop()
        assertEquals(1, registration.unregisterCount)
    }

    @Test
    fun `asynchronous registration failure is surfaced and withdraws failed record`() {
        val registrar = FakeNsdRegistrar()
        val errors = mutableListOf<String>()
        val advertiser = NsdWifiServiceAdvertiser(registrar, errors::add)
        advertiser.advertise(endpoint())
        val registration = registrar.registrations.single()

        registration.listener.onFailure(2)
        advertiser.stop()

        assertEquals(listOf("Wi-Fi discovery registration failed (2)"), errors)
        assertEquals(1, registration.unregisterCount)
    }

    @Test
    fun `replacing endpoint withdraws previous registration before publishing new one`() {
        val registrar = FakeNsdRegistrar()
        val advertiser = NsdWifiServiceAdvertiser(registrar) { }

        advertiser.advertise(endpoint(43419))
        val old = registrar.registrations.single()
        old.listener.onRegistered()
        advertiser.advertise(endpoint(55000))

        assertEquals(1, old.unregisterCount)
        assertEquals(55000, registrar.registrations.last().port)
        assertTrue(registrar.registrations.size == 2)
    }

    private fun endpoint(port: Int = 43419) = RpcEndpoint(
        address = InetAddress.getByName("192.168.1.20"),
        port = port,
        bindMode = BindMode.Wifi(InetAddress.getByName("192.168.1.20")),
    )

    private class FakeNsdRegistrar : NsdServiceRegistrar {
        val registrations = mutableListOf<FakeRegistration>()

        override fun register(
            serviceName: String,
            serviceType: String,
            port: Int,
            attributes: Map<String, String>,
            listener: NsdServiceRegistrar.Listener,
        ): AutoCloseable {
            val registration = FakeRegistration(serviceName, serviceType, port, attributes, listener)
            registrations += registration
            return AutoCloseable { registration.unregisterCount++ }
        }
    }

    private data class FakeRegistration(
        val serviceName: String,
        val serviceType: String,
        val port: Int,
        val attributes: Map<String, String>,
        val listener: NsdServiceRegistrar.Listener,
        var unregisterCount: Int = 0,
    )
}
