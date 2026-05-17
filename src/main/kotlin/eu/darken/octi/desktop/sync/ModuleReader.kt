package eu.darken.octi.desktop.sync

import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.module.ModuleId
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.DeviceId
import kotlinx.serialization.KSerializer
import okio.ByteString.Companion.toByteString

private val TAG = logTag("Sync", "ModuleReader")

/**
 * Single-shot reader for a (peer, module) pair. Fetches the encrypted payload from
 * `GET /v1/module/{moduleId}?device-id={target}`, decrypts with the active account's keyset,
 * and deserializes via the provided [KSerializer].
 *
 * Lives outside `OctiServerHttpClient` so the encryption + serialization concern is reusable
 * across the polling repos for each module type (D5 / F / G phases).
 */
class ModuleReader(private val graph: AppGraph) {

    /** Decoded result wrapper so the UI can route NotFound / Error / Ok without try/catch. */
    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data object NotFound : Result<Nothing>()
        data class Error(val cause: Throwable) : Result<Nothing>()
    }

    suspend fun <T> read(
        moduleId: ModuleId,
        targetDeviceId: DeviceId,
        serializer: KSerializer<T>,
    ): Result<T> {
        val client = graph.activeClient.value
            ?: return Result.Error(IllegalStateException("No active client (not linked)"))
        val credentials = graph.credentialsStore.load()
            ?: return Result.Error(IllegalStateException("No credentials stored"))

        return try {
            when (val response = client.readModule(moduleId, targetDeviceId)) {
                OctiServerHttpClient.ModuleReadResult.NotFound -> Result.NotFound
                is OctiServerHttpClient.ModuleReadResult.Ok -> {
                    val crypto = PayloadEncryption(keySet = credentials.encryptionKeyset)
                    val plaintext = crypto.decrypt(response.payload.toByteString())
                    val value: T = Serialization.json.decodeFromString(serializer, plaintext.utf8())
                    Result.Ok(value)
                }
            }
        } catch (e: Throwable) {
            log(TAG, Logging.Priority.WARN, e) {
                "Read failed for module=${moduleId.logLabel} peer=${targetDeviceId.logLabel}"
            }
            Result.Error(e)
        }
    }
}

