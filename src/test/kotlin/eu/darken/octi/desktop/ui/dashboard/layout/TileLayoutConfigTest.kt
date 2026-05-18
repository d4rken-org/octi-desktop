package eu.darken.octi.desktop.ui.dashboard.layout

import eu.darken.octi.desktop.protocol.module.ModuleIds
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TileLayoutConfigTest {

    private val all = setOf(
        ModuleIds.POWER.id,
        ModuleIds.WIFI.id,
        ModuleIds.CONNECTIVITY.id,
        ModuleIds.CLIPBOARD.id,
        ModuleIds.FILES.id,
        ModuleIds.APPS.id,
        ModuleIds.META.id,
    )

    @Nested
    inner class `toRows row packing` {

        @Test
        fun `default layout yields wide power then three narrow pairs`() {
            val rows = TileLayoutConfig().toRows(all)
            rows.size shouldBe 4
            rows[0].moduleIds shouldBe listOf(ModuleIds.POWER.id)               // hero
            rows[1].moduleIds shouldBe listOf(ModuleIds.WIFI.id, ModuleIds.CONNECTIVITY.id)
            rows[2].moduleIds shouldBe listOf(ModuleIds.CLIPBOARD.id, ModuleIds.FILES.id)
            rows[3].moduleIds shouldBe listOf(ModuleIds.APPS.id, ModuleIds.META.id)
        }

        @Test
        fun `narrow followed by wide is given its own row`() {
            // Wifi narrow, then Connectivity wide — Wifi cannot pair with Connectivity.
            val cfg = TileLayoutConfig(
                order = listOf(ModuleIds.WIFI.id, ModuleIds.CONNECTIVITY.id),
                wideModules = setOf(ModuleIds.CONNECTIVITY.id),
            )
            val rows = cfg.toRows(all)
            rows.size shouldBe 2
            rows[0].moduleIds shouldBe listOf(ModuleIds.WIFI.id)
            rows[1].moduleIds shouldBe listOf(ModuleIds.CONNECTIVITY.id)
        }

        @Test
        fun `last narrow tile pairs with previous narrow`() {
            val cfg = TileLayoutConfig(
                order = listOf(ModuleIds.WIFI.id, ModuleIds.CONNECTIVITY.id, ModuleIds.APPS.id),
                wideModules = emptySet(),
            )
            val rows = cfg.toRows(all)
            rows.size shouldBe 2
            rows[0].moduleIds shouldBe listOf(ModuleIds.WIFI.id, ModuleIds.CONNECTIVITY.id)
            rows[1].moduleIds shouldBe listOf(ModuleIds.APPS.id)   // orphan
        }

        @Test
        fun `all wide produces seven single-tile rows`() {
            val cfg = TileLayoutConfig(wideModules = all)
            val rows = cfg.toRows(all)
            rows.size shouldBe 7
            rows.forEach { it.moduleIds.size shouldBe 1 }
        }

        @Test
        fun `hidden modules are excluded`() {
            val cfg = TileLayoutConfig(hiddenModules = setOf(ModuleIds.POWER.id, ModuleIds.META.id))
            val visible = cfg.toRows(all).flatMap { it.moduleIds }
            visible.contains(ModuleIds.POWER.id) shouldBe false
            visible.contains(ModuleIds.META.id) shouldBe false
            visible.size shouldBe 5
        }

        @Test
        fun `module not in availableModules is skipped`() {
            val cfg = TileLayoutConfig()
            val rows = cfg.toRows(all - ModuleIds.POWER.id)
            // Without Power the layout becomes [Wifi|Connectivity], [Clipboard|Files], [Apps|Meta]
            rows.size shouldBe 3
            rows.flatMap { it.moduleIds }.contains(ModuleIds.POWER.id) shouldBe false
        }

        @Test
        fun `empty order yields empty rows`() {
            TileLayoutConfig(order = emptyList()).toRows(all).size shouldBe 0
        }

        @Test
        fun `all hidden yields empty rows`() {
            TileLayoutConfig(hiddenModules = all).toRows(all).size shouldBe 0
        }
    }

    @Nested
    inner class `normalize` {

        @Test
        fun `unknown module IDs are dropped from every field`() {
            val cfg = TileLayoutConfig(
                order = listOf("ghost.module", ModuleIds.POWER.id, ModuleIds.WIFI.id),
                wideModules = setOf("ghost.module", ModuleIds.POWER.id),
                hiddenModules = setOf("ghost.module"),
            )
            val n = cfg.normalize(all)
            n.order.contains("ghost.module") shouldBe false
            n.wideModules.contains("ghost.module") shouldBe false
            n.hiddenModules.contains("ghost.module") shouldBe false
        }

        @Test
        fun `missing modules are inserted at their default-order position`() {
            // User's saved order has only Power + Apps. The other 5 should slot in at their
            // canonical positions: Wifi/Connectivity/Clipboard/Files between Power and Apps,
            // Meta after Apps.
            val cfg = TileLayoutConfig(order = listOf(ModuleIds.POWER.id, ModuleIds.APPS.id))
            val n = cfg.normalize(all)
            n.order shouldBe listOf(
                ModuleIds.POWER.id,
                ModuleIds.WIFI.id,
                ModuleIds.CONNECTIVITY.id,
                ModuleIds.CLIPBOARD.id,
                ModuleIds.FILES.id,
                ModuleIds.APPS.id,
                ModuleIds.META.id,
            )
        }

        @Test
        fun `duplicate IDs in order are deduped`() {
            val cfg = TileLayoutConfig(
                order = listOf(ModuleIds.POWER.id, ModuleIds.WIFI.id, ModuleIds.POWER.id),
            )
            val n = cfg.normalize(all)
            n.order.count { it == ModuleIds.POWER.id } shouldBe 1
            n.order.size shouldBe all.size
        }

        @Test
        fun `default config is normalize-idempotent`() {
            val n1 = TileLayoutConfig().normalize(all)
            val n2 = n1.normalize(all)
            n1 shouldBe n2
        }

        @Test
        fun `wideModules cannot contain a hidden id (separate from drop)`() {
            // normalize doesn't enforce wide∩hidden = ∅ — that's an editor invariant, not a
            // config one. Verify normalize preserves both sets, dropping only unknowns.
            val cfg = TileLayoutConfig(
                wideModules = setOf(ModuleIds.POWER.id),
                hiddenModules = setOf(ModuleIds.POWER.id),
            )
            val n = cfg.normalize(all)
            n.wideModules shouldBe setOf(ModuleIds.POWER.id)
            n.hiddenModules shouldBe setOf(ModuleIds.POWER.id)
        }
    }
}
