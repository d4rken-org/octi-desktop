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
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.protocol.modules.clipboard.ClipboardInfo
import eu.darken.octi.desktop.ui.dashboard.ModuleSpec
import eu.darken.octi.desktop.ui.dashboard.ModuleState

/**
 * Clipboard tile — **metadata only by default** for privacy. Contents reveal in the detail
 * sheet. Codex review #12: a dashboard tile that renders peers' raw clipboard text exposes
 * secrets immediately on app open (passwords often live there). Inline preview is a future
 * Settings opt-in.
 *
 * The copy-to-local affordance is intentional: copying *to* the local clipboard requires
 * the user's explicit click, so this doesn't change the "no auto-reveal" privacy stance.
 */
@Composable
fun ClipboardTile(
    state: ModuleState<ClipboardInfo>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isHero: Boolean = false,
) {
    ModuleTileShell(spec = ModuleSpec.Clipboard, state = state, onClick = onClick, modifier = modifier, isHero = isHero) { info ->
        val clipboard = LocalClipboardManager.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                when (info.type) {
                    ClipboardInfo.Type.EMPTY -> Text(
                        text = "Empty",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    ClipboardInfo.Type.SIMPLE_TEXT -> {
                        Text(text = "Text", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${info.data.size} bytes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (info.type == ClipboardInfo.Type.SIMPLE_TEXT && info.data.size > 0) {
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(info.data.utf8())) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy to clipboard",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
