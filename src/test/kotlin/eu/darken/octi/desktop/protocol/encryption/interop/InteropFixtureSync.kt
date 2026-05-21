package eu.darken.octi.desktop.protocol.encryption.interop

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Fetch + verify the multi-source interop fixtures pinned in `fixture-lock.json`. Idempotent —
 * repeated invocations with a populated cache skip the network entirely.
 *
 * Called from each consumer test's `@BeforeAll`. Object-level singleton with double-checked
 * locking so multiple test classes (the always-on crypto gate + the per-module web consumers)
 * only sync once per JVM, regardless of test execution order or parallelism.
 *
 * Cache layout: `.cache/interop-fixtures/<owner>/<repo>/<sha>/` — owner+repo in the path
 * prevents collisions across sources.
 */
internal object InteropFixtureSync {

    private const val FETCH_TIMEOUT_MS = 15_000L
    private const val FETCH_RETRIES = 1

    /** Manifest size cap. 64 KiB is far above the realistic ceiling (~1 KiB today). */
    private const val MAX_MANIFEST_BYTES = 64 * 1024

    /**
     * Per-file size cap. 2 MiB covers app-main's largest committed `streaming-vectors.json`
     * (~1.4 MiB — the two-segment streaming AEAD vector). Web's per-module fixtures are all
     * <10 KiB so the cap is unconstrained there. If a future producer fixture file approaches
     * this number it's worth questioning whether the vector needs to be that large.
     */
    private const val MAX_FIXTURE_BYTES = 2 * 1024 * 1024

    /** Cap on file count to bound iteration on a hostile manifest. */
    private const val MAX_MANIFEST_FILES = 32

    /** Lockfile size cap. User-owned but bound it anyway to catch hand-edit mistakes. */
    private const val MAX_LOCKFILE_BYTES = 16 * 1024

    // Filenames: ASCII subset, must end in `.json`, no leading dot, no dot segments, no `..`.
    private val FIXTURE_FILE_RE = Regex(
        """^(?:[A-Za-z0-9_-][A-Za-z0-9_.-]*/)*[A-Za-z0-9_-][A-Za-z0-9_.-]*\.json$""",
    )
    private val RESERVED_FILENAMES = setOf(".sha", "manifest.json")
    private val SHA256_RE = Regex("""^[a-f0-9]{64}$""")

    @Volatile private var cacheDirsCached: Map<String, Path>? = null

    /**
     * Populate (or reuse) the cache for every source in the lockfile. Returns a map
     * `source -> cache directory`. Safe to call from multiple test classes — only the first
     * call does work.
     */
    fun ensureSynced(): Map<String, Path> {
        cacheDirsCached?.let { return it }
        synchronized(this) {
            cacheDirsCached?.let { return it }
            val dirs = runSync()
            cacheDirsCached = dirs
            return dirs
        }
    }

    /** Convenience overload — return the cache dir for one specific source. */
    fun ensureSynced(source: String): Path {
        val dirs = ensureSynced()
        return dirs[source] ?: error(
            "source '$source' not present in fixture-lock.json (known: ${dirs.keys.joinToString(", ")})",
        )
    }

    private fun runSync(): Map<String, Path> {
        val repoRoot = resolveRepoRoot()
        val lockPath = repoRoot.resolve("fixture-lock.json")
        check(Files.isRegularFile(lockPath)) {
            "fixture-lock.json not found at $lockPath. Run via `./gradlew test` (which sets " +
                "`interopRepoRoot`) or set `-DinteropRepoRoot=<path>` on the test JVM."
        }
        check(Files.size(lockPath) <= MAX_LOCKFILE_BYTES) {
            "fixture-lock.json is unexpectedly large (${Files.size(lockPath)} bytes); cap is $MAX_LOCKFILE_BYTES"
        }
        val lock = SyncRefResolver.parseLockJson(Files.readAllBytes(lockPath))
        SyncRefResolver.validateLock(lock)
        val resolvedAll = SyncRefResolver.resolveAllFromEnv(lock)
        for ((source, resolved) in resolvedAll) {
            if (resolved.manifestSha256 == null) {
                println("using override for $source: ${resolved.ref}")
            }
        }

        val out = LinkedHashMap<String, Path>(resolvedAll.size)
        for ((source, resolved) in resolvedAll) {
            out[source] = syncOne(repoRoot, resolved)
        }
        return out
    }

    private fun syncOne(repoRoot: Path, resolved: ResolvedSource): Path {
        val (owner, repo) = resolved.source.split("/", limit = 2)
        val cacheDir = repoRoot
            .resolve(".cache").resolve("interop-fixtures")
            .resolve(owner).resolve(repo).resolve(resolved.ref)

        // Under override (no committed manifest sha to pin against), always re-fetch the
        // manifest. The cache may still have valid bytes, but the manifest must come from
        // the live upstream so it can't be a poisoned local copy.
        if (resolved.manifestSha256 != null && cacheIsValid(cacheDir, resolved)) {
            println("interop fixtures cache hit: ${resolved.source}@${resolved.ref}")
            return cacheDir
        }

        println("fetching interop fixtures from ${resolved.source}@${resolved.ref}...")
        Files.createDirectories(cacheDir)

        val manifestBytes = fetchBytes("${rawBaseUrl(resolved)}/manifest.json", MAX_MANIFEST_BYTES)
        val manifest = parseAndValidateManifest(manifestBytes, resolved)
        Files.write(cacheDir.resolve("manifest.json"), manifestBytes)

        for ((name, entry) in manifest.files) {
            val dest = cacheDir.resolve(name)
            // Under override, cached files for this ref may already be valid; skip
            // re-download to spare bandwidth on unchanged blobs. Cap size first so a tampered
            // local cache file can't be slurped whole.
            if (Files.isRegularFile(dest) &&
                Files.size(dest) <= MAX_FIXTURE_BYTES &&
                InteropFixtures.sha256Hex(Files.readAllBytes(dest)) == entry.sha256
            ) {
                println("  $name (cached, sha256 ok)")
                continue
            }
            val bytes = fetchBytes("${rawBaseUrl(resolved)}/$name", MAX_FIXTURE_BYTES)
            val actual = InteropFixtures.sha256Hex(bytes)
            check(actual == entry.sha256) {
                "$name sha256 mismatch — expected ${entry.sha256}, got $actual"
            }
            Files.createDirectories(dest.parent)
            Files.write(dest, bytes)
            println("  $name (${bytes.size} bytes, sha256 ok)")
        }

        // Marker written last so an interrupted run never produces a "valid" cache.
        Files.writeString(cacheDir.resolve(".sha"), resolved.ref)
        println("interop fixtures synced: $cacheDir")
        return cacheDir
    }

    /**
     * Single source of truth for validating fixture bytes against the lockfile (or, under
     * override, against the manifest's self-claimed shape only). Used both on cold-fetch and
     * warm-cache paths so a stale cache cannot pass weaker checks than a fresh download.
     */
    private fun parseAndValidateManifest(bytes: ByteArray, resolved: ResolvedSource): FixtureManifest {
        if (resolved.manifestSha256 != null) {
            val actualSha = InteropFixtures.sha256Hex(bytes)
            check(actualSha == resolved.manifestSha256) {
                "manifest sha256 mismatch for ${resolved.source} — expected ${resolved.manifestSha256}, got $actualSha. " +
                    "Either fixture-lock.json is stale or upstream history was rewritten."
            }
        }
        val manifest = try {
            InteropFixtures.json.decodeFromString(FixtureManifest.serializer(), bytes.decodeToString())
        } catch (e: Exception) {
            error("manifest.json failed to parse: ${e.message}")
        }
        check(manifest.schemaVersion == InteropFixtures.SCHEMA_VERSION) {
            "unsupported manifest schemaVersion ${manifest.schemaVersion}; this client knows v${InteropFixtures.SCHEMA_VERSION}"
        }
        check(manifest.source == resolved.source) {
            "manifest source ${manifest.source} disagrees with resolved source ${resolved.source}"
        }
        check(manifest.files.size <= MAX_MANIFEST_FILES) {
            "manifest declares ${manifest.files.size} files; cap is $MAX_MANIFEST_FILES"
        }
        for ((name, entry) in manifest.files) {
            check(FIXTURE_FILE_RE.matches(name)) { "manifest contains invalid file name: $name" }
            check(name.split("/").none { it == "." || it == ".." }) {
                "manifest contains path-traversal file name: $name"
            }
            check(name !in RESERVED_FILENAMES) { "manifest references reserved file name: $name" }
            check(SHA256_RE.matches(entry.sha256)) { "manifest entry for $name has invalid sha256" }
        }
        return manifest
    }

    private fun cacheIsValid(cacheDir: Path, resolved: ResolvedSource): Boolean {
        val markerPath = cacheDir.resolve(".sha")
        if (!Files.isRegularFile(markerPath)) return false
        if (Files.size(markerPath) > 128) return false
        if (Files.readString(markerPath).trim() != resolved.ref) return false

        val manifestPath = cacheDir.resolve("manifest.json")
        if (!Files.isRegularFile(manifestPath)) return false
        if (Files.size(manifestPath) > MAX_MANIFEST_BYTES) return false
        val manifestBytes = Files.readAllBytes(manifestPath)

        val manifest = try {
            parseAndValidateManifest(manifestBytes, resolved)
        } catch (_: IllegalStateException) {
            return false
        }

        for ((name, entry) in manifest.files) {
            val filePath = cacheDir.resolve(name)
            if (!Files.isRegularFile(filePath)) return false
            if (Files.size(filePath) > MAX_FIXTURE_BYTES) return false
            if (InteropFixtures.sha256Hex(Files.readAllBytes(filePath)) != entry.sha256) return false
        }
        return true
    }

    private fun rawBaseUrl(resolved: ResolvedSource): String {
        val path = SyncRefResolver.SOURCE_PATHS[resolved.source]
            ?: error("source \"${resolved.source}\" not in SOURCE_PATHS")
        return "https://raw.githubusercontent.com/${resolved.source}/${resolved.ref}/$path"
    }

    private fun fetchBytes(url: String, maxBytes: Int): ByteArray {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(FETCH_TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(FETCH_TIMEOUT_MS))
            .GET()
            .build()

        var lastError: Throwable? = null
        repeat(FETCH_RETRIES + 1) { attempt ->
            try {
                // Stream the body and abort once the cap is exceeded, so a hostile or
                // mis-pinned upstream can't burn arbitrary memory/network before we notice.
                // ofByteArray() would buffer the whole response into memory first.
                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                val status = response.statusCode()
                when {
                    status in 200..299 -> return response.body().use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8 * 1024)
                        var total = 0
                        while (true) {
                            val n = stream.read(buf)
                            if (n < 0) break
                            total += n
                            check(total <= maxBytes) {
                                "response from $url exceeds $maxBytes bytes (read >= $total so far)"
                            }
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                    // 4xx is deterministic (bad ref / typo'd path / private repo). Don't burn
                    // retries — surface the real cause.
                    status in 400..499 ->
                        throw IllegalStateException("GET $url → HTTP $status (4xx, not retried)")
                    else -> throw IOException("GET $url → HTTP $status")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: HttpTimeoutException) {
                lastError = e
                if (attempt < FETCH_RETRIES) println("  fetch timed out; retrying...")
            } catch (e: IOException) {
                lastError = e
                if (attempt < FETCH_RETRIES) println("  fetch IO error (${e.message}); retrying...")
            }
        }
        throw IllegalStateException(
            "GET $url failed after ${FETCH_RETRIES + 1} attempts: ${lastError?.message}",
            lastError,
        )
    }

    private fun resolveRepoRoot(): Path {
        System.getProperty("interopRepoRoot")?.let { return Path.of(it).toAbsolutePath() }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath()
    }
}
