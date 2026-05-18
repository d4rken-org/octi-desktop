package eu.darken.octi.desktop.ui.dashboard.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import eu.darken.octi.desktop.ui.dashboard.layout.TileLayoutConfig

/**
 * Center point of a tile in window coordinates. Used for hit-testing during drag.
 */
data class TilePosition(val x: Float, val y: Float)

/**
 * Y-bounds + module IDs of one editor row. Populated by `onGloballyPositioned` on the row
 * composable and consumed by [TileEditorState.computeDropTarget].
 */
data class RowInfo(val yTop: Float, val yBottom: Float, val moduleIds: List<String>)

/**
 * Outcome of a hit-test during drag. Six cases — port of Android's `DropTarget`.
 */
sealed interface DropTarget {
    /** Insert as a wide tile before this row index. `editorRows.size` means "after last row". */
    data class BetweenRows(val beforeRowIndex: Int) : DropTarget

    /** Pair with the lone tile in this row (turns the previously-wide tile narrow too). */
    data class PairInRow(val rowIndex: Int) : DropTarget

    /** Swap into the position of [targetModuleId] in a 2-tile row from a different row. */
    data class SwapInRow(val targetModuleId: String) : DropTarget

    /** Horizontal swap within the same 2-tile row. */
    data class SwapSameRow(val targetModuleId: String) : DropTarget

    /** Drop into the hidden tray below the divider. */
    data object ToHidden : DropTarget

    /** Drag from the hidden tray back into the visible grid. */
    data object FromHidden : DropTarget
}

/**
 * In-progress editor state for one device card. Compose-state-backed for cheap recomposition.
 *
 * Created from a snapshot of the device's effective layout when the user opens the editor and
 * lives at `DashboardScreen` scope (NOT inside the editor composable) so that scrolling the
 * outer `LazyVerticalGrid` far enough to dispose the edited item doesn't lose in-flight edits.
 *
 * Mutation API:
 * - [startDrag] / [updateDrag] / [endDrag] drive the drag gesture and the implicit
 *   single↔wide resize that happens at drop.
 * - [toggleHidden] toggles a module's hidden state directly (not via drag).
 * - [toConfig] snapshots the current state back to a persistable [TileLayoutConfig].
 */
class TileEditorState(initialConfig: TileLayoutConfig) {

    var order by mutableStateOf(initialConfig.order)
        private set
    var wideModules by mutableStateOf(initialConfig.wideModules)
        private set
    var hiddenModules by mutableStateOf(initialConfig.hiddenModules)
        private set

    var draggedModuleId by mutableStateOf<String?>(null)
        private set
    var dragOffsetX by mutableFloatStateOf(0f)
        private set
    var dragOffsetY by mutableFloatStateOf(0f)
        private set
    var dropTarget by mutableStateOf<DropTarget?>(null)
        private set

    /**
     * Module-ID → tile center in window coordinates. Written by `onGloballyPositioned` on each
     * editor tile. Mutated as composables re-layout.
     */
    val tilePositions: SnapshotStateMap<String, TilePosition> = mutableStateMapOf()

    /**
     * Row-index → Y-bounds + module IDs of that row, in window coordinates. Written one slot
     * at a time by each row's `onGloballyPositioned` callback. Using a map (not a `List`) lets
     * per-row writes avoid the O(N) list-copy-and-replace that the prior `var editorRows: List`
     * pattern triggered on every layout pass.
     */
    val rowBounds: SnapshotStateMap<Int, RowInfo> = mutableStateMapOf()

    /** Sorted, dense view of [rowBounds] — convenience for display + hit-test logic. */
    val editorRows: List<RowInfo>
        get() = rowBounds.entries.sortedBy { it.key }.map { it.value }

    /**
     * Snapshot of [editorRows] taken at [startDrag] and held for the gesture's lifetime. The
     * hit-test reads from this — NOT live `rowBounds` — so layout shifts that fire mid-drag
     * (e.g. a paired row collapsing because the drag-offset visually moved a tile) don't make
     * `computeDropTarget` see partial row state. Cleared on [endDrag].
     */
    private var dragRowsSnapshot: List<RowInfo>? = null

    /**
     * Y of the divider that separates the visible grid from the hidden tray. Crossing it
     * during drag emits [DropTarget.ToHidden] / [DropTarget.FromHidden].
     */
    var dividerYPosition by mutableFloatStateOf(Float.MAX_VALUE)

    val visibleModules: List<String>
        get() = order.filter { it !in hiddenModules }

    val hiddenModulesList: List<String>
        get() = order.filter { it in hiddenModules }

    val isDragging: Boolean
        get() = draggedModuleId != null

    fun startDrag(moduleId: String) {
        draggedModuleId = moduleId
        dragOffsetX = 0f
        dragOffsetY = 0f
        dropTarget = null
        // Freeze the row layout for the duration of the gesture so hit-tests are coherent
        // even if onGloballyPositioned callbacks shift bounds mid-drag.
        dragRowsSnapshot = editorRows
    }

    fun updateDrag(deltaX: Float, deltaY: Float) {
        if (draggedModuleId == null) return
        dragOffsetX += deltaX
        dragOffsetY += deltaY
        dropTarget = computeDropTarget()
    }

    fun endDrag() {
        val dragged = draggedModuleId ?: return
        val target = dropTarget
        if (target != null) applyDrop(dragged, target)
        draggedModuleId = null
        dragOffsetX = 0f
        dragOffsetY = 0f
        dropTarget = null
        dragRowsSnapshot = null
    }

    /**
     * Force-toggle hidden state without a drag (e.g., from a context menu — unused in MVP but
     * keeps the API symmetric for future use).
     */
    fun toggleHidden(moduleId: String) {
        hiddenModules = if (moduleId in hiddenModules) hiddenModules - moduleId else hiddenModules + moduleId
        if (moduleId in hiddenModules) wideModules = wideModules - moduleId
    }

    fun toConfig(): TileLayoutConfig = TileLayoutConfig(
        order = order,
        wideModules = wideModules,
        hiddenModules = hiddenModules,
    )

    private fun computeDropTarget(): DropTarget? {
        val dragged = draggedModuleId ?: return null
        val origin = tilePositions[dragged] ?: return null
        val visualX = origin.x + dragOffsetX
        val visualY = origin.y + dragOffsetY
        val isCurrentlyHidden = dragged in hiddenModules

        // Divider crossing — the visible/hidden boundary.
        if (!isCurrentlyHidden && visualY > dividerYPosition) return DropTarget.ToHidden
        if (isCurrentlyHidden && visualY < dividerYPosition) return DropTarget.FromHidden

        // Reordering within the hidden tray: no visual target needed (tiles only render in
        // their saved order in the tray; no swap UX yet).
        if (isCurrentlyHidden) return null

        // Read the snapshot taken at startDrag — coherent for the gesture's lifetime, immune
        // to mid-drag layout shifts that would otherwise corrupt the hit-test.
        val rows = dragRowsSnapshot ?: return null
        val draggedRow = rows.find { dragged in it.moduleIds }
        val targetRow = rows.find { visualY in it.yTop..it.yBottom }

        return when {
            targetRow != null && dragged !in targetRow.moduleIds -> {
                if (targetRow.moduleIds.size == 1) {
                    DropTarget.PairInRow(rows.indexOf(targetRow))
                } else {
                    val closest = targetRow.moduleIds.minByOrNull { id ->
                        val pos = tilePositions[id] ?: return@minByOrNull Float.MAX_VALUE
                        kotlin.math.abs(visualX - pos.x)
                    } ?: return null
                    DropTarget.SwapInRow(closest)
                }
            }

            targetRow == null && rows.isNotEmpty() -> {
                val insertBefore = rows.indexOfFirst { visualY < it.yTop }
                val beforeIndex = if (insertBefore >= 0) insertBefore else rows.size
                val currentRowIndex = rows.indexOfFirst { dragged in it.moduleIds }
                val currentRow = rows.getOrNull(currentRowIndex)
                val isInPairedRow = currentRow != null && currentRow.moduleIds.size == 2

                // If the dragged tile is already wide on its own row, don't suggest dropping it
                // into the gap immediately above or below itself — that's a no-op.
                if (!isInPairedRow && (beforeIndex == currentRowIndex || beforeIndex == currentRowIndex + 1)) {
                    null
                } else {
                    DropTarget.BetweenRows(beforeIndex)
                }
            }

            draggedRow != null && draggedRow == targetRow && draggedRow.moduleIds.size == 2 -> {
                // In-row horizontal swap with the sibling tile.
                val other = draggedRow.moduleIds.first { it != dragged }
                val otherPos = tilePositions[other] ?: return null
                val draggedOriginPos = tilePositions[dragged] ?: return null
                val shouldSwap = if (draggedOriginPos.x < otherPos.x) {
                    visualX > otherPos.x
                } else {
                    visualX < otherPos.x
                }
                if (shouldSwap) DropTarget.SwapSameRow(other) else null
            }

            else -> null
        }
    }

    private fun applyDrop(dragged: String, target: DropTarget) {
        when (target) {
            is DropTarget.ToHidden -> {
                hiddenModules = hiddenModules + dragged
                wideModules = wideModules - dragged
            }

            is DropTarget.FromHidden -> {
                // Unhide at the tile's existing order position. Matches Android — keeps the
                // FromHidden code path tiny; subsequent drags can reorder it where the user
                // really wants it.
                hiddenModules = hiddenModules - dragged
            }

            is DropTarget.PairInRow -> {
                val origin = tilePositions[dragged] ?: return
                val visualX = origin.x + dragOffsetX
                val row = editorRows.getOrNull(target.rowIndex) ?: return
                val targetModule = row.moduleIds.firstOrNull() ?: return
                val targetIndex = order.indexOf(targetModule)
                val targetPos = tilePositions[targetModule] ?: return
                val currentIndex = order.indexOf(dragged)

                val insertIndex = if (visualX < targetPos.x) targetIndex else targetIndex + 1
                val mutable = order.toMutableList()
                mutable.removeAt(currentIndex)
                val adjusted = if (currentIndex < insertIndex) insertIndex - 1 else insertIndex
                mutable.add(adjusted.coerceIn(0, mutable.size), dragged)
                order = mutable
                wideModules = wideModules - dragged - targetModule
            }

            is DropTarget.SwapInRow -> {
                val currentIndex = order.indexOf(dragged)
                val closestIndex = order.indexOf(target.targetModuleId)
                val mutable = order.toMutableList()
                mutable.removeAt(currentIndex)
                val adjusted = if (currentIndex < closestIndex) closestIndex - 1 else closestIndex
                mutable.add(adjusted.coerceIn(0, mutable.size), dragged)
                order = mutable
                wideModules = wideModules - dragged - target.targetModuleId
            }

            is DropTarget.BetweenRows -> {
                val currentIndex = order.indexOf(dragged)
                val insertBeforeRow = editorRows.getOrNull(target.beforeRowIndex)
                val insertIndex = if (insertBeforeRow != null) {
                    order.indexOf(insertBeforeRow.moduleIds.first())
                } else {
                    val lastRow = editorRows.lastOrNull()
                    if (lastRow != null) order.indexOf(lastRow.moduleIds.last()) + 1 else order.size
                }
                val mutable = order.toMutableList()
                mutable.removeAt(currentIndex)
                val adjusted = if (currentIndex < insertIndex) insertIndex - 1 else insertIndex
                mutable.add(adjusted.coerceIn(0, mutable.size), dragged)
                order = mutable
                wideModules = wideModules + dragged
            }

            is DropTarget.SwapSameRow -> {
                val currentIndex = order.indexOf(dragged)
                val otherIndex = order.indexOf(target.targetModuleId)
                val mutable = order.toMutableList()
                mutable[currentIndex] = target.targetModuleId
                mutable[otherIndex] = dragged
                order = mutable
            }
        }
    }
}
