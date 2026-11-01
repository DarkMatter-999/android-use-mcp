package darkmatter.androiduse.app.pairing

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

enum class PairingStatus { WAITING, APPROVED, REJECTED, EXPIRED }

class PairingChallenge(
    val challengeId: String,
    val code: String,
    val expiresAtMillis: Long,
) {
    override fun toString(): String =
        "PairingChallenge(challengeId=$challengeId, expiresAtMillis=$expiresAtMillis)"
}

data class PairingSubmission(val challengeId: String, val status: PairingStatus)

@Serializable
data class HostRecord(
    val hostId: String,
    val hostName: String,
    val fingerprintSha256: String,
    val approvedAtMillis: Long,
)

data class ControllerLease(val leaseId: String, val fingerprintSha256: String)

interface PairingStore {
    fun load(): List<HostRecord>
    fun save(records: List<HostRecord>)
}

/** Persists only approved host metadata and certificate fingerprints. */
class SharedPreferencesPairingStore(context: Context) : PairingStore {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): List<HostRecord> {
        val value = preferences.getString(KEY_APPROVED_HOSTS, null) ?: return emptyList()
        return runCatching {
            Json.decodeFromString(ListSerializer(HostRecord.serializer()), value)
        }.getOrDefault(emptyList())
    }

    override fun save(records: List<HostRecord>) {
        preferences.edit { putString(KEY_APPROVED_HOSTS, Json.encodeToString(ListSerializer(HostRecord.serializer()), records)) }
    }

    private companion object {
        const val PREFERENCES_NAME = "android_use_pairing"
        const val KEY_APPROVED_HOSTS = "approved_hosts"
    }
}

class PairingRateLimitException : IllegalStateException("Pairing attempts are temporarily rate limited")

/** One-shot local-approval pairing state machine for one controlling host. */
class PairingManager(
    private val store: PairingStore,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val challenges = mutableMapOf<String, ChallengeState>()
    private val failedAttempts = ArrayDeque<Long>()
    private val revocationListeners = CopyOnWriteArrayList<(HostRecord) -> Unit>()
    private var pendingHost: PendingHost? = null
    private var activeLease: ControllerLease? = null

    fun begin(): PairingChallenge = synchronized(lock) {
        val now = clockMillis()
        challenges.values.filter { it.status == PairingStatus.WAITING }
            .forEach { it.status = PairingStatus.EXPIRED }
        val challenge = PairingChallenge(
            challengeId = UUID.randomUUID().toString(),
            code = buildString(CODE_LENGTH) {
                repeat(CODE_LENGTH) { append(CODE_ALPHABET[secureRandom.nextInt(CODE_ALPHABET.length)]) }
            },
            expiresAtMillis = now + CHALLENGE_TTL_MILLIS,
        )
        challenges[challenge.challengeId] = ChallengeState(challenge)
        pendingHost = null
        challenge
    }

    fun submit(
        code: String,
        hostId: String,
        hostName: String,
        hostCertificate: X509Certificate,
    ): PairingSubmission = synchronized(lock) {
        val now = clockMillis()
        trimFailedAttempts(now)
        if (failedAttempts.size >= MAX_FAILED_ATTEMPTS_PER_WINDOW) throw PairingRateLimitException()
        val state = challenges.values.lastOrNull { it.status == PairingStatus.WAITING }
            ?: return@synchronized PairingSubmission("", PairingStatus.EXPIRED)
        if (now >= state.challenge.expiresAtMillis) {
            state.status = PairingStatus.EXPIRED
            return@synchronized PairingSubmission(state.challenge.challengeId, PairingStatus.EXPIRED)
        }
        val supplied = code.toByteArray(Charsets.UTF_8)
        val expected = state.challenge.code.toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(expected, supplied)) {
            failedAttempts.addLast(now)
            return@synchronized PairingSubmission(state.challenge.challengeId, PairingStatus.REJECTED)
        }
        if (state.consumed) return@synchronized PairingSubmission(state.challenge.challengeId, PairingStatus.REJECTED)
        state.consumed = true
        if (!validHost(hostId, hostName, hostCertificate, now)) {
            state.status = PairingStatus.REJECTED
            return@synchronized PairingSubmission(state.challenge.challengeId, PairingStatus.REJECTED)
        }
        val pending = PendingHost(
            challengeId = state.challenge.challengeId,
            hostId = hostId,
            hostName = hostName,
            fingerprintSha256 = hostCertificate.fingerprintSha256(),
        )
        pendingHost = pending
        PairingSubmission(pending.challengeId, PairingStatus.WAITING)
    }

    fun approvalStatus(challengeId: String): PairingStatus = synchronized(lock) {
        val state = challenges[challengeId] ?: return@synchronized PairingStatus.EXPIRED
        if (state.status == PairingStatus.WAITING && clockMillis() >= state.challenge.expiresAtMillis) {
            state.status = PairingStatus.EXPIRED
            if (pendingHost?.challengeId == challengeId) pendingHost = null
        }
        state.status
    }

    /** Local UI only. This method is intentionally not exposed through RPC handlers. */
    fun approve(hostId: String) = synchronized(lock) {
        val pending = pendingHost?.takeIf { it.hostId == hostId } ?: return@synchronized
        val state = challenges[pending.challengeId] ?: return@synchronized
        if (clockMillis() >= state.challenge.expiresAtMillis) {
            state.status = PairingStatus.EXPIRED
            pendingHost = null
            return@synchronized
        }
        val record = HostRecord(pending.hostId, pending.hostName, pending.fingerprintSha256, clockMillis())
        val updated = store.load().filterNot { it.hostId == hostId } + record
        store.save(updated)
        state.status = PairingStatus.APPROVED
        pendingHost = null
    }

    /** Local UI only. */
    fun reject(hostId: String) = synchronized(lock) {
        val pending = pendingHost?.takeIf { it.hostId == hostId } ?: return@synchronized
        challenges[pending.challengeId]?.status = PairingStatus.REJECTED
        pendingHost = null
    }

    /** Local UI only. Revocation callbacks execute after the state lock is released. */
    fun revoke(hostId: String) {
        val revoked = synchronized(lock) {
            val records = store.load()
            val record = records.firstOrNull { it.hostId == hostId } ?: return
            store.save(records.filterNot { it.hostId == hostId })
            if (activeLease?.fingerprintSha256 == record.fingerprintSha256) activeLease = null
            record
        }
        revocationListeners.forEach { listener -> runCatching { listener(revoked) } }
    }

    fun addRevocationListener(listener: (HostRecord) -> Unit) {
        revocationListeners += listener
    }

    fun removeRevocationListener(listener: (HostRecord) -> Unit) {
        revocationListeners -= listener
    }

    fun isApproved(fingerprint: String): Boolean = store.load().any {
        constantTimeEquals(it.fingerprintSha256, fingerprint)
    }

    fun acquireController(fingerprint: String): ControllerLease? = synchronized(lock) {
        if (!store.load().any { constantTimeEquals(it.fingerprintSha256, fingerprint) }) return@synchronized null
        if (activeLease != null) return@synchronized null
        ControllerLease(UUID.randomUUID().toString(), fingerprint).also { activeLease = it }
    }

    fun releaseController(leaseId: String) = synchronized(lock) {
        if (activeLease?.leaseId == leaseId) activeLease = null
    }

    fun isControllerLeaseActive(lease: ControllerLease): Boolean = synchronized(lock) {
        activeLease == lease && store.load().any {
            constantTimeEquals(it.fingerprintSha256, lease.fingerprintSha256)
        }
    }

    private fun validHost(
        hostId: String,
        hostName: String,
        certificate: X509Certificate,
        now: Long,
    ): Boolean = runCatching {
        require(UUID.fromString(hostId).toString() == hostId)
        require(hostName.isNotBlank() && hostName.length <= MAX_HOST_NAME_LENGTH)
        require(hostName.none { it.isISOControl() })
        certificate.checkValidity(Date(now))
        certificate.verify(certificate.publicKey)
        require(certificate.extendedKeyUsage?.contains(CLIENT_AUTH_EKU) == true)
        val sans = certificate.subjectAlternativeNames.orEmpty()
        require(sans.any { entry ->
            entry.size >= 2 && entry[0] == URI_SAN_TYPE &&
                entry[1] == "urn:android-use:host:$hostId"
        })
    }.isSuccess

    private fun trimFailedAttempts(now: Long) {
        while (failedAttempts.isNotEmpty() && now - failedAttempts.first() >= RATE_LIMIT_WINDOW_MILLIS) {
            failedAttempts.removeFirst()
        }
    }

    private fun constantTimeEquals(first: String, second: String): Boolean = MessageDigest.isEqual(
        first.toByteArray(Charsets.US_ASCII),
        second.toByteArray(Charsets.US_ASCII),
    )

    private data class ChallengeState(
        val challenge: PairingChallenge,
        var status: PairingStatus = PairingStatus.WAITING,
        var consumed: Boolean = false,
    )

    private data class PendingHost(
        val challengeId: String,
        val hostId: String,
        val hostName: String,
        val fingerprintSha256: String,
    )

    companion object {
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val CODE_LENGTH = 8
        const val CHALLENGE_TTL_MILLIS = 5 * 60 * 1000L
        const val MAX_FAILED_ATTEMPTS_PER_WINDOW = 5
        const val RATE_LIMIT_WINDOW_MILLIS = 60 * 1000L
        private const val MAX_HOST_NAME_LENGTH = 80
        private const val URI_SAN_TYPE = 6
        private const val CLIENT_AUTH_EKU = "1.3.6.1.5.5.7.3.2"
    }
}

private fun X509Certificate.fingerprintSha256(): String =
    MessageDigest.getInstance("SHA-256").digest(encoded)
        .joinToString("") { "%02X".format(it) }
