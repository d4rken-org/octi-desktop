package eu.darken.octi.desktop.debug.rpc

import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.modules.meta.DeviceMetadataProvider
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Source of `/dev/state` JSON snapshots. Extracted as an interface so the Ktor route tests can
 * substitute a static payload without constructing a real [AppGraph].
 */
interface DebugStateSource {
    fun snapshot(): JsonObject
}

/**
 * Production [DebugStateSource]. Reads `.value` from each observable graph flow rather than
 * collecting, so the response is fast and never blocks waiting for an event to arrive.
 *
 * Codex review #6: don't claim more than we know. `activeClient` being non-null means
 * "credentials configured" — not "the WebSocket is currently connected." Report the WebSocket's
 * own state separately so callers can tell which.
 */
class DebugStateProvider(private val graph: AppGraph) : DebugStateSource {

    override fun snapshot(): JsonObject = buildJsonObject {
        put("version", JsonPrimitive(DeviceMetadataProvider.APP_VERSION))
        put("deviceId", JsonPrimitive(graph.deviceId.id))
        put("screen", JsonPrimitive(graph.navigator.current.value.routeName()))
        put("activeClientPresent", JsonPrimitive(graph.activeClient.value != null))
        put("webSocketState", JsonPrimitive(webSocketStateName()))
        put("deviceListLoadState", JsonPrimitive(deviceListStateName()))
        put("deviceCount", JsonPrimitive(graph.deviceListRepo.devices.value.size))
        put("knownDevices", buildJsonArray {
            graph.deviceListRepo.devices.value.forEach { device ->
                add(buildJsonObject {
                    put("deviceId", JsonPrimitive(device.id))
                    put("label", device.label.toJson())
                    put("platform", device.platform.toJson())
                    put("lastSeen", device.lastSeen?.toString().toJson())
                    // Preserve the null-vs-empty distinction — null = peer hasn't reported,
                    // empty array = peer explicitly reports no capabilities. The Capability
                    // authority semantics depend on this difference.
                    put(
                        "capabilities",
                        device.capabilities?.let { caps ->
                            buildJsonArray { caps.sorted().forEach { add(JsonPrimitive(it)) } }
                        } ?: JsonNull,
                    )
                })
            }
        })
        put("lastMetaWriteSuccessAt", graph.metaWriter.lastWriteSuccessAt.value?.toString().toJson())
        put("lastWsEventAt", graph.syncEventBus.lastEventAt.value?.toString().toJson())
    }

    private fun webSocketStateName(): String = graph.webSocketClient.state.value::class.simpleName
        ?: "Unknown"

    private fun deviceListStateName(): String = graph.deviceListRepo.loadState.value::class.simpleName
        ?: "Unknown"

    private fun String?.toJson(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

    private fun eu.darken.octi.desktop.ui.nav.Screen.routeName(): String = when (this) {
        eu.darken.octi.desktop.ui.nav.Screen.Linking -> "linking"
        eu.darken.octi.desktop.ui.nav.Screen.Dashboard -> "dashboard"
        eu.darken.octi.desktop.ui.nav.Screen.Clipboard -> "clipboard"
        eu.darken.octi.desktop.ui.nav.Screen.Settings -> "settings"
        is eu.darken.octi.desktop.ui.nav.Screen.Files -> "files:${this.deviceId}"
    }
}
