package eu.darken.octi.desktop.debug.rpc

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Registry of named verbs the debug RPC server can invoke.
 *
 * Lifetime: one instance per [eu.darken.octi.desktop.di.AppGraph]. Not a process-wide singleton —
 * Codex review surfaced that a global would leak state across tests and make multi-graph wiring
 * (e.g. integration tests that spin up the graph repeatedly) painful.
 *
 * Concurrency model:
 *
 * 1. The action map itself is a [ConcurrentHashMap] keyed on the action name.
 * 2. Each registration carries a unique token; [Registration.unregister] only removes the entry
 *    if its current token still matches. That prevents this race:
 *      - Screen A mounts, registers `linking.submit`.
 *      - Screen A unmounts, `DisposableEffect.onDispose` fires asynchronously.
 *      - Screen B (or A on hot-remount) mounts and registers a fresh `linking.submit`.
 *      - The pending unregister from screen A would otherwise clobber B's handler.
 * 3. Per-action [Mutex]es serialize concurrent invocations of the same action — two parallel
 *    `POST /dev/action/dashboard.refresh` requests don't race the underlying refresh logic.
 * 4. An overall [actionTimeout] caps any single invocation; on expiry the route returns 504.
 *
 * UI actions (anything that touches Compose state) should be registered via [registerUiAction],
 * which wraps the handler so it runs on [Dispatchers.Swing]. Background actions (file I/O, HTTP
 * to the local sync server etc.) use the plain [register].
 */
class DebugActionRegistry(
    private val actionTimeout: Duration = 30.seconds,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Swing,
) {

    /**
     * Metadata describing an action — surfaced verbatim by `GET /dev/actions` so the MCP shim
     * (or a curious developer with curl) can discover what's drivable without reading the source.
     *
     * `example` is rendered as a JSON snippet inline; keep it small enough to read at a glance.
     */
    data class Metadata(
        val name: String,
        val description: String,
        val params: Map<String, String> = emptyMap(),
        val example: String = "{}",
    )

    /** Handle returned by [register]. Holding screens call [unregister] in `onDispose`. */
    class Registration internal constructor(
        private val registry: DebugActionRegistry,
        internal val name: String,
        internal val token: String,
    ) {
        fun unregister() {
            registry.unregisterIfMatches(name, token)
        }
    }

    private data class Entry(
        val metadata: Metadata,
        val token: String,
        val mutex: Mutex,
        val handler: suspend (JsonObject) -> JsonElement,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Register a background action. Handler runs on the request's coroutine context — wrap with
     * [withContext] yourself if it should hop dispatchers.
     */
    fun register(
        metadata: Metadata,
        handler: suspend (JsonObject) -> JsonElement,
    ): Registration {
        val token = UUID.randomUUID().toString()
        val entry = Entry(
            metadata = metadata,
            token = token,
            mutex = Mutex(),
            handler = handler,
        )
        entries[metadata.name] = entry
        return Registration(registry = this, name = metadata.name, token = token)
    }

    /**
     * Register an action whose handler must run on the AWT EDT (Compose UI thread). The wrapper
     * forwards on [uiDispatcher]. The Mutex is still acquired on the caller's context to avoid
     * tying up the EDT during contention.
     */
    fun registerUiAction(
        metadata: Metadata,
        handler: suspend (JsonObject) -> JsonElement,
    ): Registration = register(metadata) { params ->
        withContext(uiDispatcher) { handler(params) }
    }

    /**
     * Invoke an action by name. Returns one of [InvokeResult] sealed cases; the HTTP route maps
     * these to status codes.
     */
    suspend fun invoke(name: String, params: JsonObject): InvokeResult {
        val entry = entries[name] ?: return InvokeResult.NotFound(name)
        return entry.mutex.withLock {
            try {
                val result = withTimeout(actionTimeout) { entry.handler(params) }
                InvokeResult.Ok(result)
            } catch (e: TimeoutCancellationException) {
                InvokeResult.Timeout(name, actionTimeout)
            } catch (e: Throwable) {
                InvokeResult.Failed(name, e)
            }
        }
    }

    /** Returns a snapshot of currently-registered action metadata. */
    fun list(): List<Metadata> = entries.values.map { it.metadata }.sortedBy { it.name }

    private fun unregisterIfMatches(name: String, expectedToken: String) {
        entries.compute(name) { _, current ->
            if (current?.token == expectedToken) null else current
        }
    }

    sealed class InvokeResult {
        data class Ok(val result: JsonElement) : InvokeResult()
        data class NotFound(val name: String) : InvokeResult()
        data class Failed(val name: String, val cause: Throwable) : InvokeResult()
        data class Timeout(val name: String, val after: Duration) : InvokeResult()
    }
}
