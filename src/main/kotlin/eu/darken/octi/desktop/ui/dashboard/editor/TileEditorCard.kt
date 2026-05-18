package eu.darken.octi.desktop.ui.dashboard.editor

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.ui.LocalAppGraph
import eu.darken.octi.desktop.ui.dashboard.ModuleSpec
import eu.darken.octi.desktop.ui.dashboard.TileForSpec
import eu.darken.octi.desktop.ui.dashboard.layout.TileRow
import eu.darken.octi.desktop.ui.dashboard.layout.toRows

/**
 * Editor-mode replacement for a device card. Hosts a [TileEditorState] (provided by the parent
 * so that scrolling the outer LazyVerticalGrid doesn't lose in-flight edits if the item
 * disposes) and renders:
 *
 * - Header with the device name and Cancel / Save action buttons.
 * - Editor grid with each tile carrying a [Icons.Filled.DragHandle] in the top-right corner.
 *   Drag from the handle, drop to reorder / resize / hide.
 * - Hidden tray (chip-like compact tiles) below a divider — drag visible tiles down to hide,
 *   drag hidden tiles up to unhide at their existing order position.
 * - Footer with Reset (per-device) and Save-as-default actions.
 *
 * Drag gesture is wired with `change.consume()` and a `nestedScroll` pre-consumer so the outer
 * `LazyVerticalGrid` can't steal vertical drags mid-gesture.
 */
@Composable
fun TileEditorCard(
    editor: TileEditorState,
    deviceLabel: String,
    deviceId: DeviceId,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onSaveAsDefault: () -> Unit,
) {
    val nestedScrollConnection = remember(editor) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (editor.isDragging) available else Offset.Zero
        }
    }
    val rows = editor.toConfig().toRows(ModuleSpec.allModuleIds)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp)
            .nestedScroll(nestedScrollConnection),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EditorHeader(deviceLabel = deviceLabel, onCancel = onCancel, onSave = onSave)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            EditorTilesColumn(rows = rows, editor = editor, deviceId = deviceId)
            HiddenTrayDivider(editor = editor)
            HiddenTray(editor = editor, deviceId = deviceId)
            EditorFooter(onReset = onReset, onSaveAsDefault = onSaveAsDefault)
        }
    }
}

@Composable
private fun EditorHeader(deviceLabel: String, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Editing: $deviceLabel",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Drag handles to reorder, hide, or change width",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
        Button(onClick = onSave) {
            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text("Save")
        }
    }
}

@Composable
private fun EditorTilesColumn(
    rows: List<TileRow>,
    editor: TileEditorState,
    deviceId: DeviceId,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEachIndexed { rowIndex, row ->
            val isWideRow = row.moduleIds.size == 1
            val targetForDropLine = editor.dropTarget as? DropTarget.BetweenRows
            if (targetForDropLine?.beforeRowIndex == rowIndex) {
                DropIndicatorLine()
            }
            EditorRow(row = row, isWideRow = isWideRow, editor = editor, deviceId = deviceId, rowIndex = rowIndex)
        }
        val targetForLastDropLine = editor.dropTarget as? DropTarget.BetweenRows
        if (targetForLastDropLine?.beforeRowIndex == rows.size) {
            DropIndicatorLine()
        }
        // Drop stale row-bound entries that belong to a now-shorter row list (e.g. two paired
        // tiles collapsed to one wide row).
        TrimRowBounds(rowCount = rows.size, editor = editor)
    }
}

@Composable
private fun EditorRow(
    row: TileRow,
    isWideRow: Boolean,
    editor: TileEditorState,
    deviceId: DeviceId,
    rowIndex: Int,
) {
    if (isWideRow) {
        EditorTile(
            moduleId = row.moduleIds[0],
            editor = editor,
            deviceId = deviceId,
            modifier = Modifier.fillMaxWidth().writeRowBounds(rowIndex, row.moduleIds, editor),
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max).writeRowBounds(rowIndex, row.moduleIds, editor),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            row.moduleIds.forEach { id ->
                EditorTile(
                    moduleId = id,
                    editor = editor,
                    deviceId = deviceId,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * One editable tile = the same display tile (via [TileForSpec]) + a drag-handle overlay in the
 * top-right corner. The handle has the `pointerInput` that drives [TileEditorState] mutations;
 * the rest of the tile is non-interactive in edit mode (clicks suppressed by passing a no-op
 * `onClick`).
 *
 * Position tracking writes the tile's center-in-window to `editor.tilePositions[moduleId]` via
 * `onGloballyPositioned` — this is the coordinate space `computeDropTarget` hit-tests against.
 *
 * When this tile is being dragged, `graphicsLayer { translationX/Y }` lifts it visually without
 * triggering a relayout of its siblings.
 */
@Composable
private fun EditorTile(
    moduleId: String,
    editor: TileEditorState,
    deviceId: DeviceId,
    modifier: Modifier = Modifier,
) {
    val graph = LocalAppGraph.current
    val spec = ModuleSpec.byModuleIdString(moduleId) ?: return
    val isBeingDragged = editor.draggedModuleId == moduleId
    val isDropTarget = isDropTargetForTile(moduleId, editor)

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                val center = coords.boundsInWindow().center
                editor.tilePositions[moduleId] = TilePosition(center.x, center.y)
            }
            .graphicsLayer {
                if (isBeingDragged) {
                    translationX = editor.dragOffsetX
                    translationY = editor.dragOffsetY
                    shadowElevation = 8f
                }
            }
            .zIndex(if (isBeingDragged) 1f else 0f),
    ) {
        // Highlight ring around tiles that are the current drop target — gives the user clear
        // feedback during the drag without committing to a hover animation.
        val highlight = if (isDropTarget) {
            Modifier.clip(RoundedCornerShape(12.dp)).then(
                Modifier.fillMaxWidth().padding(2.dp),
            )
        } else {
            Modifier
        }
        Box(modifier = highlight) {
            TileForSpec(
                spec = spec,
                deviceId = deviceId,
                graph = graph,
                modifier = Modifier.fillMaxWidth(),
                isHero = false,    // hero tinting is for display mode only — clutter in editor
                onClick = { /* clicks suppressed in edit mode */ },
            )
        }
        DragHandleIcon(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .tileDragHandle(moduleId, editor),
        )
    }
}

@Composable
private fun DragHandleIcon(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(24.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f),
        tonalElevation = 1.dp,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = "Drag to rearrange",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DropIndicatorLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp)),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = MaterialTheme.colorScheme.primary,
        ) {}
    }
}

@Composable
private fun HiddenTrayDivider(editor: TileEditorState) {
    HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .onGloballyPositioned { coords ->
                editor.dividerYPosition = coords.boundsInWindow().top
            },
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun HiddenTray(editor: TileEditorState, deviceId: DeviceId) {
    val hidden = editor.hiddenModulesList
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (hidden.isEmpty()) "Hidden tiles (drag here to hide)" else "Hidden tiles",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hidden.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "No hidden tiles",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        } else {
            // Hidden tiles live in a flow-style row — compact, mostly icon + label.
            // For now lay them out as a vertical column so each remains its own drop source.
            // (A FlowRow would be visually denser; can be a follow-up.)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                hidden.forEach { id ->
                    HiddenChip(moduleId = id, editor = editor, deviceId = deviceId)
                }
            }
        }
    }
}

@Composable
private fun HiddenChip(moduleId: String, editor: TileEditorState, deviceId: DeviceId) {
    val spec = ModuleSpec.byModuleIdString(moduleId) ?: return
    val isBeingDragged = editor.draggedModuleId == moduleId
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                val c = coords.boundsInWindow().center
                editor.tilePositions[moduleId] = TilePosition(c.x, c.y)
            }
            .graphicsLayer {
                if (isBeingDragged) {
                    translationX = editor.dragOffsetX
                    translationY = editor.dragOffsetY
                    shadowElevation = 8f
                }
            }
            .zIndex(if (isBeingDragged) 1f else 0f),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(40.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = spec.icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = spec.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                DragHandleIcon(modifier = Modifier.tileDragHandle(moduleId, editor))
            }
        }
    }
}

@Composable
private fun EditorFooter(onReset: () -> Unit, onSaveAsDefault: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onReset) {
            Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text("Reset")
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSaveAsDefault) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text("Save as default")
        }
    }
}

private fun isDropTargetForTile(moduleId: String, editor: TileEditorState): Boolean =
    when (val target = editor.dropTarget) {
        is DropTarget.SwapInRow -> target.targetModuleId == moduleId
        is DropTarget.SwapSameRow -> target.targetModuleId == moduleId
        is DropTarget.PairInRow -> editor.editorRows.getOrNull(target.rowIndex)?.moduleIds?.contains(moduleId) == true
        else -> false
    }

/**
 * Writes one row's Y-bounds + module IDs into [TileEditorState.rowBounds] via the map's
 * single-slot setter. Replaces the prior O(N²) list-copy-per-row pattern.
 */
private fun Modifier.writeRowBounds(
    rowIndex: Int,
    moduleIds: List<String>,
    editor: TileEditorState,
): Modifier = this.onGloballyPositioned { coords ->
    val bounds = coords.boundsInWindow()
    editor.rowBounds[rowIndex] = RowInfo(yTop = bounds.top, yBottom = bounds.bottom, moduleIds = moduleIds)
}

/**
 * Drops [TileEditorState.rowBounds] entries whose row index is beyond [rowCount] — needed when
 * the row list shortens (e.g. two paired tiles collapsed into one wide row). Without this,
 * stale row entries would confuse [TileEditorState.computeDropTarget].
 */
@Composable
private fun TrimRowBounds(rowCount: Int, editor: TileEditorState) {
    val stale = editor.rowBounds.keys.filter { it >= rowCount }
    if (stale.isNotEmpty()) stale.forEach { editor.rowBounds.remove(it) }
}

/**
 * Drag-handle gesture: starts/updates/ends [TileEditorState] drag for the given module. Shared
 * between the visible-grid [EditorTile] and the [HiddenChip] tray so we only have one path
 * to update.
 */
private fun Modifier.tileDragHandle(moduleId: String, editor: TileEditorState): Modifier =
    this.pointerInput(moduleId) {
        detectDragGestures(
            onDragStart = { editor.startDrag(moduleId) },
            onDrag = { change, delta ->
                change.consume()
                editor.updateDrag(delta.x, delta.y)
            },
            onDragEnd = { editor.endDrag() },
            onDragCancel = { editor.endDrag() },
        )
    }
