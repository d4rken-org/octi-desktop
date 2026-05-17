package eu.darken.octi.desktop.ui.device

import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.protocol.modules.power.PowerInfo
import eu.darken.octi.desktop.protocol.octiserver.ws.EventPayload
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.sync.ModuleReader
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@Suppress("unused")
private val TAG = logTag("UI", "DeviceDetail")

/**
 * Loads the read-only module data for one peer. MVP scope: just Power. Adding Meta / Wifi /
 * etc. is mechanical — same pattern, different [ModuleReader] call.
 */
class DeviceDetailViewModel(
    private val graph: AppGraph,
    private val targetDeviceId: DeviceId,
) {

    private val _powerState = MutableStateFlow<ModuleState<PowerInfo>>(ModuleState.Loading)
    val powerState: StateFlow<ModuleState<PowerInfo>> = _powerState.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refresh()
        // Targeted WS-driven refresh: when a ModuleChanged event arrives for THIS peer's POWER
        // module, re-fetch. Other module IDs (meta, wifi, …) wake their own sections once those
        // readers are wired in later phases — we filter here to avoid spurious refetches that
        // would burn rate-limit budget without changing the visible UI.
        graph.syncEventBus.events
            .filter { event ->
                event is EventPayload.Event.ModuleChanged &&
                    event.deviceId == targetDeviceId.id &&
                    event.moduleId == ModuleIds.POWER.id
            }
            .onEach { refresh() }
            .launchIn(graph.appScope)
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = graph.appScope.launch {
            _powerState.value = ModuleState.Loading
            val result = graph.moduleReader.read(
                moduleId = ModuleIds.POWER,
                targetDeviceId = targetDeviceId,
                serializer = PowerInfo.serializer(),
            )
            _powerState.value = when (result) {
                is ModuleReader.Result.Ok -> ModuleState.Ok(result.value)
                ModuleReader.Result.NotFound -> ModuleState.NotFound
                is ModuleReader.Result.Error -> ModuleState.Error(
                    result.cause.message ?: result.cause.javaClass.simpleName,
                )
            }
        }
    }

    sealed class ModuleState<out T> {
        data object Loading : ModuleState<Nothing>()
        data class Ok<T>(val value: T) : ModuleState<T>()
        data object NotFound : ModuleState<Nothing>()
        data class Error(val message: String) : ModuleState<Nothing>()
    }
}
