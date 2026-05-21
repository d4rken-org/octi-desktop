package eu.darken.octi.desktop.protocol.encryption.interop.published

import eu.darken.octi.desktop.protocol.encryption.interop.InteropFixtureSync
import eu.darken.octi.desktop.protocol.encryption.interop.InteropFixtures
import eu.darken.octi.desktop.protocol.encryption.interop.PublishedModuleFixture
import eu.darken.octi.desktop.protocol.encryption.interop.PublishedVector
import eu.darken.octi.desktop.protocol.modules.files.FileShareInfo
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.RemoteBlobRef
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Instant

/**
 * Verify desktop's FileShareInfo decoder consumes what octi-web publishes.
 *
 * Pin: SharedFile.size as Long (the 'files-large' vector trips any Int-typed field),
 * connectorRefs map keying, Instant ISO-8601 parsing, optional `deleteRequests` branch.
 */
class WebFilesInteropTest {

    companion object {
        private lateinit var cacheDir: Path

        private const val PROD_CONNECTOR =
            "kserver-prod.kserver.octi.darken.eu-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        private const val BETA_CONNECTOR =
            "kserver-beta.kserver.octi.darken.eu-ffffffff-1111-2222-3333-444444444444"

        @JvmStatic
        @BeforeAll
        fun setUp() {
            cacheDir = InteropFixtureSync.ensureSynced("d4rken-org/octi-web")
        }
    }

    @Test
    fun `web files fixture schema sanity`() {
        val fixture = loadFixture()
        fixture.schemaVersion shouldBe InteropFixtures.SCHEMA_VERSION
        fixture.module shouldBe "eu.darken.octi.module.core.files"
        fixture.producer shouldBe "d4rken-org/octi-web"
        fixture.vectors.map { it.name } shouldBe listOf(
            "empty",
            "single-file",
            "with-multiple-files",
            "with-delete-requests",
            "multi-connector",
            "files-large",
        )
    }

    @Test
    fun `web files 'empty' vector decodes to empty lists`() {
        val info = decode(vector("empty"))
        info.files shouldBe emptyList()
        info.deleteRequests shouldBe emptyList()
    }

    @Test
    fun `web files 'single-file' vector decodes one SharedFile`() {
        val info = decode(vector("single-file"))
        info.files.size shouldBe 1
        info.deleteRequests shouldBe emptyList()

        val f = info.files[0]
        f.name shouldBe "notes.txt"
        f.mimeType shouldBe "text/plain"
        f.size shouldBe 1234L
        f.blobKey shouldBe "sha256:0000000000000000000000000000000000000000000000000000000000000001"
        f.checksum shouldBe "0000000000000000000000000000000000000000000000000000000000000001"
        f.sharedAt shouldBe Instant.parse("2026-05-01T12:00:00Z")
        f.expiresAt shouldBe Instant.parse("2026-05-31T12:00:00Z")
        f.availableOn shouldBe setOf(PROD_CONNECTOR)
        f.connectorRefs shouldBe mapOf(PROD_CONNECTOR to RemoteBlobRef("blob-id-aaaa"))
    }

    @Test
    fun `web files 'with-multiple-files' vector decodes both entries field-by-field`() {
        val info = decode(vector("with-multiple-files"))
        info.files.size shouldBe 2
        info.deleteRequests shouldBe emptyList()

        val alpha = info.files[0]
        alpha.name shouldBe "alpha.bin"
        alpha.mimeType shouldBe "application/octet-stream"
        alpha.size shouldBe 256L
        alpha.blobKey shouldBe "sha256:0000000000000000000000000000000000000000000000000000000000000002"
        alpha.checksum shouldBe "0000000000000000000000000000000000000000000000000000000000000002"
        alpha.sharedAt shouldBe Instant.parse("2026-05-01T12:00:00Z")
        alpha.expiresAt shouldBe Instant.parse("2026-05-31T12:00:00Z")
        alpha.availableOn shouldBe setOf(PROD_CONNECTOR)
        alpha.connectorRefs shouldBe mapOf(PROD_CONNECTOR to RemoteBlobRef("blob-id-bbbb"))

        val beta = info.files[1]
        beta.name shouldBe "beta.pdf"
        beta.mimeType shouldBe "application/pdf"
        beta.size shouldBe 4096L
        beta.blobKey shouldBe "sha256:0000000000000000000000000000000000000000000000000000000000000003"
        beta.checksum shouldBe "0000000000000000000000000000000000000000000000000000000000000003"
        beta.sharedAt shouldBe Instant.parse("2026-05-01T13:00:00Z")
        beta.expiresAt shouldBe Instant.parse("2026-05-31T13:00:00Z")
        beta.availableOn shouldBe setOf(PROD_CONNECTOR)
        beta.connectorRefs shouldBe mapOf(PROD_CONNECTOR to RemoteBlobRef("blob-id-cccc"))
    }

    @Test
    fun `web files 'with-delete-requests' vector decodes the deleteRequests branch field-by-field`() {
        val info = decode(vector("with-delete-requests"))
        info.files.size shouldBe 1
        info.deleteRequests.size shouldBe 1

        val f = info.files[0]
        f.name shouldBe "shared.txt"
        f.mimeType shouldBe "text/plain"
        f.size shouldBe 100L
        f.blobKey shouldBe "sha256:0000000000000000000000000000000000000000000000000000000000000004"
        f.checksum shouldBe "0000000000000000000000000000000000000000000000000000000000000004"
        f.sharedAt shouldBe Instant.parse("2026-05-01T12:00:00Z")
        f.expiresAt shouldBe Instant.parse("2026-05-31T12:00:00Z")
        f.availableOn shouldBe setOf(PROD_CONNECTOR)
        f.connectorRefs shouldBe mapOf(PROD_CONNECTOR to RemoteBlobRef("blob-id-dddd"))

        val req = info.deleteRequests[0]
        req.targetDeviceId shouldBe "99999999-8888-7777-6666-555555555555"
        req.blobKey shouldBe "sha256:0000000000000000000000000000000000000000000000000000000000000005"
        req.requestedAt shouldBe Instant.parse("2026-05-10T00:00:00Z")
        req.retainUntil shouldBe Instant.parse("2026-05-17T00:00:00Z")
    }

    @Test
    fun `web files 'multi-connector' vector decodes both connectorRefs entries field-by-field`() {
        val info = decode(vector("multi-connector"))
        info.files.size shouldBe 1
        info.deleteRequests shouldBe emptyList()

        val f = info.files[0]
        f.name shouldBe "shared-across.bin"
        f.mimeType shouldBe "application/octet-stream"
        f.size shouldBe 512L
        f.blobKey shouldBe "sha256:0000000000000000000000000000000000000000000000000000000000000007"
        f.checksum shouldBe "0000000000000000000000000000000000000000000000000000000000000007"
        f.sharedAt shouldBe Instant.parse("2026-05-01T12:00:00Z")
        f.expiresAt shouldBe Instant.parse("2026-05-31T12:00:00Z")
        f.availableOn shouldBe setOf(PROD_CONNECTOR, BETA_CONNECTOR)
        f.connectorRefs shouldBe mapOf(
            PROD_CONNECTOR to RemoteBlobRef("blob-id-prod-7777"),
            BETA_CONNECTOR to RemoteBlobRef("blob-id-beta-7777"),
        )
    }

    @Test
    fun `web files 'files-large' vector decodes size larger than Int MAX_VALUE`() {
        // Pins Long handling on the JVM consumer. If SharedFile.size were typed `Int`, the
        // 8e9 byte value would fail decode here in the right place.
        val info = decode(vector("files-large"))
        info.files.size shouldBe 1
        info.deleteRequests shouldBe emptyList()

        val f = info.files[0]
        f.name shouldBe "big.iso"
        f.mimeType shouldBe "application/octet-stream"
        f.size shouldBe 8_000_000_000L
        check(f.size > Int.MAX_VALUE.toLong()) { "vector did not exceed Int.MAX_VALUE" }
        f.blobKey shouldBe "sha256:0000000000000000000000000000000000000000000000000000000000000006"
        f.checksum shouldBe "0000000000000000000000000000000000000000000000000000000000000006"
        f.sharedAt shouldBe Instant.parse("2026-05-01T12:00:00Z")
        f.expiresAt shouldBe Instant.parse("2026-05-31T12:00:00Z")
        f.availableOn shouldBe setOf(PROD_CONNECTOR)
        f.connectorRefs shouldBe mapOf(PROD_CONNECTOR to RemoteBlobRef("blob-id-eeee"))
    }

    private fun loadFixture(): PublishedModuleFixture {
        val bytes = Files.readAllBytes(cacheDir.resolve("octi-web-files.json"))
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

    private fun decode(v: PublishedVector): FileShareInfo {
        // Production wire Json — catches consumer-side serializer-config drift in the gate.
        return Serialization.json.decodeFromString(FileShareInfo.serializer(), v.payloadJson)
    }
}
