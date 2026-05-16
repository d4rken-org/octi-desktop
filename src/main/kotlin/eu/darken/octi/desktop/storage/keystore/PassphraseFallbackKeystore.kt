package eu.darken.octi.desktop.storage.keystore

import de.mkammerer.argon2.Argon2Factory
import eu.darken.octi.desktop.common.files.AtomicWrites
import eu.darken.octi.desktop.platform.PlatformDetector
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AEAD-encrypted file-backed keystore for hosts where no OS keystore is usable (headless Linux
 * without D-Bus, sandboxed Mac CI, etc.). The user supplies a passphrase at app start; we derive
 * a 256-bit key with Argon2id and use AES-256-GCM for each stored value.
 *
 * Each stored item is a self-contained envelope (its own salt + nonce). Argon2 cost parameters
 * are saved in the envelope so they can be tuned in future releases without breaking older items.
 *
 * Security note: protection is only as strong as the passphrase. We surface that explicitly in
 * Settings UI. See the plan's "Risks" section.
 */
internal class PassphraseFallbackKeystore(
    private val passphrase: CharArray,
    private val baseDir: Path = PlatformDetector.configDir().resolve("secrets-fallback"),
    private val json: Json = Json,
) : Keystore {

    override val backendDescription: String = "Encrypted file (passphrase-derived)"

    private val argon2 = Argon2Factory.createAdvanced(Argon2Factory.Argon2Types.ARGON2id)
    private val rng = SecureRandom()

    init {
        require(passphrase.isNotEmpty()) { "Passphrase must not be empty" }
        Files.createDirectories(baseDir)
    }

    override fun store(key: String, value: ByteArray) {
        val salt = ByteArray(SALT_BYTES).also { rng.nextBytes(it) }
        val nonce = ByteArray(NONCE_BYTES).also { rng.nextBytes(it) }
        val derived = deriveKey(salt, ARGON2_ITERATIONS, ARGON2_MEMORY_KB, ARGON2_PARALLELISM)
        try {
            val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                doFinal(value)
            }
            val envelope = Envelope(
                v = ENVELOPE_VERSION,
                salt = java.util.Base64.getEncoder().encodeToString(salt),
                nonce = java.util.Base64.getEncoder().encodeToString(nonce),
                ciphertext = java.util.Base64.getEncoder().encodeToString(ciphertext),
                iterations = ARGON2_ITERATIONS,
                memoryKb = ARGON2_MEMORY_KB,
                parallelism = ARGON2_PARALLELISM,
            )
            val bytes = json.encodeToString(Envelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
            AtomicWrites.writeBytes(filePath(key), bytes)
        } finally {
            derived.fill(0)
        }
    }

    override fun load(key: String): ByteArray? {
        val path = filePath(key)
        if (!Files.exists(path)) return null
        val envelope = json.decodeFromString(
            Envelope.serializer(),
            Files.readAllBytes(path).toString(Charsets.UTF_8),
        )
        require(envelope.v == ENVELOPE_VERSION) { "Unsupported envelope version: ${envelope.v}" }
        val salt = java.util.Base64.getDecoder().decode(envelope.salt)
        val nonce = java.util.Base64.getDecoder().decode(envelope.nonce)
        val ciphertext = java.util.Base64.getDecoder().decode(envelope.ciphertext)
        val derived = deriveKey(salt, envelope.iterations, envelope.memoryKb, envelope.parallelism)
        try {
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                doFinal(ciphertext)
            }
        } catch (e: javax.crypto.AEADBadTagException) {
            throw KeystoreUnavailableException("Passphrase incorrect or data tampered", e)
        } finally {
            derived.fill(0)
        }
    }

    override fun delete(key: String) {
        Files.deleteIfExists(filePath(key))
    }

    private fun deriveKey(salt: ByteArray, iterations: Int, memoryKb: Int, parallelism: Int): ByteArray {
        // argon2-jvm 2.11 rawHash(CharArray, ByteArray) → 32-byte output (DEFAULT_HASH_LENGTH).
        // Exactly the size we need for an AES-256 SecretKeySpec.
        return argon2.rawHash(iterations, memoryKb, parallelism, passphrase, salt)
    }

    private fun filePath(key: String): Path {
        val safe = key.map { c -> if (c.isLetterOrDigit() || c in "._-") c else '_' }.joinToString("")
        return baseDir.resolve("$safe.enc.json")
    }

    @Serializable
    private data class Envelope(
        val v: Int,
        val salt: String,
        val nonce: String,
        val ciphertext: String,
        val iterations: Int,
        val memoryKb: Int,
        val parallelism: Int,
    )

    companion object {
        private const val ENVELOPE_VERSION = 1
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128

        // Tuned for ~250-400ms on a modern laptop. Adjust based on real-world measurements.
        private const val ARGON2_ITERATIONS = 3
        private const val ARGON2_MEMORY_KB = 64 * 1024
        private const val ARGON2_PARALLELISM = 1
    }
}
