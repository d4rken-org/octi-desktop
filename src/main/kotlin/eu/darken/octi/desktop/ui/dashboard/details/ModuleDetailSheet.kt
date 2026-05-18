package eu.darken.octi.desktop.ui.dashboard.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.protocol.modules.apps.AppsInfo
import eu.darken.octi.desktop.protocol.modules.clipboard.ClipboardInfo
import eu.darken.octi.desktop.protocol.modules.connectivity.ConnectivityInfo
import eu.darken.octi.desktop.protocol.modules.files.FileShareInfo
import eu.darken.octi.desktop.protocol.modules.meta.MetaInfo
import eu.darken.octi.desktop.protocol.modules.power.PowerInfo
import eu.darken.octi.desktop.protocol.modules.wifi.WifiInfo
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.ui.LocalAppGraph
import eu.darken.octi.desktop.ui.dashboard.ModuleSpec
import eu.darken.octi.desktop.ui.dashboard.ModuleState
import eu.darken.octi.desktop.ui.nav.Screen

/**
 * Modal bottom sheet that opens when a dashboard tile is clicked. Capped at 640dp wide
 * (`sheetMaxWidth`) so it doesn't sprawl on 27" monitors. Mirrors Android's per-tile drill-down.
 *
 * Content dispatches by [ModuleSpec]. Each per-module composable reads the same slice the
 * tile uses — no extra fetch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleDetailSheet(
    deviceId: String,
    spec: ModuleSpec<*>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 640.dp,
    ) {
        // SelectionContainer lets users select and copy any text in the sheet — IPs, app
        // names, clipboard contents, etc. Per-control widgets (TextButton in FilesDetail)
        // continue to receive clicks normally; selection only affects Text composables.
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PaddingValues(horizontal = 24.dp, vertical = 8.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Header(spec)
                Body(deviceId = DeviceId(deviceId), spec = spec, onDismiss = onDismiss)
            }
        }
    }
}

@Composable
private fun Header(spec: ModuleSpec<*>) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            imageVector = spec.icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(spec.displayName, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun Body(deviceId: DeviceId, spec: ModuleSpec<*>, onDismiss: () -> Unit) {
    val graph = LocalAppGraph.current

    // Dispatch on the spec object identity. The cast is safe because flowFor(spec) returns
    // a flow whose type variable matches spec — same generic-binding the tile path uses.
    when (spec) {
        ModuleSpec.Power -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Power).collectAsState()
            PowerDetail(state)
        }
        ModuleSpec.Wifi -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Wifi).collectAsState()
            WifiDetail(state)
        }
        ModuleSpec.Connectivity -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Connectivity).collectAsState()
            ConnectivityDetail(state)
        }
        ModuleSpec.Clipboard -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Clipboard).collectAsState()
            ClipboardDetail(state)
        }
        ModuleSpec.Files -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Files).collectAsState()
            FilesDetail(
                state = state,
                onOpenFiles = {
                    onDismiss()
                    graph.navigator.navigateTo(Screen.Files(deviceId.id))
                },
            )
        }
        ModuleSpec.Apps -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Apps).collectAsState()
            AppsDetail(state)
        }
        ModuleSpec.Meta -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Meta).collectAsState()
            MetaDetail(state)
        }
    }
}

/** Used inside each module-detail composable for the Loading / NotFound / Error cases. */
@Composable
internal fun ModuleDetailFallback(state: ModuleState<*>) {
    when (state) {
        ModuleState.Loading -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
        ModuleState.NotFound -> Text(
            text = "No data — this device hasn't shared anything yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        is ModuleState.Error -> Text(
            text = "Couldn't load: ${state.message}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        is ModuleState.Ok<*> -> error("ModuleDetailFallback called with Ok state")
    }
}

// --- per-module detail bodies ---

@Composable
private fun PowerDetail(state: ModuleState<PowerInfo>) {
    if (state !is ModuleState.Ok) return ModuleDetailFallback(state)
    val power = state.value
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "${(power.battery.percent * 100).toInt()}%",
            style = MaterialTheme.typography.displayMedium,
        )
        val status = buildString {
            append(power.status.name.lowercase().replaceFirstChar { it.titlecase() })
            if (power.isCharging) append(" (charging)")
        }
        Text(status, style = MaterialTheme.typography.titleMedium)
        val temp = power.battery.temp
        if (temp != null) Text("Temperature: ${"%.1f".format(temp)}°", style = MaterialTheme.typography.bodyMedium)
        val current = power.chargeIO.currentNow
        if (current != null) {
            Text("Current: ${current / 1000} mA • ${power.chargeIO.speed.name.lowercase()}",
                style = MaterialTheme.typography.bodyMedium)
        }
        val health = power.battery.health
        if (health != null) Text("Health: $health", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WifiDetail(state: ModuleState<WifiInfo>) {
    if (state !is ModuleState.Ok) return ModuleDetailFallback(state)
    val current = state.value.currentWifi
    if (current == null) {
        Text("Not connected to a network.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(current.ssid ?: "unknown SSID", style = MaterialTheme.typography.titleLarge)
        val band = when (current.freqType) {
            WifiInfo.Wifi.Type.FIVE_GHZ -> "5 GHz"
            WifiInfo.Wifi.Type.TWO_POINT_FOUR_GHZ -> "2.4 GHz"
            WifiInfo.Wifi.Type.UNKNOWN, null -> "unknown band"
        }
        Text("Band: $band", style = MaterialTheme.typography.bodyMedium)
        current.reception?.let {
            Text("Signal: ${(it * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ConnectivityDetail(state: ModuleState<ConnectivityInfo>) {
    if (state !is ModuleState.Ok) return ModuleDetailFallback(state)
    val info = state.value
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = info.connectionType?.name?.lowercase()?.replaceFirstChar { it.titlecase() } ?: "Unknown",
            style = MaterialTheme.typography.titleLarge,
        )
        info.publicIp?.let { Text("Public IP: $it", style = MaterialTheme.typography.bodyMedium) }
        info.localAddressIpv4?.let { Text("Local IPv4: $it", style = MaterialTheme.typography.bodyMedium) }
        info.localAddressIpv6?.let { Text("Local IPv6: $it", style = MaterialTheme.typography.bodyMedium) }
        info.gatewayIp?.let { Text("Gateway: $it", style = MaterialTheme.typography.bodyMedium) }
        info.dnsServers?.takeIf { it.isNotEmpty() }?.let {
            Text("DNS: ${it.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ClipboardDetail(state: ModuleState<ClipboardInfo>) {
    if (state !is ModuleState.Ok) return ModuleDetailFallback(state)
    val info = state.value
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (info.type) {
            ClipboardInfo.Type.EMPTY -> Text("Clipboard is empty.", style = MaterialTheme.typography.bodyMedium)
            ClipboardInfo.Type.SIMPLE_TEXT -> {
                Text("Text — ${info.data.size} bytes", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = info.data.utf8(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun FilesDetail(state: ModuleState<FileShareInfo>, onOpenFiles: () -> Unit) {
    if (state !is ModuleState.Ok) return ModuleDetailFallback(state)
    val info = state.value
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (info.files.size == 1) "1 file shared" else "${info.files.size} files shared",
            style = MaterialTheme.typography.titleLarge,
        )
        info.files.take(5).forEach { f ->
            Text("• ${f.name} (${humanBytes(f.size)})", style = MaterialTheme.typography.bodyMedium)
        }
        if (info.files.size > 5) {
            Text("… and ${info.files.size - 5} more", style = MaterialTheme.typography.bodySmall)
        }
        androidx.compose.material3.TextButton(onClick = onOpenFiles) {
            Text("Open Files…")
        }
    }
}

@Composable
private fun AppsDetail(state: ModuleState<AppsInfo>) {
    if (state !is ModuleState.Ok) return ModuleDetailFallback(state)
    val info = state.value
    val mostRecent = info.installedPackages.sortedByDescending { it.installedAt }.take(8)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "${info.installedPackages.size} apps installed",
            style = MaterialTheme.typography.titleLarge,
        )
        if (mostRecent.isNotEmpty()) {
            Text("Most recently installed", style = MaterialTheme.typography.titleSmall)
            mostRecent.forEach { pkg ->
                Text(
                    text = pkg.label?.takeIf { it.isNotBlank() } ?: pkg.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun MetaDetail(state: ModuleState<MetaInfo>) {
    if (state !is ModuleState.Ok) return ModuleDetailFallback(state)
    val meta = state.value
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(meta.labelOrFallback, style = MaterialTheme.typography.titleLarge)
        meta.osType?.let { osType ->
            val osLine = listOfNotNull(osType, meta.osVersionName).joinToString(" ")
            Text("OS: $osLine", style = MaterialTheme.typography.bodyMedium)
        }
        meta.androidVersionName?.let { Text("Android: $it (API ${meta.androidApiLevel})", style = MaterialTheme.typography.bodyMedium) }
        meta.androidSecurityPatch?.let { Text("Security patch: $it", style = MaterialTheme.typography.bodyMedium) }
        Text("Type: ${meta.deviceType}", style = MaterialTheme.typography.bodyMedium)
        Text("Octi: ${meta.octiVersionName} (${meta.octiGitSha.take(10)})", style = MaterialTheme.typography.bodyMedium)
        Text("Manufacturer: ${meta.deviceManufacturer}", style = MaterialTheme.typography.bodyMedium)
        Text("Device name: ${meta.deviceName}", style = MaterialTheme.typography.bodyMedium)
    }
}

private fun humanBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024.0 && i < units.size - 1) {
        v /= 1024.0
        i++
    }
    return if (i == 0) "$bytes B" else "%.1f %s".format(v, units[i])
}
