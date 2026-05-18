package eu.darken.octi.desktop.ui.dashboard.tiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.protocol.modules.files.FileShareInfo
import eu.darken.octi.desktop.ui.dashboard.ModuleSpec
import eu.darken.octi.desktop.ui.dashboard.ModuleState

@Composable
fun FilesTile(state: ModuleState<FileShareInfo>, onClick: () -> Unit) {
    ModuleTileShell(spec = ModuleSpec.Files, state = state, onClick = onClick) { info ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val count = info.files.size
            Text(
                text = if (count == 1) "1 file" else "$count files",
                style = MaterialTheme.typography.titleSmall,
            )
            val totalBytes = info.files.sumOf { it.size }
            if (count > 0 && totalBytes > 0) {
                Text(
                    text = humanBytes(totalBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
