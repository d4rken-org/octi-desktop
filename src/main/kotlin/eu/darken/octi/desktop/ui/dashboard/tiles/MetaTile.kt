package eu.darken.octi.desktop.ui.dashboard.tiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.protocol.modules.meta.MetaInfo
import eu.darken.octi.desktop.ui.dashboard.ModuleSpec
import eu.darken.octi.desktop.ui.dashboard.ModuleState
import kotlin.time.Clock

@Composable
fun MetaTile(state: ModuleState<MetaInfo>, onClick: () -> Unit) {
    ModuleTileShell(spec = ModuleSpec.Meta, state = state, onClick = onClick) { meta ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val osLine = listOfNotNull(
                meta.osType?.takeIf { it.isNotBlank() },
                meta.osVersionName?.takeIf { it.isNotBlank() },
            ).joinToString(" ").ifBlank { meta.deviceType.name.lowercase().replaceFirstChar { it.titlecase() } }
            Text(
                text = osLine,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val uptime = meta.deviceBootedAt?.let { boot ->
                val ms = (Clock.System.now() - boot).inWholeMinutes
                when {
                    ms < 1 -> "Up <1m"
                    ms < 60 -> "Up ${ms}m"
                    ms < 60 * 24 -> "Up ${ms / 60}h"
                    else -> "Up ${ms / (60 * 24)}d"
                }
            }
            if (uptime != null) {
                Text(
                    text = uptime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
