package eu.darken.octi.desktop.protocol.sync

import eu.darken.octi.desktop.protocol.encryption.EncryptionMode

/**
 * Central registry of capability tags for [DeviceMetadata.capabilities] on the wire.
 *
 * Port of `app-main`'s `sync-core/Capability.kt` — must stay byte-stable so Android peers and
 * the server interpret our tags identically. Authority rules (matching upstream):
 *
 * - `caps == null` → peer reports no capabilities at all → unknown
 * - non-null, `X:_reported` absent → namespace X unknown for this peer
 * - non-null, `X:_reported` present, value tag absent → known unsupported
 * - non-null, `X:_reported` present, value tag present → known supported
 *
 * Tag format: `<namespace>:<value>`, regex enforced by [CapabilitiesCodec.TAG_REGEX].
 *
 * To add a new namespace:
 *   1. Add a `<X>_NAMESPACE_REPORTED` constant.
 *   2. Add tag-construction helpers (`<x>(value)`).
 *   3. Add a `supports<X>(caps, value): Boolean?` semantic helper that handles authority.
 *   4. Update [eu.darken.octi.desktop.modules.meta.DeviceCapabilitiesProvider] to publish the
 *      marker and the value tags this device actually supports.
 */
object Capability {

    // --- encryption namespace ---

    const val ENCRYPTION_NAMESPACE_REPORTED = "encryption:_reported"

    fun encryption(mode: EncryptionMode): String = "encryption:${mode.typeString}"

    /**
     * Tri-state encryption-mode check:
     *   - null  → caps null, OR peer doesn't participate in the encryption namespace
     *   - true  → peer participates and reports support for [mode]
     *   - false → peer participates and explicitly doesn't support [mode]
     */
    fun supportsEncryption(caps: Set<String>?, mode: EncryptionMode): Boolean? {
        if (caps == null) return null
        if (ENCRYPTION_NAMESPACE_REPORTED !in caps) return null
        return encryption(mode) in caps
    }
}
