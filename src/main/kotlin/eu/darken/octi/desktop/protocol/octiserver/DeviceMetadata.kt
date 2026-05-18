package eu.darken.octi.desktop.protocol.octiserver

/**
 * Identification headers sent on every authenticated REST request and on the WebSocket upgrade.
 * The server records these on auth/touch — without them, peer cards show poor labels/platforms.
 *
 * @property version Desktop build version string, e.g. `"0.1.0-dev"`. Independent release train
 *   from Android — post-octi#308, Android's version gates (outdated / GCM-SIV-incompatible)
 *   apply only when [platform] equals `"android"`, so the desktop string is free-form.
 * @property platform Wire string identifying the OS family — `"desktop-linux"`, `"desktop-macos"`, `"desktop-windows"`.
 *   The `desktop-*` prefix is load-bearing: it's what disengages Android's version-comparison gates.
 * @property label Human-readable label, e.g. the hostname or a user-chosen name from Settings.
 * @property capabilities Encoded JSON-array value for `Octi-Device-Capabilities` (octi#309 +
 *   octi-server#23). The codec produces a canonical sorted form. Null/blank means "don't send
 *   the header" — the server will preserve whatever it has on file for this device.
 */
data class DeviceMetadata(
    val version: String,
    val platform: String,
    val label: String,
    val capabilities: String? = null,
) {
    companion object {
        const val HEADER_VERSION = "Octi-Device-Version"
        const val HEADER_PLATFORM = "Octi-Device-Platform"
        const val HEADER_LABEL = "Octi-Device-Label"
        const val HEADER_CAPABILITIES = "Octi-Device-Capabilities"
        const val HEADER_DEVICE_ID = "X-Device-ID"
    }
}
