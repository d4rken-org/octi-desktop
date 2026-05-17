package eu.darken.octi.desktop.sync

import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
import eu.darken.octi.desktop.protocol.octiserver.dto.DevicesResponse
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

private val TAG = logTag("Sync", "DeviceListRepo")

/**
 * Source of truth for "which devices are in this account?". Polls `/v1/devices` while a
 * client is active, falls back to the on-disk cache on cold start, and tolerates transient
 * fetch failures (keeps emitting the last-known list rather than erroring out the UI).
 *
 * Owns a single polling loop tied to [AppGraph.activeClient]. The `flatMapLatest` operator
 * tears down the previous loop when [AppGraph.activeClient] transitions (e.g. unlink), so
 * we don't leak coroutines or attempt requests against a closed [OctiServerHttpClient].
 */
class DeviceListRepo(
    private val graph: AppGraph,
    private val pollIntervalSeconds: Int = graph.settings.data.syncIntervalSeconds,
    private val cache: DeviceListCache = DeviceListCache(),
) {

    private val _state = MutableStateFlow<LoadState>(LoadState.Initial)
    val loadState: StateFlow<LoadState> = _state.asStateFlow()

    /**
     * Wakes the polling loop early. `CONFLATED` capacity means a burst of N kicks within one
     * poll window collapses to a single refresh — the server's 500ms debounce already groups
     * related events, so further collapsing on the client side is correct, not lossy.
     */
    private val kickChannel = Channel<Unit>(Channel.CONFLATED)

    /** Manually request an immediate refresh (UI refresh button, post-link warm-up, …). */
    fun kick() {
        kickChannel.trySend(Unit)
    }

    /**
     * Latest known device list. Initial value is whatever the cache holds (possibly stale).
     * Emits an updated list after every successful poll.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val devices: StateFlow<List<DevicesResponse.Device>> = graph.activeClient
        .flatMapLatest { client ->
            if (client == null) {
                _state.value = LoadState.Idle
                flowOf(emptyList())
            } else {
                pollLoop(client)
            }
        }
        .stateIn(
            scope = graph.appScope,
            started = SharingStarted.Eagerly,
            initialValue = cache.load() ?: emptyList(),
        )

    init {
        // Side effect: when the client disconnects (unlink), the cache should be wiped so a
        // future link with a different account doesn't show ghost devices.
        graph.activeClient
            .onEach { if (it == null) cache.clear() }
            .launchIn(graph.appScope)

        // WS-driven freshness: any incoming sync event means at least one peer wrote a module,
        // which means at least one peer's lastSeen advanced. Kick the polling loop so the
        // Dashboard's "Online" badges update without waiting for the 5-min REST tick.
        graph.syncEventBus.events
            .onEach { kick() }
            .launchIn(graph.appScope)
    }

    private fun pollLoop(client: OctiServerHttpClient): Flow<List<DevicesResponse.Device>> = flow {
        while (true) {
            _state.value = LoadState.Loading
            try {
                val response = client.getDeviceList()
                cache.save(response.devices)
                _state.value = LoadState.Ok
                emit(response.devices)
            } catch (e: Throwable) {
                log(TAG, Logging.Priority.WARN, e) { "getDeviceList failed; keeping last value" }
                _state.value = LoadState.Error(e.message ?: e.javaClass.simpleName)
            }
            // withTimeoutOrNull returns null on timeout (natural poll tick) and Unit on a kick.
            // Either way we restart the loop body and refetch.
            withTimeoutOrNull(pollIntervalSeconds.seconds) { kickChannel.receive() }
        }
    }

    sealed class LoadState {
        data object Initial : LoadState()
        data object Loading : LoadState()
        data object Ok : LoadState()
        data object Idle : LoadState()
        data class Error(val message: String) : LoadState()
    }
}
