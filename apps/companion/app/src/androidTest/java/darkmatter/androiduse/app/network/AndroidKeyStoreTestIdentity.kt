package darkmatter.androiduse.app.network

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import javax.security.auth.x500.X500Principal

internal class AndroidKeyStoreTestIdentity private constructor(
    val alias: String,
    val keyPair: KeyPair,
    val certificate: X509Certificate,
) : AutoCloseable {
    val privateKey: PrivateKey get() = keyPair.private

    override fun close() {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply {
            load(null)
            deleteEntry(alias)
        }
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TEST_ALIAS_PREFIX = "android-use-feasibility-"

        fun deleteStaleTestIdentities() {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            keyStore.aliases().toList()
                .filter { it.startsWith(TEST_ALIAS_PREFIX) }
                .forEach(keyStore::deleteEntry)
        }

        fun create(label: String): AndroidKeyStoreTestIdentity {
            val alias = "$TEST_ALIAS_PREFIX$label-${UUID.randomUUID()}"
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
                    .setCertificateSubject(X500Principal("CN=$alias"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now).abs())
                    .setCertificateNotBefore(Date(now - 60_000))
                    .setCertificateNotAfter(Date(now + 86_400_000))
                    .build(),
            )
            val keyPair = generator.generateKeyPair()
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            val certificate = keyStore.getCertificate(alias) as X509Certificate
            return AndroidKeyStoreTestIdentity(alias, keyPair, certificate)
        }
    }
}
