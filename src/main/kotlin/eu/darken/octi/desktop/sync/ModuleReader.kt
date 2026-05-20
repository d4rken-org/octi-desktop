package eu.darken.octi.desktop.sync

import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.collections.fromGzip
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.module.ModuleId
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import okio.ByteString.Companion.toByteString

private val TAG = logTag("Sync", "ModuleReader")

/**
 * Single-shot reader for a (peer, module) pair. Thin facade over [ModuleResolver]: figures out
 * which connectors are candidate sources for the peer, asks the resolver for the freshest
 * payload, then decrypts with the winning connector's keyset (each connector has its own).
 *
 * Candidate resolution:
 *  - **Self device** (`targetDeviceId == graph.deviceId`): every active connector is a
 *    candidate, since this desktop is registered with each. Once write fan-out lands in PR-4
 *    each connector will independently have our latest payload; until then only the primary
 *    has data and the others return NotFound (the resolver's failure ladder ignores those).
 *  - **Peer device**: look up the matching [MergedDevice] in [DeviceListRepo.mergedDevices]
 *    and use its [MergedDevice.sources]. Falls back to every active connector if the device
 *    list hasn't loaded yet (cold start) — same behaviour as the old single-connector
 *    `ModuleReader`, which probed primaryConnector regardless of merge state.
 *
 * The AAD shape (`${ownerDeviceId}:${moduleId}`) and the gzip-before-encrypt wire order match
 * Android — the resolver returns raw bytes and this class unwraps them.
 */
class ModuleReader(private val graph: AppGraph) {

    /** Decoded result wrapper so the UI can route NotFound / Error / Ok without try/catch. */
    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data object NotFound : Result<Nothing>()
        data class Error(val cause: Throwable) : Result<Nothing>()
    }

    /**
     * AAD used for module-payload encryption. Matches Android's `OctiServerConnector
     * .buildAssociatedData()` exactly — `${ownerDeviceId}:${moduleId}`. Tag verification fails
     * if either side uses a different shape, so this is a wire-stable contract.
     */
    fun buildAad(ownerDeviceId: DeviceId, moduleId: ModuleId): ByteArray =
        "${ownerDeviceId.id}:${moduleId.id}".toByteArray(Charsets.UTF_8)

    suspend fun <T> read(
        moduleId: ModuleId,
        targetDeviceId: DeviceId,
        serializer: KSerializer<T>,
    ): Result<T> {
        val candidates = resolveCandidates(targetDeviceId)
        if (candidates.isEmpty()) {
            return Result.Error(IllegalStateException("No active connector (not linked)"))
        }

        return try {
            when (val outcome = graph.moduleResolver.read(targetDeviceId, moduleId, candidates)) {
                ModuleResolver.Result.NotFound -> Result.NotFound
                is ModuleResolver.Result.Error -> Result.Error(outcome.cause)
                is ModuleResolver.Result.Ok -> {
                    if (outcome.payload.isEmpty()) {
                        // 204 No Content (empty body) on a slot the peer hasn't written yet —
                        // treat as NotFound rather than feeding Tink an empty buffer (which
                        // would fail "decryption failed" and obscure the real state).
                        return Result.NotFound
                    }
                    val sourceConnector = connectorById(outcome.source)
                        ?: return Result.Error(
                            IllegalStateException("Resolved source ${outcome.source.idString} not in activeConnectors"),
                        )
                    val crypto = PayloadEncryption(keySet = sourceConnector.credentials.encryptionKeyset)
                    val aad = buildAad(targetDeviceId, moduleId)
                    val decrypted = crypto.decrypt(outcome.payload.toByteString(), aad)
                    val plaintext = decrypted.fromGzip()
                    val value: T = Serialization.json.decodeFromString(serializer, plaintext.utf8())
                    Result.Ok(value)
                }
            }
        } catch (e: CancellationException) {
            // MUST rethrow before the generic Throwable branch — kotlinx.coroutines uses
            // CancellationException to tear down job hierarchies. Swallowing it here turns
            // structured cancellation into a stale Result.Error emission and breaks any caller
            // that relies on cancel-replace semantics.
            throw e
        } catch (e: Throwable) {
            log(TAG, Logging.Priority.WARN, e) {
                "Read failed for module=${moduleId.logLabel} peer=${targetDeviceId.logLabel}"
            }
            Result.Error(e)
        }
    }

    private fun resolveCandidates(targetDeviceId: DeviceId): Set<ConnectorId> {
        if (targetDeviceId == graph.deviceId) {
            // Self read — every active connector is a potential source. Order doesn't matter;
            // the resolver picks newest modifiedAt regardless.
            return graph.activeConnectors.value.map { it.identifier }.toSet()
        }
        val mergedSources = graph.deviceListRepo.mergedDevices.value
            .firstOrNull { it.device.id == targetDeviceId.id }
            ?.sources
        if (!mergedSources.isNullOrEmpty()) return mergedSources
        // Cold start: device list hasn't loaded yet but the caller (e.g. dashboard module repo
        // reacting to a SyncEvent) needs a read. Fall back to every active connector — same
        // behaviour as the old single-connector ModuleReader, which probed primaryConnector
        // regardless of merge state.
        return graph.activeConnectors.value.map { it.identifier }.toSet()
    }

    private fun connectorById(id: ConnectorId): OctiServerConnector? =
        graph.activeConnectors.value.firstOrNull { it.identifier == id }
}
