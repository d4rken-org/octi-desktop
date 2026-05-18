package eu.darken.octi.desktop.ui.dashboard.tiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.protocol.modules.apps.AppsInfo
import eu.darken.octi.desktop.ui.dashboard.ModuleSpec
import eu.darken.octi.desktop.ui.dashboard.ModuleState

@Composable
fun AppsTile(state: ModuleState<AppsInfo>, onClick: () -> Unit) {
    ModuleTileShell(spec = ModuleSpec.Apps, state = state, onClick = onClick) { info ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val count = info.installedPackages.size
            Text(
                text = if (count == 1) "1 app" else "$count apps",
                style = MaterialTheme.typography.titleSmall,
            )
            val mostRecent = info.installedPackages.maxByOrNull { it.installedAt }
            val mostRecentLabel = mostRecent?.label?.takeIf { it.isNotBlank() }
                ?: mostRecent?.packageName
            if (mostRecentLabel != null) {
                Text(
                    text = mostRecentLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
