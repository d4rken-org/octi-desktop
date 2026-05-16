package eu.darken.octi.desktop.platform

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Identifies the host operating system and resolves canonical config / data directories per
 * platform conventions.
 */
object PlatformDetector {

    enum class Os { LINUX, MACOS, WINDOWS, UNKNOWN }

    val current: Os by lazy {
        val name = System.getProperty("os.name", "").lowercase()
        when {
            name.startsWith("mac") || name.contains("darwin") -> Os.MACOS
            name.startsWith("win") -> Os.WINDOWS
            name.contains("linux") || name.contains("nux") -> Os.LINUX
            else -> Os.UNKNOWN
        }
    }

    /**
     * Returns the canonical config directory for the app. Persistent user-editable settings live
     * here. Per platform conventions:
     * - Linux: `$XDG_CONFIG_HOME/octi` (defaults to `~/.config/octi`)
     * - macOS: `~/Library/Application Support/octi`
     * - Windows: `%APPDATA%\octi`
     */
    fun configDir(appName: String = "octi"): Path = when (current) {
        Os.LINUX -> {
            val xdg = System.getenv("XDG_CONFIG_HOME")?.takeUnless { it.isBlank() }
            if (xdg != null) Paths.get(xdg, appName)
            else Paths.get(System.getProperty("user.home"), ".config", appName)
        }
        Os.MACOS -> Paths.get(System.getProperty("user.home"), "Library", "Application Support", appName)
        Os.WINDOWS -> {
            val appData = System.getenv("APPDATA")?.takeUnless { it.isBlank() }
                ?: Paths.get(System.getProperty("user.home"), "AppData", "Roaming").toString()
            Paths.get(appData, appName)
        }
        Os.UNKNOWN -> Paths.get(System.getProperty("user.home"), ".$appName")
    }

    /**
     * Persistent app-managed data (caches, downloaded blobs, sync state).
     * - Linux: `$XDG_DATA_HOME/octi`
     * - macOS: `~/Library/Application Support/octi/data`
     * - Windows: `%LOCALAPPDATA%\octi\data`
     */
    fun dataDir(appName: String = "octi"): Path = when (current) {
        Os.LINUX -> {
            val xdg = System.getenv("XDG_DATA_HOME")?.takeUnless { it.isBlank() }
            if (xdg != null) Paths.get(xdg, appName)
            else Paths.get(System.getProperty("user.home"), ".local", "share", appName)
        }
        Os.MACOS -> Paths.get(System.getProperty("user.home"), "Library", "Application Support", appName, "data")
        Os.WINDOWS -> {
            val local = System.getenv("LOCALAPPDATA")?.takeUnless { it.isBlank() }
                ?: Paths.get(System.getProperty("user.home"), "AppData", "Local").toString()
            Paths.get(local, appName, "data")
        }
        Os.UNKNOWN -> Paths.get(System.getProperty("user.home"), ".$appName", "data")
    }
}
