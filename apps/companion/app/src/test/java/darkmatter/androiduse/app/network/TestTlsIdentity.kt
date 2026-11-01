package darkmatter.androiduse.app.network

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal data class TestTlsIdentity(val certificate: X509Certificate, val privateKey: PrivateKey) {
    fun sslContext(trustedPeer: X509Certificate, includeClientIdentity: Boolean): SSLContext {
        val keyManagers = if (!includeClientIdentity) null else {
            val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, TEST_PASSWORD) }
            keyStore.setKeyEntry("test", privateKey, TEST_PASSWORD, arrayOf(certificate))
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, TEST_PASSWORD)
            }.keyManagers
        }
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain)
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(trustedPeer)

            private fun verify(chain: Array<out X509Certificate>?) {
                val peer = chain?.singleOrNull() ?: throw java.security.cert.CertificateException("expected one leaf")
                peer.checkValidity()
                peer.verify(peer.publicKey)
                if (peer != trustedPeer) throw java.security.cert.CertificateException("unexpected peer certificate")
            }
        }
        return SSLContext.getInstance("TLSv1.3").apply {
            init(keyManagers, arrayOf(trustManager), SecureRandom())
        }
    }

    companion object {
        private val TEST_PASSWORD = "fixture-only".toCharArray()

        fun load(resources: Class<*>, prefix: String): TestTlsIdentity {
            val base = "/darkmatter/androiduse/app/network/$prefix"
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(resources.getResourceAsStream("$base-cert.pem")!!)
                as X509Certificate
            val keyText = resources.getResourceAsStream("$base-key.pem")!!.bufferedReader().use { it.readText() }
            val keyBytes = Base64.getMimeDecoder().decode(
                keyText.lineSequence().filterNot { it.startsWith("-----") }.joinToString(""),
            )
            val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            return TestTlsIdentity(certificate, privateKey)
        }
    }
}
