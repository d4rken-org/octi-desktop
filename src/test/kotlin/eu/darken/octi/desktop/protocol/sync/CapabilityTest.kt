package eu.darken.octi.desktop.protocol.sync

import eu.darken.octi.desktop.protocol.encryption.EncryptionMode
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CapabilityTest {

    @Test
    fun `encryption tag uses EncryptionMode typeString`() {
        Capability.encryption(EncryptionMode.AES256_GCM_SIV) shouldBe "encryption:AES256_GCM_SIV"
        Capability.encryption(EncryptionMode.AES256_SIV) shouldBe "encryption:AES256_SIV"
    }

    @Test
    fun `namespace marker is constant`() {
        Capability.ENCRYPTION_NAMESPACE_REPORTED shouldBe "encryption:_reported"
    }

    @Test
    fun `supportsEncryption returns null for null caps`() {
        Capability.supportsEncryption(null, EncryptionMode.AES256_GCM_SIV).shouldBeNull()
    }

    @Test
    fun `supportsEncryption returns null when namespace marker is absent`() {
        val caps = setOf("clipboard:rich")
        Capability.supportsEncryption(caps, EncryptionMode.AES256_GCM_SIV).shouldBeNull()
    }

    @Test
    fun `supportsEncryption returns null even with value tag if marker is absent`() {
        val caps = setOf("encryption:AES256_GCM_SIV")
        Capability.supportsEncryption(caps, EncryptionMode.AES256_GCM_SIV).shouldBeNull()
    }

    @Test
    fun `supportsEncryption returns true when marker and value tag both present`() {
        val caps = setOf(
            Capability.ENCRYPTION_NAMESPACE_REPORTED,
            Capability.encryption(EncryptionMode.AES256_GCM_SIV),
        )
        Capability.supportsEncryption(caps, EncryptionMode.AES256_GCM_SIV) shouldBe true
    }

    @Test
    fun `supportsEncryption returns false when marker present but value tag absent`() {
        val caps = setOf(
            Capability.ENCRYPTION_NAMESPACE_REPORTED,
            Capability.encryption(EncryptionMode.AES256_SIV),
        )
        Capability.supportsEncryption(caps, EncryptionMode.AES256_GCM_SIV) shouldBe false
    }

    @Test
    fun `supportsEncryption distinguishes per-mode`() {
        val caps = setOf(
            Capability.ENCRYPTION_NAMESPACE_REPORTED,
            Capability.encryption(EncryptionMode.AES256_SIV),
        )
        Capability.supportsEncryption(caps, EncryptionMode.AES256_SIV) shouldBe true
        Capability.supportsEncryption(caps, EncryptionMode.AES256_GCM_SIV) shouldBe false
    }
}
