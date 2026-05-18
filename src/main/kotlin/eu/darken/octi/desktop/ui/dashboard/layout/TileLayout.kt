package eu.darken.octi.desktop.ui.dashboard.layout

import eu.darken.octi.desktop.protocol.module.ModuleIds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-device tile-grid configuration. Three independent dimensions:
 *
 * - [order] — module IDs in display order. Stable across re-renders; the source of truth for
 *   "which tile sits before which".
 * - [wideModules] — module IDs that occupy a full-width row instead of pairing with a neighbour.
 * - [hiddenModules] — module IDs not rendered at all (sit in the editor's hidden tray).
 *
 * The row-packing algorithm in [toRows] guarantees only 1-tile (wide) or 2-tile (paired narrow)
 * rows are emitted — 3-tile rows are structurally impossible. This mirrors Android
 * `app-main/.../dashboard/DashboardConfig.kt` byte-for-byte so per-device layouts authored on one
 * platform render identically on the other.
 */
@Serializable
data class TileLayoutConfig(
    @SerialName("order") val order: List<String> = DEFAULT_ORDER,
    @SerialName("wideModules") val wideModules: Set<String> = DEFAULT_WIDE,
    @SerialName("hiddenModules") val hiddenModules: Set<String> = emptySet(),
) {

    companion object {
        /**
         * Canonical tile order — same as Android `ModuleUiRegistry.orderedTileIds`. Drift here
         * and a fresh install's default layout will visually differ from the phone.
         */
        val DEFAULT_ORDER: List<String> = listOf(
            ModuleIds.POWER.id,
            ModuleIds.WIFI.id,
            ModuleIds.CONNECTIVITY.id,
            ModuleIds.CLIPBOARD.id,
            ModuleIds.FILES.id,
            ModuleIds.APPS.id,
            ModuleIds.META.id,
        )

        /** Power is the only wide tile by default — it's the hero. */
        val DEFAULT_WIDE: Set<String> = setOf(ModuleIds.POWER.id)
    }
}

data class TileRow(val moduleIds: List<String>) {
    init {
        require(moduleIds.size in 1..2) { "TileRow must have 1 or 2 modules, got ${moduleIds.size}" }
    }
}

/**
 * Pack the visible tiles into rows. Algorithm (verbatim port of Android):
 *
 * ```
 * while i < visible.size:
 *     if visible[i] in wideModules           → emit 1-tile row
 *     elif i+1 < size && visible[i+1] not wide → emit 2-tile row
 *     else                                    → emit 1-tile row (orphan narrow)
 * ```
 *
 * The "orphan narrow" case handles a narrow tile immediately before a wide tile — pairing it
 * with a wide one would produce a 3-cell visual; instead it gets a full row to itself, which
 * looks fine visually because the next wide row spans the same width.
 *
 * [availableModules] gates which IDs from [order] are actually rendered (filters out modules
 * the desktop doesn't know about; defensive against a stale config carrying ID strings from a
 * future schema).
 */
fun TileLayoutConfig.toRows(availableModules: Set<String>): List<TileRow> {
    val visible = order.filter { it !in hiddenModules && it in availableModules }
    val rows = mutableListOf<TileRow>()
    var i = 0
    while (i < visible.size) {
        when {
            visible[i] in wideModules -> {
                rows.add(TileRow(listOf(visible[i])))
                i++
            }

            i + 1 < visible.size && visible[i + 1] !in wideModules -> {
                rows.add(TileRow(listOf(visible[i], visible[i + 1])))
                i += 2
            }

            else -> {
                rows.add(TileRow(listOf(visible[i])))
                i++
            }
        }
    }
    return rows
}

/**
 * Clean up a config against the current set of known module IDs. Removes IDs not in
 * [allModuleIds] from every field; inserts missing IDs (modules the user hasn't seen yet) at
 * their position in [TileLayoutConfig.DEFAULT_ORDER].
 *
 * Idempotent. Safe to call on both read and write — defensive against an older settings.json
 * carrying retired module IDs, or a fresh install meeting a config authored against a newer
 * desktop build.
 */
fun TileLayoutConfig.normalize(allModuleIds: Set<String>): TileLayoutConfig {
    val knownOrder = order.filter { it in allModuleIds }.distinct()
    val missing = allModuleIds - knownOrder.toSet()

    val result = knownOrder.toMutableList()
    for (newId in TileLayoutConfig.DEFAULT_ORDER.filter { it in missing }) {
        val defaultIdx = TileLayoutConfig.DEFAULT_ORDER.indexOf(newId)
        var insertAt = result.size
        for (i in result.indices.reversed()) {
            val existingDefaultIdx = TileLayoutConfig.DEFAULT_ORDER.indexOf(result[i])
            if (existingDefaultIdx in 0 until defaultIdx) {
                insertAt = i + 1
                break
            }
            if (i == 0) insertAt = 0
        }
        result.add(insertAt, newId)
    }
    // Anything still missing isn't in DEFAULT_ORDER either (shouldn't happen with the canonical
    // module set, but stay defensive — append in sorted order for determinism).
    val stillMissing = missing - TileLayoutConfig.DEFAULT_ORDER.toSet()
    result.addAll(stillMissing.sorted())

    return copy(
        order = result,
        wideModules = wideModules.filter { it in allModuleIds }.toSet(),
        hiddenModules = hiddenModules.filter { it in allModuleIds }.toSet(),
    )
}
