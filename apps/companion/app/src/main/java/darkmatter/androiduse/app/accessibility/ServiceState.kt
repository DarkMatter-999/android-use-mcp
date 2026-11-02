package darkmatter.androiduse.app.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.edit
import java.util.UUID
import darkmatter.androiduse.app.network.BindMode
import darkmatter.androiduse.app.network.RpcEndpoint
import java.net.InetAddress
import java.net.Inet4Address

/** Active transport paths. */
enum class TransportKind {
    NONE,
    USB,
    WIFI,
}

/**
 * Honest, user-facing service state.
 *
 * [enabled] means the user has enabled the service in Android accessibility
 * settings; [connected] means Android has bound the service. [remoteEnabled] is
 * the explicit in-app control toggle. [activeHost] and [transport] stay empty
 * until an authenticated transport/session exists (Task 5). This task never
 * claims screenshot or gesture support, so no such flags live here.
 */
data class ServiceState(
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val remoteEnabled: Boolean = false,
    val activeHost: String? = null,
    val transport: TransportKind = TransportKind.NONE,
    /** True only for an authenticated, active transport session. */
    val sessionActive: Boolean = false,
    val lastSnapshotRevision: Int = 0,
    val lastError: String? = null,
    val endpoint: RpcEndpoint? = null,
    val deviceFingerprintSha256: String? = null,
    val deviceCertificatePem: String? = null,
    val pairingCode: String? = null,
    val pairingExpiresAtMillis: Long? = null,
    val pendingHostId: String? = null,
    val pendingHostName: String? = null,
    val pendingHostFingerprintSha256: String? = null,
    val approvedHosts: List<ApprovedHostState> = emptyList(),
    val overlayOptions: darkmatter.androiduse.app.overlay.OverlayOptions = darkmatter.androiduse.app.overlay.OverlayOptions(false, true, true, true, true),
) {
    /** Remote control can start only after accessibility is enabled and bound. */
    val canEnableRemoteControl: Boolean get() = enabled && connected

    override fun toString(): String = "ServiceState(enabled=$enabled, connected=$connected, " +
        "remoteEnabled=$remoteEnabled, activeHost=$activeHost, transport=$transport, " +
        "sessionActive=$sessionActive, endpoint=$endpoint, lastSnapshotRevision=$lastSnapshotRevision, " +
        "lastError=$lastError)"
}

data class ApprovedHostState(val hostId: String, val hostName: String, val fingerprintSha256: String)

/** Rejects a delayed disconnect notification from an obsolete controller lease. */
internal class ControllerLeaseState {
    private var leaseId: String? = null

    @Synchronized
    fun update(eventLeaseId: String, active: Boolean): Boolean {
        if (active) {
            leaseId = eventLeaseId
            return true
        }
        if (leaseId != eventLeaseId) return false
        leaseId = null
        return true
    }

    @Synchronized
    fun currentLeaseId(): String? = leaseId

    @Synchronized
    fun clear() {
        leaseId = null
    }
}

/** Small deterministic lifecycle boundary used by the service and unit tests. */
internal class TransportLifecycle(
    private val start: (BindMode) -> RpcEndpoint,
    private val stop: () -> Unit,
) {
    private var endpoint: RpcEndpoint? = null
    private var enabled = false
    private var mode: BindMode = BindMode.Loopback

    @Synchronized
    fun enable(accessibilityReady: Boolean, notificationVisible: Boolean): Boolean {
        if (!accessibilityReady || !notificationVisible) return false
        if (enabled && endpoint != null) return true
        return try {
            mode = BindMode.Loopback
            endpoint = start(mode)
            enabled = true
            true
        } catch (_: Exception) {
            runCatching(stop)
            endpoint = null
            enabled = false
            false
        }
    }

    @Synchronized
    fun enableWifi(address: InetAddress): Boolean {
        if (!enabled) return false
        val wifiMode = try { BindMode.Wifi(address) } catch (_: IllegalArgumentException) { return false }
        return try {
            stop()
            endpoint = start(wifiMode)
            mode = wifiMode
            true
        } catch (_: Exception) {
            runCatching(stop)
            endpoint = null
            enabled = false
            false
        }
    }

    @Synchronized
    fun enableLoopback(): Boolean {
        if (!enabled) return false
        if (mode == BindMode.Loopback && endpoint != null) return true
        return try {
            stop()
            mode = BindMode.Loopback
            endpoint = start(mode)
            true
        } catch (_: Exception) {
            runCatching(stop)
            endpoint = null
            enabled = false
            false
        }
    }

    @Synchronized
    fun disable() {
        val wasActive = endpoint != null
        endpoint = null
        enabled = false
        mode = BindMode.Loopback
        if (wasActive) runCatching(stop)
    }

    fun onUnbind() = disable()
    fun onDestroy() = disable()

    @Synchronized
    fun state(): ServiceState = ServiceState(
        remoteEnabled = enabled,
        endpoint = endpoint,
        transport = when (mode) {
            BindMode.Loopback -> TransportKind.NONE
            is BindMode.Wifi -> TransportKind.WIFI
        }.takeIf { endpoint != null } ?: TransportKind.NONE,
    )
}

internal object WifiAddressSelector {
    fun select(candidates: List<Pair<String, InetAddress>>): Inet4Address? = candidates.firstNotNullOfOrNull { (name, address) ->
        val wifiInterface = name.startsWith("wlan", ignoreCase = true) || name.startsWith("wifi", ignoreCase = true)
        val ipv4 = address as? Inet4Address
        ipv4?.takeIf {
            wifiInterface && !it.isLoopbackAddress && !it.isAnyLocalAddress &&
                !it.isLinkLocalAddress && !it.isMulticastAddress
        }
    }
}

/**
 * Local, user-controlled preferences. They are intentionally not credentials and
 * cannot enable Android accessibility on their own.
 */
object ServicePreferences {

    private const val PREFS_NAME = "android_use_control"
    private const val KEY_REMOTE_ENABLED = "remote_enabled"
    private const val KEY_EXPLANATION_ACK = "device_control_explained"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_OVERLAY_INSPECTOR = "overlay_inspector"
    private const val KEY_OVERLAY_BOUNDS = "overlay_bounds"
    private const val KEY_OVERLAY_LABELS = "overlay_labels"
    private const val KEY_OVERLAY_ACTION_TRACE = "overlay_action_trace"
    private const val KEY_OVERLAY_CURSOR = "overlay_cursor"
    private const val KEY_ALLOWED_LAUNCH_PACKAGES = "allowed_launch_packages"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on_during_remote_control"

    fun allowedLaunchPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ALLOWED_LAUNCH_PACKAGES, emptySet()).orEmpty().toSet()

    fun setAllowedLaunchPackages(context: Context, packages: Set<String>) {
        prefs(context).edit {
            putStringSet(KEY_ALLOWED_LAUNCH_PACKAGES, packages.filter(String::isNotBlank).toSet())
        }
    }

    fun isKeepScreenOnEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, false)

    fun setKeepScreenOnEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_KEEP_SCREEN_ON, enabled) }
    }

    fun overlayOptions(context: Context): darkmatter.androiduse.app.overlay.OverlayOptions {
        val preferences = prefs(context)
        return darkmatter.androiduse.app.overlay.OverlayOptions(
            inspector = preferences.getBoolean(KEY_OVERLAY_INSPECTOR, false),
            bounds = preferences.getBoolean(KEY_OVERLAY_BOUNDS, true),
            labels = preferences.getBoolean(KEY_OVERLAY_LABELS, true),
            actionTrace = preferences.getBoolean(KEY_OVERLAY_ACTION_TRACE, true),
            cursor = preferences.getBoolean(KEY_OVERLAY_CURSOR, true),
        )
    }

    fun setOverlayOptions(context: Context, options: darkmatter.androiduse.app.overlay.OverlayOptions) {
        prefs(context).edit {
            putBoolean(KEY_OVERLAY_INSPECTOR, options.inspector)
            putBoolean(KEY_OVERLAY_BOUNDS, options.bounds)
            putBoolean(KEY_OVERLAY_LABELS, options.labels)
            putBoolean(KEY_OVERLAY_ACTION_TRACE, options.actionTrace)
            putBoolean(KEY_OVERLAY_CURSOR, options.cursor)
        }
    }

    fun isRemoteEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REMOTE_ENABLED, false)

    fun setRemoteEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_REMOTE_ENABLED, enabled) }
    }

    fun hasAcknowledgedExplanation(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXPLANATION_ACK, false)

    fun acknowledgeExplanation(context: Context) {
        prefs(context).edit { putBoolean(KEY_EXPLANATION_ACK, true) }
    }

    /**
     * App-scoped installation id for snapshot correlation. It is generated
     * locally and is not a hardware identifier; Task 5 replaces it with the
     * pairing identity.
     */
    @Synchronized
    fun deviceId(context: Context): String {
        val preferences = prefs(context)
        preferences.getString(KEY_DEVICE_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        preferences.edit { putString(KEY_DEVICE_ID, generated) }
        return generated
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/** Policy for keeping the display awake without affecting remote-control consent. */
internal object RemoteOverlayPolicy {
    fun shouldKeepScreenOn(
        preferenceEnabled: Boolean,
        remoteEnabled: Boolean,
        serviceConnected: Boolean,
    ): Boolean = preferenceEnabled && remoteEnabled && serviceConnected

    fun shouldAttach(
        inspectorEnabled: Boolean,
        traceEnabled: Boolean,
        keepScreenOn: Boolean,
    ): Boolean = inspectorEnabled || traceEnabled || keepScreenOn
}

/**
 * Side-effect-free transition decision for the explicit remote-control toggle.
 * The caller supplies persistence and notification effects so the fail-closed
 * ordering is unit testable without an Android service instance.
 */
internal object RemoteControlToggle {

    /** A persisted toggle is never consent to restore a network listener after service restart. */
    fun restoreAfterServiceRestart(savedRemoteEnabled: Boolean): Boolean = false

    fun apply(
        enabled: Boolean,
        accessibilityEnabled: Boolean,
        connected: Boolean,
        notificationAllowed: Boolean,
        persistRemoteEnabled: (Boolean) -> Unit,
        postNotification: () -> Boolean,
        cancelNotification: () -> Unit,
    ): Boolean {
        if (!enabled) {
            persistRemoteEnabled(false)
            cancelNotification()
            return true
        }
        if (!accessibilityEnabled || !connected || !notificationAllowed) {
            return false
        }
        persistRemoteEnabled(true)
        val posted = try {
            postNotification()
        } catch (_: RuntimeException) {
            false
        }
        if (!posted) {
            // The ongoing notification is a required user-visible control surface.
            // Roll the preference back and fail closed if it cannot be posted.
            persistRemoteEnabled(false)
            try {
                cancelNotification()
            } catch (_: RuntimeException) {
                // Cancellation is best effort; the preference is already off.
            }
            return false
        }
        return true
    }
}

/** Pure policy for whether the required active-session notification can be shown. */
internal object NotificationVisibilityPolicy {
    fun canShow(
        permissionGranted: Boolean,
        appNotificationsEnabled: Boolean,
        channelEnabled: Boolean,
    ): Boolean = permissionGranted && appNotificationsEnabled && channelEnabled
}

/**
 * True only when this app's accessibility service is present in Android's
 * enabled-services setting. The setting is the authority; the app never tries to
 * write it.
 */
fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val component = ComponentName(context, UseService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    if (enabled.split(':').any { ComponentName.unflattenFromString(it) == component }) {
        return true
    }

    // Fallback for OEM builds that expose the enabled service only through the
    // manager. Neither path enables the service; both merely read user consent.
    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { info ->
            val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
            serviceInfo.packageName == context.packageName &&
                serviceInfo.name == UseService::class.java.name
        }
}
