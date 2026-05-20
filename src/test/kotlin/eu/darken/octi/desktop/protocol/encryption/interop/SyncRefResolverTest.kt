package eu.darken.octi.desktop.protocol.encryption.interop

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SyncRefResolverTest {

    private val validSha40 = "a".repeat(40)
    private val altSha40 = "b".repeat(40)
    private val validSha256 = "a".repeat(64)
    private val lockedRef = "c".repeat(40)

    private val lock = FixtureLock(
        source = "d4rken-org/octi",
        ref = lockedRef,
        manifestSha256 = validSha256,
    )

    // ---------- validateLock ----------

    @Test
    fun `validateLock accepts a well-formed lock`() {
        SyncRefResolver.validateLock(lock) // no throw
    }

    @Test
    fun `validateLock rejects malformed owner-repo`() {
        val ex = shouldThrow<IllegalArgumentException> {
            SyncRefResolver.validateLock(lock.copy(source = "no-slash"))
        }
        ex.message!!.contains("<owner>/<repo>") shouldBe true
    }

    @Test
    fun `validateLock rejects bad ref shape`() {
        shouldThrow<IllegalArgumentException> {
            SyncRefResolver.validateLock(lock.copy(ref = "abc"))
        }
        shouldThrow<IllegalArgumentException> {
            // uppercase hex
            SyncRefResolver.validateLock(lock.copy(ref = validSha40.uppercase()))
        }
    }

    @Test
    fun `validateLock rejects bad manifest_sha256 shape`() {
        shouldThrow<IllegalArgumentException> {
            SyncRefResolver.validateLock(lock.copy(manifestSha256 = "abc"))
        }
    }

    @Test
    fun `validateLock rejects source not in SOURCE_PATHS registry`() {
        val ex = shouldThrow<IllegalArgumentException> {
            SyncRefResolver.validateLock(lock.copy(source = "some/other-repo"))
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
        // "no-slash" lacks the "/" required by REPO_OWNER_RE — hits the owner/repo
        // regex branch before the source-allowlist check. Matches the A1 test.
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
        // Too short
        shouldThrow<IllegalArgumentException> {
            SyncRefResolver.parseOverrides("""{"d4rken-org/octi":"abc"}""")
        }
        // Uppercase hex
        shouldThrow<IllegalArgumentException> {
            SyncRefResolver.parseOverrides("""{"d4rken-org/octi":"${"A".repeat(40)}"}""")
        }
        // Right length but non-hex
        shouldThrow<IllegalArgumentException> {
            SyncRefResolver.parseOverrides("""{"d4rken-org/octi":"${"z".repeat(40)}"}""")
        }
    }

    @Test
    fun `parseOverrides accepts any 40 lowercase hex chars (existence not checked here)`() {
        val env = """{"d4rken-org/octi":"${"0".repeat(40)}"}"""
        SyncRefResolver.parseOverrides(env) shouldBe mapOf("d4rken-org/octi" to "0".repeat(40))
    }

    // ---------- resolveSource ----------

    @Test
    fun `resolveSource falls through to locked ref + sha when no override matches`() {
        SyncRefResolver.resolveSource(lock, emptyMap()) shouldBe
            ResolvedSource("d4rken-org/octi", lockedRef, validSha256)
    }

    @Test
    fun `resolveSource applies override and drops manifestSha256 when source matches`() {
        SyncRefResolver.resolveSource(lock, mapOf("d4rken-org/octi" to altSha40)) shouldBe
            ResolvedSource("d4rken-org/octi", altSha40, manifestSha256 = null)
    }

    @Test
    fun `resolveSource ignores override keys that don't match the lock's source`() {
        SyncRefResolver.resolveSource(lock, mapOf("some-other/repo" to altSha40)) shouldBe
            ResolvedSource("d4rken-org/octi", lockedRef, validSha256)
    }

    // ---------- resolveFromEnv ----------

    @Test
    fun `resolveFromEnv returns the locked source when env is empty`() {
        SyncRefResolver.resolveFromEnv(lock, emptyMap()) shouldBe
            ResolvedSource("d4rken-org/octi", lockedRef, validSha256)
    }

    @Test
    fun `resolveFromEnv returns the override-resolved source when env has matching override`() {
        // Pin the integration: env → resolved.ref. The cache dir derived from this MUST
        // match where sync writes, or the consumer reads stale files. Mirror of the
        // equivalent regression test on the octi-web side.
        val env = mapOf(
            "INTEROP_FIXTURE_OVERRIDES" to """{"d4rken-org/octi":"$altSha40"}""",
        )
        SyncRefResolver.resolveFromEnv(lock, env) shouldBe
            ResolvedSource("d4rken-org/octi", altSha40, manifestSha256 = null)
    }

    @Test
    fun `resolveFromEnv propagates parseOverrides failures (no silent fallback)`() {
        val env = mapOf("INTEROP_FIXTURE_OVERRIDES" to "not json")
        val ex = shouldThrow<IllegalArgumentException> {
            SyncRefResolver.resolveFromEnv(lock, env)
        }
        ex.message!!.contains("not valid JSON") shouldBe true
    }

    // ---------- SOURCE_PATHS invariants ----------

    @Test
    fun `SOURCE_PATHS contains the current upstream`() {
        ("d4rken-org/octi" in SyncRefResolver.SOURCE_PATHS) shouldBe true
    }

    @Test
    fun `SOURCE_PATHS values are relative paths containing interop`() {
        for ((source, path) in SyncRefResolver.SOURCE_PATHS) {
            (path.startsWith("/")) shouldBe false
            (path.contains("..")) shouldBe false
            (path.isNotEmpty()) shouldBe true
            (path.contains("interop")) shouldBe true
            // Source matches the owner/repo regex
            Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""").matches(source) shouldBe true
        }
    }
}
