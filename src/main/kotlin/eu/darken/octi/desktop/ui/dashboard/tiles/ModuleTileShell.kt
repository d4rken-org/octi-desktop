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
 * Tile dimensions: minimum height ~96dp. Width is whatever the LazyVerticalGrid cell allocates
 * (3-column inner grid → ~110dp at the 360dp outer-grid minSize); Power's tile spans 2 columns
 * via [ModuleSpec.gridSpan] so it gets ~230dp.
 */
@Composable
fun <T : Any> ModuleTileShell(
    spec: ModuleSpec<T>,
    state: ModuleState<T>,
    onClick: () -> Unit,
    content: @Composable (T) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        onClick = onClick,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
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
