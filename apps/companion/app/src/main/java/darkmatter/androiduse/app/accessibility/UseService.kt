package darkmatter.androiduse.app.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import darkmatter.androiduse.app.actions.ActionBackend
import darkmatter.androiduse.app.actions.ActionCancellation
import darkmatter.androiduse.app.actions.ActionRequest
import darkmatter.androiduse.app.actions.ActionExecutor
import darkmatter.androiduse.app.actions.ActionLogStore
import darkmatter.androiduse.app.actions.ActionNode
import darkmatter.androiduse.app.actions.GestureKind
import darkmatter.androiduse.app.actions.GestureResult
import darkmatter.androiduse.app.actions.GestureSpec
import darkmatter.androiduse.app.actions.GlobalAction
import darkmatter.androiduse.app.actions.ScrollDirection
import darkmatter.androiduse.app.actions.AppLaunchPolicy
import darkmatter.androiduse.app.actions.AppLaunchValidation
import darkmatter.androiduse.app.MainActivity
import darkmatter.androiduse.app.R
import darkmatter.androiduse.app.network.UiBounds
import darkmatter.androiduse.app.network.UiSnapshot
import darkmatter.androiduse.app.selectors.CurrentTree
import darkmatter.androiduse.app.selectors.NodePath
import darkmatter.androiduse.app.selectors.NodePathMissingException
import darkmatter.androiduse.app.selectors.NodePathStaleException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.BindException
import darkmatter.androiduse.app.network.BindMode
import darkmatter.androiduse.app.network.CompanionRpcHandler
import darkmatter.androiduse.app.network.DeviceTls
import darkmatter.androiduse.app.network.RpcEndpoint
import darkmatter.androiduse.app.network.RpcError
import darkmatter.androiduse.app.network.RpcErrorCode
import darkmatter.androiduse.app.network.RpcResponse
import darkmatter.androiduse.app.network.RpcProtocol
import darkmatter.androiduse.app.network.PROTOCOL_VERSION
import darkmatter.androiduse.app.network.RpcServer
import darkmatter.androiduse.app.network.RpcRequest
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import darkmatter.androiduse.app.pairing.PairingChallenge
import darkmatter.androiduse.app.pairing.PairingManager
import darkmatter.androiduse.app.pairing.SharedPreferencesPairingStore
import darkmatter.androiduse.app.security.DeviceCertificate
import darkmatter.androiduse.app.security.DeviceIdentity
import darkmatter.androiduse.app.screenshots.ScreenshotCapture
import darkmatter.androiduse.app.screenshots.ScreenshotResult
import darkmatter.androiduse.app.network.ScreenshotRpcResult
import darkmatter.androiduse.app.network.AppListRpcResult
import darkmatter.androiduse.app.network.NsdWifiServiceAdvertiser
import darkmatter.androiduse.app.network.WifiServiceAdvertiser
import darkmatter.androiduse.app.apps.LaunchableAppRepository
import darkmatter.androiduse.app.apps.PackageManagerLauncherActivitySource
import darkmatter.androiduse.app.overlay.InspectorOverlay
import darkmatter.androiduse.app.overlay.OverlayDisplay
import darkmatter.androiduse.app.overlay.OverlayModel
import darkmatter.androiduse.app.overlay.OverlayOptions

/**
 * Accessibility service that exposes bounded, on-demand snapshots.
 *
 * The service never keeps an [AccessibilityNodeInfo] past the call that acquired
 * it. [snapshot] acquires the active root, walks it with a short-lived adapter,
 * and releases every node in `finally`. Accessibility events only invalidate a
 * revision; they do not acquire or retain nodes.
 *
 * Enabling this service is exclusively a user action in Android settings. The
 * app links to those settings and cannot turn the service on by itself.
 */
class UseService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val revision = AtomicInteger(0)
    private var debounceJob: Job? = null
    private var overlayActionJob: Job? = null

    @Volatile
    private var connected: Boolean = false

    @Volatile
    private var activeHost: String? = null

    @Volatile
    private var activeHostFingerprint: String? = null

    private val controllerLeaseState = ControllerLeaseState()

    @Volatile
    private var transport: TransportKind = TransportKind.NONE

    @Volatile
    private var sessionActive: Boolean = false

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var rpcServer: RpcServer? = null

    @Volatile
    private var wifiServiceAdvertiser: WifiServiceAdvertiser? = null

    @Volatile
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var pairingManager: PairingManager? = null

    @Volatile
    private var deviceCertificate: DeviceCertificate? = null

    @Volatile
    private var pairingChallenge: PairingChallenge? = null

    @Volatile
    private var inspectorOverlay: InspectorOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wifiServiceAdvertiser = NsdWifiServiceAdvertiser(this, ::recordWifiDiscoveryError)
        transportLifecycle = TransportLifecycle(
            start = { mode -> startTransport(mode, ServicePreferences.listenerPort(this)) },
            stop = ::stopTransport,
            publishWifiEndpoint = { endpoint ->
                if (endpoint == null) wifiServiceAdvertiser?.stop()
                else wifiServiceAdvertiser?.advertise(endpoint)
            },
        )
        connected = true
        lastError = null
        inspectorOverlay = runCatching { InspectorOverlay(this, onError = ::recordOverlayError) }.getOrNull()
        configureOverlay()
        overlayActionJob?.cancel()
        overlayActionJob = scope.launch {
            ActionLogStore.shared.recentEntries.collect { refreshOverlay() }
        }

        if (!RemoteControlToggle.restoreAfterServiceRestart(ServicePreferences.isRemoteEnabled(this))) {
            ServicePreferences.setRemoteEnabled(this, false)
            SessionNotification.cancel(this)
        }
        updateKeepScreenOn()
        publishState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type !in OBSERVED_EVENT_TYPES) {
            return
        }
        invalidateTree()
    }

    override fun onInterrupt() {
        lastError = "Accessibility feedback was interrupted"
        publishState()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        transportLifecycle?.onUnbind()
        unregisterWifiNetworkCallback()
        wifiServiceAdvertiser?.stop()
        wifiServiceAdvertiser = null
        connected = false
        overlayActionJob?.cancel()
        overlayActionJob = null
        inspectorOverlay?.close()
        inspectorOverlay = null
        instance = null
        // An unbound accessibility service cannot control anything. Fail closed
        // instead of leaving a stale "remote enabled" state or notification.
        ServicePreferences.setRemoteEnabled(this, false)
        clearSession()
        SessionNotification.cancel(this)
        publishCompanionState(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        transportLifecycle?.onDestroy()
        unregisterWifiNetworkCallback()
        wifiServiceAdvertiser?.stop()
        wifiServiceAdvertiser = null
        debounceJob?.cancel()
        overlayActionJob?.cancel()
        overlayActionJob = null
        scope.cancel()
        connected = false
        inspectorOverlay?.close()
        inspectorOverlay = null
        instance = null
        ServicePreferences.setRemoteEnabled(this, false)
        activeHost = null
        activeHostFingerprint = null
        transport = TransportKind.NONE
        sessionActive = false
        SessionNotification.cancel(this)
        publishCompanionState(this)
        super.onDestroy()
    }

    /**
     * Acquires the current root only for this call and always releases it. The
     * returned [UiSnapshot] contains copies of primitive values, never node
     * handles.
     */
    @Synchronized
    fun snapshot(mode: SnapshotMode = SnapshotMode.FULL): UiSnapshot {
        val root = rootInActiveWindow
            ?: throw IllegalStateException("No active accessibility window")
        val resources = NodeResources()
        try {
            resources.track(root)
            val revisionAtStart = revision.get()
            val result = TreeSnapshotter.serialize(
                root = AndroidSnapshotNode(root, resources),
                deviceId = deviceId(),
                windowId = root.windowId.toString(),
                revision = revisionAtStart,
                limits = SnapshotLimits(),
                mode = mode,
                packageName = root.packageName?.toString(),
                windowTitle = null,
            )
            lastError = null
            publishState()
            return result
        } finally {
            resources.releaseAll()
        }
    }

    /** Task 5 calls this once an authenticated host/session exists. */
    fun updateSession(
        host: String?,
        transport: TransportKind,
        active: Boolean,
        fingerprintSha256: String? = null,
        leaseId: String? = null,
    ) {
        if (leaseId == null) controllerLeaseState.clear()
        else if (!controllerLeaseState.update(leaseId, active)) return
        activeHost = host
        activeHostFingerprint = fingerprintSha256.takeIf { active }
        this.transport = transport
        sessionActive = active
        publishState()
    }

    /** Current event-driven revision, readable without acquiring a node. */
    fun currentRevision(): Int = revision.get()

    /** Platform backend for the local native action engine. */
    fun actionBackend(): ActionBackend = AndroidActionBackend()

    fun actionExecutor(logStore: ActionLogStore = ActionLogStore.shared): ActionExecutor =
        ActionExecutor(backend = actionBackend(), logStore = logStore)

    @Synchronized
    private fun invalidateTree() {
        // Revision changes immediately so refs become stale as soon as the user
        // interface changes; only state publication is debounced.
        revision.incrementAndGet()
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(INVALIDATION_DEBOUNCE_MILLIS)
            publishState()
            refreshOverlay()
        }
    }

    private fun clearSession() {
        controllerLeaseState.clear()
        activeHost = null
        activeHostFingerprint = null
        transport = TransportKind.NONE
        sessionActive = false
    }

    private fun publishState() {
        val pairing = pairingManager
        val certificate = deviceCertificate
        val endpoint = rpcServer?.endpoint
        val pending = pairing?.pendingHostInfo()
        mutableState.value = ServiceState(
            enabled = isAccessibilityServiceEnabled(this) || connected,
            connected = connected,
            remoteEnabled = ServicePreferences.isRemoteEnabled(this),
            activeHost = activeHost,
            transport = endpoint?.let {
                if (it.bindMode is BindMode.Wifi) TransportKind.WIFI else TransportKind.NONE
            } ?: transport,
            sessionActive = sessionActive,
            lastSnapshotRevision = revision.get(),
            lastError = lastError,
            endpoint = endpoint,
            deviceFingerprintSha256 = certificate?.fingerprintSha256,
            deviceCertificatePem = certificate?.certificatePem,
            pairingCode = pairingChallenge?.code?.takeIf {
                pairingChallenge?.let { challenge -> System.currentTimeMillis() < challenge.expiresAtMillis } == true
            },
            pairingExpiresAtMillis = pairingChallenge?.expiresAtMillis,
            pendingHostId = pending?.hostId,
            pendingHostName = pending?.hostName,
            pendingHostFingerprintSha256 = pending?.fingerprintSha256,
            approvedHosts = pairing?.approvedHosts()?.map {
                ApprovedHostState(it.hostId, it.hostName, it.fingerprintSha256)
            }.orEmpty(),
            overlayOptions = ServicePreferences.overlayOptions(this),
        )
    }

    private fun configureOverlay(options: OverlayOptions = ServicePreferences.overlayOptions(this)) {
        inspectorOverlay?.setOptions(options)
        if (options.inspector && connected) refreshOverlay()
    }

    private fun updateKeepScreenOn() {
        val keepScreenOn = RemoteOverlayPolicy.shouldKeepScreenOn(
            preferenceEnabled = ServicePreferences.isKeepScreenOnEnabled(this),
            remoteEnabled = ServicePreferences.isRemoteEnabled(this),
            serviceConnected = connected,
        )
        inspectorOverlay?.setKeepScreenOn(keepScreenOn)
    }

    private fun refreshOverlay() {
        if (!connected) return
        val overlay = inspectorOverlay ?: return
        val options = ServicePreferences.overlayOptions(this)
        if (!options.inspector) {
            overlay.setOptions(options)
            return
        }
        runCatching {
            val snapshot = snapshot(SnapshotMode.FULL)
            val metrics = resources.displayMetrics
            val rotation = when (getSystemService(android.view.WindowManager::class.java)?.defaultDisplay?.rotation) {
                android.view.Surface.ROTATION_90 -> 90
                android.view.Surface.ROTATION_180 -> 180
                android.view.Surface.ROTATION_270 -> 270
                else -> 0
            }
            val frame = OverlayModel.from(
                snapshot,
                ActionLogStore.shared.recentEntries.value,
                options,
                OverlayDisplay(metrics.widthPixels, metrics.heightPixels, metrics.density, rotation),
            )
            overlay.setOptions(options)
            overlay.update(frame)
        }.onFailure { error -> recordOverlayError(error as? Exception ?: RuntimeException("overlay update failed")) }
    }

    private fun recordOverlayError(error: Exception) {
        // Keep visualization failures in the existing redacted diagnostic channel.
        lastError = "Inspector overlay unavailable (${error.javaClass.simpleName})"
    }

    private fun recordWifiDiscoveryError(message: String) {
        scope.launch {
            lastError = message
            publishState()
        }
    }

    private fun registerWifiNetworkCallback(): Boolean {
        if (wifiNetworkCallback != null) return true
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshWifiTransportFromNetwork()

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) =
                refreshWifiTransportFromNetwork()

            override fun onLost(network: Network) = refreshWifiTransportFromNetwork()
        }
        return try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            manager.registerNetworkCallback(request, callback)
            wifiNetworkCallback = callback
            true
        } catch (_: SecurityException) {
            lastError = "Wi-Fi network monitoring permission is unavailable"
            false
        } catch (_: RuntimeException) {
            lastError = "Wi-Fi network monitoring could not start"
            false
        }
    }

    private fun unregisterWifiNetworkCallback() {
        val callback = wifiNetworkCallback ?: return
        wifiNetworkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    private fun refreshWifiTransportFromNetwork() {
        scope.launch(Dispatchers.Main.immediate) {
            val address = activeWifiAddress()
            val accepted = transportLifecycle?.refreshWifiAddress(address) == true
            if (accepted) {
                lastError = null
                publishState()
                return@launch
            }
            unregisterWifiNetworkCallback()
            ServicePreferences.setRemoteEnabled(this@UseService, false)
            SessionNotification.cancel(this@UseService)
            clearSession()
            lastError = if (address == null) {
                "Wi-Fi network unavailable; remote control was stopped"
            } else {
                "Wi-Fi listener could not be rebound; remote control was stopped"
            }
            updateKeepScreenOn()
            publishState()
        }
    }

    private fun hideOverlayForCapture(): AutoCloseable = inspectorOverlay?.hideForCapture() ?: AutoCloseable { }

    private fun deviceId(): String = ServicePreferences.deviceId(this)

    /** Native actions always resolve a fresh tree and release every acquired node. */
    private inner class AndroidActionBackend : ActionBackend {
        override fun currentRevision(): Int = revision.get()

        override fun currentTree(): CurrentTree = CurrentTree(snapshot(SnapshotMode.FULL))

        @Synchronized
        override fun <T> withNode(path: NodePath, block: (ActionNode) -> T): T {
            if (path.revision != revision.get() || path.ref != TreeSnapshotter.refFor(path.revision, path.index)) {
                throw NodePathStaleException()
            }
            val root = rootInActiveWindow ?: throw NodePathMissingException()
            val resources = NodeResources()
            try {
                resources.track(root)
                val node = locateNode(root, path.index, resources) ?: throw NodePathMissingException()
                if (path.revision != revision.get()) throw NodePathStaleException()
                return block(AndroidActionNode(node))
            } finally {
                resources.releaseAll()
            }
        }

        override fun dispatchGesture(spec: GestureSpec): GestureResult {
            if ((serviceInfo?.capabilities ?: 0) and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES == 0) {
                return GestureResult(success = false, unsupported = true, message = "Gesture dispatch is not supported")
            }
            val path = Path().apply {
                moveTo(spec.startX.toFloat(), spec.startY.toFloat())
                if (spec.kind == GestureKind.SWIPE) {
                    lineTo(requireNotNull(spec.endX).toFloat(), requireNotNull(spec.endY).toFloat())
                }
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, spec.durationMillis))
                .build()
            val latch = CountDownLatch(1)
            var completed = false
            var cancelled = false
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { completed = true; latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { cancelled = true; latch.countDown() }
            }
            val dispatched = try {
                dispatchGesture(gesture, callback, Handler(Looper.getMainLooper()))
            } catch (_: RuntimeException) {
                false
            }
            if (!dispatched) return GestureResult(success = false, message = "Gesture dispatch was rejected")
            val finished = try {
                latch.await(spec.timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            return when {
                !finished -> GestureResult(success = false, timedOut = true, message = "Gesture did not complete before timeout")
                cancelled -> GestureResult(success = false, message = "Gesture was cancelled by the system")
                completed -> GestureResult(success = true)
                else -> GestureResult(success = false, message = "Gesture outcome is unknown")
            }
        }

        override fun performGlobalAction(action: GlobalAction): Boolean = when (action) {
            GlobalAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            GlobalAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            GlobalAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            GlobalAction.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }

        private fun locateNode(
            root: AccessibilityNodeInfo,
            targetIndex: Int,
            resources: NodeResources,
        ): AccessibilityNodeInfo? {
            var visited = -1
            fun visit(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
                visited++
                if (visited == targetIndex) return node
                if (visited > targetIndex || depth > SnapshotLimits.DEFAULT_MAX_DEPTH) return null
                for (index in 0 until node.childCount) {
                    if (visited >= targetIndex) return null
                    val child = try { node.getChild(index) } catch (_: RuntimeException) { null } ?: continue
                    resources.track(child)
                    visit(child, depth + 1)?.let { return it }
                }
                return null
            }
            return visit(root, 0)
        }

        private inner class AndroidActionNode(private val node: AccessibilityNodeInfo) : ActionNode {
            override val clickable get() = node.isClickable
            override val editable get() = node.isEditable
            override val enabled get() = node.isEnabled
            override val visible get() = node.isVisibleToUser
            override val scrollable get() = node.isScrollable
            override val bounds: UiBounds
                get() = Rect().let { rect ->
                    node.getBoundsInScreen(rect)
                    UiBounds(rect.left, rect.top, rect.right, rect.bottom)
                }
            override val resourceId: String? get() = node.viewIdResourceName
            override fun click() = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            override fun longClick() = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            override fun setText(value: String): Boolean = node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) },
            )
            override fun scroll(direction: ScrollDirection): Boolean = when (direction) {
                ScrollDirection.UP, ScrollDirection.BACKWARD -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                ScrollDirection.DOWN, ScrollDirection.FORWARD -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                ScrollDirection.LEFT -> node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id)
                ScrollDirection.RIGHT -> node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id)
            }
        }
    }

    // ----------------------------------------------------------- node adapter

    /**
     * Owns every node acquired during one snapshot. Nodes are released in reverse
     * acquisition order, including the root, so no handle survives the call.
     */
    private class NodeResources {
        private val nodes = mutableListOf<AccessibilityNodeInfo>()

        fun track(node: AccessibilityNodeInfo) {
            nodes += node
        }

        fun releaseAll() {
            for (index in nodes.indices.reversed()) {
                try {
                    nodes[index].recycle()
                } catch (_: RuntimeException) {
                    // A stale/recycled node must not mask the snapshot result.
                }
            }
            nodes.clear()
        }
    }

    private class AndroidSnapshotNode(
        private val node: AccessibilityNodeInfo,
        private val resources: NodeResources,
    ) : SnapshotNode {

        override val className: String? get() = node.className?.toString()
        override val role: String? get() = roleFor(node)
        override val text: String? get() = node.text?.toString()
        override val contentDescription: String? get() = node.contentDescription?.toString()
        override val resourceId: String? get() = node.viewIdResourceName
        override val bounds: UiBounds
            get() = Rect().let { rect ->
                node.getBoundsInScreen(rect)
                UiBounds(rect.left, rect.top, rect.right, rect.bottom)
            }
        override val clickable: Boolean get() = node.isClickable
        override val scrollable: Boolean get() = node.isScrollable
        override val editable: Boolean get() = node.isEditable
        override val enabled: Boolean get() = node.isEnabled
        override val focused: Boolean get() = node.isFocused
        override val visible: Boolean get() = node.isVisibleToUser
        override val checked: Boolean? get() = if (node.isCheckable) node.isChecked else null
        override val supportedActions: List<String>
            get() = node.actionList.mapNotNull { actionName(it) }
        override val isPassword: Boolean get() = node.isPassword
        override val childCount: Int get() = node.childCount

        override fun childAt(index: Int): SnapshotNode? {
            val child = try {
                node.getChild(index)
            } catch (_: RuntimeException) {
                null
            } ?: return null
            resources.track(child)
            return AndroidSnapshotNode(child, resources)
        }
    }

    companion object {
        private const val INVALIDATION_DEBOUNCE_MILLIS = 250L

        private val OBSERVED_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
        )

        private val mutableState = MutableStateFlow(ServiceState())

        /** Observable, honest state for the home screen. */
        val state: StateFlow<ServiceState> = mutableState.asStateFlow()

        @Volatile
        private var instance: UseService? = null

        @Volatile
        private var transportLifecycle: TransportLifecycle? = null

        /** Recomputes enabled/connected state from Android settings and binding. */
        fun refreshState(context: Context) {
            publishCompanionState(context.applicationContext)
        }

        /**
         * Enables/disables the explicit remote-control toggle. Enabling is
         * fail-closed: if the required ongoing notification cannot be posted, the
         * preference is rolled back and this returns `false`.
         */
        fun setRemoteEnabled(context: Context, enabled: Boolean): Boolean {
            val appContext = context.applicationContext
            val current = instance
            val accepted = RemoteControlToggle.apply(
                enabled = enabled,
                accessibilityEnabled = isAccessibilityServiceEnabled(appContext),
                connected = current?.connected == true,
                notificationAllowed = SessionNotification.canPost(appContext),
                persistRemoteEnabled = { value ->
                    ServicePreferences.setRemoteEnabled(appContext, value)
                },
                postNotification = {
                    val visible = SessionNotification.show(appContext)
                    visible && (!enabled || current?.transportLifecycleEnable() == true)
                },
                cancelNotification = { SessionNotification.cancel(appContext) },
            )

            if (accepted && enabled) {
                current?.lastError = null
            } else if (accepted) {
                current?.transportLifecycleDisable()
                current?.clearSession()
            } else if (enabled) {
                current?.let { service ->
                    service.setTransportError(
                        service.lastError ?: "Remote listener failed to start; remote control remains off",
                    )
                }
            }
            current?.updateKeepScreenOn()
            current?.publishState() ?: publishCompanionState(appContext)
            return accepted
        }

        /** Stops the local toggle and clears any session metadata immediately. */
        fun requestDisconnect(context: Context) {
            val appContext = context.applicationContext
            ServicePreferences.setRemoteEnabled(appContext, false)
            SessionNotification.cancel(appContext)
            instance?.let { service ->
                service.transportLifecycleDisable()
                service.clearSession()
                service.updateKeepScreenOn()
                service.publishState()
            } ?: publishCompanionState(appContext)
        }

        fun setOverlayOptions(context: Context, options: OverlayOptions) {
            ServicePreferences.setOverlayOptions(context.applicationContext, options)
            instance?.configureOverlay(options)
            instance?.publishState() ?: publishCompanionState(context.applicationContext)
        }

        fun setKeepScreenOnEnabled(context: Context, enabled: Boolean) {
            val appContext = context.applicationContext
            ServicePreferences.setKeepScreenOnEnabled(appContext, enabled)
            instance?.let { service ->
                service.updateKeepScreenOn()
                service.publishState()
            } ?: publishCompanionState(appContext)
        }

        private fun publishCompanionState(context: Context) {
            val current = instance
            if (current != null) {
                current.publishState()
                return
            }
            mutableState.value = ServiceState(
                enabled = isAccessibilityServiceEnabled(context),
                connected = current?.connected == true,
                remoteEnabled = ServicePreferences.isRemoteEnabled(context),
                activeHost = current?.activeHost,
                transport = current?.transport ?: TransportKind.NONE,
                sessionActive = current?.sessionActive == true,
                lastSnapshotRevision = current?.revision?.get()
                    ?: mutableState.value.lastSnapshotRevision,
                lastError = current?.lastError,
                overlayOptions = ServicePreferences.overlayOptions(context),
            )
        }

        fun setWifiEnabled(context: Context, enabled: Boolean): Boolean {
            val service = instance ?: return false
            var accepted = if (enabled) {
                val address = activeWifiAddress() ?: return false
                service.transportLifecycleWifi(address)
            } else {
                service.transportLifecycleLoopback()
            }
            if (enabled && accepted) accepted = service.registerWifiNetworkCallback()
            if (!enabled || !accepted) service.unregisterWifiNetworkCallback()
            if (!accepted) {
                service.transportLifecycleDisable()
                ServicePreferences.setRemoteEnabled(context.applicationContext, false)
                SessionNotification.cancel(context.applicationContext)
                service.clearSession()
                service.setTransportError(
                    service.lastError ?: "Listener could not be restarted; remote control was stopped",
                )
            }
            service.updateKeepScreenOn()
            service.publishState()
            return accepted
        }

        fun beginPairing(context: Context): PairingChallenge? = instance?.let { service ->
            if (!ServicePreferences.isRemoteEnabled(context.applicationContext)) return@let null
            service.pairingManager?.begin()?.also {
                service.pairingChallenge = it
                service.publishState()
            }
        }

        fun approveHost(hostId: String) = instance?.let { service ->
            service.pairingManager?.approve(hostId)
            service.publishState()
        }

        fun rejectHost(hostId: String) = instance?.let { service ->
            service.pairingManager?.reject(hostId)
            service.publishState()
        }

        fun revokeHost(hostId: String) = instance?.let { service ->
            val revoked = service.pairingManager?.approvedHosts()?.firstOrNull { it.hostId == hostId }
            service.pairingManager?.revoke(hostId)
            if (revoked?.fingerprintSha256 == service.activeHostFingerprint) {
                service.updateSession(null, TransportKind.NONE, false)
            }
            service.publishState()
        }

        private fun activeWifiAddress(): java.net.InetAddress? {
            return runCatching {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
                val candidates = java.util.Collections.list(interfaces)
                    .filter { networkInterface -> runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false) }
                    .flatMap { networkInterface ->
                        java.util.Collections.list(networkInterface.inetAddresses).map { networkInterface.name to it }
                    }
                WifiAddressSelector.select(candidates)
            }.getOrNull()
        }
    }

    private fun transportLifecycleEnable(): Boolean = transportLifecycle?.enable(
        accessibilityReady = connected && isAccessibilityServiceEnabled(this),
        notificationVisible = SessionNotification.canPost(this) && ServicePreferences.isRemoteEnabled(this),
    ) == true

    private fun transportLifecycleDisable() {
        unregisterWifiNetworkCallback()
        transportLifecycle?.disable()
    }

    private fun transportLifecycleWifi(address: java.net.InetAddress): Boolean = transportLifecycle?.enableWifi(address) == true

    private fun transportLifecycleLoopback(): Boolean {
        unregisterWifiNetworkCallback()
        return transportLifecycle?.enableLoopback() == true
    }

    private fun startTransport(mode: BindMode, port: Int): RpcEndpoint {
        val certificate = DeviceIdentity.getOrCreate(this)
        val pairing = pairingManager ?: PairingManager(SharedPreferencesPairingStore(this)).also { pairingManager = it }
        val cancellations = java.util.concurrent.ConcurrentHashMap<String, ActionCancellation>()
        val executor = actionExecutor()
        val handler = CompanionRpcHandler(
            pairingManager = pairing,
            deviceStatus = { session ->
                kotlinx.serialization.json.buildJsonObject {
                    put("deviceFingerprintSha256", kotlinx.serialization.json.JsonPrimitive(certificate.fingerprintSha256))
                    put("activeHost", kotlinx.serialization.json.JsonPrimitive(activeHost ?: ""))
                    put("sessionActive", kotlinx.serialization.json.JsonPrimitive(sessionActive))
                    put("listenerPort", kotlinx.serialization.json.JsonPrimitive(rpcServer?.endpoint?.port ?: 0))
                }
            },
            businessDispatcher = { request, _ ->
                dispatchRemoteRequest(request, executor, cancellations)
            },
            cancelAction = { requestId -> cancellations[requestId]?.let { it.cancel(); true } ?: false },
            pairingChanged = { publishState() },
        )
        val server = RpcServer(
            sslContext = DeviceTls.serverContext(certificate),
            pairingManager = pairing,
            handler = handler,
            sessionChanged = { lease, host ->
                val activeMode = rpcServer?.endpoint?.bindMode
                updateSession(
                    host?.hostName,
                    if (activeMode is BindMode.Wifi) TransportKind.WIFI else TransportKind.NONE,
                    host != null,
                    host?.fingerprintSha256,
                    lease.leaseId,
                )
            },
            sessionStopRequested = { stopRemoteFromController() },
        )
        return try {
            server.start(mode, port).also {
                deviceCertificate = certificate
                rpcServer = server
                transport = if (mode is BindMode.Wifi) TransportKind.WIFI else TransportKind.NONE
                lastError = null
            }
        } catch (error: Exception) {
            if (error is BindException) {
                lastError = "Listener port $port is already in use. Choose another port in Connection and session."
            }
            server.close()
            throw error
        }
    }

    private fun stopTransport() {
        rpcServer?.close()
        rpcServer = null
        pairingManager?.cancelPendingChallenge()
        pairingChallenge = null
        activeHost = null
        activeHostFingerprint = null
        controllerLeaseState.clear()
        transport = TransportKind.NONE
        sessionActive = false
    }

    private fun stopRemoteFromController() {
        ServicePreferences.setRemoteEnabled(this, false)
        SessionNotification.cancel(this)
        transportLifecycleDisable()
        clearSession()
        updateKeepScreenOn()
        publishState()
    }

    private fun setTransportError(message: String) {
        lastError = message
    }

    private fun dispatchRemoteRequest(
        request: RpcRequest,
        executor: ActionExecutor,
        cancellations: java.util.concurrent.ConcurrentHashMap<String, ActionCancellation>,
    ): RpcResponse {
        return try {
            val result = when (request.method) {
                "app.list" -> AppListRpcResult.encode(
                    LaunchableAppRepository(PackageManagerLauncherActivitySource(packageManager))
                        .listAllowedLaunchableApps(ServicePreferences.allowedLaunchPackages(this)),
                )
                "app.launch" -> {
                    val packageName = (request.params["packageName"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.takeIf { it.isString }?.content
                        ?: return RpcResponse(PROTOCOL_VERSION, request.requestId, error = RpcError(RpcErrorCode.INVALID_MESSAGE, "packageName is required"))
                    when (AppLaunchPolicy.validate(packageName, ServicePreferences.allowedLaunchPackages(this))) {
                        AppLaunchValidation.INVALID -> return RpcResponse(
                            PROTOCOL_VERSION, request.requestId,
                            error = RpcError(RpcErrorCode.INVALID_MESSAGE, "packageName is invalid"),
                        )
                        AppLaunchValidation.NOT_ALLOWLISTED -> return RpcResponse(
                            PROTOCOL_VERSION, request.requestId,
                            error = RpcError(
                                RpcErrorCode.UNAUTHORIZED,
                                "app package is not allowlisted on this phone",
                                kotlinx.serialization.json.buildJsonObject { put("reason", kotlinx.serialization.json.JsonPrimitive("NOT_ALLOWLISTED")) },
                            ),
                        )
                        AppLaunchValidation.ALLOWED -> Unit
                    }
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                        ?: return RpcResponse(
                            PROTOCOL_VERSION, request.requestId,
                            error = RpcError(
                                RpcErrorCode.NOT_FOUND,
                                "app package is unavailable or not visible",
                                kotlinx.serialization.json.buildJsonObject { put("reason", kotlinx.serialization.json.JsonPrimitive("PACKAGE_UNAVAILABLE")) },
                            ),
                        )
                    try {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                        kotlinx.serialization.json.buildJsonObject {
                            put("launched", kotlinx.serialization.json.JsonPrimitive(true))
                            put("packageName", kotlinx.serialization.json.JsonPrimitive(packageName))
                        }
                    } catch (_: Exception) {
                        return RpcResponse(
                            PROTOCOL_VERSION, request.requestId,
                            error = RpcError(
                                RpcErrorCode.INTERNAL,
                                "app launch was rejected by Android",
                                kotlinx.serialization.json.buildJsonObject {
                                    put("reason", kotlinx.serialization.json.JsonPrimitive("LAUNCH_REJECTED"))
                                },
                            ),
                        )
                    }
                }
                "screenshot.capture" -> {
                    val windowId = snapshot(SnapshotMode.ACTIONABLE_ONLY).windowId
                    when (val capture = ScreenshotCapture.capture(this, deviceId(), windowId, ::hideOverlayForCapture)) {
                        is ScreenshotResult.Success -> RpcProtocol.encodeScreenshot(
                            ScreenshotRpcResult(
                                imageBase64 = java.util.Base64.getEncoder().encodeToString(capture.bytes),
                                mimeType = capture.mimeType,
                                width = capture.width,
                                height = capture.height,
                                timestampMillis = capture.timestampMillis,
                                deviceId = capture.deviceId,
                                windowId = capture.windowId,
                            ),
                        )
                        is ScreenshotResult.Failure -> return RpcResponse(
                            PROTOCOL_VERSION,
                            request.requestId,
                            error = RpcError(
                                if (capture.reason == darkmatter.androiduse.app.screenshots.ScreenshotFailure.UNSUPPORTED) RpcErrorCode.UNSUPPORTED
                                else RpcErrorCode.INTERNAL,
                                when (capture.reason) {
                                    darkmatter.androiduse.app.screenshots.ScreenshotFailure.PROTECTED_CONTENT -> "screen content is protected"
                                    darkmatter.androiduse.app.screenshots.ScreenshotFailure.UNSUPPORTED -> "screenshot capture is unsupported"
                                    darkmatter.androiduse.app.screenshots.ScreenshotFailure.UNAVAILABLE -> "screenshot service is unavailable"
                                    darkmatter.androiduse.app.screenshots.ScreenshotFailure.SIZE_LIMIT -> "screenshot exceeds the size limit"
                                    darkmatter.androiduse.app.screenshots.ScreenshotFailure.CAPTURE_FAILED -> "screenshot capture failed"
                                },
                                details = kotlinx.serialization.json.buildJsonObject {
                                    put("reason", kotlinx.serialization.json.JsonPrimitive(capture.reason.name))
                                },
                            ),
                        )
                    }
                }
                "snapshot.get" -> {
                    val modeName = (request.params["mode"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.contentOrNull?.uppercase() ?: SnapshotMode.FULL.name
                    val mode = runCatching { SnapshotMode.valueOf(modeName) }.getOrDefault(SnapshotMode.FULL)
                    RpcProtocol.encodeSnapshot(snapshot(mode))
                }
                "element.action", "gesture.perform" -> {
                    val action = RpcProtocol.json.decodeFromJsonElement<ActionRequest>(request.params)
                    val token = ActionCancellation()
                    if (cancellations.putIfAbsent(request.requestId, token) != null) {
                        throw IllegalArgumentException("duplicate request id")
                    }
                    try {
                        RpcProtocol.encodeActionResult(executor.execute(action, token))
                    } finally {
                        cancellations.remove(request.requestId, token)
                    }
                }
                "session.start", "session.stop" -> kotlinx.serialization.json.JsonObject(emptyMap())
                else -> return RpcResponse(
                    PROTOCOL_VERSION,
                    request.requestId,
                    error = RpcError(RpcErrorCode.UNSUPPORTED, "RPC method is not supported by this companion"),
                )
            }
            RpcResponse(PROTOCOL_VERSION, request.requestId, result = result)
        } catch (_: kotlinx.serialization.SerializationException) {
            RpcResponse(
                PROTOCOL_VERSION,
                request.requestId,
                error = RpcError(RpcErrorCode.INVALID_MESSAGE, "request parameters were invalid"),
            )
        } catch (_: Exception) {
            RpcResponse(
                PROTOCOL_VERSION,
                request.requestId,
                error = RpcError(RpcErrorCode.INTERNAL, "request failed"),
            )
        }
    }
}

/** Maps node actions to the stable names exposed in snapshots. */
private fun actionName(action: AccessibilityNodeInfo.AccessibilityAction): String? =
    when (action.id) {
        AccessibilityNodeInfo.ACTION_CLICK -> "click"
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "longClick"
        AccessibilityNodeInfo.ACTION_SET_TEXT -> "setText"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scrollForward"
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scrollBackward"
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id -> "scrollLeft"
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id -> "scrollRight"
        AccessibilityNodeInfo.ACTION_FOCUS -> "focus"
        AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "clearFocus"
        AccessibilityNodeInfo.ACTION_SELECT -> "select"
        AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> "clearSelection"
        AccessibilityNodeInfo.ACTION_EXPAND -> "expand"
        AccessibilityNodeInfo.ACTION_COLLAPSE -> "collapse"
        AccessibilityNodeInfo.ACTION_DISMISS -> "dismiss"
        else -> action.label?.toString()?.takeIf { it.isNotBlank() }
    }

/** Best-effort role vocabulary for semantic selectors; never a security boundary. */
private fun roleFor(node: AccessibilityNodeInfo): String? {
    val className = node.className?.toString() ?: return null
    return when {
        node.isEditable -> "textField"
        className.endsWith("EditText") -> "textField"
        className.endsWith("Button") -> "button"
        className.endsWith("CheckBox") -> "checkbox"
        className.endsWith("RadioButton") -> "radio"
        className.endsWith("Switch") -> "switch"
        node.isScrollable -> "scrollView"
        className.endsWith("TextView") -> "text"
        className.endsWith("ImageView") -> "image"
        else -> null
    }
}

/**
 * Persistent, user-visible indication that remote control is on. It is a normal
 * ongoing notification, not a foreground service: the accessibility binding
 * itself is what keeps the process alive.
 */
object SessionNotification {

    const val CHANNEL_ID: String = "android_use_active_session"
    const val NOTIFICATION_ID: Int = 4103

    fun requiresPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun canPost(context: Context): Boolean =
        NotificationVisibilityPolicy.canShow(
            permissionGranted = !requiresPermission() || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
            appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            channelEnabled = context.getSystemService(NotificationManager::class.java)?.let { manager ->
                val channel = manager.getNotificationChannel(CHANNEL_ID)
                channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
            } == true,
        )

    fun show(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!canPost(appContext)) {
            return false
        }
        ensureChannel(appContext)
        if (!canPost(appContext)) {
            return false
        }
        return try {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, build(appContext))
            true
        } catch (_: SecurityException) {
            // Permission was revoked between the check and notify; fail closed.
            false
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.session_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.session_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun build(context: Context): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.session_notification_title))
            .setContentText(context.getString(R.string.session_notification_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.session_notification_text)),
            )
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
