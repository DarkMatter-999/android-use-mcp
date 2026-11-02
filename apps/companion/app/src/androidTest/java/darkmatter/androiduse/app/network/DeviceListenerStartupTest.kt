package darkmatter.androiduse.app.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import darkmatter.androiduse.app.security.DeviceIdentity
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.ssl.SSLServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceListenerStartupTest {
    @Test
    fun productionDeviceIdentityCanStartConfiguredLoopbackListener() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = DeviceIdentity.getOrCreate(context)
        val server = DeviceTls.serverContext(identity).serverSocketFactory.createServerSocket() as SSLServerSocket

        server.use {
            DeviceTls.configure(it)
            it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))

            assertEquals("127.0.0.1", it.inetAddress.hostAddress)
            assertTrue(it.localPort in 1..65535)
            assertTrue(it.needClientAuth)
            assertEquals(listOf("TLSv1.3"), it.enabledProtocols.toList())
        }
    }
}
