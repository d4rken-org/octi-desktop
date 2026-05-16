package eu.darken.octi.desktop.linking

import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.storage.keystore.Keystore

/**
 * Thin facade over the [Keystore] for the single MVP credentials slot. Keys are JSON-encoded so
 * the on-disk format is debuggable (and so we don't burn binary-format complexity for a single
 * payload type).
 *
 * The MVP supports one active OctiServer account at a time. Multi-account would key by
 * `accountId` instead of the fixed [STORAGE_KEY]; that's a follow-up.
 */
class CredentialsStore(private val keystore: Keystore) {

    fun save(credentials: OctiServer.Credentials) {
        val json = Serialization.json.encodeToString(OctiServer.Credentials.serializer(), credentials)
        keystore.store(STORAGE_KEY, json.toByteArray(Charsets.UTF_8))
    }

    fun load(): OctiServer.Credentials? {
        val bytes = keystore.load(STORAGE_KEY) ?: return null
        return Serialization.json.decodeFromString(
            OctiServer.Credentials.serializer(),
            bytes.toString(Charsets.UTF_8),
        )
    }

    fun clear() {
        keystore.delete(STORAGE_KEY)
    }

    companion object {
        const val STORAGE_KEY = "octiserver.credentials.active"
    }
}
