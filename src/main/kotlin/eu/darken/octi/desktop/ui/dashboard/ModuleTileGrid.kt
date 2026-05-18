package eu.darken.octi.desktop.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.ui.LocalAppGraph
import eu.darken.octi.desktop.ui.dashboard.layout.TileRow
import eu.darken.octi.desktop.ui.dashboard.tiles.AppsTile
import eu.darken.octi.desktop.ui.dashboard.tiles.ClipboardTile
import eu.darken.octi.desktop.ui.dashboard.tiles.ConnectivityTile
import eu.darken.octi.desktop.ui.dashboard.tiles.FilesTile
import eu.darken.octi.desktop.ui.dashboard.tiles.MetaTile
import eu.darken.octi.desktop.ui.dashboard.tiles.PowerTile
import eu.darken.octi.desktop.ui.dashboard.tiles.WifiTile

/**
 * Displays a device's tile grid driven by [rows] from [eu.darken.octi.desktop.ui.dashboard.layout.toRows].
 *
 * Row rendering rules — derived from the layout engine, NOT a free-form grid:
 * - Single-tile row: full width via [Modifier.fillMaxWidth]. Hero tinting iff `rowIndex == 0`.
 * - Two-tile row: `Row` with `height(IntrinsicSize.Max)`; each child gets `weight(1f).fillMaxHeight()`
 *   so the two siblings share row width 50/50 and match in height regardless of their natural
 *   content size.
 *
 * Tiles look up their state via [AppGraph.dashboardModuleRepo] — lazy per-(device,module) slices
 * with `WhileSubscribed(30s)` teardown, so hidden tiles stop fetching shortly after they leave
 * the composition.
 */
@Composable
fun ModuleTileGrid(
    rows: List<TileRow>,
    deviceId: DeviceId,
    onTileClick: (ModuleSpec<*>) -> Unit,
) {
    val graph = LocalAppGraph.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEachIndexed { rowIndex, row ->
            val isWideRow = row.moduleIds.size == 1
            val isHeroRow = rowIndex == 0 && isWideRow
            if (isWideRow) {
                val spec = ModuleSpec.byModuleIdString(row.moduleIds[0]) ?: return@forEachIndexed
                TileForSpec(
                    spec = spec,
                    deviceId = deviceId,
                    graph = graph,
                    modifier = Modifier.fillMaxWidth(),
                    isHero = isHeroRow,
                    onClick = { onTileClick(spec) },
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.moduleIds.forEach { id ->
                        val spec = ModuleSpec.byModuleIdString(id) ?: return@forEach
                        TileForSpec(
                            spec = spec,
                            deviceId = deviceId,
                            graph = graph,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            isHero = false,
                            onClick = { onTileClick(spec) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dispatch from a [ModuleSpec] to the matching tile composable. The `when` is exhaustive over
 * the sealed [ModuleSpec] hierarchy so the compiler enforces full coverage on new modules.
 *
 * Each branch collects the per-tile [ModuleState] from [AppGraph.dashboardModuleRepo], which
 * decides between "remote peer" reads and "self device" routing internally.
 */
@Composable
fun TileForSpec(
    spec: ModuleSpec<*>,
    deviceId: DeviceId,
    graph: AppGraph,
    modifier: Modifier = Modifier,
    isHero: Boolean = false,
    onClick: () -> Unit,
) {
    when (spec) {
        ModuleSpec.Power -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Power).collectAsState()
            PowerTile(state = state, onClick = onClick, modifier = modifier, isHero = isHero)
        }
        ModuleSpec.Wifi -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Wifi).collectAsState()
            WifiTile(state = state, onClick = onClick, modifier = modifier, isHero = isHero)
        }
        ModuleSpec.Connectivity -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Connectivity).collectAsState()
            ConnectivityTile(state = state, onClick = onClick, modifier = modifier, isHero = isHero)
        }
        ModuleSpec.Clipboard -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Clipboard).collectAsState()
            ClipboardTile(state = state, onClick = onClick, modifier = modifier, isHero = isHero)
        }
        ModuleSpec.Files -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Files).collectAsState()
            FilesTile(state = state, onClick = onClick, modifier = modifier, isHero = isHero)
        }
        ModuleSpec.Apps -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Apps).collectAsState()
            AppsTile(state = state, onClick = onClick, modifier = modifier, isHero = isHero)
        }
        ModuleSpec.Meta -> {
            val state by graph.dashboardModuleRepo.flowFor(deviceId, ModuleSpec.Meta).collectAsState()
            MetaTile(state = state, onClick = onClick, modifier = modifier, isHero = isHero)
        }
    }
}
