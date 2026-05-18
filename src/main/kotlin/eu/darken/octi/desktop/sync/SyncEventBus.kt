package eu.darken.octi.desktop.sync

import eu.darken.octi.desktop.protocol.octiserver.ws.EventPayload
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Process-wide bus for inbound sync events. The [OctiServerWebSocketClient] emits decoded
 * [EventPayload.Event]s here after self-suppression; repos subscribe to invalidate their
 * caches and trigger targeted refreshes.
 *
 * `replay = 0`, `extraBufferCapacity = 32` — we don't replay history to late subscribers, but
 * we don't want to drop events if a slow consumer falls a frame or two behind.
 *
 * [lastEventAt] is the timestamp of the most recent successful emit, used by the debug RPC
 * `/dev/state` endpoint as a coarse "are events still flowing?" signal. Null until the first
 * event arrives.
 */
class SyncEventBus {

    private val _events = MutableSharedFlow<EventPayload.Event>(replay = 0, extraBufferCapacity = 32)
    val events: SharedFlow<EventPayload.Event> = _events.asSharedFlow()

    private val _lastEventAt = MutableStateFlow<Instant?>(null)
    val lastEventAt: StateFlow<Instant?> = _lastEventAt.asStateFlow()

    suspend fun emit(event: EventPayload.Event) {
        _events.emit(event)
        _lastEventAt.value = Clock.System.now()
    }
}
