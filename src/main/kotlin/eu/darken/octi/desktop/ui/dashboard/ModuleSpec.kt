package eu.darken.octi.desktop.ui.dashboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.octi.desktop.protocol.module.ModuleId
import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.protocol.modules.apps.AppsInfo
import eu.darken.octi.desktop.protocol.modules.clipboard.ClipboardInfo
import eu.darken.octi.desktop.protocol.modules.connectivity.ConnectivityInfo
import eu.darken.octi.desktop.protocol.modules.files.FileShareInfo
import eu.darken.octi.desktop.protocol.modules.meta.MetaInfo
import eu.darken.octi.desktop.protocol.modules.power.PowerInfo
import eu.darken.octi.desktop.protocol.modules.wifi.WifiInfo
import kotlinx.serialization.KSerializer

/**
 * What it means for a module read to return [eu.darken.octi.desktop.sync.ModuleReader.Result.NotFound]
 * (i.e. server has no payload for this (peer, module) pair). Drives the tile's empty-state copy:
 *
 * - [EMPTY_STATE] — the module *is* shared but there's nothing in it yet ("clipboard is empty",
 *   "no files shared"). Render the tile as a normal compact view with empty content.
 * - [NOT_SHARED] — the peer's build doesn't expose this module at all (desktop doesn't collect
 *   wifi/apps/etc.; older Android builds may skip files). Render a muted "Not shared by this
 *   device" placeholder.
 */
enum class NotFoundPolicy { EMPTY_STATE, NOT_SHARED }

/**
 * Registry of all dashboard-renderable modules. One sealed-object per module; carries everything
 * a tile needs:
 *
 * - [moduleId] / [serializer]: pair that [eu.darken.octi.desktop.sync.ModuleReader] needs to
 *   decrypt + decode the wire payload.
 * - [displayName] / [icon]: tile presentation.
 * - [notFoundPolicy]: what to show when the peer hasn't written this module.
 * - [gridSpan]: column span in the per-card tile grid. Power spans 2 (wide), others span 1.
 *
 * **Order** declared via [entries] mirrors Android's `ModuleUiRegistry`: Power, WiFi, Connectivity,
 * Clipboard, Files, Apps, Meta. Drift here and the desktop tile layout will look different from
 * the phone — fix by reordering, not by renaming.
 */
sealed class ModuleSpec<T : Any>(
    val moduleId: ModuleId,
    val displayName: String,
    val icon: ImageVector,
    val serializer: KSerializer<T>,
    val notFoundPolicy: NotFoundPolicy,
    val gridSpan: Int,
) {

    data object Power : ModuleSpec<PowerInfo>(
        moduleId = ModuleIds.POWER,
        displayName = "Power",
        icon = Icons.Filled.BatteryFull,
        serializer = PowerInfo.serializer(),
        notFoundPolicy = NotFoundPolicy.NOT_SHARED,
        gridSpan = 2,
    )

    data object Wifi : ModuleSpec<WifiInfo>(
        moduleId = ModuleIds.WIFI,
        displayName = "WiFi",
        icon = Icons.Filled.Wifi,
        serializer = WifiInfo.serializer(),
        notFoundPolicy = NotFoundPolicy.NOT_SHARED,
        gridSpan = 1,
    )

    data object Connectivity : ModuleSpec<ConnectivityInfo>(
        moduleId = ModuleIds.CONNECTIVITY,
        displayName = "Connectivity",
        icon = Icons.Filled.NetworkCheck,
        serializer = ConnectivityInfo.serializer(),
        notFoundPolicy = NotFoundPolicy.NOT_SHARED,
        gridSpan = 1,
    )

    data object Clipboard : ModuleSpec<ClipboardInfo>(
        moduleId = ModuleIds.CLIPBOARD,
        displayName = "Clipboard",
        icon = Icons.Filled.ContentPaste,
        serializer = ClipboardInfo.serializer(),
        notFoundPolicy = NotFoundPolicy.EMPTY_STATE,
        gridSpan = 1,
    )

    data object Files : ModuleSpec<FileShareInfo>(
        moduleId = ModuleIds.FILES,
        displayName = "Files",
        icon = Icons.Filled.Folder,
        serializer = FileShareInfo.serializer(),
        notFoundPolicy = NotFoundPolicy.EMPTY_STATE,
        gridSpan = 1,
    )

    data object Apps : ModuleSpec<AppsInfo>(
        moduleId = ModuleIds.APPS,
        displayName = "Apps",
        icon = Icons.Filled.Apps,
        serializer = AppsInfo.serializer(),
        notFoundPolicy = NotFoundPolicy.NOT_SHARED,
        gridSpan = 1,
    )

    data object Meta : ModuleSpec<MetaInfo>(
        moduleId = ModuleIds.META,
        displayName = "Device",
        icon = Icons.Filled.Info,
        serializer = MetaInfo.serializer(),
        notFoundPolicy = NotFoundPolicy.NOT_SHARED,
        gridSpan = 1,
    )

    companion object {
        /** Tile order on the dashboard, matching Android. Power first, wide. */
        val entries: List<ModuleSpec<*>> = listOf(Power, Wifi, Connectivity, Clipboard, Files, Apps, Meta)

        fun byModuleId(moduleId: ModuleId): ModuleSpec<*>? = entries.firstOrNull { it.moduleId == moduleId }
    }
}
