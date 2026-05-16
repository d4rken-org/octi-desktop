package eu.darken.octi.desktop.storage.keystore

/**
 * Cross-platform protected secret store. Implementations:
 * - macOS Keychain via the `security` CLI
 * - Linux libsecret via the `secret-tool` CLI
 * - Windows DPAPI via JNA Crypt32 (ciphertext kept in a file under configDir)
 * - Passphrase fallback (Argon2id-derived AEAD, prompts on app start)
 *
 * Keys are app-local strings (e.g. "octiserver.credentials.<accountId>"). Values are opaque byte
 * arrays — callers handle their own serialization.
 *
 * All methods are blocking. Use from a coroutine on Dispatchers.IO.
 */
interface Keystore {

    fun store(key: String, value: ByteArray)

    fun load(key: String): ByteArray?

    fun delete(key: String)

    /**
     * Identifier shown to the user in Settings ("Stored in macOS Keychain", etc.). Useful when
     * communicating the active storage backend, particularly when fallback was used.
     */
    val backendDescription: String
}

class KeystoreUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
