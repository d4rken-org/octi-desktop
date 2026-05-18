package eu.darken.octi.desktop.ui.dashboard.editor

import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.ui.dashboard.layout.TileLayoutConfig
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class TileEditorStateTest {

    private val power = ModuleIds.POWER.id
    private val wifi = ModuleIds.WIFI.id
    private val connectivity = ModuleIds.CONNECTIVITY.id
    private val clipboard = ModuleIds.CLIPBOARD.id
    private val files = ModuleIds.FILES.id
    private val apps = ModuleIds.APPS.id
    private val meta = ModuleIds.META.id

    /**
     * Builds an editor state in a deterministic layout for drop-target tests.
     *
     * Default layout (Power wide hero, then 3 paired narrow rows) — with real 20-unit gaps
     * between rows so [DropTarget.BetweenRows] is reachable in tests:
     * ```
     * Row 0 (y  0..80) : [Power]                                 wide
     * gap   (y 80..100)
     * Row 1 (y 100..180): [Wifi (x=100), Connectivity (x=300)]
     * gap   (y 180..200)
     * Row 2 (y 200..280): [Clipboard (x=100), Files (x=300)]
     * gap   (y 280..300)
     * Row 3 (y 300..380): [Apps (x=100), Meta (x=300)]
     * dividerY = 1000 (hidden tray far below)
     * ```
     */
    private fun defaultState(): TileEditorState {
        val state = TileEditorState(TileLayoutConfig())
        state.rowBounds[0] = RowInfo(yTop = 0f, yBottom = 80f, moduleIds = listOf(power))
        state.rowBounds[1] = RowInfo(yTop = 100f, yBottom = 180f, moduleIds = listOf(wifi, connectivity))
        state.rowBounds[2] = RowInfo(yTop = 200f, yBottom = 280f, moduleIds = listOf(clipboard, files))
        state.rowBounds[3] = RowInfo(yTop = 300f, yBottom = 380f, moduleIds = listOf(apps, meta))
        state.tilePositions[power] = TilePosition(200f, 40f)
        state.tilePositions[wifi] = TilePosition(100f, 140f)
        state.tilePositions[connectivity] = TilePosition(300f, 140f)
        state.tilePositions[clipboard] = TilePosition(100f, 240f)
        state.tilePositions[files] = TilePosition(300f, 240f)
        state.tilePositions[apps] = TilePosition(100f, 340f)
        state.tilePositions[meta] = TilePosition(300f, 340f)
        state.dividerYPosition = 1000f
        return state
    }

    @Test
    fun `start, drag, end clears drag state`() {
        val state = defaultState()
        state.startDrag(wifi)
        state.isDragging shouldBe true
        state.draggedModuleId shouldBe wifi
        state.endDrag()
        state.isDragging shouldBe false
        state.draggedModuleId shouldBe null
        state.dragOffsetX shouldBe 0f
        state.dragOffsetY shouldBe 0f
    }

    @Test
    fun `drag narrow alone between rows promotes it to wide`() {
        val state = defaultState()
        // Drag wifi (narrow, in paired row 1) up into the gap above row 1.
        state.startDrag(wifi)
        state.updateDrag(deltaX = 0f, deltaY = -55f)   // wifi.y 140 → 85, into gap 80..100 (no row)
        state.dropTarget.shouldBeInstanceOf<DropTarget.BetweenRows>()
        (state.dropTarget as DropTarget.BetweenRows).beforeRowIndex shouldBe 1
        state.endDrag()
        state.wideModules.contains(wifi) shouldBe true
        // Power should remain wide (untouched).
        state.wideModules.contains(power) shouldBe true
    }

    @Test
    fun `drag wide next to a narrow tile demotes both to narrow paired`() {
        val state = defaultState()
        // Drag Power (wide row 0) down into row 1 alongside Wifi.
        state.startDrag(power)
        state.updateDrag(deltaX = -100f, deltaY = 100f)   // power.y 50 → 150, inside row 1
        // Power is moving INTO row 1 which is a 2-tile row → SwapInRow with closest neighbour.
        state.dropTarget.shouldBeInstanceOf<DropTarget.SwapInRow>()
        state.endDrag()
        state.wideModules.contains(power) shouldBe false
    }

    @Test
    fun `pair-in-row keeps both tiles narrow`() {
        // Setup: Power wide, lone narrow Wifi on its own row, narrow Connectivity below.
        // Drag Connectivity up into Wifi's row to pair them.
        val cfg = TileLayoutConfig(
            order = listOf(power, wifi, connectivity),
            wideModules = setOf(power),
        )
        val state = TileEditorState(cfg)
        state.rowBounds[0] = RowInfo(yTop = 0f, yBottom = 80f, moduleIds = listOf(power))
        state.rowBounds[1] = RowInfo(yTop = 100f, yBottom = 180f, moduleIds = listOf(wifi))     // lone narrow
        state.rowBounds[2] = RowInfo(yTop = 200f, yBottom = 280f, moduleIds = listOf(connectivity))
        state.tilePositions[power] = TilePosition(200f, 40f)
        state.tilePositions[wifi] = TilePosition(200f, 140f)
        state.tilePositions[connectivity] = TilePosition(200f, 240f)
        state.dividerYPosition = 1000f

        state.startDrag(connectivity)
        state.updateDrag(deltaX = 0f, deltaY = -100f)   // connectivity.y 240 → 140, inside row 1
        state.dropTarget.shouldBeInstanceOf<DropTarget.PairInRow>()
        state.endDrag()
        state.wideModules.contains(connectivity) shouldBe false
        state.wideModules.contains(wifi) shouldBe false
    }

    @Test
    fun `swap-same-row reorders horizontally`() {
        val state = defaultState()
        // Drag wifi (x=100, in row 1) past connectivity (x=300, same row).
        state.startDrag(wifi)
        state.updateDrag(deltaX = 250f, deltaY = 0f)    // wifi.x 100 → 350, past connectivity (x=300)
        state.dropTarget.shouldBeInstanceOf<DropTarget.SwapSameRow>()
        state.endDrag()
        // wifi and connectivity have swapped positions in the order list.
        val wifiIdx = state.order.indexOf(wifi)
        val connIdx = state.order.indexOf(connectivity)
        (connIdx < wifiIdx) shouldBe true
    }

    @Test
    fun `drag below divider hides the tile and removes wide flag`() {
        val state = defaultState()
        state.dividerYPosition = 350f  // bring the divider above some rows
        state.startDrag(power)
        state.updateDrag(deltaX = 0f, deltaY = 400f)    // power.y 50 → 450 > divider
        state.dropTarget shouldBe DropTarget.ToHidden
        state.endDrag()
        state.hiddenModules.contains(power) shouldBe true
        state.wideModules.contains(power) shouldBe false
    }

    @Test
    fun `drag back above divider unhides at existing order position`() {
        val cfg = TileLayoutConfig(
            order = listOf(power, wifi, connectivity, clipboard, files, apps, meta),
            wideModules = setOf(power),
            hiddenModules = setOf(meta),    // meta is hidden
        )
        val state = TileEditorState(cfg)
        state.rowBounds[0] = RowInfo(0f, 100f, listOf(power))
        state.tilePositions[meta] = TilePosition(50f, 500f)   // pretend meta is rendered down in the tray
        state.dividerYPosition = 200f

        state.startDrag(meta)
        state.updateDrag(deltaX = 0f, deltaY = -350f)         // meta.y 500 → 150 < divider
        state.dropTarget shouldBe DropTarget.FromHidden
        state.endDrag()
        state.hiddenModules.contains(meta) shouldBe false
        // Order is preserved — meta is still at its original index, just not hidden.
        state.order.indexOf(meta) shouldBe 6
    }

    @Test
    fun `cancel drag (no target) does not mutate config`() {
        val state = defaultState()
        val before = state.toConfig()
        state.startDrag(wifi)
        // Tiny drag that stays inside wifi's own slot → no drop target computed.
        state.updateDrag(deltaX = 1f, deltaY = 1f)
        state.endDrag()
        state.toConfig() shouldBe before
    }

    @Test
    fun `toggleHidden flips state and clears wide`() {
        val state = defaultState()
        state.toggleHidden(power)
        state.hiddenModules.contains(power) shouldBe true
        state.wideModules.contains(power) shouldBe false
        state.toggleHidden(power)
        state.hiddenModules.contains(power) shouldBe false
    }

    @Test
    fun `toConfig round-trips`() {
        val cfg = TileLayoutConfig(
            order = listOf(meta, power, wifi, connectivity, clipboard, files, apps),
            wideModules = setOf(meta),
            hiddenModules = setOf(apps),
        )
        val state = TileEditorState(cfg)
        state.toConfig() shouldBe cfg
    }
}
