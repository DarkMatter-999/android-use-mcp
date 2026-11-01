package darkmatter.androiduse.app.ui

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import darkmatter.androiduse.app.accessibility.ServicePreferences
import darkmatter.androiduse.app.accessibility.ServiceState
import darkmatter.androiduse.app.accessibility.SessionNotification
import darkmatter.androiduse.app.accessibility.TransportKind
import darkmatter.androiduse.app.accessibility.UseService
import darkmatter.androiduse.app.accessibility.isAccessibilityServiceEnabled
import darkmatter.androiduse.app.actions.ActionLogEntry
import darkmatter.androiduse.app.actions.ActionLogStore

/** Immutable screen state. Every field is derived from an observable source. */
data class HomeUiState(
    val deviceModel: String,
    val apiLevel: Int,
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val remoteEnabled: Boolean,
    val activeHost: String?,
    val transport: TransportKind,
    val sessionActive: Boolean,
    val recentActions: List<ActionLogEntry>,
    val firstRunExplanationVisible: Boolean,
    val notificationPermissionMissing: Boolean,
    val statusMessage: String,
)

/**
 * Home-screen coordinator. It owns no Android node handles and only observes the
 * service state plus the bounded action log.
 */
class HomeViewModel(
    context: Context,
    private val actionLogStore: ActionLogStore = ActionLogStore.shared,
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val explanationAcknowledged = MutableStateFlow(
        ServicePreferences.hasAcknowledgedExplanation(appContext),
    )
    private val statusOverride = MutableStateFlow<String?>(null)
    private var enableRemoteAfterPermission = false

    private val mutableState = MutableStateFlow(
        buildState(
            service = UseService.state.value,
            actions = actionLogStore.recentEntries.value,
            acknowledged = explanationAcknowledged.value,
            statusOverride = null,
        ),
    )

    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    init {
        UseService.refreshState(appContext)
        scope.launch {
            combine(
                UseService.state,
                actionLogStore.recentEntries,
                explanationAcknowledged,
                statusOverride,
            ) { service, actions, acknowledged, override ->
                buildState(service, actions, acknowledged, override)
            }.collect { mutableState.value = it }
        }
    }

    /** Re-reads Android settings, e.g. after returning from accessibility settings. */
    fun refresh() {
        explanationAcknowledged.value = ServicePreferences.hasAcknowledgedExplanation(appContext)
        UseService.refreshState(appContext)
    }

    fun acknowledgeExplanation() {
        ServicePreferences.acknowledgeExplanation(appContext)
        explanationAcknowledged.value = true
        statusOverride.value = "Explanation acknowledged. Remote control can now be enabled."
    }

    fun requiresNotificationPermission(): Boolean =
        SessionNotification.requiresPermission() && !SessionNotification.canPost(appContext)

    /**
     * Returns `true` when the caller must request notification permission before
     * the toggle can take effect. The requested value is remembered for the
     * permission result.
     */
    fun beginRemoteToggle(enabled: Boolean): Boolean {
        if (enabled && !explanationAcknowledged.value) {
            statusOverride.value = "Review and acknowledge the device-control explanation first."
            return false
        }
        if (enabled && requiresNotificationPermission()) {
            enableRemoteAfterPermission = true
            statusOverride.value =
                "Notification permission is required for the persistent remote-control notice."
            return true
        }
        setRemoteControlEnabled(enabled)
        return false
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        val shouldEnable = enableRemoteAfterPermission
        enableRemoteAfterPermission = false
        if (!granted) {
            statusOverride.value = "Notification permission denied; remote control stays off."
            return
        }
        if (shouldEnable) {
            setRemoteControlEnabled(true)
        } else {
            statusOverride.value = "Notification permission granted."
        }
    }

    fun setRemoteControlEnabled(enabled: Boolean) {
        if (enabled && !explanationAcknowledged.value) {
            statusOverride.value = "Review and acknowledge the device-control explanation first."
            return
        }
        if (enabled && requiresNotificationPermission()) {
            statusOverride.value =
                "Notification permission is required before remote control can start."
            return
        }

        val changed = UseService.setRemoteEnabled(appContext, enabled)
        statusOverride.value = when {
            changed && enabled -> "Remote control enabled. No authenticated host is connected yet."
            changed -> "Remote control stopped. No host is connected."
            enabled -> "Enable the accessibility service first; remote control stays off."
            else -> "Remote control is already off."
        }
    }

    fun disconnect() {
        UseService.requestDisconnect(appContext)
        statusOverride.value = "Remote control stopped and session metadata cleared."
    }

    override fun close() {
        scope.cancel()
    }

    private fun buildState(
        service: ServiceState,
        actions: List<ActionLogEntry>,
        acknowledged: Boolean,
        statusOverride: String?,
    ): HomeUiState {
        val accessibilityEnabled = service.enabled || isAccessibilityServiceEnabled(appContext)
        val status = statusOverride ?: when {
            !accessibilityEnabled ->
                "Accessibility is off. Enable AndroidUse in Android accessibility settings."
            !service.connected ->
                "Accessibility is enabled, but the service is not connected yet."
            service.remoteEnabled ->
                "Remote control is on. Waiting for an authenticated host."
            else -> "Accessibility is ready. Remote control is off."
        }

        return HomeUiState(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            apiLevel = Build.VERSION.SDK_INT,
            accessibilityEnabled = accessibilityEnabled,
            accessibilityConnected = service.connected,
            remoteEnabled = service.remoteEnabled,
            activeHost = service.activeHost,
            transport = service.transport,
            sessionActive = service.sessionActive,
            recentActions = actions.take(5),
            firstRunExplanationVisible = !acknowledged,
            notificationPermissionMissing =
                SessionNotification.requiresPermission() && !SessionNotification.canPost(appContext),
            statusMessage = status,
        )
    }
}
