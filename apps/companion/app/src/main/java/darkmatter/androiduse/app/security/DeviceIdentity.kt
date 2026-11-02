package darkmatter.androiduse.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.UUID
import javax.security.auth.x500.X500Principal

/** Public certificate metadata for the installation's non-exportable Keystore identity. */
class DeviceCertificate internal constructor(
    val certificate: X509Certificate,
    val certificatePem: String,
    val fingerprintSha256: String,
    val keystoreAlias: String,
)

/** Creates or reuses one self-signed TLS server identity in Android Keystore. */
class DeviceIdentity(
    private val backend: Backend,
    private val alias: String = DEFAULT_ALIAS,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    interface Backend {
        fun loadCertificate(alias: String): X509Certificate?
        fun hasUsablePrivateKey(alias: String): Boolean
        fun create(alias: String): X509Certificate
        fun delete(alias: String)
    }

    fun getOrCreate(): DeviceCertificate {
        val existing = backend.loadCertificate(alias)
        val certificate = if (existing != null && isValid(existing) && backend.hasUsablePrivateKey(alias)) {
            existing
        } else {
            backend.delete(alias)
            backend.create(alias).also { created ->
                require(isValid(created) && backend.hasUsablePrivateKey(alias)) {
                    "Android Keystore created an invalid or unusable device identity"
                }
            }
        }
        return DeviceCertificate(
            certificate = certificate,
            certificatePem = certificate.toPem(),
            fingerprintSha256 = certificate.fingerprintSha256(),
            keystoreAlias = alias,
        )
    }

    private fun isValid(certificate: X509Certificate): Boolean = runCatching {
        val now = Date(clockMillis())
        certificate.checkValidity(now)
        certificate.verify(certificate.publicKey)
        require(certificate.publicKey.algorithm.equals("RSA", ignoreCase = true))
        require(certificate.publicKey.encoded.size >= MIN_RSA_PUBLIC_KEY_BYTES)
        require(hasDigitalSignatureKeyUsage(certificate.keyUsage))
        val validityMillis = certificate.notAfter.time - certificate.notBefore.time
        require(validityMillis in 1..MAX_CERTIFICATE_VALIDITY_MILLIS)
    }.isSuccess

    companion object {
        private const val DEFAULT_ALIAS = "android-use-device-tls"
        private const val KEY_USAGE_DIGITAL_SIGNATURE = 0
        private const val MIN_RSA_PUBLIC_KEY_BYTES = 256
        private const val MAX_CERTIFICATE_VALIDITY_MILLIS = 10L * 365 * 24 * 60 * 60 * 1000

        /** An absent X.509 keyUsage extension is unrestricted; an explicit one must permit signatures. */
        internal fun hasDigitalSignatureKeyUsage(keyUsage: BooleanArray?): Boolean =
            keyUsage == null || keyUsage.getOrNull(KEY_USAGE_DIGITAL_SIGNATURE) == true

        /** Production Android Keystore entry point; no private-key material is returned. */
        fun getOrCreate(context: Context): DeviceCertificate {
            val alias = "android-use-device-tls-${context.applicationContext.packageName}"
            return DeviceIdentity(AndroidKeyStoreBackend(), alias).getOrCreate()
        }

        fun fingerprintSha256(certificate: X509Certificate): String = certificate.fingerprintSha256()

        fun certificatePem(certificate: X509Certificate): String = certificate.toPem()
    }
}

private class AndroidKeyStoreBackend : DeviceIdentity.Backend {
    override fun loadCertificate(alias: String): X509Certificate? {
        val keyStore = keyStore()
        return runCatching { keyStore.getCertificate(alias) as? X509Certificate }.getOrNull()
    }

    override fun create(alias: String): X509Certificate {
        val now = System.currentTimeMillis()
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            ANDROID_KEY_STORE,
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384)
                .setSignaturePaddings(
                    KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                    KeyProperties.SIGNATURE_PADDING_RSA_PSS,
                )
                .setCertificateSubject(X500Principal("CN=AndroidUse Device"))
                .setCertificateSerialNumber(java.math.BigInteger(UUID.randomUUID().toString().replace("-", ""), 16))
                .setCertificateNotBefore(Date(now - 60_000))
                .setCertificateNotAfter(Date(now + MAX_CERTIFICATE_VALIDITY_MILLIS - 60_000))
                .build(),
        )
        generator.generateKeyPair()
        return keyStore().getCertificate(alias) as? X509Certificate
            ?: error("Android Keystore did not return the generated certificate")
    }

    override fun hasUsablePrivateKey(alias: String): Boolean = runCatching {
        val keyStore = keyStore()
        val key = keyStore.getKey(alias, null) as? java.security.PrivateKey ?: return false
        if (key.encoded != null) return false
        val certificate = keyStore.getCertificate(alias) as? X509Certificate ?: return false
        val challenge = "android-use-keystore-identity-check".toByteArray(Charsets.US_ASCII)
        val signer = java.security.Signature.getInstance("SHA256withRSA")
        signer.initSign(key)
        signer.update(challenge)
        val signature = signer.sign()
        signer.initVerify(certificate.publicKey)
        signer.update(challenge)
        signer.verify(signature)
    }.getOrDefault(false)

    override fun delete(alias: String) {
        keyStore().deleteEntry(alias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val MAX_CERTIFICATE_VALIDITY_MILLIS = 10L * 365 * 24 * 60 * 60 * 1000
    }
}

private fun X509Certificate.fingerprintSha256(): String =
    MessageDigest.getInstance("SHA-256").digest(encoded)
        .joinToString("") { "%02X".format(it) }

private fun X509Certificate.toPem(): String = buildString {
    append("-----BEGIN CERTIFICATE-----\n")
    append(Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(encoded))
    append("\n-----END CERTIFICATE-----\n")
}
