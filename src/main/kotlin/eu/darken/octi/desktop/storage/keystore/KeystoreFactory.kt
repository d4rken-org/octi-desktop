package eu.darken.octi.desktop.storage.keystore

import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.platform.PlatformDetector

private val TAG = logTag("KeystoreFactory")

/**
 * Selects the best available keystore for the host. Tries OS keystore first; falls back to
 * passphrase-encrypted file if unavailable.
 *
 * Callers that need the passphrase fallback must supply the passphrase via [passphraseProvider].
 * It's invoked lazily — only when the OS keystore actually fails — so a typical macOS/Windows
 * user with a working keychain never sees a passphrase prompt.
 */
object KeystoreFactory {

    fun create(passphraseProvider: () -> CharArray): Keystore {
        val osBackend = tryOsKeystore()
        if (osBackend != null) {
            log(TAG, Logging.Priority.INFO) { "Using OS keystore: ${osBackend.backendDescription}" }
            return osBackend
        }
        log(TAG, Logging.Priority.WARN) { "OS keystore unavailable; prompting for passphrase fallback" }
        val passphrase = passphraseProvider()
        return PassphraseFallbackKeystore(passphrase = passphrase).also {
            log(TAG, Logging.Priority.INFO) { "Using fallback: ${it.backendDescription}" }
        }
    }

    private fun tryOsKeystore(): Keystore? = try {
        when (PlatformDetector.current) {
            PlatformDetector.Os.MACOS -> KeychainKeystore()
            PlatformDetector.Os.LINUX -> LibsecretKeystore()
            PlatformDetector.Os.WINDOWS -> DpapiKeystore()
            PlatformDetector.Os.UNKNOWN -> null
        }
    } catch (e: KeystoreUnavailableException) {
        log(TAG, Logging.Priority.WARN, e) { "OS keystore probe failed: ${e.message}" }
        null
    } catch (e: Exception) {
        log(TAG, Logging.Priority.ERROR, e) { "Unexpected OS keystore failure" }
        null
    }
}
