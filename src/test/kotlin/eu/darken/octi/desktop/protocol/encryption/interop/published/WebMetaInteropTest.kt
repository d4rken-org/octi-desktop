package eu.darken.octi.desktop.protocol.encryption.interop.published

import eu.darken.octi.desktop.protocol.encryption.interop.InteropFixtureSync
import eu.darken.octi.desktop.protocol.encryption.interop.InteropFixtures
import eu.darken.octi.desktop.protocol.encryption.interop.PublishedModuleFixture
import eu.darken.octi.desktop.protocol.encryption.interop.PublishedVector
import eu.darken.octi.desktop.protocol.modules.meta.MetaInfo
import eu.darken.octi.desktop.protocol.serialization.Serialization
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verify desktop's MetaInfo decoder consumes what octi-web publishes.
 *
 * Loads `octi-web-meta.json` from `.cache/interop-fixtures/d4rken-org/octi-web/<ref>/`, parses
 * each `payloadJson` through the production [MetaInfo] decoder, and asserts field values
 * match the canonical inputs declared in octi-web's `tools/generate-fixtures.ts`.
 *
 * Sister tests: app-main's WebMetaInteropTest under modules-meta and octi-web's
 * published-self-check.test.ts (producer side).
 */
class WebMetaInteropTest {

    companion object {
        private lateinit var cacheDir: Path

        @JvmStatic
        @BeforeAll
        fun setUp() {
            cacheDir = InteropFixtureSync.ensureSynced("d4rken-org/octi-web")
        }
    }

    @Test
    fun `web meta fixture schema sanity`() {
        val fixture = loadFixture()
        fixture.schemaVersion shouldBe InteropFixtures.SCHEMA_VERSION
        fixture.module shouldBe "eu.darken.octi.module.core.meta"
        fixture.producer shouldBe "d4rken-org/octi-web"
        fixture.vectors.map { it.name } shouldBe listOf("full", "minimal", "unicode-label")
    }

    @Test
    fun `web meta 'full' vector decodes to expected MetaInfo`() {
        val info = decode(vector("full"))
        info.deviceLabel shouldBe "Test Browser"
        info.deviceId.id shouldBe "11111111-2222-3333-4444-555555555555"
        info.octiVersionName shouldBe "0.0.0-test"
        info.octiGitSha shouldBe "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
        info.deviceManufacturer shouldBe "Mozilla"
        info.deviceName shouldBe "Firefox 134.0 on Linux"
        info.deviceType shouldBe MetaInfo.DeviceType.BROWSER
        info.deviceBootedAt shouldBe null
        info.androidVersionName shouldBe null
        info.androidApiLevel shouldBe null
        info.androidSecurityPatch shouldBe null
        info.osType shouldBe "linux"
        info.osVersionName shouldBe "6.8.0"
    }

    @Test
    fun `web meta 'minimal' vector decodes with absent optionals as null`() {
        val info = decode(vector("minimal"))
        info.deviceLabel shouldBe null
        info.deviceId.id shouldBe "11111111-2222-3333-4444-555555555555"
        info.octiVersionName shouldBe "0.0.0-test"
        info.octiGitSha shouldBe "dev"
        info.deviceManufacturer shouldBe "Mozilla"
        info.deviceName shouldBe "Browser"
        info.deviceType shouldBe MetaInfo.DeviceType.BROWSER
        info.deviceBootedAt shouldBe null
        info.osType shouldBe null
        info.osVersionName shouldBe null
    }

    @Test
    fun `web meta 'unicode-label' vector decodes every field including non-ASCII deviceLabel`() {
        val info = decode(vector("unicode-label"))
        info.deviceLabel shouldBe "ブラウザ 👋 العربية"
        info.deviceId.id shouldBe "11111111-2222-3333-4444-555555555555"
        info.octiVersionName shouldBe "0.0.0-test"
        info.octiGitSha shouldBe "dev"
        info.deviceManufacturer shouldBe "Mozilla"
        info.deviceName shouldBe "Firefox"
        info.deviceType shouldBe MetaInfo.DeviceType.BROWSER
        info.deviceBootedAt shouldBe null
        info.osType shouldBe "linux"
        info.osVersionName shouldBe null
    }

    private fun loadFixture(): PublishedModuleFixture {
        val bytes = Files.readAllBytes(cacheDir.resolve("octi-web-meta.json"))
        return InteropFixtures.json.decodeFromString(
            PublishedModuleFixture.serializer(),
            bytes.decodeToString(),
        )
    }

    private fun vector(name: String): PublishedVector {
        val fixture = loadFixture()
        val v = fixture.vectors.firstOrNull { it.name == name }
            ?: error("vector '$name' missing in ${fixture.module}")
        // Re-verify the per-vector sha256 + byteLength against payloadJson. The producer's
        // self-check pins these at generate time; we re-check on read so a hand-edit to one
        // of these files fails here, not as a green decode.
        InteropFixtures.verifyVectorIntegrity(v)
        return v
    }

    private fun decode(v: PublishedVector): MetaInfo {
        // Use the production wire Json (Serialization.json) so consumer-side config drift
        // (contextual serializers, encodeDefaults, etc.) is caught at the same gate.
        return Serialization.json.decodeFromString(MetaInfo.serializer(), v.payloadJson)
    }
}
