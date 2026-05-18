package eu.darken.octi.desktop.ui.dashboard.tiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.protocol.modules.connectivity.ConnectivityInfo
import eu.darken.octi.desktop.ui.dashboard.ModuleSpec
import eu.darken.octi.desktop.ui.dashboard.ModuleState

@Composable
fun ConnectivityTile(
    state: ModuleState<ConnectivityInfo>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isHero: Boolean = false,
) {
    ModuleTileShell(spec = ModuleSpec.Connectivity, state = state, onClick = onClick, modifier = modifier, isHero = isHero) { info ->
        val clipboard = LocalClipboardManager.current
        val typeLabel = when (info.connectionType) {
            ConnectivityInfo.ConnectionType.WIFI -> "WiFi"
            ConnectivityInfo.ConnectionType.CELLULAR -> "Cellular"
            ConnectivityInfo.ConnectionType.ETHERNET -> "Ethernet"
            ConnectivityInfo.ConnectionType.NONE -> "Offline"
            null -> "Unknown"
        }
        val ip = info.localAddressIpv4?.takeIf { it.isNotBlank() }
            ?: info.localAddressIpv6?.takeIf { it.isNotBlank() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = typeLabel, style = MaterialTheme.typography.titleSmall)
                if (ip != null) {
                    Text(
                        text = ip,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (ip != null) {
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(ip)) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy IP",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
