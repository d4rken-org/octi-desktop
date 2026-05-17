package eu.darken.octi.desktop.protocol.encryption

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Self-round-trip tests for [PayloadEncryption]. The desktop encrypts and decrypts using its
 * own Tink-Java install; failures here typically mean either Tink/BouncyCastle dependency drift
 * or a regression in [CryptoBootstrap]'s provider install.
 *
 * Cross-platform fixture tests (Android-encrypted → desktop-decrypted) are deliberately separate
 * — they need a fixture file checked in from the Android side and aren't useful until both
 * codebases pin a fixture format.
 */
class PayloadEncryptionTest {

    private val plaintext = "Octi 🐙 desktop wire-compat probe — 0123456789".encodeUtf8()
    private val aad = "device-abc:module-power:blob-xyz".toByteArray(Charsets.UTF_8)

    @Test
    @DisplayName("GCM-SIV: encrypt then decrypt with the same instance round-trips")
    fun gcmSivSameInstanceRoundTrip() {
        val crypto = PayloadEncryption()
        val ciphertext = crypto.encrypt(plaintext, aad)
        ciphertext shouldNotBe plaintext
        crypto.decrypt(ciphertext, aad) shouldBe plaintext
    }

    @Test
    @DisplayName("AES-SIV (legacy): encrypt then decrypt with the same instance round-trips")
    fun aesSivSameInstanceRoundTrip() {
        val crypto = PayloadEncryption(useLegacyEncryption = true)
        val ciphertext = crypto.encrypt(plaintext)
        ciphertext shouldNotBe plaintext
        crypto.decrypt(ciphertext) shouldBe plaintext
    }

    @Test
    @DisplayName("GCM-SIV: exported keyset bytes load back into a new instance and decrypt")
    fun gcmSivExportImportDecryptsExistingCiphertext() {
        val original = PayloadEncryption()
        val ciphertext = original.encrypt(plaintext, aad)
        val keyset = original.exportKeyset()
        keyset.type shouldBe EncryptionMode.AES256_GCM_SIV.typeString

        val reloaded = PayloadEncryption(keySet = keyset)
        reloaded.decrypt(ciphertext, aad) shouldBe plaintext
    }

    @Test
    @DisplayName("AES-SIV: exported keyset bytes load back into a new instance and decrypt")
    fun aesSivExportImportDecryptsExistingCiphertext() {
        val original = PayloadEncryption(useLegacyEncryption = true)
        val ciphertext = original.encrypt(plaintext)
        val keyset = original.exportKeyset()
        keyset.type shouldBe EncryptionMode.AES256_SIV.typeString

        val reloaded = PayloadEncryption(keySet = keyset)
        reloaded.decrypt(ciphertext) shouldBe plaintext
    }

    @Test
    @DisplayName("GCM-SIV: decrypting with a different AAD throws")
    fun gcmSivWrongAadFails() {
        val crypto = PayloadEncryption()
        val ciphertext = crypto.encrypt(plaintext, aad)
        val wrongAad = "device-zzz:module-power:blob-xyz".toByteArray(Charsets.UTF_8)
        shouldThrow<Exception> { crypto.decrypt(ciphertext, wrongAad) }
    }

    @Test
    @DisplayName("AES-SIV: passing a different AAD is silently ignored (legacy contract)")
    fun aesSivIgnoresAad() {
        // The Android impl explicitly documents that legacy SIV ignores AAD — passing a
        // non-empty AAD must NOT change ciphertext or decryption behavior. This pins that
        // contract on desktop so we don't accidentally regress to honoring AAD.
        val crypto = PayloadEncryption(useLegacyEncryption = true)
        val ct1 = crypto.encrypt(plaintext, aad)
        val ct2 = crypto.encrypt(plaintext, "totally-different-aad".toByteArray(Charsets.UTF_8))
        ct1 shouldBe ct2 // deterministic AEAD: same plaintext → same ciphertext regardless of AAD
        assertDoesNotThrow { crypto.decrypt(ct1, "yet-another-aad".toByteArray(Charsets.UTF_8)) shouldBe plaintext }
    }

    @Test
    @DisplayName("GCM-SIV: decrypting with a fresh keyset of the same mode fails")
    fun gcmSivKeysetIsolation() {
        val crypto1 = PayloadEncryption()
        val crypto2 = PayloadEncryption()
        val ciphertext = crypto1.encrypt(plaintext, aad)
        shouldThrow<Exception> { crypto2.decrypt(ciphertext, aad) }
    }

    @Test
    @DisplayName("AES-SIV vs GCM-SIV ciphertexts produced from the same logical plaintext are not interchangeable")
    fun cipherFamilyIsolation() {
        val gcm = PayloadEncryption()
        val siv = PayloadEncryption(useLegacyEncryption = true)
        val gcmCt = gcm.encrypt(plaintext, aad)
        val sivCt = siv.encrypt(plaintext)
        gcmCt shouldNotBe sivCt
        shouldThrow<Exception> { gcm.decrypt(sivCt, aad) }
        shouldThrow<Exception> { siv.decrypt(gcmCt) }
    }
}
