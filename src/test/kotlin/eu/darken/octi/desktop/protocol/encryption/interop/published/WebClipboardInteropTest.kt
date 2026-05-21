package eu.darken.octi.desktop.protocol.encryption.interop.published

import eu.darken.octi.desktop.protocol.encryption.interop.InteropFixtureSync
import eu.darken.octi.desktop.protocol.encryption.interop.InteropFixtures
import eu.darken.octi.desktop.protocol.encryption.interop.PublishedModuleFixture
import eu.darken.octi.desktop.protocol.encryption.interop.PublishedVector
import eu.darken.octi.desktop.protocol.modules.clipboard.ClipboardInfo
import eu.darken.octi.desktop.protocol.serialization.Serialization
import io.kotest.matchers.shouldBe
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verify desktop's ClipboardInfo decoder consumes what octi-web publishes.
 * Pin: type enum + base64-encoded data. ByteString equality must hold across the
 * encode boundary (web emits base64, desktop's ByteStringSerializer reads base64).
 */
class WebClipboardInteropTest {

    companion object {
        private lateinit var cacheDir: Path

        @JvmStatic
        @BeforeAll
        fun setUp() {
            cacheDir = InteropFixtureSync.ensureSynced("d4rken-org/octi-web")
        }
    }

    @Test
    fun `web clipboard fixture schema sanity`() {
        val fixture = loadFixture()
        fixture.schemaVersion shouldBe InteropFixtures.SCHEMA_VERSION
        fixture.module shouldBe "eu.darken.octi.module.core.clipboard"
        fixture.producer shouldBe "d4rken-org/octi-web"
        fixture.vectors.map { it.name } shouldBe
            listOf("EMPTY", "SIMPLE_TEXT_short", "SIMPLE_TEXT_unicode")
    }

    @Test
    fun `web clipboard 'EMPTY' vector decodes to empty data`() {
        val info = decode(vector("EMPTY"))
        info.type shouldBe ClipboardInfo.Type.EMPTY
        info.data.size shouldBe 0
    }

    @Test
    fun `web clipboard 'SIMPLE_TEXT_short' vector decodes ASCII payload`() {
        val info = decode(vector("SIMPLE_TEXT_short"))
        info.type shouldBe ClipboardInfo.Type.SIMPLE_TEXT
        info.data shouldBe "hello clipboard".encodeUtf8()
    }

    @Test
    fun `web clipboard 'SIMPLE_TEXT_unicode' vector decodes multi-codepoint payload`() {
        val info = decode(vector("SIMPLE_TEXT_unicode"))
        info.type shouldBe ClipboardInfo.Type.SIMPLE_TEXT
        info.data shouldBe "café 👋 你好 — العربية".encodeUtf8()
    }

    private fun loadFixture(): PublishedModuleFixture {
        val bytes = Files.readAllBytes(cacheDir.resolve("octi-web-clipboard.json"))
        return InteropFixtures.json.decodeFromString(
            PublishedModuleFixture.serializer(),
            bytes.decodeToString(),
        )
    }

    private fun vector(name: String): PublishedVector {
        val fixture = loadFixture()
        val v = fixture.vectors.firstOrNull { it.name == name }
            ?: error("vector '$name' missing in ${fixture.module}")
        InteropFixtures.verifyVectorIntegrity(v)
        return v
    }

    private fun decode(v: PublishedVector): ClipboardInfo {
        // Production wire Json — catches consumer-side serializer-config drift in the gate.
        return Serialization.json.decodeFromString(ClipboardInfo.serializer(), v.payloadJson)
    }
}
