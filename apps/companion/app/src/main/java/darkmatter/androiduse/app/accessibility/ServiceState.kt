package darkmatter.androiduse.app.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.edit
import java.util.UUID

/** Connection paths the companion can eventually expose. Only [NONE] is used in this build. */
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
) {
    /** Remote control can start only after accessibility is enabled and bound. */
    val canEnableRemoteControl: Boolean get() = enabled && connected
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

/**
 * Side-effect-free transition decision for the explicit remote-control toggle.
 * The caller supplies persistence and notification effects so the fail-closed
 * ordering is unit testable without an Android service instance.
 */
internal object RemoteControlToggle {

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
