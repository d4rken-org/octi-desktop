package eu.darken.octi.desktop.ui.dashboard.tiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.protocol.modules.power.PowerInfo
import eu.darken.octi.desktop.ui.dashboard.ModuleSpec
import eu.darken.octi.desktop.ui.dashboard.ModuleState

@Composable
fun PowerTile(state: ModuleState<PowerInfo>, onClick: () -> Unit) {
    ModuleTileShell(spec = ModuleSpec.Power, state = state, onClick = onClick) { power ->
        val percent = power.battery.percent.coerceIn(0f, 1f)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "${(percent * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (power.isCharging) {
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = "Charging",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
                val temp = power.battery.temp
                if (temp != null) {
                    Text(
                        text = "• ${"%.0f".format(temp)}°",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LinearProgressIndicator(progress = { percent }, modifier = Modifier.fillMaxWidth())
        }
    }
}
