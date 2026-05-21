package eu.darken.octi.desktop.protocol.encryption.interop

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Resolved fetch target for one source after override merge.
 *
 * `manifestSha256` is null when an override is in effect — there's no committed sha that could
 * pin against an arbitrary upstream commit, so the manifest's per-file sha256s become the sole
 * trust anchor for that run.
 */
internal data class ResolvedSource(
    val source: String,
    val ref: String,
    val manifestSha256: String?,
)

/**
 * Multi-source resolver. Mirror of:
 *  - octi-web/src/__interop__/sync-ref-resolver.ts
 *  - app-common-test/.../testhelpers/interop/SyncRefResolver.kt
 *
 * [SOURCE_PATHS] is subset-based per repo — each consumer lists only the producers it
 * actually consumes. The cross-repo invariant is: for any source that more than one
 * consumer lists, the path string must match byte-for-byte in every consumer's map. This is
 * the cross-repo trust boundary; drift here silently breaks consumers.
 */
internal object SyncRefResolver {

    /**
     * Code-owned allowlist of upstream sources THIS REPO consumes. Adding a new entry that
     * another consumer already knows about requires the path string to match exactly. Adding
     * a brand-new producer requires a coordinated rollout: producer commits + ships its
     * fixtures, then each consumer adds it to its own SOURCE_PATHS map.
     *
     * Value is the path under the source repo root that hosts `manifest.json` + fixture files.
     */
    val SOURCE_PATHS: Map<String, String> = mapOf(
        "d4rken-org/octi" to "sync-core/src/test/resources/interop",
        "d4rken-org/octi-web" to "src/__interop__/published",
    )

    private val SHA40_RE = Regex("""^[a-f0-9]{40}$""")
    private val SHA256_RE = Regex("""^[a-f0-9]{64}$""")
    private val REPO_OWNER_RE = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""")

    /**
     * Parse `fixture-lock.json`. Accepts both the v1 single-source flat shape and the v2
     * multi-source shape; normalizes to v2 internally. The migration window per the plan is
     * "one full PR cycle per consumer" — desktop migrates to v2 with this PR, but the
     * dual-shape parser tolerates a future revert that hand-edits the lockfile back to v1.
     */
    fun parseLockJson(bytes: ByteArray): FixtureLock {
        val element = try {
            InteropFixtures.json.parseToJsonElement(bytes.decodeToString())
        } catch (e: Exception) {
            error("fixture-lock.json failed to parse: ${e.message}")
        }
        require(element is JsonObject) { "fixture-lock.json must be a JSON object" }
        val schemaVersion = element["schemaVersion"]?.jsonPrimitive?.intOrNull
        return when (schemaVersion) {
            null -> {
                // Legacy v1: no schemaVersion field, flat shape.
                val v1 = try {
                    InteropFixtures.json.decodeFromString(FixtureLockV1.serializer(), bytes.decodeToString())
                } catch (e: Exception) {
                    error("fixture-lock.json (v1 shape) failed to parse: ${e.message}")
                }
                FixtureLock(
                    schemaVersion = InteropFixtures.LOCK_SCHEMA_VERSION,
                    sources = mapOf(v1.source to LockedSource(v1.ref, v1.manifestSha256)),
                )
            }
            InteropFixtures.LOCK_SCHEMA_VERSION -> try {
                InteropFixtures.json.decodeFromString(FixtureLock.serializer(), bytes.decodeToString())
            } catch (e: Exception) {
                error("fixture-lock.json (v2 shape) failed to parse: ${e.message}")
            }
            else -> error(
                "fixture-lock.json schemaVersion $schemaVersion not supported; " +
                    "this client knows v${InteropFixtures.LOCK_SCHEMA_VERSION} (and the legacy v1 unversioned shape)",
            )
        }
    }

    /** Throws [IllegalArgumentException] on shape drift. */
    fun validateLock(lock: FixtureLock) {
        require(lock.schemaVersion == InteropFixtures.LOCK_SCHEMA_VERSION) {
            "fixture-lock.json schemaVersion ${lock.schemaVersion} not supported; expected ${InteropFixtures.LOCK_SCHEMA_VERSION}"
        }
        require(lock.sources.isNotEmpty()) {
            "fixture-lock.json sources must not be empty"
        }
        for ((source, locked) in lock.sources) {
            require(REPO_OWNER_RE.matches(source)) {
                "fixture-lock.json sources key must be \"<owner>/<repo>\", got: $source"
            }
            require(source in SOURCE_PATHS) {
                "fixture-lock.json source \"$source\" not in code-owned SOURCE_PATHS registry; " +
                    "add it to SyncRefResolver if this is a new trusted upstream."
            }
            require(SHA40_RE.matches(locked.ref)) {
                "fixture-lock.json sources[$source].ref must be a 40-char lowercase commit SHA, got: ${locked.ref}"
            }
            require(SHA256_RE.matches(locked.manifestSha256)) {
                "fixture-lock.json sources[$source].manifest_sha256 must be 64 lowercase hex chars"
            }
        }
    }

    /**
     * Parse and validate `INTEROP_FIXTURE_OVERRIDES`. Empty/unset → empty map.
     * Every value validation throws — loud failure when a workflow sends a malformed override,
     * not silent fallback to locked SHAs.
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

    /**
     * Apply overrides on top of locked refs. Returns one [ResolvedSource] per lock entry.
     *
     * Throws if an override targets a source allowlisted but not present in this repo's lock
     * — that's a workflow misconfiguration we want to surface loudly. Silent drop would let a
     * cross-repo gate pass green against a lock that doesn't yet know about that source.
     */
    fun resolveAll(lock: FixtureLock, overrides: Map<String, String>): Map<String, ResolvedSource> {
        val unknown = overrides.keys - lock.sources.keys
        require(unknown.isEmpty()) {
            "INTEROP_FIXTURE_OVERRIDES targets source(s) not present in fixture-lock.json: " +
                "${unknown.joinToString(", ")}. Known: ${lock.sources.keys.joinToString(", ")}"
        }
        val resolved = LinkedHashMap<String, ResolvedSource>(lock.sources.size)
        for ((source, locked) in lock.sources) {
            val overrideRef = overrides[source]
            resolved[source] = if (overrideRef != null) {
                ResolvedSource(source, overrideRef, manifestSha256 = null)
            } else {
                ResolvedSource(source, locked.ref, manifestSha256 = locked.manifestSha256)
            }
        }
        return resolved
    }

    /** One-shot: parse env, merge with lock, return one resolved entry per source. */
    fun resolveAllFromEnv(
        lock: FixtureLock,
        env: Map<String, String> = System.getenv(),
    ): Map<String, ResolvedSource> {
        return resolveAll(lock, parseOverrides(env["INTEROP_FIXTURE_OVERRIDES"]))
    }
}
