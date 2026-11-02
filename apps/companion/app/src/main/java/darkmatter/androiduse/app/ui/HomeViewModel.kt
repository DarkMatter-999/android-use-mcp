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
import kotlinx.coroutines.delay
import darkmatter.androiduse.app.accessibility.ServicePreferences
import darkmatter.androiduse.app.accessibility.ListenerPortConfig
import darkmatter.androiduse.app.accessibility.ServiceState
import darkmatter.androiduse.app.accessibility.SessionNotification
import darkmatter.androiduse.app.accessibility.TransportKind
import darkmatter.androiduse.app.accessibility.ApprovedHostState
import darkmatter.androiduse.app.accessibility.UseService
import darkmatter.androiduse.app.accessibility.isAccessibilityServiceEnabled
import darkmatter.androiduse.app.actions.ActionLogEntry
import darkmatter.androiduse.app.actions.ActionLogStore
import darkmatter.androiduse.app.apps.AllowedAppsSelection
import darkmatter.androiduse.app.apps.LaunchableApp
import darkmatter.androiduse.app.apps.LaunchableAppRepository
import darkmatter.androiduse.app.apps.PackageManagerLauncherActivitySource
import darkmatter.androiduse.app.overlay.OverlayOptions

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
    val endpointAddress: String? = null,
    val endpointPort: Int? = null,
    val deviceFingerprintSha256: String? = null,
    val deviceCertificatePem: String? = null,
    val pairingCode: String? = null,
    val pairingExpiresAtMillis: Long? = null,
    val pendingHostId: String? = null,
    val pendingHostName: String? = null,
    val pendingHostFingerprintSha256: String? = null,
    val approvedHosts: List<ApprovedHostState> = emptyList(),
    val transportError: String? = null,
    val overlayOptions: OverlayOptions = OverlayOptions(false, true, true, true, true),
    val launchableApps: List<LaunchableApp> = emptyList(),
    val allowedLaunchPackages: Set<String> = emptySet(),
    val keepScreenOnEnabled: Boolean = false,
    val listenerPortSetting: Int = ListenerPortConfig.DEFAULT_PORT,
) {
    override fun toString(): String = "HomeUiState(deviceModel=$deviceModel, apiLevel=$apiLevel, " +
        "accessibilityEnabled=$accessibilityEnabled, accessibilityConnected=$accessibilityConnected, " +
        "remoteEnabled=$remoteEnabled, activeHost=$activeHost, transport=$transport, " +
        "sessionActive=$sessionActive, firstRunExplanationVisible=$firstRunExplanationVisible, " +
        "notificationPermissionMissing=$notificationPermissionMissing, statusMessage=$statusMessage, " +
        "endpointAddress=$endpointAddress, endpointPort=$endpointPort, approvedHostCount=${approvedHosts.size})"
}

internal fun remoteConnectionStatus(service: ServiceState): String = when {
    service.sessionActive && service.activeHost != null -> "Connected to ${service.activeHost}."
    service.remoteEnabled -> "Remote control is on. Waiting for an authenticated host."
    else -> "Remote control is off."
}

internal fun listenerEndpointLabel(transport: TransportKind, address: String?, port: Int?): String = when {
    transport == TransportKind.WIFI && address != null && port != null -> "$address:$port"
    transport == TransportKind.WIFI -> "Wi-Fi listener unavailable"
    address != null && port != null -> "Loopback (local only)"
    else -> "Not listening"
}

/**
 * Home-screen coordinator. It owns no Android node handles and only observes the
 * service state plus the bounded action log.
 */
class HomeViewModel(
    context: Context,
    private val actionLogStore: ActionLogStore = ActionLogStore.shared,
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val launchableApps = MutableStateFlow(
        LaunchableAppRepository(PackageManagerLauncherActivitySource(appContext.packageManager)).listLaunchableApps(),
    )
    private val allowedLaunchPackages = MutableStateFlow(ServicePreferences.allowedLaunchPackages(appContext))
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
            launchableApps = launchableApps.value,
            allowedLaunchPackages = allowedLaunchPackages.value,
        ),
    )

    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    init {
        UseService.refreshState(appContext)
        scope.launch {
            val appsSelection = combine(launchableApps, allowedLaunchPackages) { catalog, allowed -> catalog to allowed }
            combine(
                UseService.state,
                actionLogStore.recentEntries,
                explanationAcknowledged,
                statusOverride,
                appsSelection,
            ) { service, actions, acknowledged, override, selection ->
                buildState(service, actions, acknowledged, override, selection.first, selection.second)
            }.collect { mutableState.value = it }
        }
    }

    /** Re-reads Android settings, e.g. after returning from accessibility settings. */
    fun refresh() {
        explanationAcknowledged.value = ServicePreferences.hasAcknowledgedExplanation(appContext)
        launchableApps.value = LaunchableAppRepository(PackageManagerLauncherActivitySource(appContext.packageManager)).listLaunchableApps()
        allowedLaunchPackages.value = ServicePreferences.allowedLaunchPackages(appContext)
        UseService.refreshState(appContext)
    }

    fun setAppAllowed(packageName: String, allowed: Boolean) {
        if (launchableApps.value.none { it.packageName == packageName }) return
        val selection = AllowedAppsSelection(allowedLaunchPackages.value).setAllowed(packageName, allowed)
        ServicePreferences.setAllowedLaunchPackages(appContext, selection.allowedPackages)
        allowedLaunchPackages.value = selection.allowedPackages
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
            enabled -> "Remote control could not start. Check the accessibility binding, notification visibility, and listener availability."
            else -> "Remote control is already off."
        }
    }

    fun disconnect() {
        UseService.requestDisconnect(appContext)
        statusOverride.value = "Remote control stopped and session metadata cleared."
    }

    fun setWifiEnabled(enabled: Boolean) {
        val changed = UseService.setWifiEnabled(appContext, enabled)
        statusOverride.value = if (changed) {
            if (enabled) "Wi-Fi listener enabled on the current local IPv4 address." else "Wi-Fi listener disabled; loopback listener remains active."
        } else if (enabled) {
            "Wi-Fi listener could not start. Confirm remote control is on and Wi-Fi is connected."
        } else {
            "Could not restore the loopback listener. Stop and re-enable remote control."
        }
    }

    fun setOverlayOptions(options: OverlayOptions) {
        UseService.setOverlayOptions(appContext, options)
        statusOverride.value = if (options.inspector) {
            "Inspector overlay enabled independently of remote control."
        } else {
            "Inspector overlay disabled."
        }
    }

    fun setKeepScreenOnEnabled(enabled: Boolean) {
        UseService.setKeepScreenOnEnabled(appContext, enabled)
        mutableState.value = mutableState.value.copy(keepScreenOnEnabled = enabled)
    }

    fun setListenerPort(port: Int) {
        if (UseService.state.value.remoteEnabled || port !in 1024..65535) return
        ServicePreferences.setListenerPort(appContext, port)
        mutableState.value = mutableState.value.copy(listenerPortSetting = port)
        statusOverride.value = "Listener port saved. It applies the next time remote control starts."
    }

    fun beginPairing() {
        val challenge = UseService.beginPairing(appContext)
        statusOverride.value = if (challenge != null) {
            "Pairing code created. It expires in five minutes; approve the host here."
        } else {
            "Turn on remote control before creating a pairing code."
        }
        if (challenge != null) {
            scope.launch {
                delay((challenge.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0L))
                UseService.refreshState(appContext)
            }
        }
    }

    fun approveHost(hostId: String) {
        UseService.approveHost(hostId)
        statusOverride.value = "Host approval recorded on this phone."
    }

    fun rejectHost(hostId: String) {
        UseService.rejectHost(hostId)
        statusOverride.value = "Pending host rejected on this phone."
    }

    fun revokeHost(hostId: String) {
        UseService.revokeHost(hostId)
        statusOverride.value = "Host authorization revoked."
    }

    override fun close() {
        scope.cancel()
    }

    private fun buildState(
        service: ServiceState,
        actions: List<ActionLogEntry>,
        acknowledged: Boolean,
        statusOverride: String?,
        launchableApps: List<LaunchableApp>,
        allowedLaunchPackages: Set<String>,
    ): HomeUiState {
        val accessibilityEnabled = service.enabled || isAccessibilityServiceEnabled(appContext)
        val status = statusOverride ?: when {
            !accessibilityEnabled ->
                "Accessibility is off. Enable AndroidUse in Android accessibility settings."
            !service.connected ->
                "Accessibility is enabled, but the service is not connected yet."
            else -> "Accessibility is ready. ${remoteConnectionStatus(service)}"
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
            endpointAddress = service.endpoint?.address?.hostAddress,
            endpointPort = service.endpoint?.port,
            deviceFingerprintSha256 = service.deviceFingerprintSha256,
            deviceCertificatePem = service.deviceCertificatePem,
            pairingCode = service.pairingCode,
            pairingExpiresAtMillis = service.pairingExpiresAtMillis,
            pendingHostId = service.pendingHostId,
            pendingHostName = service.pendingHostName,
            pendingHostFingerprintSha256 = service.pendingHostFingerprintSha256,
            approvedHosts = service.approvedHosts,
            transportError = service.lastError,
            overlayOptions = service.overlayOptions,
            launchableApps = launchableApps,
            allowedLaunchPackages = allowedLaunchPackages,
            keepScreenOnEnabled = ServicePreferences.isKeepScreenOnEnabled(appContext),
            listenerPortSetting = ServicePreferences.listenerPort(appContext),
        )
    }
}
