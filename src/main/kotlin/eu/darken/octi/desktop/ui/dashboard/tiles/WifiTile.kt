package eu.darken.octi.desktop.ui.dashboard.tiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.protocol.modules.wifi.WifiInfo
import eu.darken.octi.desktop.ui.dashboard.ModuleSpec
import eu.darken.octi.desktop.ui.dashboard.ModuleState

@Composable
fun WifiTile(state: ModuleState<WifiInfo>, onClick: () -> Unit) {
    ModuleTileShell(spec = ModuleSpec.Wifi, state = state, onClick = onClick) { wifi ->
        val current = wifi.currentWifi
        if (current == null) {
            Text(
                text = "Not connected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            return@ModuleTileShell
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier) {
            Text(
                text = current.ssid?.takeIf { it.isNotBlank() } ?: "unknown SSID",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val reception = current.reception
            val band = when (current.freqType) {
                WifiInfo.Wifi.Type.FIVE_GHZ -> "5 GHz"
                WifiInfo.Wifi.Type.TWO_POINT_FOUR_GHZ -> "2.4 GHz"
                WifiInfo.Wifi.Type.UNKNOWN, null -> null
            }
            val signal = reception?.let { "${(it * 100).toInt()}%" }
            val sub = listOfNotNull(band, signal).joinToString(" • ")
            if (sub.isNotEmpty()) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
