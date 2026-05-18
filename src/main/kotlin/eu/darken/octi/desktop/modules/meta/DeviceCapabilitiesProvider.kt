package eu.darken.octi.desktop.modules.meta

import eu.darken.octi.desktop.protocol.encryption.EncryptionMode
import eu.darken.octi.desktop.protocol.sync.Capability

/**
 * Computes the local desktop's capability tag set, sent as the `Octi-Device-Capabilities` HTTP
 * header on every authenticated request and on the WebSocket upgrade.
 *
 * Port of `app-main`'s `DeviceCapabilitiesProvider`. The encryption tags advertise our ability
 * to decrypt **module documents** in each mode — both modes are supported by [PayloadEncryption]
 * on this side (Conscrypt provides GCM-SIV, BouncyCastle is the fallback).
 *
 * **Not** advertised: blob streaming AEAD capability (`StreamingPayloadCipher` is GCM-SIV-only
 * by Tink design — same as Android). PR #309's tag schema doesn't yet split modules vs blobs,
 * so we mirror Android's tag set exactly. The "no streaming for legacy accounts" limitation is
 * surfaced separately in the Files screen, not via capability tags.
 *
 * Future namespaces: extend the buildSet block — add `<X>:_reported` plus value tags for the
 * features this device actually supports. See [Capability] for the authority semantics.
 */
object DeviceCapabilitiesProvider {

    fun current(): Set<String> = buildSet {
        // Encryption namespace — both module-document modes are supported.
        add(Capability.ENCRYPTION_NAMESPACE_REPORTED)
        add(Capability.encryption(EncryptionMode.AES256_SIV))
        add(Capability.encryption(EncryptionMode.AES256_GCM_SIV))
        // Future namespaces: add NAMESPACE_REPORTED + value tags here.
    }
}
