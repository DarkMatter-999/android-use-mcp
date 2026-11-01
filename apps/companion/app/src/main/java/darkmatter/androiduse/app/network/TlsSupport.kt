package darkmatter.androiduse.app.network

import darkmatter.androiduse.app.security.DeviceCertificate
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import java.net.Socket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/** TLS configuration that retains private-key operations inside Android Keystore. */
object DeviceTls {
    fun serverContext(identity: DeviceCertificate): SSLContext {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val privateKey = keyStore.getKey(identity.keystoreAlias, null) as? PrivateKey
            ?: throw IllegalStateException("Android Keystore device private key is unavailable")
        val certificate = keyStore.getCertificate(identity.keystoreAlias) as? X509Certificate
            ?: throw IllegalStateException("Android Keystore device certificate is unavailable")
        if (certificate != identity.certificate) {
            throw IllegalStateException("Android Keystore device identity changed; pairing is required")
        }
        return SSLContext.getInstance("TLSv1.3").apply {
            init(
                arrayOf(DeviceKeyManager(identity.keystoreAlias, privateKey, certificate)),
                arrayOf(SelfSignedClientTrustManager()),
                SecureRandom(),
            )
        }
    }

    fun configure(server: SSLServerSocket) {
        check("TLSv1.3" in server.supportedProtocols) { "TLS 1.3 is unavailable" }
        server.enabledProtocols = arrayOf("TLSv1.3")
        server.needClientAuth = true
        check(server.needClientAuth && !server.wantClientAuth) {
            "TLS server must require a client certificate"
        }
    }

    private class DeviceKeyManager(
        private val alias: String,
        private val key: PrivateKey,
        private val certificate: X509Certificate,
    ) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(alias)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: java.net.Socket?): String = alias
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(alias)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: java.net.Socket?): String = alias
        override fun getCertificateChain(alias: String?): Array<X509Certificate> =
            if (alias == this.alias) arrayOf(certificate) else emptyArray()
        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == this.alias) key else null
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: javax.net.ssl.SSLEngine?): String = alias
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: javax.net.ssl.SSLEngine?): String = alias
    }

    private class SelfSignedClientTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificate = chain?.singleOrNull()
                ?: throw CertificateException("expected one self-signed client certificate")
            try {
                certificate.checkValidity(Date())
                certificate.verify(certificate.publicKey)
            } catch (exception: Exception) {
                throw CertificateException("invalid self-signed client certificate", exception)
            }
            if (certificate.keyUsage?.getOrNull(0) != true) {
                throw CertificateException("client certificate must allow digital signatures")
            }
            if (certificate.extendedKeyUsage?.contains(CLIENT_AUTH_EKU) != true) {
                throw CertificateException("client certificate lacks clientAuth EKU")
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("server trust validation is not used by this listener")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val CLIENT_AUTH_EKU = "1.3.6.1.5.5.7.3.2"
}

/** Closes a socket when a blocking write exceeds its deadline. */
internal object SocketWriteDeadline {
    fun run(
        socket: Socket,
        scheduler: ScheduledExecutorService,
        timeoutMillis: Int,
        write: () -> Unit,
    ) {
        require(timeoutMillis > 0)
        val timeout = scheduler.schedule(
            { runCatching { socket.close() } },
            timeoutMillis.toLong(),
            TimeUnit.MILLISECONDS,
        )
        try {
            write()
        } finally {
            timeout.cancel(false)
        }
    }
}
