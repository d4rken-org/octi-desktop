package eu.darken.octi.desktop.protocol.encryption.interop

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Resolved fetch target after override merge.
 *
 * `manifestSha256` is null when an override is in effect — there's no
 * committed SHA we could pin against an arbitrary upstream commit, so the
 * manifest's per-file sha256s become the sole trust anchor for that run.
 */
internal data class ResolvedSource(
    val source: String,
    val ref: String,
    val manifestSha256: String?,
)

/**
 * Shared between [InteropFixtureSync] (fetch + cache write) and any future
 * consumer that reads the cache directly. Both call [resolveFromEnv] so they
 * always agree on the effective ref + cache directory after applying
 * `INTEROP_FIXTURE_OVERRIDES`.
 *
 * Mirror of `octi-web/src/__interop__/sync-ref-resolver.ts`. Keep the
 * validation rules and SOURCE_PATHS entries in lockstep — they form the same
 * cross-repo trust boundary on both sides.
 */
internal object SyncRefResolver {

    /**
     * Code-owned allowlist of upstream sources. Adding a fourth source requires
     * a code change here — cross-repo trust is not a runtime config. The value
     * is the path under the source repo root that hosts `manifest.json` and the
     * fixture files.
     */
    val SOURCE_PATHS: Map<String, String> = mapOf(
        "d4rken-org/octi" to "sync-core/src/test/resources/interop",
    )

    private val SHA40_RE = Regex("""^[a-f0-9]{40}$""")
    private val SHA256_RE = Regex("""^[a-f0-9]{64}$""")
    private val REPO_OWNER_RE = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""")

    /** Throws [IllegalArgumentException] on shape drift. */
    fun validateLock(lock: FixtureLock) {
        require(REPO_OWNER_RE.matches(lock.source)) {
            "fixture-lock.json source must be \"<owner>/<repo>\", got: ${lock.source}"
        }
        require(SHA40_RE.matches(lock.ref)) {
            "fixture-lock.json ref must be a 40-character commit SHA (no tags or branches), got: ${lock.ref}"
        }
        require(SHA256_RE.matches(lock.manifestSha256)) {
            "fixture-lock.json manifest_sha256 must be 64 lowercase hex chars"
        }
        require(lock.source in SOURCE_PATHS) {
            "fixture-lock.json source \"${lock.source}\" not in code-owned SOURCE_PATHS registry; " +
                "add it to SyncRefResolver if this is a new trusted upstream."
        }
    }

    /**
     * Parse and validate `INTEROP_FIXTURE_OVERRIDES`. Empty/unset → empty map.
     * Every value validation throws — loud failure when the workflow sends a
     * malformed override, not silent fallback to the locked SHA.
     */
    fun parseOverrides(envValue: String?): Map<String, String> {
        if (envValue.isNullOrBlank()) return emptyMap()
        val parsed = try {
            InteropFixtures.json.parseToJsonElement(envValue)
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "INTEROP_FIXTURE_OVERRIDES is not valid JSON: ${e.message}",
                e,
            )
        }
        require(parsed is JsonObject) {
            "INTEROP_FIXTURE_OVERRIDES must be a JSON object"
        }
        val out = mutableMapOf<String, String>()
        for ((key, value) in parsed) {
            require(REPO_OWNER_RE.matches(key)) {
                "INTEROP_FIXTURE_OVERRIDES key must be \"<owner>/<repo>\", got: $key"
            }
            require(key in SOURCE_PATHS) {
                "INTEROP_FIXTURE_OVERRIDES references unknown source \"$key\"; " +
                    "must be one of: ${SOURCE_PATHS.keys.joinToString(", ")}"
            }
            require(value is JsonPrimitive && value.isString) {
                "INTEROP_FIXTURE_OVERRIDES value for \"$key\" must be a string"
            }
            val str = value.content
            require(SHA40_RE.matches(str)) {
                "INTEROP_FIXTURE_OVERRIDES value for \"$key\" must be a 40-char lowercase commit SHA, got: $str"
            }
            out[key] = str
        }
        return out
    }

    /** Apply override (if any) on top of the locked ref. */
    fun resolveSource(lock: FixtureLock, overrides: Map<String, String>): ResolvedSource {
        val overrideRef = overrides[lock.source]
        return if (overrideRef != null) {
            ResolvedSource(lock.source, overrideRef, manifestSha256 = null)
        } else {
            ResolvedSource(lock.source, lock.ref, manifestSha256 = lock.manifestSha256)
        }
    }

    /**
     * One-shot: parse env, merge with lock, return resolved source. Used by
     * [InteropFixtureSync] so any future read-side cache consumer that calls
     * this returns the same cache directory.
     */
    fun resolveFromEnv(lock: FixtureLock, env: Map<String, String> = System.getenv()): ResolvedSource {
        return resolveSource(lock, parseOverrides(env["INTEROP_FIXTURE_OVERRIDES"]))
    }
}
