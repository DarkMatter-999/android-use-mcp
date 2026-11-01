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
import darkmatter.androiduse.app.actions.ActionExecutor
import darkmatter.androiduse.app.actions.ActionLogStore
import darkmatter.androiduse.app.actions.ActionNode
import darkmatter.androiduse.app.actions.GestureKind
import darkmatter.androiduse.app.actions.GestureResult
import darkmatter.androiduse.app.actions.GestureSpec
import darkmatter.androiduse.app.actions.GlobalAction
import darkmatter.androiduse.app.actions.ScrollDirection
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

    @Volatile
    private var connected: Boolean = false

    @Volatile
    private var activeHost: String? = null

    @Volatile
    private var transport: TransportKind = TransportKind.NONE

    @Volatile
    private var sessionActive: Boolean = false

    @Volatile
    private var lastError: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected = true
        lastError = null

        val remote = ServicePreferences.isRemoteEnabled(this)
        if (remote && !SessionNotification.show(this)) {
            // Without the persistent notification the user cannot see that remote
            // control is active, so fail closed and require a new explicit toggle.
            ServicePreferences.setRemoteEnabled(this, false)
        }
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
        connected = false
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
        debounceJob?.cancel()
        scope.cancel()
        connected = false
        instance = null
        ServicePreferences.setRemoteEnabled(this, false)
        activeHost = null
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
    fun updateSession(host: String?, transport: TransportKind, active: Boolean) {
        activeHost = host
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
        }
    }

    private fun clearSession() {
        activeHost = null
        transport = TransportKind.NONE
        sessionActive = false
    }

    private fun publishState() {
        mutableState.value = ServiceState(
            enabled = isAccessibilityServiceEnabled(this) || connected,
            connected = connected,
            remoteEnabled = ServicePreferences.isRemoteEnabled(this),
            activeHost = activeHost,
            transport = transport,
            sessionActive = sessionActive,
            lastSnapshotRevision = revision.get(),
            lastError = lastError,
        )
    }

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
                postNotification = { SessionNotification.show(appContext) },
                cancelNotification = { SessionNotification.cancel(appContext) },
            )

            if (accepted && !enabled) {
                current?.clearSession()
            }
            current?.publishState() ?: publishCompanionState(appContext)
            return accepted
        }

        /** Stops the local toggle and clears any session metadata immediately. */
        fun requestDisconnect(context: Context) {
            val appContext = context.applicationContext
            ServicePreferences.setRemoteEnabled(appContext, false)
            SessionNotification.cancel(appContext)
            instance?.let { service ->
                service.clearSession()
                service.publishState()
            } ?: publishCompanionState(appContext)
        }

        private fun publishCompanionState(context: Context) {
            val current = instance
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
