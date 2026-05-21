package eu.darken.octi.desktop.protocol.encryption.interop

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Cross-repo wire-format fixtures. Desktop consumes from two producers:
 *  - **d4rken-org/octi** (sync-core) — Tink + streaming AEAD fixtures, the always-on crypto gate.
 *  - **d4rken-org/octi-web** (`src/__interop__/published/`) — per-module canonical JSON payloads,
 *    asserts desktop's MetaInfo / ClipboardInfo / FileShareInfo decoders accept what web emits.
 *
 * Wire-shape sister files (must stay in lockstep):
 *  - sync-core/src/test/.../interop/InteropFixtures.kt (app-main, producer of crypto vectors)
 *  - octi-web/src/__interop__/fixture-loader.ts (web, consumer of app-main + own self-check)
 *  - octi-web/tools/generate-fixtures.ts (web, producer of module vectors)
 *  - app-common-test/.../testhelpers/interop/InteropFixtures.kt (app-main, multi-source consumer)
 */
internal object InteropFixtures {

    /** Lockfile schema version. v2 introduces multi-source `sources: Map<...>`. */
    const val LOCK_SCHEMA_VERSION = 2

    /** Per-source manifest schema version. Pinned at v1 across all producers. */
    const val SCHEMA_VERSION = 1

    /** Filenames inside the d4rken-org/octi cache (the original crypto producer). */
    const val MANIFEST_FILE = "manifest.json"
    const val TINK_FILE = "tink-vectors.json"
    const val STREAMING_FILE = "streaming-vectors.json"

    /** First byte of every Tink-AEAD-prefixed ciphertext. Pinned so wire drift fails loudly. */
    const val TINK_PREFIX_BYTE: Byte = 0x01

    /** Lenient about unknown keys so a forward-compat field addition upstream doesn't break us. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * Reconstruct plaintext bytes for a [StreamingPlaintextPattern]. Mirror of app-main's
     * `InteropFixtures.materializePattern`. New kinds must be added here, on app-main, AND on
     * octi-web at the same time.
     */
    fun materializePattern(pattern: StreamingPlaintextPattern): ByteArray = when (pattern.kind) {
        "sequential" -> ByteArray(pattern.size) { i -> (i and 0xFF).toByte() }
        else -> error("unknown plaintextPattern.kind=${pattern.kind}")
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (b in digest) append("%02x".format(b))
        }
    }

    /**
     * Re-verify a [PublishedVector]'s self-claimed `sha256` + `byteLength` against the actual
     * `payloadJson` bytes. The producer's self-check pins these at generate time; we re-check
     * on the consumer side so a hand-edited fixture fails here, not silently as a green decode.
     */
    fun verifyVectorIntegrity(vector: PublishedVector) {
        val bytes = vector.payloadJson.toByteArray(Charsets.UTF_8)
        check(vector.byteLength == bytes.size) {
            "vector '${vector.name}': declared byteLength ${vector.byteLength} disagrees with payloadJson bytes ${bytes.size}"
        }
        val actualSha = sha256Hex(bytes)
        check(vector.sha256 == actualSha) {
            "vector '${vector.name}': declared sha256 ${vector.sha256} disagrees with payloadJson bytes (${actualSha})"
        }
    }
}

@Serializable
internal data class FixtureManifest(
    val schemaVersion: Int,
    val source: String,
    val generator: String? = null,
    val files: Map<String, FileEntry>,
)

@Serializable
internal data class FileEntry(
    val sha256: String,
    val byteLength: Int? = null,
)

@Serializable
internal data class TinkVectorsFixture(
    val schemaVersion: Int,
    val note: String,
    val gcmsiv: KeysetBlock,
    val siv: KeysetBlock,
)

@Serializable
internal data class KeysetBlock(
    val keysetType: String,
    val keysetBase64: String,
    val vectors: List<PayloadVector>,
)

@Serializable
internal data class PayloadVector(
    val name: String,
    val plaintextBase64: String,
    /** UTF-8 string. Empty for legacy SIV by construction. */
    val aad: String,
    val ciphertextBase64: String,
)

@Serializable
internal data class StreamingVectorsFixture(
    val schemaVersion: Int,
    val note: String,
    val keysetType: String,
    val keysetBase64: String,
    val vectors: List<StreamingVector>,
)

@Serializable
internal data class StreamingVector(
    val name: String,
    val aad: String,
    /** Inline plaintext bytes (base64). Mutually exclusive with [plaintextPattern]. */
    val plaintextBase64: String? = null,
    /** Deterministic plaintext reference for large vectors. Mutually exclusive with [plaintextBase64]. */
    val plaintextPattern: StreamingPlaintextPattern? = null,
    val plaintextSize: Int,
    val ciphertextBase64: String,
    val ciphertextSize: Int,
)

@Serializable
internal data class StreamingPlaintextPattern(
    val kind: String,
    val size: Int,
)

/**
 * Multi-source `fixture-lock.json` (v2). One [LockedSource] per upstream producer the
 * consumer pins. Parsers also accept the v1 shape (single-source flat fields) during the
 * migration window — see [SyncRefResolver.parseLockJson].
 */
@Serializable
internal data class FixtureLock(
    val schemaVersion: Int,
    val sources: Map<String, LockedSource>,
)

@Serializable
internal data class LockedSource(
    val ref: String,
    @SerialName("manifest_sha256") val manifestSha256: String,
)

/** Legacy v1 lockfile shape — accepted by [SyncRefResolver.parseLockJson] and normalized to v2. */
@Serializable
internal data class FixtureLockV1(
    val source: String,
    val ref: String,
    @SerialName("manifest_sha256") val manifestSha256: String,
)

/** Per-module fixture file shape — what each producer commits under its published dir. */
@Serializable
internal data class PublishedModuleFixture(
    val schemaVersion: Int,
    val module: String,
    val producer: String,
    val note: String,
    val vectors: List<PublishedVector>,
)

@Serializable
internal data class PublishedVector(
    val name: String,
    val payloadJson: String,
    val sha256: String,
    val byteLength: Int,
)
