package eu.darken.octi.desktop.protocol.encryption.interop

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SyncRefResolverTest {

    private val validSha40 = "a".repeat(40)
    private val altSha40 = "b".repeat(40)
    private val validSha256 = "a".repeat(64)
    private val lockedRefA = "c".repeat(40)
    private val lockedRefB = "d".repeat(40)

    private fun validLock(
        sources: Map<String, LockedSource> = mapOf(
            "d4rken-org/octi" to LockedSource(lockedRefA, validSha256),
        ),
    ): FixtureLock = FixtureLock(
        schemaVersion = InteropFixtures.LOCK_SCHEMA_VERSION,
        sources = sources,
    )

    // ---------- parseLockJson ----------

    @Test
    fun `parseLockJson accepts a well-formed v2 multi-source shape`() {
        val json = """
            {
              "schemaVersion": 2,
              "sources": {
                "d4rken-org/octi": { "ref": "$lockedRefA", "manifest_sha256": "$validSha256" },
                "d4rken-org/octi-web": { "ref": "$lockedRefB", "manifest_sha256": "$validSha256" }
              }
            }
        """.trimIndent()
        val lock = SyncRefResolver.parseLockJson(json.toByteArray(Charsets.UTF_8))
        lock.schemaVersion shouldBe 2
        lock.sources.keys shouldBe setOf("d4rken-org/octi", "d4rken-org/octi-web")
        lock.sources.getValue("d4rken-org/octi").ref shouldBe lockedRefA
        lock.sources.getValue("d4rken-org/octi-web").ref shouldBe lockedRefB
    }

    @Test
    fun `parseLockJson accepts legacy v1 flat shape and normalizes to v2`() {
        // During the migration window: a hand-edit revert to the v1 shape (no schemaVersion
        // field, flat source/ref/manifest_sha256) still parses. Mirror of octi-web's TS parser
        // accepting the same.
        val json = """
            {
              "source": "d4rken-org/octi",
              "ref": "$lockedRefA",
              "manifest_sha256": "$validSha256"
            }
        """.trimIndent()
        val lock = SyncRefResolver.parseLockJson(json.toByteArray(Charsets.UTF_8))
        lock.schemaVersion shouldBe InteropFixtures.LOCK_SCHEMA_VERSION
        lock.sources.size shouldBe 1
        lock.sources.getValue("d4rken-org/octi") shouldBe LockedSource(lockedRefA, validSha256)
    }

    @Test
    fun `parseLockJson rejects an unknown schemaVersion`() {
        val json = """{"schemaVersion": 99, "sources": {}}"""
        shouldThrow<IllegalStateException> {
            SyncRefResolver.parseLockJson(json.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `parseLockJson rejects malformed JSON`() {
        shouldThrow<IllegalStateException> {
            SyncRefResolver.parseLockJson("{not json".toByteArray(Charsets.UTF_8))
        }
    }

    // ---------- validateLock ----------

    @Test
    fun `validateLock accepts a well-formed v2 lock`() {
        SyncRefResolver.validateLock(validLock())
    }

    @Test
    fun `validateLock accepts a multi-source v2 lock`() {
        SyncRefResolver.validateLock(
            validLock(
                sources = mapOf(
                    "d4rken-org/octi" to LockedSource(lockedRefA, validSha256),
                    "d4rken-org/octi-web" to LockedSource(lockedRefB, validSha256),
                ),
            ),
        )
    }

    @Test
    fun `validateLock rejects an empty sources map`() {
        shouldThrow<IllegalArgumentException> {
            SyncRefResolver.validateLock(validLock(sources = emptyMap()))
        }
    }

    @Test
    fun `validateLock rejects an unsupported schemaVersion`() {
        shouldThrow<IllegalArgumentException> {
            SyncRefResolver.validateLock(validLock().copy(schemaVersion = 1))
        }
    }

    @Test
    fun `validateLock rejects malformed owner-repo`() {
        val ex = shouldThrow<IllegalArgumentException> {
            SyncRefResolver.validateLock(
                validLock(sources = mapOf("no-slash" to LockedSource(lockedRefA, validSha256))),
            )
        }
        ex.message!!.contains("<owner>/<repo>") shouldBe true
    }

    @Test
    fun `validateLock rejects bad ref shape`() {
        shouldThrow<IllegalArgumentException> {
            SyncRefResolver.validateLock(
                validLock(sources = mapOf("d4rken-org/octi" to LockedSource("abc", validSha256))),
            )
        }
        shouldThrow<IllegalArgumentException> {
            // uppercase hex
            SyncRefResolver.validateLock(
                validLock(sources = mapOf("d4rken-org/octi" to LockedSource(validSha40.uppercase(), validSha256))),
            )
        }
    }

    @Test
    fun `validateLock rejects bad manifest_sha256 shape`() {
        shouldThrow<IllegalArgumentException> {
            SyncRefResolver.validateLock(
                validLock(sources = mapOf("d4rken-org/octi" to LockedSource(lockedRefA, "abc"))),
            )
        }
    }

    @Test
    fun `validateLock rejects source not in SOURCE_PATHS registry`() {
        val ex = shouldThrow<IllegalArgumentException> {
            SyncRefResolver.validateLock(
                validLock(sources = mapOf("some/other-repo" to LockedSource(lockedRefA, validSha256))),
            )
        }
        ex.message!!.contains("SOURCE_PATHS") shouldBe true
    }

    // ---------- parseOverrides ----------

    @Test
    fun `parseOverrides returns empty map for null env`() {
        SyncRefResolver.parseOverrides(null) shouldBe emptyMap()
    }

    @Test
    fun `parseOverrides returns empty map for blank env`() {
        SyncRefResolver.parseOverrides("") shouldBe emptyMap()
        SyncRefResolver.parseOverrides("   ") shouldBe emptyMap()
    }

    @Test
    fun `parseOverrides parses a valid single-source override`() {
        val env = """{"d4rken-org/octi":"$validSha40"}"""
        SyncRefResolver.parseOverrides(env) shouldBe mapOf("d4rken-org/octi" to validSha40)
    }

    @Test
    fun `parseOverrides parses a valid multi-source override`() {
        val env = """{"d4rken-org/octi":"$validSha40","d4rken-org/octi-web":"$altSha40"}"""
        SyncRefResolver.parseOverrides(env) shouldBe mapOf(
            "d4rken-org/octi" to validSha40,
            "d4rken-org/octi-web" to altSha40,
        )
    }

    @Test
    fun `parseOverrides throws on non-JSON env`() {
        val ex = shouldThrow<IllegalArgumentException> {
            SyncRefResolver.parseOverrides("not json")
        }
        ex.message!!.contains("not valid JSON") shouldBe true
    }

    @Test
    fun `parseOverrides throws on non-object JSON (array, null, primitive)`() {
        shouldThrow<IllegalArgumentException> { SyncRefResolver.parseOverrides("[]") }
        shouldThrow<IllegalArgumentException> { SyncRefResolver.parseOverrides("null") }
        shouldThrow<IllegalArgumentException> { SyncRefResolver.parseOverrides("\"string\"") }
    }

    @Test
    fun `parseOverrides throws on key not in SOURCE_PATHS registry`() {
        val env = """{"unknown/repo":"$validSha40"}"""
        val ex = shouldThrow<IllegalArgumentException> {
            SyncRefResolver.parseOverrides(env)
        }
        ex.message!!.contains("unknown source") shouldBe true
    }

    @Test
    fun `parseOverrides throws on key that doesn't match owner-repo shape`() {
        val env = """{"no-slash":"$validSha40"}"""
        val ex = shouldThrow<IllegalArgumentException> {
            SyncRefResolver.parseOverrides(env)
        }
        ex.message!!.contains("<owner>/<repo>") shouldBe true
    }

    @Test
    fun `parseOverrides throws on non-string value`() {
        val env = """{"d4rken-org/octi":42}"""
        val ex = shouldThrow<IllegalArgumentException> {
            SyncRefResolver.parseOverrides(env)
        }
        ex.message!!.contains("must be a string") shouldBe true
    }

    @Test
    fun `parseOverrides throws on value that isn't a 40-char lowercase sha`() {
        shouldThrow<IllegalArgumentException> {
            SyncRefResolver.parseOverrides("""{"d4rken-org/octi":"abc"}""")
        }
        shouldThrow<IllegalArgumentException> {
            SyncRefResolver.parseOverrides("""{"d4rken-org/octi":"${"A".repeat(40)}"}""")
        }
        shouldThrow<IllegalArgumentException> {
            SyncRefResolver.parseOverrides("""{"d4rken-org/octi":"${"z".repeat(40)}"}""")
        }
    }

    // ---------- resolveAll ----------

    @Test
    fun `resolveAll with no overrides keeps locked ref + manifestSha256`() {
        val resolved = SyncRefResolver.resolveAll(validLock(), emptyMap())
        resolved.size shouldBe 1
        val entry = resolved.getValue("d4rken-org/octi")
        entry.source shouldBe "d4rken-org/octi"
        entry.ref shouldBe lockedRefA
        entry.manifestSha256 shouldBe validSha256
    }

    @Test
    fun `resolveAll applies an override and drops the manifestSha256 trust anchor`() {
        val resolved = SyncRefResolver.resolveAll(
            validLock(),
            mapOf("d4rken-org/octi" to altSha40),
        )
        val entry = resolved.getValue("d4rken-org/octi")
        entry.ref shouldBe altSha40
        entry.manifestSha256 shouldBe null
    }

    @Test
    fun `resolveAll resolves each source independently in a multi-source lock`() {
        val lock = validLock(
            sources = mapOf(
                "d4rken-org/octi" to LockedSource(lockedRefA, validSha256),
                "d4rken-org/octi-web" to LockedSource(lockedRefB, validSha256),
            ),
        )
        val resolved = SyncRefResolver.resolveAll(
            lock,
            // Override one source; leave the other on the locked ref.
            mapOf("d4rken-org/octi-web" to altSha40),
        )
        resolved.getValue("d4rken-org/octi").ref shouldBe lockedRefA
        resolved.getValue("d4rken-org/octi").manifestSha256 shouldBe validSha256
        resolved.getValue("d4rken-org/octi-web").ref shouldBe altSha40
        resolved.getValue("d4rken-org/octi-web").manifestSha256 shouldBe null
    }

    @Test
    fun `resolveAll throws when override targets a source not present in the lock`() {
        // Workflow misconfiguration guard: a override for an allowlisted-but-not-yet-locked
        // source must fail loudly, not silently fall back. Mirror of the equivalent check on
        // the app-main side.
        shouldThrow<IllegalArgumentException> {
            SyncRefResolver.resolveAll(
                validLock(),
                mapOf("d4rken-org/octi-web" to altSha40),
            )
        }
    }

    // ---------- resolveAllFromEnv ----------

    @Test
    fun `resolveAllFromEnv returns the locked source when env is empty`() {
        val resolved = SyncRefResolver.resolveAllFromEnv(validLock(), env = emptyMap())
        resolved.getValue("d4rken-org/octi").ref shouldBe lockedRefA
        resolved.getValue("d4rken-org/octi").manifestSha256 shouldBe validSha256
    }

    @Test
    fun `resolveAllFromEnv applies the env override for the matching source`() {
        val env = mapOf("INTEROP_FIXTURE_OVERRIDES" to """{"d4rken-org/octi":"$altSha40"}""")
        val resolved = SyncRefResolver.resolveAllFromEnv(validLock(), env = env)
        resolved.getValue("d4rken-org/octi").ref shouldBe altSha40
        resolved.getValue("d4rken-org/octi").manifestSha256 shouldBe null
    }

    @Test
    fun `resolveAllFromEnv propagates parseOverrides failures (no silent fallback)`() {
        val env = mapOf("INTEROP_FIXTURE_OVERRIDES" to "not json")
        val ex = shouldThrow<IllegalArgumentException> {
            SyncRefResolver.resolveAllFromEnv(validLock(), env = env)
        }
        ex.message!!.contains("not valid JSON") shouldBe true
    }

    // ---------- SOURCE_PATHS invariants ----------

    @Test
    fun `SOURCE_PATHS contains both producer entries`() {
        ("d4rken-org/octi" in SyncRefResolver.SOURCE_PATHS) shouldBe true
        ("d4rken-org/octi-web" in SyncRefResolver.SOURCE_PATHS) shouldBe true
    }

    @Test
    fun `SOURCE_PATHS values are relative paths`() {
        for ((source, path) in SyncRefResolver.SOURCE_PATHS) {
            (path.startsWith("/")) shouldBe false
            (path.contains("..")) shouldBe false
            (path.isNotEmpty()) shouldBe true
            Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""").matches(source) shouldBe true
        }
    }
}
