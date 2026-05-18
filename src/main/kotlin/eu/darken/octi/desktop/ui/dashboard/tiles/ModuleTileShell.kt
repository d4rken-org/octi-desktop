package eu.darken.octi.desktop.ui.dashboard.tiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.ui.dashboard.ModuleSpec
import eu.darken.octi.desktop.ui.dashboard.ModuleState
import eu.darken.octi.desktop.ui.dashboard.NotFoundPolicy

/**
 * Common chrome for all dashboard module tiles. Each tile passes its [ModuleSpec], its current
 * [ModuleState], and a `content` slot for the module-specific compact body. The shell handles
 * the Card, the header row (icon + name), and the Loading / NotFound / Error states so module
 * tiles only need to render the happy path.
 *
 * Tile dimensions: minimum height ~96dp. Width is delegated to the caller via [modifier] — the
 * layout engine sets `weight(1f).fillMaxHeight()` for 2-tile rows and `fillMaxWidth()` for wide
 * rows.
 *
 * [isHero] = true tints the card with [androidx.compose.material3.ColorScheme.primaryContainer]
 * at 25% alpha to match Android's hero treatment for the top wide row. Non-top wide rows are
 * NOT hero — they render with the default container color.
 */
@Composable
fun <T : Any> ModuleTileShell(
    spec: ModuleSpec<T>,
    state: ModuleState<T>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isHero: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    val containerColor = if (isHero) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = modifier.fillMaxWidth().heightIn(min = 96.dp),
        onClick = onClick,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(10.dp).fillMaxWidth()) {
            TileHeader(name = spec.displayName, icon = spec.icon)
            Spacer(Modifier.size(6.dp))
            Box(modifier = Modifier.fillMaxSize()) {
                when (state) {
                    ModuleState.Loading -> LoadingBody()
                    ModuleState.NotFound -> NotFoundBody(spec.notFoundPolicy)
                    is ModuleState.Error -> ErrorBody(state.message)
                    is ModuleState.Ok -> content(state.value)
                }
            }
        }
    }
}

@Composable
private fun TileHeader(name: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun NotFoundBody(policy: NotFoundPolicy) {
    val label = when (policy) {
        NotFoundPolicy.EMPTY_STATE -> "Empty"
        NotFoundPolicy.NOT_SHARED -> "Not shared"
    }
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun ErrorBody(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
