package eu.darken.octi.desktop.sync

import eu.darken.octi.desktop.protocol.module.ModuleId
import eu.darken.octi.desktop.protocol.octiserver.OctiServerConnector
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
import eu.darken.octi.desktop.protocol.octiserver.ws.EventPayload
import eu.darken.octi.desktop.protocol.sync.ConnectorId
import eu.darken.octi.desktop.protocol.sync.ConnectorType
import eu.darken.octi.desktop.protocol.sync.DeviceId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ModuleResolverTest {

    private val moduleId = ModuleId("eu.darken.octi.module.core.meta")
    private val targetDeviceId = DeviceId("device-target")

    private val connectorAlphaId = ConnectorId(ConnectorType.OCTISERVER, "alpha.example.com", "acct-a")
    private val connectorBravoId = ConnectorId(ConnectorType.OCTISERVER, "bravo.example.com", "acct-b")

    /**
     * Counter wrapper: the test asserts on [readCalls] directly rather than via mockk verify so
     * argument-matcher idiosyncrasies (suspend continuation, value-class wrapping) can't mask
     * a missed call.
     */
    private class TestConnector(
        val id: ConnectorId,
        val outcomes: List<OctiServerHttpClient.ModuleReadResult>,
    ) {
        val readCalls = AtomicInteger(0)
        val httpClient: OctiServerHttpClient = mockk<OctiServerHttpClient>().also { client ->
            coEvery { client.readModule(any(), any()) } answers {
                val idx = readCalls.getAndIncrement().coerceAtMost(outcomes.size - 1)
                outcomes[idx]
            }
        }
        val asConnector: OctiServerConnector = mockk<OctiServerConnector>(relaxed = true).also { c ->
            io.mockk.every { c.identifier } returns id
            io.mockk.every { c.client } returns httpClient
        }
    }

    private fun connector(id: ConnectorId, outcomes: List<OctiServerHttpClient.ModuleReadResult>) =
        TestConnector(id, outcomes)

    private fun throwingConnector(id: ConnectorId, throwable: Throwable): TestConnector {
        val tc = TestConnector(id, emptyList())
        coEvery { tc.httpClient.readModule(any(), any()) } answers {
            tc.readCalls.incrementAndGet()
            throw throwable
        }
        return tc
    }

    private fun buildResolver(
        connectors: List<OctiServerConnector>,
        clockSource: () -> Instant = { Instant.fromEpochMilliseconds(0) },
    ): Pair<ModuleResolver, ResolverHandles> {
        val activeConnectors = MutableStateFlow(connectors)
        val events = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 16)
        val scope = TestScope(UnconfinedTestDispatcher())
        val resolver = ModuleResolver(
            activeConnectors = activeConnectors,
            syncEvents = events,
            scope = scope,
            clock = clockSource,
        )
        return resolver to ResolverHandles(activeConnectors, events, scope)
    }

    private data class ResolverHandles(
        val activeConnectors: MutableStateFlow<List<OctiServerConnector>>,
        val events: MutableSharedFlow<SyncEvent>,
        val scope: TestScope,
    )

    @Test
    fun `n=1 parity returns the single source's payload`() = runTest {
        val payload = byteArrayOf(1, 2, 3)
        val outcome = OctiServerHttpClient.ModuleReadResult.Ok(payload, etag = "e1", modifiedAt = Instant.fromEpochMilliseconds(1000))
        val alpha = connector(connectorAlphaId, listOf(outcome))
        val (resolver, _) = buildResolver(listOf(alpha.asConnector))

        val result = resolver.read(targetDeviceId, moduleId, setOf(connectorAlphaId))
        result.shouldBeInstanceOf<ModuleResolver.Result.Ok>()
        result.source shouldBe connectorAlphaId
        result.payload shouldBe payload
        result.etag shouldBe "e1"
        result.modifiedAt shouldBe Instant.fromEpochMilliseconds(1000)
        alpha.readCalls.get() shouldBe 1
    }

    @Test
    fun `n=2 newest modifiedAt wins`() = runTest {
        val olderPayload = byteArrayOf(0xAA.toByte())
        val newerPayload = byteArrayOf(0xBB.toByte())
        val older = connector(
            connectorAlphaId,
            listOf(OctiServerHttpClient.ModuleReadResult.Ok(olderPayload, etag = "old", modifiedAt = Instant.fromEpochMilliseconds(500))),
        )
        val newer = connector(
            connectorBravoId,
            listOf(OctiServerHttpClient.ModuleReadResult.Ok(newerPayload, etag = "new", modifiedAt = Instant.fromEpochMilliseconds(2000))),
        )
        val (resolver, _) = buildResolver(listOf(older.asConnector, newer.asConnector))

        val result = resolver.read(targetDeviceId, moduleId, setOf(connectorAlphaId, connectorBravoId))
        result.shouldBeInstanceOf<ModuleResolver.Result.Ok>()
        result.source shouldBe connectorBravoId
        result.payload shouldBe newerPayload
    }

    @Test
    fun `equal modifiedAt ties break on lex-smallest idString`() = runTest {
        val sameTime = Instant.fromEpochMilliseconds(1000)
        // alpha.idString < bravo.idString (lex)
        val alpha = connector(
            connectorAlphaId,
            listOf(OctiServerHttpClient.ModuleReadResult.Ok(byteArrayOf(1), etag = "a", modifiedAt = sameTime)),
        )
        val bravo = connector(
            connectorBravoId,
            listOf(OctiServerHttpClient.ModuleReadResult.Ok(byteArrayOf(2), etag = "b", modifiedAt = sameTime)),
        )
        val (resolver, _) = buildResolver(listOf(alpha.asConnector, bravo.asConnector))

        val result = resolver.read(targetDeviceId, moduleId, setOf(connectorAlphaId, connectorBravoId))
        result.shouldBeInstanceOf<ModuleResolver.Result.Ok>()
        // lex-smallest idString wins so the choice is stable across runs.
        result.source shouldBe connectorAlphaId
    }

    @Test
    fun `cache hit on second read avoids re-probe of all candidates`() = runTest {
        val outcome = OctiServerHttpClient.ModuleReadResult.Ok(byteArrayOf(1), etag = "e", modifiedAt = Instant.fromEpochMilliseconds(1000))
        // Same outcome on every call so we can verify call counts.
        val alpha = connector(connectorAlphaId, listOf(outcome, outcome, outcome))
        val bravo = connector(
            connectorBravoId,
            listOf(OctiServerHttpClient.ModuleReadResult.Ok(byteArrayOf(2), etag = "e2", modifiedAt = Instant.fromEpochMilliseconds(500))),
        )
        val (resolver, _) = buildResolver(listOf(alpha.asConnector, bravo.asConnector))

        // First read: fan-out probes both.
        val first = resolver.read(targetDeviceId, moduleId, setOf(connectorAlphaId, connectorBravoId))
        first.shouldBeInstanceOf<ModuleResolver.Result.Ok>()
        first.source shouldBe connectorAlphaId  // higher modifiedAt → alpha wins
        alpha.readCalls.get() shouldBe 1
        bravo.readCalls.get() shouldBe 1

        // Second read should hit cache → only alpha (the cached winner) gets called. Bravo's
        // call count stays at 1.
        val second = resolver.read(targetDeviceId, moduleId, setOf(connectorAlphaId, connectorBravoId))
        second.shouldBeInstanceOf<ModuleResolver.Result.Ok>()
        second.source shouldBe connectorAlphaId
        alpha.readCalls.get() shouldBe 2
        bravo.readCalls.get() shouldBe 1
    }

    @Test
    fun `SyncEvent for the matching key evicts cache so next read re-probes`() = runTest {
        val alphaOk = OctiServerHttpClient.ModuleReadResult.Ok(byteArrayOf(1), etag = "e", modifiedAt = Instant.fromEpochMilliseconds(1000))
        val bravoOk = OctiServerHttpClient.ModuleReadResult.Ok(byteArrayOf(2), etag = "e2", modifiedAt = Instant.fromEpochMilliseconds(500))
        val alpha = connector(connectorAlphaId, List(4) { alphaOk })
        val bravo = connector(connectorBravoId, List(4) { bravoOk })
        val (resolver, handles) = buildResolver(listOf(alpha.asConnector, bravo.asConnector))

        resolver.read(targetDeviceId, moduleId, setOf(connectorAlphaId, connectorBravoId))
        alpha.readCalls.get() shouldBe 1
        bravo.readCalls.get() shouldBe 1

        // Emit a SyncEvent for the same (device, module). Cache evicts.
        handles.events.emit(
            SyncEvent(
                connectorId = connectorBravoId,
                event = EventPayload.Event.ModuleChanged(
                    deviceId = targetDeviceId.id,
                    moduleId = moduleId.id,
                    modifiedAt = "2026-05-20T19:00:00Z",
                    action = "WRITE",
                    sourceDeviceId = "peer-x",
                ),
            ),
        )

        // Next read should re-probe both connectors (cache was evicted).
        resolver.read(targetDeviceId, moduleId, setOf(connectorAlphaId, connectorBravoId))
        alpha.readCalls.get() shouldBe 2
        bravo.readCalls.get() shouldBe 2
    }

    @Test
    fun `connector removal from activeConnectors evicts cache entries that pointed at it`() = runTest {
        val alphaOk = OctiServerHttpClient.ModuleReadResult.Ok(byteArrayOf(1), etag = "e", modifiedAt = Instant.fromEpochMilliseconds(1000))
        val alpha = connector(connectorAlphaId, List(4) { alphaOk })
        val (resolver, handles) = buildResolver(listOf(alpha.asConnector))

        resolver.read(targetDeviceId, moduleId, setOf(connectorAlphaId))
        // Now drop alpha (unlink).
        handles.activeConnectors.value = emptyList()
        // Read again with the now-invalid candidate should return Error rather than try to use
        // the stale cached source.
        val result = resolver.read(targetDeviceId, moduleId, setOf(connectorAlphaId))
        result.shouldBeInstanceOf<ModuleResolver.Result.Error>()
    }

    @Test
    fun `cache TTL expiry triggers re-probe`() = runTest {
        var now = Instant.fromEpochMilliseconds(0)
        val outcome = OctiServerHttpClient.ModuleReadResult.Ok(byteArrayOf(1), etag = "e", modifiedAt = Instant.fromEpochMilliseconds(1000))
        // alpha has the higher modifiedAt → it's the cached winner; bravo gets probed on cold reads only.
        val alpha = connector(connectorAlphaId, List(4) { outcome })
        val bravo = connector(
            connectorBravoId,
            List(4) { OctiServerHttpClient.ModuleReadResult.Ok(byteArrayOf(2), etag = "x", modifiedAt = Instant.fromEpochMilliseconds(500)) },
        )
        val (resolver, _) = buildResolver(listOf(alpha.asConnector, bravo.asConnector)) { now }

        resolver.read(targetDeviceId, moduleId, setOf(connectorAlphaId, connectorBravoId))
        bravo.readCalls.get() shouldBe 1

        // Advance clock past TTL — cache entry expires.
        now = now + ModuleResolver.CACHE_TTL + 1.seconds

        resolver.read(targetDeviceId, moduleId, setOf(connectorAlphaId, connectorBravoId))
        bravo.readCalls.get() shouldBe 2  // re-probed
    }

    @Test
    fun `one NotFound + one Ok → Ok wins`() = runTest {
        val alpha = connector(connectorAlphaId, listOf(OctiServerHttpClient.ModuleReadResult.NotFound))
        val bravo = connector(
            connectorBravoId,
            listOf(OctiServerHttpClient.ModuleReadResult.Ok(byteArrayOf(2), etag = "e", modifiedAt = Instant.fromEpochMilliseconds(500))),
        )
        val (resolver, _) = buildResolver(listOf(alpha.asConnector, bravo.asConnector))

        val result = resolver.read(targetDeviceId, moduleId, setOf(connectorAlphaId, connectorBravoId))
        result.shouldBeInstanceOf<ModuleResolver.Result.Ok>()
        result.source shouldBe connectorBravoId
    }

    @Test
    fun `all sources NotFound → NotFound`() = runTest {
        val alpha = connector(connectorAlphaId, listOf(OctiServerHttpClient.ModuleReadResult.NotFound))
        val bravo = connector(connectorBravoId, listOf(OctiServerHttpClient.ModuleReadResult.NotFound))
        val (resolver, _) = buildResolver(listOf(alpha.asConnector, bravo.asConnector))

        resolver.read(targetDeviceId, moduleId, setOf(connectorAlphaId, connectorBravoId)) shouldBe
            ModuleResolver.Result.NotFound
    }

    @Test
    fun `all sources throw → first error propagated`() = runTest {
        val alphaError = RuntimeException("alpha down")
        val bravoError = RuntimeException("bravo down")
        val alpha = throwingConnector(connectorAlphaId, alphaError)
        val bravo = throwingConnector(connectorBravoId, bravoError)
        val (resolver, _) = buildResolver(listOf(alpha.asConnector, bravo.asConnector))

        val result = resolver.read(targetDeviceId, moduleId, setOf(connectorAlphaId, connectorBravoId))
        result.shouldBeInstanceOf<ModuleResolver.Result.Error>()
        // First-error semantics — could be either depending on probe order, but it must be one
        // of them, not some other exception.
        check(result.cause === alphaError || result.cause === bravoError) {
            "expected propagated cause to be one of the probe errors, got ${result.cause}"
        }
    }

    @Test
    fun `empty candidates returns Error`() = runTest {
        val (resolver, _) = buildResolver(emptyList())
        val result = resolver.read(targetDeviceId, moduleId, emptySet())
        result.shouldBeInstanceOf<ModuleResolver.Result.Error>()
    }
}
