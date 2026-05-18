package eu.darken.octi.desktop.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.protocol.octiserver.dto.DevicesResponse
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.sync.DeviceListRepo
import eu.darken.octi.desktop.ui.LocalAppGraph
import eu.darken.octi.desktop.ui.dashboard.details.ModuleDetailSheet
import eu.darken.octi.desktop.ui.dashboard.tiles.AppsTile
import eu.darken.octi.desktop.ui.dashboard.tiles.ClipboardTile
import eu.darken.octi.desktop.ui.dashboard.tiles.ConnectivityTile
import eu.darken.octi.desktop.ui.dashboard.tiles.FilesTile
import eu.darken.octi.desktop.ui.dashboard.tiles.MetaTile
import eu.darken.octi.desktop.ui.dashboard.tiles.PowerTile
import eu.darken.octi.desktop.ui.dashboard.tiles.WifiTile
import eu.darken.octi.desktop.ui.nav.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    val graph = LocalAppGraph.current
    val devices by graph.deviceListRepo.devices.collectAsState()
    val loadState by graph.deviceListRepo.loadState.collectAsState()

    // Tracks which tile sheet is open across the whole dashboard. Single source so opening one
    // tile closes any other one — matches Android's modal-stack behavior.
    var openSheet by remember { mutableStateOf<Pair<String, ModuleSpec<*>>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices") },
                actions = {
                    if (loadState is DeviceListRepo.LoadState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { graph.deviceListRepo.kick() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                    IconButton(onClick = { graph.navigator.navigateTo(Screen.Clipboard) }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "Clipboard")
                    }
                    IconButton(onClick = { graph.navigator.navigateTo(Screen.Settings) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { contentPadding ->
        Surface(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            if (devices.isEmpty()) {
                EmptyState(loadState = loadState)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 360.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items = devices, key = { it.id }) { device ->
                        DeviceCard(
                            device = device,
                            isSelf = device.id == graph.deviceId.id,
                            onTileClick = { spec -> openSheet = device.id to spec },
                        )
                    }
                }
            }
        }
    }

    openSheet?.let { (deviceId, spec) ->
        ModuleDetailSheet(
            deviceId = deviceId,
            spec = spec,
            onDismiss = { openSheet = null },
        )
    }
}

@Composable
private fun EmptyState(loadState: DeviceListRepo.LoadState) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (loadState) {
                is DeviceListRepo.LoadState.Loading,
                DeviceListRepo.LoadState.Initial -> CircularProgressIndicator()
                is DeviceListRepo.LoadState.Error -> Text(
                    text = "Couldn't reach the server: ${loadState.message}",
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text("No devices yet")
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DevicesResponse.Device,
    isSelf: Boolean,
    onTileClick: (ModuleSpec<*>) -> Unit,
) {
    val online = Presence.isOnline(device.lastSeen)
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DeviceHeader(device = device, isSelf = isSelf, online = online)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TileGrid(device = device, onTileClick = onTileClick)
        }
    }
}

@Composable
private fun DeviceHeader(device: DevicesResponse.Device, isSelf: Boolean, online: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            imageVector = iconFor(device.platform),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = device.label ?: device.id.take(8),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusDot(online = online)
                Text(
                    text = buildString {
                        append(if (online) "Online" else "Offline")
                        append(" • ")
                        append(Presence.describe(device.lastSeen))
                        if (isSelf) append(" • this device")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Inner module-tile layout. Manually composed as rows (NOT `LazyVerticalGrid`) because we live
 * inside the outer dashboard's `LazyVerticalGrid` — nesting two infinite-height vertical scrolls
 * crashes Compose. Manual `Row` composition is fine: the cell count is fixed (7 tiles, deterministic
 * layout) so we don't need lazy materialisation.
 *
 * Layout matches Android: 3 columns, Power wide (spans 2), rest single-cell.
 *
 *     [ Power Power ] [ WiFi      ]
 *     [ Connect    ] [ Clipboard ] [ Files ]
 *     [ Apps       ] [ Meta      ] [       ]
 */
@Composable
private fun TileGrid(
    device: DevicesResponse.Device,
    onTileClick: (ModuleSpec<*>) -> Unit,
) {
    val graph = LocalAppGraph.current
    val deviceId = remember(device.id) { DeviceId(device.id) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(modifier = Modifier.weight(2f)) {
                TileForSpec(ModuleSpec.Power, deviceId, graph) { onTileClick(ModuleSpec.Power) }
            }
            Box(modifier = Modifier.weight(1f)) {
                TileForSpec(ModuleSpec.Wifi, deviceId, graph) { onTileClick(ModuleSpec.Wifi) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                TileForSpec(ModuleSpec.Connectivity, deviceId, graph) { onTileClick(ModuleSpec.Connectivity) }
            }
            Box(modifier = Modifier.weight(1f)) {
                TileForSpec(ModuleSpec.Clipboard, deviceId, graph) { onTileClick(ModuleSpec.Clipboard) }
            }
            Box(modifier = Modifier.weight(1f)) {
                TileForSpec(ModuleSpec.Files, deviceId, graph) { onTileClick(ModuleSpec.Files) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                TileForSpec(ModuleSpec.Apps, deviceId, graph) { onTileClick(ModuleSpec.Apps) }
            }
            Box(modifier = Modifier.weight(1f)) {
                TileForSpec(ModuleSpec.Meta, deviceId, graph) { onTileClick(ModuleSpec.Meta) }
            }
            // Empty cell to keep the third column gap consistent.
            Box(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TileForSpec(
    spec: ModuleSpec<*>,
    deviceId: DeviceId,
    graph: eu.darken.octi.desktop.di.AppGraph,
    onClick: () -> Unit,
) {
    when (spec) {
        ModuleSpec.Power -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Power).collectAsState()
            PowerTile(state = state, onClick = onClick)
        }
        ModuleSpec.Wifi -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Wifi).collectAsState()
            WifiTile(state = state, onClick = onClick)
        }
        ModuleSpec.Connectivity -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Connectivity).collectAsState()
            ConnectivityTile(state = state, onClick = onClick)
        }
        ModuleSpec.Clipboard -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Clipboard).collectAsState()
            ClipboardTile(state = state, onClick = onClick)
        }
        ModuleSpec.Files -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Files).collectAsState()
            FilesTile(state = state, onClick = onClick)
        }
        ModuleSpec.Apps -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Apps).collectAsState()
            AppsTile(state = state, onClick = onClick)
        }
        ModuleSpec.Meta -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Meta).collectAsState()
            MetaTile(state = state, onClick = onClick)
        }
    }
}

@Composable
private fun StatusDot(online: Boolean) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(
                color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            ),
    )
}

private fun iconFor(platform: String?): ImageVector {
    val p = platform?.lowercase().orEmpty()
    return when {
        p.contains("desktop") -> Icons.Filled.Computer
        p.contains("tablet") -> Icons.Filled.Tablet
        p == "web" || p.contains("browser") -> Icons.Filled.Language
        p.contains("phone") || p.contains("android") -> Icons.Filled.PhoneAndroid
        else -> Icons.Filled.DeviceUnknown
    }
}
