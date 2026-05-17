package eu.darken.octi.desktop.linking

import eu.darken.octi.desktop.common.log.Logging.Priority.ERROR
import eu.darken.octi.desktop.common.log.Logging.Priority.INFO
import eu.darken.octi.desktop.common.log.Logging.Priority.WARN
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.octiserver.DeviceMetadata
import eu.darken.octi.desktop.protocol.octiserver.LinkingData
import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpException
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.DeviceId
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerializationException

private val TAG = logTag("Link", "Controller")

/**
 * Drives the staged link flow from a pasted share-code string to persisted credentials.
 *
 * The contract (per plan + Codex review #9): every local validation runs **before** the server
 * consume call. If anything past consume fails (keystore write, in particular), we attempt an
 * immediate rollback via `DELETE /v1/devices/{self}` while the new credentials are still in
 * memory. The result discriminates between rolled-back and orphaned-on-server outcomes so the
 * UI can route the user to recovery.
 */
class LinkController(
    private val deviceMetadata: DeviceMetadata,
    private val credentialsStore: CredentialsStore,
    private val httpClientFactory: HttpClientFactory = DefaultHttpClientFactory,
) {

    /**
     * Validate the supplied [encoded] share-code string and, if everything passes, register
     * this device against the existing account on the server. Caller supplies a fresh local
     * [deviceId].
     */
    suspend fun link(encoded: String, deviceId: DeviceId): LinkResult {
        // Stage 1: decode + parse + validate. Pure-local — never touches the network.
        val linkingData = when (val decoded = decode(encoded)) {
            is DecodeOutcome.Ok -> decoded.value
            is DecodeOutcome.Fail -> return decoded.result
        }

        // Stage 2: probe the keyset. If Tink can't parse it, the share code is unusable. We
        // fail here rather than after consuming the share code.
        val keysetCheck = runCatching { PayloadEncryption(keySet = linkingData.encryptionKeyset).exportKeyset() }
        keysetCheck.exceptionOrNull()?.let { return LinkResult.InvalidKeyset(it) }

        // Stage 3: server consume. Beyond this point, server state has changed.
        val (newCredentials, http) = runCatching {
            val client = httpClientFactory.create(
                address = linkingData.serverAdress,
                deviceId = deviceId,
                deviceMetadata = deviceMetadata,
                credentials = null,
            )
            val response = client.register(shareCode = linkingData.linkCode.code)
            val credentials = OctiServer.Credentials(
                serverAdress = linkingData.serverAdress,
                accountId = OctiServer.Credentials.AccountId(response.accountID),
                devicePassword = OctiServer.Credentials.DevicePassword(response.password),
                encryptionKeyset = linkingData.encryptionKeyset,
            )
            credentials to client
        }.getOrElse { cause ->
            return when {
                cause is OctiServerHttpException && cause.status == HttpStatusCode.NotFound ->
                    LinkResult.ShareCodeExpiredOrConsumed
                cause is OctiServerHttpException && cause.status == HttpStatusCode.Unauthorized ->
                    LinkResult.ShareCodeExpiredOrConsumed
                else -> LinkResult.NetworkError(cause)
            }
        }

        // Stage 4: persist locally. If this throws, immediately roll back on the server.
        try {
            credentialsStore.save(newCredentials)
            log(TAG, INFO) { "Link succeeded for account=${newCredentials.accountId.id.take(8)}..." }
            http.close()
            return LinkResult.Success
        } catch (keystoreCause: Throwable) {
            log(TAG, ERROR, keystoreCause) { "Keystore write failed; rolling back server registration" }
            // Build an authenticated client with the just-issued credentials so DELETE works.
            val authedClient = httpClientFactory.create(
                address = linkingData.serverAdress,
                deviceId = deviceId,
                deviceMetadata = deviceMetadata,
                credentials = newCredentials,
            )
            try {
                authedClient.deleteDevice(deviceId)
                log(TAG, WARN) { "Rollback DELETE succeeded; device unregistered" }
                http.close()
                authedClient.close()
                return LinkResult.KeystoreFailureRolledBack(keystoreCause)
            } catch (rollbackCause: Throwable) {
                log(TAG, ERROR, rollbackCause) { "Rollback DELETE failed; device is orphaned on server" }
                http.close()
                authedClient.close()
                return LinkResult.OrphanedDevice(keystoreCause, rollbackCause)
            }
        }
    }

    private sealed class DecodeOutcome {
        data class Ok(val value: LinkingData) : DecodeOutcome()
        data class Fail(val result: LinkResult) : DecodeOutcome()
    }

    private fun decode(encoded: String): DecodeOutcome {
        if (encoded.isBlank()) return DecodeOutcome.Fail(LinkResult.InvalidBase64)
        return try {
            DecodeOutcome.Ok(LinkingData.fromEncodedString(Serialization.json, encoded.trim()))
        } catch (e: SerializationException) {
            // MUST precede the IllegalArgumentException catch — SerializationException extends
            // IllegalArgumentException in kotlinx-serialization, so reversing the order would
            // mis-route every JSON-shape error as InvalidBase64.
            DecodeOutcome.Fail(LinkResult.InvalidJson(e))
        } catch (e: IllegalArgumentException) {
            // LinkingData.fromEncodedString throws this for "not valid base64".
            DecodeOutcome.Fail(LinkResult.InvalidBase64)
        } catch (e: java.io.IOException) {
            // Okio's gzip path throws ProtocolException / IOException for bad gzip frames.
            DecodeOutcome.Fail(LinkResult.InvalidGzip)
        } catch (e: Exception) {
            // Catch-all for unexpected decoder errors; surface as JSON shape mismatch since
            // base64/gzip stages already handled their specific exceptions.
            DecodeOutcome.Fail(LinkResult.InvalidJson(e))
        }
    }

    /** Pluggable so tests can swap in a fake. */
    fun interface HttpClientFactory {
        fun create(
            address: OctiServer.Address,
            deviceId: DeviceId,
            deviceMetadata: DeviceMetadata,
            credentials: OctiServer.Credentials?,
        ): OctiServerHttpClient
    }

    object DefaultHttpClientFactory : HttpClientFactory {
        override fun create(
            address: OctiServer.Address,
            deviceId: DeviceId,
            deviceMetadata: DeviceMetadata,
            credentials: OctiServer.Credentials?,
        ): OctiServerHttpClient = OctiServerHttpClient(
            address = address,
            deviceId = deviceId,
            deviceMetadata = deviceMetadata,
            credentials = credentials,
        )
    }
}
