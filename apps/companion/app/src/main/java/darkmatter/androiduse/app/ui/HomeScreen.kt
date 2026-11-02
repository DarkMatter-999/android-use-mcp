package darkmatter.androiduse.app.ui

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import darkmatter.androiduse.app.accessibility.TransportKind
import darkmatter.androiduse.app.actions.ActionLogEntry
import darkmatter.androiduse.app.overlay.OverlayOptions
import darkmatter.androiduse.app.apps.LaunchableApp
import darkmatter.androiduse.app.network.UiBounds
import darkmatter.androiduse.app.ui.theme.AndroidUseTheme

// Literal keeps the API-33 permission string usable on API 30 without an
// InlinedApi lint warning; the launcher is called only after a runtime guard.
private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

/**
 * User-mediated onboarding and control surface. The screen links to Android's
 * accessibility settings; it never enables the service itself.
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onNotificationPermissionResult(granted) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    HomeContent(
        state = state,
        onOpenAccessibilitySettings = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        onRemoteControlChange = { enabled ->
            if (viewModel.beginRemoteToggle(enabled)) {
                notificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
            }
        },
        onRequestNotificationPermission = {
            notificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
        },
        onAcknowledgeExplanation = viewModel::acknowledgeExplanation,
        onDisconnect = viewModel::disconnect,
        onWifiChange = viewModel::setWifiEnabled,
        onOverlayOptionsChange = viewModel::setOverlayOptions,
        onSetAppAllowed = viewModel::setAppAllowed,
        onBeginPairing = viewModel::beginPairing,
        onApproveHost = viewModel::approveHost,
        onRejectHost = viewModel::rejectHost,
        onRevokeHost = viewModel::revokeHost,
        onCopyPublicValue = { label, value ->
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(label, value))
        },
    )
}

@Composable
fun HomeContent(
    state: HomeUiState,
    onOpenAccessibilitySettings: () -> Unit,
    onRemoteControlChange: (Boolean) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onAcknowledgeExplanation: () -> Unit,
    onDisconnect: () -> Unit,
    onWifiChange: (Boolean) -> Unit = {},
    onOverlayOptionsChange: (OverlayOptions) -> Unit = {},
    onSetAppAllowed: (String, Boolean) -> Unit = { _, _ -> },
    onBeginPairing: () -> Unit = {},
    onApproveHost: (String) -> Unit = {},
    onRejectHost: (String) -> Unit = {},
    onRevokeHost: (String) -> Unit = {},
    onCopyPublicValue: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(state)
            AccessibilityCard(state, onOpenAccessibilitySettings)
            if (state.firstRunExplanationVisible) {
                FirstRunExplanation(onAcknowledgeExplanation)
            }
            RemoteControlCard(
                state = state,
                onRemoteControlChange = onRemoteControlChange,
                onRequestNotificationPermission = onRequestNotificationPermission,
            )
            ConnectionCard(
                state = state,
                onWifiChange = onWifiChange,
                onBeginPairing = onBeginPairing,
                onApproveHost = onApproveHost,
                onRejectHost = onRejectHost,
                onRevokeHost = onRevokeHost,
                onCopyPublicValue = onCopyPublicValue,
            )
            OverlaySettingsCard(state.overlayOptions, onOverlayOptionsChange, state.accessibilityConnected)
            AllowedAppsCard(state.launchableApps, state.allowedLaunchPackages, onSetAppAllowed)
            RecentActionsCard(state.recentActions)
            StopControl(state, onDisconnect)
            Text(
                text = "This build has a local accessibility action engine for clicks, text entry, " +
                    "scrolling, bounded gestures, and supported system actions. Authenticated " +
                    "transport is available only through explicit pairing. Screenshots are unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AllowedAppsCard(
    apps: List<LaunchableApp>,
    allowedPackages: Set<String>,
    onSetAppAllowed: (String, Boolean) -> Unit,
) {
    SectionCard(title = "Allowed apps") {
        Text(
            "Only apps selected here can be opened by an approved remote host. No apps are selected by default.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (apps.isEmpty()) {
            Text("No launcher apps are visible to AndroidUse.", style = MaterialTheme.typography.bodyMedium)
        } else {
            apps.forEach { app ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyMedium)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Checkbox(
                        checked = app.packageName in allowedPackages,
                        onCheckedChange = { onSetAppAllowed(app.packageName, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlaySettingsCard(
    options: OverlayOptions,
    onOptionsChange: (OverlayOptions) -> Unit,
    accessibilityConnected: Boolean,
) {
    SectionCard(title = "Inspector overlay") {
        Text(
            "Visual-only decorations. This setting does not enable accessibility, remote control, or Wi-Fi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OverlayToggle("Enable inspector", options.inspector, accessibilityConnected) {
            onOptionsChange(options.copy(inspector = it))
        }
        OverlayToggle("Element bounds", options.bounds, options.inspector) {
            onOptionsChange(options.copy(bounds = it))
        }
        OverlayToggle("Compact labels", options.labels, options.inspector) {
            onOptionsChange(options.copy(labels = it))
        }
        OverlayToggle("Action trace", options.actionTrace, options.inspector) {
            onOptionsChange(options.copy(actionTrace = it))
        }
        OverlayToggle("Cursor feedback", options.cursor, options.inspector) {
            onOptionsChange(options.copy(cursor = it))
        }
    }
}

@Composable
private fun OverlayToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Header(state: HomeUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("AndroidUse", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Device control companion",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${state.deviceModel} • API ${state.apiLevel}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AccessibilityCard(
    state: HomeUiState,
    onOpenAccessibilitySettings: () -> Unit,
) {
    SectionCard(title = "Accessibility service") {
        StatusLine(
            label = "Enabled in Android settings",
            value = state.accessibilityEnabled,
            onText = "Yes",
            offText = "No",
        )
        StatusLine(
            label = "Bound by Android",
            value = state.accessibilityConnected,
            onText = "Connected",
            offText = "Not connected",
        )
        Text(state.statusMessage, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onOpenAccessibilitySettings) {
            Text("Open Android accessibility settings")
        }
        Text(
            "AndroidUse cannot enable this service for you. Turn it on in settings, " +
                "review the system warning, and return here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FirstRunExplanation(onAcknowledgeExplanation: () -> Unit) {
    SectionCard(
        title = "Before you enable device control",
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            "The local action engine can inspect the current screen's accessibility tree and " +
                "perform supported actions while this service is on. A computer host can connect " +
                "only after you enable remote control and approve its certificate here.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "It does not bypass the lock screen, authentication prompts, secure windows, " +
                "or app protections. Password values are redacted from snapshots and logs. " +
                "A persistent notification stays visible while remote control is on, and " +
                "you can stop it at any time with the prominent control below.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onAcknowledgeExplanation) {
            Text("I understand — continue")
        }
    }
}

@Composable
private fun RemoteControlCard(
    state: HomeUiState,
    onRemoteControlChange: (Boolean) -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    SectionCard(title = "Remote control") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (state.remoteEnabled) "Remote control is on" else "Remote control is off",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Enables a loopback TLS listener after the visible notification is active. " +
                        "Wi-Fi exposure is a separate choice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.remoteEnabled,
                onCheckedChange = onRemoteControlChange,
                enabled = state.accessibilityEnabled &&
                    state.accessibilityConnected &&
                    !state.firstRunExplanationVisible,
            )
        }

        if (state.notificationPermissionMissing) {
            Text(
                "Android 13+ requires notification permission for the persistent " +
                    "active-session notice. Remote control stays off without it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onRequestNotificationPermission) {
                Text("Grant notification permission")
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: HomeUiState,
    onWifiChange: (Boolean) -> Unit,
    onBeginPairing: () -> Unit,
    onApproveHost: (String) -> Unit,
    onRejectHost: (String) -> Unit,
    onRevokeHost: (String) -> Unit,
    onCopyPublicValue: (String, String) -> Unit,
) {
    SectionCard(title = "Connection and session") {
        InfoRow(
            "Transport",
            when (state.transport) {
                TransportKind.NONE -> if (state.endpointAddress != null) "Loopback (local only)" else "None configured"
                TransportKind.USB -> "USB (ADB forward)"
                TransportKind.WIFI -> "Wi-Fi (local network)"
            },
        )
        InfoRow("Paired host", state.activeHost ?: "None")
        InfoRow(
            "Listener endpoint",
            listenerEndpointLabel(state.transport, state.endpointAddress, state.endpointPort),
        )
        InfoRow(
            "Session",
            when {
                state.sessionActive -> "Active"
                state.remoteEnabled -> "Waiting for authenticated host"
                else -> "Inactive"
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Expose on current Wi-Fi", modifier = Modifier.weight(1f))
            Switch(
                checked = state.transport == TransportKind.WIFI,
                onCheckedChange = onWifiChange,
                enabled = state.remoteEnabled && state.endpointAddress != null,
            )
        }
        state.transportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.deviceFingerprintSha256?.let { fingerprint ->
            Text("Phone certificate fingerprint", style = MaterialTheme.typography.titleSmall)
            Text(fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { onCopyPublicValue("AndroidUse fingerprint", fingerprint) }) {
                Text("Copy fingerprint")
            }
        }
        state.deviceCertificatePem?.let { certificate ->
            OutlinedButton(onClick = { onCopyPublicValue("AndroidUse public certificate", certificate) }) {
                Text("Copy public certificate")
            }
        }
        if (state.remoteEnabled) {
            OutlinedButton(onClick = onBeginPairing) { Text("Create pairing code") }
        }
        state.pairingCode?.let { code ->
            Text("Pairing code (expires ${state.pairingExpiresAtMillis?.let { Date(it).toString() } ?: "soon"})")
            Text(code, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
        }
        if (state.pendingHostId != null) {
            HorizontalDivider()
            Text("Pending host: ${state.pendingHostName ?: "Unnamed host"}", style = MaterialTheme.typography.titleSmall)
            Text(state.pendingHostFingerprintSha256.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onApproveHost(state.pendingHostId) }) { Text("Approve") }
                OutlinedButton(onClick = { onRejectHost(state.pendingHostId) }) { Text("Reject") }
            }
        }
        state.approvedHosts.forEach { host ->
            HorizontalDivider()
            Text(host.hostName, style = MaterialTheme.typography.titleSmall)
            Text(host.fingerprintSha256, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { onRevokeHost(host.hostId) }) { Text("Revoke host") }
        }
    }
}

@Composable
private fun RecentActionsCard(actions: List<ActionLogEntry>) {
    SectionCard(title = "Recent actions") {
        if (actions.isEmpty()) {
            Text(
                "No actions recorded yet. Snapshots and actions are never logged with " +
                    "password or input text values.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        actions.forEachIndexed { index, entry ->
            if (index > 0) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
            ActionLogRow(entry)
        }
    }
}

@Composable
private fun ActionLogRow(entry: ActionLogEntry) {
    val time = remember(entry.timestampMillis) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestampMillis))
    }
    val outcome = when {
        entry.accepted && entry.verified == true -> "verified"
        entry.accepted -> "accepted, unverified"
        else -> "rejected"
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "${entry.action} — $outcome",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            entry.targetResourceId
                ?: entry.selectorKind?.let { "selector kind: $it" }
                ?: "target metadata unavailable",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "revision ${entry.revisionBefore} → ${entry.revisionAfter} • $time" +
                (entry.errorCode?.let { " • $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StopControl(state: HomeUiState, onDisconnect: () -> Unit) {
    val active = state.remoteEnabled || state.sessionActive || state.activeHost != null
    Button(
        onClick = onDisconnect,
        enabled = active,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Text("Stop remote control and disconnect", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SectionCard(
    title: String,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: Boolean,
    onText: String,
    offText: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (value) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                ),
        )
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            if (value) onText else offText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeContentPreview() {
    AndroidUseTheme {
        HomeContent(
            state = HomeUiState(
                deviceModel = "Pixel 8",
                apiLevel = 35,
                accessibilityEnabled = true,
                accessibilityConnected = true,
                remoteEnabled = true,
                activeHost = null,
                transport = TransportKind.NONE,
                sessionActive = false,
                recentActions = listOf(
                    ActionLogEntry(
                        actionId = "preview",
                        action = "click",
                        selectorKind = "text",
                        targetResourceId = "com.example:id/continue",
                        targetBounds = UiBounds(10, 20, 200, 100),
                        accepted = true,
                        verified = null,
                        revisionBefore = 7,
                        revisionAfter = 8,
                        timestampMillis = 1_700_000_000_000,
                        sensitive = false,
                    ),
                ),
                firstRunExplanationVisible = false,
                notificationPermissionMissing = false,
                statusMessage = "Remote control is on. Waiting for an authenticated host.",
            ),
            onOpenAccessibilitySettings = {},
            onRemoteControlChange = {},
            onRequestNotificationPermission = {},
            onAcknowledgeExplanation = {},
            onDisconnect = {},
        )
    }
}
