package darkmatter.androiduse.app.security

import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {

    @Test
    fun acceptsAbsentKeyUsageExtensionButRejectsExplicitMissingDigitalSignatureUsage() {
        assertTrue(DeviceIdentity.hasDigitalSignatureKeyUsage(null))
        assertTrue(DeviceIdentity.hasDigitalSignatureKeyUsage(booleanArrayOf(true)))
        assertFalse(DeviceIdentity.hasDigitalSignatureKeyUsage(booleanArrayOf(false, true)))
    }

    @Test
    fun reusesValidKeystoreIdentityAndReturnsOnlyPublicCertificateData() {
        val certificate = testCertificate()
        val backend = FakeIdentityBackend(certificate)
        val identity = DeviceIdentity(backend, alias = "device-identity")

        val first = identity.getOrCreate()
        val second = identity.getOrCreate()

        assertEquals("device-identity", first.keystoreAlias)
        assertEquals(first.certificatePem, second.certificatePem)
        assertEquals(first.fingerprintSha256, second.fingerprintSha256)
        assertEquals(certificate, first.certificate)
        assertEquals(expectedFingerprint(certificate), first.fingerprintSha256)
        assertTrue(first.certificatePem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertFalse(first.certificatePem.contains("PRIVATE KEY"))
        assertEquals(0, backend.createCount)
        assertEquals(0, backend.deleteCount)
        assertFalse(DeviceCertificate::class.java.declaredFields.any {
            it.type == ByteArray::class.java || java.security.PrivateKey::class.java.isAssignableFrom(it.type)
        })
        assertTrue(DeviceIdentity.Backend::class.java.methods.all {
            it.returnType != ByteArray::class.java &&
                !java.security.PrivateKey::class.java.isAssignableFrom(it.returnType)
        })
    }

    @Test
    fun replacesMissingOrInvalidKeystoreCertificateWithoutSoftwareKeyFallback() {
        val replacement = testCertificate()
        val backend = FakeIdentityBackend(initialCertificate = null, createdCertificate = replacement)
        val identity = DeviceIdentity(backend, alias = "device-identity")

        val result = identity.getOrCreate()

        assertEquals(replacement, result.certificate)
        assertEquals("device-identity", result.keystoreAlias)
        assertEquals(1, backend.createCount)
        assertEquals(1, backend.deleteCount)
    }

    @Test
    fun expiredKeystoreCertificateIsDeletedAndInvalidReplacementFailsClosed() {
        val expired = testCertificate()
        val backend = FakeIdentityBackend(expired, expired)
        val identity = DeviceIdentity(
            backend,
            alias = "device-identity",
            clockMillis = { expired.notAfter.time + 1 },
        )

        try {
            identity.getOrCreate()
            throw AssertionError("expired Keystore certificate must not be reused")
        } catch (_: IllegalArgumentException) {
            // Invalid Keystore material fails closed; no software identity is substituted.
        }

        assertEquals(1, backend.deleteCount)
        assertEquals(1, backend.createCount)
    }

    @Test
    fun missingOrUnusableKeystorePrivateKeyReplacesCertificateAndChangesFingerprint() {
        val oldCertificate = testCertificate()
        val newCertificate = testHostCertificate()
        val backend = FakeIdentityBackend(
            initialCertificate = oldCertificate,
            createdCertificate = newCertificate,
            initialKeyAvailable = false,
        )
        val result = DeviceIdentity(backend, alias = "device-identity").getOrCreate()

        assertEquals(newCertificate, result.certificate)
        assertNotEquals(expectedFingerprint(oldCertificate), result.fingerprintSha256)
        assertEquals(1, backend.deleteCount)
        assertEquals(1, backend.createCount)
    }

    private fun testCertificate(): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(
            javaClass.getResourceAsStream("device-identity-test.pem")!!,
        ) as X509Certificate

    private fun testHostCertificate(): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(
            javaClass.getResourceAsStream("/darkmatter/androiduse/app/security/host-pairing-test.pem")!!,
        ) as X509Certificate

    private fun expectedFingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02X".format(it) }

    private class FakeIdentityBackend(
        initialCertificate: X509Certificate?,
        private val createdCertificate: X509Certificate = initialCertificate ?: testCertificateStatic(),
        initialKeyAvailable: Boolean = initialCertificate != null,
    ) : DeviceIdentity.Backend {
        private var storedCertificate = initialCertificate
        private var keyAvailable = initialKeyAvailable
        var createCount = 0
            private set
        var deleteCount = 0
            private set
        override fun loadCertificate(alias: String): X509Certificate? = storedCertificate

        override fun hasUsablePrivateKey(alias: String): Boolean = keyAvailable

        override fun create(alias: String): X509Certificate {
            createCount++
            storedCertificate = createdCertificate
            keyAvailable = true
            return createdCertificate
        }

        override fun delete(alias: String) {
            deleteCount++
            storedCertificate = null
            keyAvailable = false
        }

        companion object {
            private fun testCertificateStatic(): X509Certificate =
                CertificateFactory.getInstance("X.509").generateCertificate(
                    DeviceIdentityTest::class.java.getResourceAsStream("device-identity-test.pem")!!,
                ) as X509Certificate
        }
    }
}
