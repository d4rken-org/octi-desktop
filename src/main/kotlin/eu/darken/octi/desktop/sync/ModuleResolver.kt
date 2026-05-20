package eu.darken.octi.desktop.sync

import eu.darken.octi.desktop.common.log.Logging.Priority.DEBUG
import eu.darken.octi.desktop.common.log.Logging.Priority.WARN
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.module.ModuleId
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
import eu.darken.octi.desktop.protocol.octiserver.ws.EventPayload
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val TAG = logTag("Sync", "ModuleResolver")

/**
 * Per-(deviceId × moduleId) freshness routing across the connectors that report a peer.
 *
 * Mirrors Android's `latestData()` semantics: when the same peer is visible through multiple
 * connectors, the module payload with the newest `modifiedAt` wins. Different modules on the
 * same peer can resolve to different connectors — a device's meta might land freshest on
 * connector A while its clipboard comes from B.
 *
 * **Probe-and-cache.** A cold read fans out a GET to every candidate connector that reports the
 * peer (typically [MergedDevice.sources]). The winning [ConnectorId] is cached against the
 * `(DeviceId, ModuleId)` key with a short TTL. Subsequent reads on the same key skip the fan-out
 * and go straight to the cached connector. Cache entries invalidate on:
 *  - TTL expiry ([CACHE_TTL]),
 *  - inbound [SyncEvent] for the same `(deviceId, moduleId)` — a peer just wrote a module so
 *    the previous winner may no longer be freshest,
 *  - the cached source leaving [AppGraph.activeConnectors] (unlink or relink-with-new-id).
 *
 * **Tie-break.** Equal `modifiedAt` across candidates → pick the lex-smallest `idString`. Equal
 * `null` modifiedAt → same rule, so the choice is still deterministic.
 *
 * **Failure ladder.**
 *  - empty candidates → `Error` (caller didn't supply any sources to probe),
 *  - any candidate not in `activeConnectors` → silently skipped,
 *  - all probes return `NotFound` → `NotFound`,
 *  - some `Ok`, some failed → use the `Ok` ones, ignore failures (best-effort),
 *  - all probes throw → propagate the first error.
 *
 * The cache only stores the routing decision, not the payload — every read still pulls fresh
 * bytes from the winner. The win on hot reads is "no fan-out", not "no upstream call". This
 * keeps reads fresh enough for clipboard / meta sync without growing memory with payload
 * bodies (some modules are blob-shaped large).
 */
class ModuleResolver(
    private val activeConnectors: StateFlow<List<OctiServerConnector>>,
    syncEvents: Flow<SyncEvent>,
    scope: CoroutineScope,
    private val clock: () -> Instant = { Clock.System.now() },
) {

    /** Convenience: wire from an AppGraph. */
    constructor(graph: AppGraph) : this(
        activeConnectors = graph.activeConnectors,
        syncEvents = graph.syncEventBus.events,
        scope = graph.appScope,
    )


    sealed class Result {
        /** Decrypted-payload-bytes are NOT here — callers decrypt with the winner's keyset. */
        data class Ok(
            val source: ConnectorId,
            val payload: ByteArray,
            val etag: String?,
            val modifiedAt: Instant?,
        ) : Result() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Ok) return false
                return source == other.source &&
                    payload.contentEquals(other.payload) &&
                    etag == other.etag &&
                    modifiedAt == other.modifiedAt
            }

            override fun hashCode(): Int {
                var r = source.hashCode()
                r = 31 * r + payload.contentHashCode()
                r = 31 * r + (etag?.hashCode() ?: 0)
                r = 31 * r + (modifiedAt?.hashCode() ?: 0)
                return r
            }
        }

        data object NotFound : Result()
        data class Error(val cause: Throwable) : Result()
    }

    private data class CacheKey(val deviceId: DeviceId, val moduleId: ModuleId)
    private data class CacheEntry(val winner: ConnectorId, val expiresAt: Instant)

    private val cacheLock = Mutex()
    private val cache: MutableMap<CacheKey, CacheEntry> = mutableMapOf()

    init {
        // Cache invalidation triggers wire up at construction so callers never see a stale
        // routing decision past a known invalidation event.
        syncEvents
            .onEach { syncEvent ->
                val ev = syncEvent.event
                if (ev is EventPayload.Event.ModuleChanged) {
                    invalidate(DeviceId(ev.deviceId), ModuleId(ev.moduleId))
                }
            }
            .launchIn(scope)

        // Prune cache entries whose winning connector has gone away (unlink). Paused
        // connectors stay in activeConnectors so their cache entries survive a pause.
        activeConnectors
            .onEach { connectors ->
                val activeIds = connectors.map { it.identifier }.toSet()
                cacheLock.withLock {
                    cache.entries.removeAll { (_, entry) -> entry.winner !in activeIds }
                }
            }
            .launchIn(scope)
    }

    /**
     * Read the freshest module payload across [candidates]. Returns the source connector along
     * with the bytes so the caller can decrypt with the right keyset (each connector has its
     * own).
     */
    suspend fun read(
        deviceId: DeviceId,
        moduleId: ModuleId,
        candidates: Set<ConnectorId>,
    ): Result {
        if (candidates.isEmpty()) {
            return Result.Error(IllegalStateException("ModuleResolver.read called with no candidates"))
        }

        val key = CacheKey(deviceId, moduleId)
        val now = clock()

        // Cache hit: read straight from the cached winner. If the read fails or the winner has
        // disappeared from activeConnectors since the cache was written, fall through to a
        // fresh probe (with an evicted cache entry).
        val cached = cacheLock.withLock {
            val entry = cache[key] ?: return@withLock null
            if (entry.expiresAt <= now) {
                cache.remove(key)
                null
            } else entry
        }
        if (cached != null) {
            val connector = connectorById(cached.winner)
            if (connector != null) {
                val outcome = try {
                    connector.client.readModule(moduleId, deviceId)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    // Cached source threw — fall through to re-probe so a peer that's moved off
                    // the cached connector still resolves.
                    log(TAG, DEBUG, t) {
                        "Cached source ${cached.winner.logLabel} read threw for " +
                            "${moduleId.logLabel}/${deviceId.logLabel}; re-probing"
                    }
                    cacheLock.withLock { cache.remove(key) }
                    null
                }
                if (outcome is OctiServerHttpClient.ModuleReadResult.Ok) {
                    return Result.Ok(
                        source = cached.winner,
                        payload = outcome.payload,
                        etag = outcome.etag,
                        modifiedAt = outcome.modifiedAt,
                    )
                }
                if (outcome is OctiServerHttpClient.ModuleReadResult.NotFound) {
                    log(TAG, DEBUG) {
                        "Cached source ${cached.winner.logLabel} returned NotFound for " +
                            "${moduleId.logLabel}/${deviceId.logLabel}; re-probing"
                    }
                    cacheLock.withLock { cache.remove(key) }
                }
            } else {
                cacheLock.withLock { cache.remove(key) }
            }
        }

        // Cold path: probe every candidate that's still active. Skip candidates not in
        // activeConnectors (defensive against caller passing stale ids).
        val activeById = activeConnectors.value.associateBy { it.identifier }
        val probeTargets = candidates.mapNotNull { activeById[it] }
        if (probeTargets.isEmpty()) {
            return Result.Error(IllegalStateException("ModuleResolver.read: no candidates are in activeConnectors"))
        }

        val probes = coroutineScope {
            probeTargets.map { connector ->
                async {
                    val outcome = try {
                        kotlin.Result.success(connector.client.readModule(moduleId, deviceId))
                    } catch (cancel: CancellationException) {
                        // Rethrow so cancellation propagates up through coroutineScope rather
                        // than getting trapped in a failed-probe slot.
                        throw cancel
                    } catch (t: Throwable) {
                        kotlin.Result.failure(t)
                    }
                    Probe(connector.identifier, outcome)
                }
            }.awaitAll()
        }

        val successes = probes.mapNotNull { p ->
            val o = p.outcome.getOrNull()
            if (o is OctiServerHttpClient.ModuleReadResult.Ok) ProbeOk(p.source, o) else null
        }
        val notFoundCount = probes.count {
            it.outcome.getOrNull() is OctiServerHttpClient.ModuleReadResult.NotFound
        }
        val firstError = probes.firstNotNullOfOrNull { it.outcome.exceptionOrNull() }

        if (successes.isNotEmpty()) {
            // Newest modifiedAt wins; null-modifiedAt sorts last; deterministic tiebreak on
            // lex-smallest idString so two desktops resolving the same race pick the same source.
            val winner = successes.maxWithOrNull(
                compareBy<ProbeOk> { it.outcome.modifiedAt }
                    .thenByDescending { it.source.idString },
            )!!
            cacheLock.withLock {
                cache[key] = CacheEntry(winner.source, now + CACHE_TTL)
            }
            log(TAG, DEBUG) {
                "Resolved ${deviceId.logLabel}/${moduleId.logLabel} to ${winner.source.logLabel} " +
                    "(modifiedAt=${winner.outcome.modifiedAt})"
            }
            return Result.Ok(
                source = winner.source,
                payload = winner.outcome.payload,
                etag = winner.outcome.etag,
                modifiedAt = winner.outcome.modifiedAt,
            )
        }
        if (notFoundCount == probes.size) return Result.NotFound

        // Some failures, no successes — propagate the first error.
        val cause = firstError ?: IllegalStateException("No candidates produced a result")
        log(TAG, WARN, cause) {
            "All ${probes.size} probes failed for ${deviceId.logLabel}/${moduleId.logLabel}"
        }
        return Result.Error(cause)
    }

    /** Drop the cached source for a (deviceId, moduleId) key. Called on inbound SyncEvent. */
    suspend fun invalidate(deviceId: DeviceId, moduleId: ModuleId) {
        cacheLock.withLock {
            cache.remove(CacheKey(deviceId, moduleId))
        }
    }

    private fun connectorById(id: ConnectorId): OctiServerConnector? =
        activeConnectors.value.firstOrNull { it.identifier == id }

    private data class Probe(
        val source: ConnectorId,
        val outcome: kotlin.Result<OctiServerHttpClient.ModuleReadResult>,
    )

    private data class ProbeOk(
        val source: ConnectorId,
        val outcome: OctiServerHttpClient.ModuleReadResult.Ok,
    )

    companion object {
        // 30 seconds matches the eyeball reasoning in the multi-connector plan: long enough
        // that repeated dashboard reads coalesce, short enough that stale data self-heals fast
        // even if SyncEvent invalidation is missed. Inbound WS events still invalidate
        // immediately so this is a belt-and-suspenders cap, not the primary freshness signal.
        val CACHE_TTL: Duration = 30.seconds
    }
}
