package darkmatter.androiduse.app.pairing

import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingManagerTest {
    private val hostId = "11111111-1111-4111-8111-111111111111"
    private val hostCertificate: X509Certificate by lazy { testHostCertificate() }

    @Test
    fun stoppingRemoteControlExpiresOutstandingChallengeAndDropsPendingHost() {
        val manager = PairingManager(InMemoryPairingStore(), ZeroRandom())
        val challenge = manager.begin()

        manager.cancelPendingChallenge()

        assertEquals(PairingStatus.EXPIRED, manager.approvalStatus(challenge.challengeId))
        assertNull(manager.pendingHostInfo())
    }

    @Test
    fun revocationCannotLinearizeBetweenFinalAuthorizationAndResponseCommit() {
        val manager = PairingManager(InMemoryPairingStore())
        val challenge = manager.begin()
        manager.submit(challenge.code, hostId, "Test Host", hostCertificate)
        manager.approve(hostId)
        val lease = requireNotNull(manager.acquireController(fingerprint(hostCertificate)))
        val commitEntered = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val revokeReturned = CountDownLatch(1)
        val committed = AtomicBoolean(false)

        val commitThread = Thread {
            manager.commitControllerResponse(lease) {
                commitEntered.countDown()
                allowCommit.await()
                committed.set(true)
            }
        }.apply { start() }
        assertTrue(commitEntered.await(1, TimeUnit.SECONDS))
        val revokeThread = Thread {
            manager.revoke(hostId)
            revokeReturned.countDown()
        }.apply { start() }

        assertFalse(revokeReturned.await(100, TimeUnit.MILLISECONDS))
        allowCommit.countDown()
        commitThread.join(1_000)
        revokeThread.join(1_000)

        assertTrue(committed.get())
        assertTrue(revokeReturned.await(1, TimeUnit.SECONDS))
        assertFalse(manager.isControllerLeaseActive(lease))
    }

    @Test
    fun challengeUsesDeterministicEightCharacterUnambiguousCodeAndExpiresAfterFiveMinutes() {
        var now = 1_000_000L
        val manager = PairingManager(InMemoryPairingStore(), ZeroRandom(), { now })

        val challenge = manager.begin()

        assertEquals("AAAAAAAA", challenge.code)
        assertEquals(8, challenge.code.length)
        assertTrue(challenge.code.all { it in PairingManager.CODE_ALPHABET })
        assertEquals(now + PairingManager.CHALLENGE_TTL_MILLIS, challenge.expiresAtMillis)
        now = challenge.expiresAtMillis
        assertEquals(PairingStatus.EXPIRED, manager.approvalStatus(challenge.challengeId))
    }

    @Test
    fun submissionConsumesCodeOnceAndRequiresExplicitApprovalAndSingleController() {
        val store = InMemoryPairingStore()
        val now = hostCertificate.notBefore.time + 1_000L
        val manager = PairingManager(store, ZeroRandom(), { now })
        val challenge = manager.begin()

        val submission = manager.submit(challenge.code, hostId, "Test Host", hostCertificate)
        val replay = manager.submit(challenge.code, hostId, "Test Host", hostCertificate)

        assertEquals(PairingStatus.WAITING, submission.status)
        assertEquals(PairingStatus.REJECTED, replay.status)
        assertFalse(manager.isApproved(fingerprint(hostCertificate)))
        assertNull(manager.acquireController(fingerprint(hostCertificate)))
        assertEquals(PairingStatus.WAITING, manager.approvalStatus(submission.challengeId))
        assertEquals(PendingHostInfo(hostId, "Test Host", fingerprint(hostCertificate)), manager.pendingHostInfo())
        assertEquals(emptyList<HostRecord>(), manager.approvedHosts())

        manager.approve(hostId)

        assertEquals(PairingStatus.APPROVED, manager.approvalStatus(submission.challengeId))
        assertTrue(manager.isApproved(fingerprint(hostCertificate)))
        assertNull(manager.pendingHostInfo())
        assertEquals("Test Host", manager.approvedHosts().single().hostName)
        val lease = manager.acquireController(fingerprint(hostCertificate))
        assertNotNull(lease)
        assertNull(manager.acquireController(fingerprint(hostCertificate)))
        manager.releaseController(lease!!.leaseId)
        assertNotNull(manager.acquireController(fingerprint(hostCertificate)))
        assertEquals(1, store.load().size)
        assertEquals(fingerprint(hostCertificate), store.load().single().fingerprintSha256)
        assertEquals(
            setOf("hostId", "hostName", "fingerprintSha256", "approvedAtMillis"),
            (0 until HostRecord.serializer().descriptor.elementsCount)
                .map { HostRecord.serializer().descriptor.getElementName(it) }.toSet(),
        )
    }

    @Test
    fun certificateMustBeValidSelfSignedClientIdentityBoundToHostId() {
        val now = hostCertificate.notBefore.time + 1_000L
        val manager = PairingManager(InMemoryPairingStore(), ZeroRandom(), { now })
        val challenge = manager.begin()

        val wrongHost = manager.submit(challenge.code, UUID.randomUUID().toString(), "Test Host", hostCertificate)

        assertEquals(PairingStatus.REJECTED, wrongHost.status)
        assertFalse(manager.isApproved(fingerprint(hostCertificate)))
        assertEquals(PairingStatus.REJECTED, manager.approvalStatus(challenge.challengeId))
    }

    @Test
    fun expiredHostCertificateIsRejectedAndChallengeCannotBeApproved() {
        val expiredAt = hostCertificate.notAfter.time + 1
        val manager = PairingManager(InMemoryPairingStore(), ZeroRandom(), { expiredAt })
        val challenge = manager.begin()

        val submission = manager.submit(challenge.code, hostId, "Test Host", hostCertificate)

        assertEquals(PairingStatus.REJECTED, submission.status)
        assertEquals(PairingStatus.REJECTED, manager.approvalStatus(challenge.challengeId))
    }

    @Test
    fun invalidCertificateSignatureAndNonClientAuthEkuAreRejected() {
        val now = hostCertificate.notBefore.time + 1_000L
        val invalidSignatureManager = PairingManager(InMemoryPairingStore(), ZeroRandom(), { now })
        val invalidSignatureChallenge = invalidSignatureManager.begin()
        val invalidSignature = invalidSignatureManager.submit(
            invalidSignatureChallenge.code,
            hostId,
            "Test Host",
            testCertificate("host-bad-signature.pem"),
        )
        assertEquals(PairingStatus.REJECTED, invalidSignature.status)

        val wrongEkuManager = PairingManager(InMemoryPairingStore(), ZeroRandom(), { now })
        val wrongEkuChallenge = wrongEkuManager.begin()
        val wrongEku = wrongEkuManager.submit(
            wrongEkuChallenge.code,
            hostId,
            "Test Host",
            testCertificate("host-server-auth.pem"),
        )
        assertEquals(PairingStatus.REJECTED, wrongEku.status)
    }

    @Test
    fun failedCodesAreRateLimitedAndApprovedHostsSurviveStoreReload() {
        var now = hostCertificate.notBefore.time + 1_000L
        val store = InMemoryPairingStore()
        val manager = PairingManager(store, ZeroRandom(), { now })
        val challenge = manager.begin()

        repeat(PairingManager.MAX_FAILED_ATTEMPTS_PER_WINDOW) {
            assertEquals(
                PairingStatus.REJECTED,
                manager.submit("AAAAAAAB", hostId, "Test Host", hostCertificate).status,
            )
        }
        try {
            manager.submit("AAAAAAAB", hostId, "Test Host", hostCertificate)
            throw AssertionError("expected pairing attempt rate limit")
        } catch (_: PairingRateLimitException) {
            // The next attempt is blocked for the remainder of the rolling window.
        }

        now += PairingManager.RATE_LIMIT_WINDOW_MILLIS
        val submission = manager.submit(challenge.code, hostId, "Test Host", hostCertificate)
        assertEquals(PairingStatus.WAITING, submission.status)
        manager.approve(hostId)

        val freshManager = PairingManager(store, ZeroRandom(), { now })
        assertTrue(freshManager.isApproved(fingerprint(hostCertificate)))
        assertEquals(PairingStatus.APPROVED, manager.approvalStatus(submission.challengeId))
    }

    @Test
    fun localRejectAndRevokeClearApprovalLeaseAndNotifyAfterStateChanges() {
        val store = InMemoryPairingStore()
        val now = hostCertificate.notBefore.time + 1_000L
        val manager = PairingManager(store, ZeroRandom(), { now })
        val challenge = manager.begin()
        val submission = manager.submit(challenge.code, hostId, "Test Host", hostCertificate)
        val callbackCouldAcquireStateLock = AtomicBoolean(false)
        manager.addRevocationListener { revoked ->
            val otherThread = Thread {
                manager.isApproved(revoked.fingerprintSha256)
                callbackCouldAcquireStateLock.set(true)
            }
            otherThread.start()
            otherThread.join(1_000)
        }

        manager.reject(hostId)
        assertEquals(PairingStatus.REJECTED, manager.approvalStatus(submission.challengeId))
        assertFalse(manager.isApproved(fingerprint(hostCertificate)))

        val retry = manager.begin()
        val approvedSubmission = manager.submit(retry.code, hostId, "Test Host", hostCertificate)
        manager.approve(hostId)
        val lease = manager.acquireController(fingerprint(hostCertificate))
        assertNotNull(lease)
        manager.revoke(hostId)

        assertEquals(PairingStatus.APPROVED, manager.approvalStatus(approvedSubmission.challengeId))
        assertFalse(manager.isApproved(fingerprint(hostCertificate)))
        assertNull(manager.acquireController(fingerprint(hostCertificate)))
        assertTrue(callbackCouldAcquireStateLock.get())
    }

    private fun testHostCertificate(): X509Certificate = testCertificate("host-pairing-test.pem")

    private fun testCertificate(name: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(
            javaClass.getResourceAsStream("/darkmatter/androiduse/app/security/$name")!!,
        ) as X509Certificate

    private fun fingerprint(certificate: X509Certificate): String = certificate.encoded
        .let { java.security.MessageDigest.getInstance("SHA-256").digest(it) }
        .joinToString("") { "%02X".format(it) }

    private class ZeroRandom : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.fill(0)
        }
    }

    private class InMemoryPairingStore : PairingStore {
        private var records = emptyList<HostRecord>()
        override fun load(): List<HostRecord> = records.toList()
        override fun save(records: List<HostRecord>) {
            this.records = records.toList()
        }
    }
}
