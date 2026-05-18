package eu.darken.octi.desktop.modules.meta

import eu.darken.octi.desktop.BuildConfig
import eu.darken.octi.desktop.platform.PlatformDetector
import eu.darken.octi.desktop.protocol.octiserver.DeviceMetadata
import eu.darken.octi.desktop.protocol.sync.CapabilitiesCodec
import java.net.InetAddress

/**
 * Produces the [DeviceMetadata] sent on every authenticated request. Pulls the user-overridable
 * `deviceLabel` from settings (falling back to system hostname), the build version from a
 * compile-time constant, the platform string from [PlatformDetector], and the capability tag
 * set from [DeviceCapabilitiesProvider] (encoded via [CapabilitiesCodec] into the canonical
 * sorted JSON-array form the server expects).
 */
object DeviceMetadataProvider {

    private val capabilitiesCodec = CapabilitiesCodec()

    fun current(userLabel: String?, appVersion: String = APP_VERSION): DeviceMetadata = DeviceMetadata(
        version = appVersion,
        platform = platformString(),
        label = userLabel?.takeIf { it.isNotBlank() } ?: hostnameOrUnknown(),
        capabilities = capabilitiesCodec.encodeToHeader(DeviceCapabilitiesProvider.current()),
    )

    private fun platformString(): String = when (PlatformDetector.current) {
        PlatformDetector.Os.LINUX -> "desktop-linux"
        PlatformDetector.Os.MACOS -> "desktop-macos"
        PlatformDetector.Os.WINDOWS -> "desktop-windows"
        PlatformDetector.Os.UNKNOWN -> "desktop-unknown"
    }

    private fun hostnameOrUnknown(): String = try {
        InetAddress.getLocalHost().hostName.takeIf { it.isNotBlank() } ?: "octi-desktop"
    } catch (_: Exception) {
        "octi-desktop"
    }

    // Reads from the generated [BuildConfig.VERSION] — single source of truth is
    // gradle.properties `version=` bumped by release-prepare.yml.
    //
    // Independent release train from Android. Post-octi#308, Android scopes its version gates
    // (MIN_COMPATIBLE_VERSION, MIN_GCM_SIV_CLIENT_VERSION) to `platform == "android"`, so this
    // string no longer has to satisfy those Android-specific thresholds — `platformString()`
    // emits `desktop-*` which disengages the gates for us.
    val APP_VERSION: String = BuildConfig.VERSION
}
