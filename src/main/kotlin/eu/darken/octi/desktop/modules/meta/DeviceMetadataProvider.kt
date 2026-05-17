package eu.darken.octi.desktop.modules.meta

import eu.darken.octi.desktop.platform.PlatformDetector
import eu.darken.octi.desktop.protocol.octiserver.DeviceMetadata
import java.net.InetAddress

/**
 * Produces the [DeviceMetadata] sent on every authenticated request. Pulls the user-overridable
 * `deviceLabel` from settings (falling back to system hostname), the build version from a
 * compile-time constant, and the platform string from [PlatformDetector].
 */
object DeviceMetadataProvider {

    fun current(userLabel: String?, appVersion: String = APP_VERSION): DeviceMetadata = DeviceMetadata(
        version = appVersion,
        platform = platformString(),
        label = userLabel?.takeIf { it.isNotBlank() } ?: hostnameOrUnknown(),
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

    // Updated by jpackage-driven release tooling; kept as a compile-time constant rather than
    // a Gradle-generated buildinfo file for now (the app-main side has a Versions object; we
    // can wire a similar generator when packaging gets wired in Phase H).
    //
    // Must parse to >= "1.0.0" for Android peers: VersionCompat strips the suffix and uses the
    // numeric prefix for two gates — MIN_COMPATIBLE_VERSION (0.14.0) for "outdated" badges, and
    // OctiServerEncryptionCompat.MIN_GCM_SIV_CLIENT_VERSION (1.0.0) for the GCM-SIV-capability
    // gate. Anything below 1.0.0 makes Android flag this peer as incompatible.
    const val APP_VERSION = "1.0.0-dev"
}
