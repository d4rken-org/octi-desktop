package eu.darken.octi.desktop.debug.rpc

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class DebugActionRegistryTest {

    private val ping = DebugActionRegistry.Metadata(name = "ping", description = "echoes")

    @Test
    fun `unknown action returns NotFound`() = runTest {
        val registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default)
        val result = registry.invoke("nope", buildJsonObject {})
        result.shouldBeInstanceOf<DebugActionRegistry.InvokeResult.NotFound>()
        result.name shouldBe "nope"
    }

    @Test
    fun `registered action runs and returns Ok`() = runTest {
        val registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default)
        registry.register(ping) { JsonPrimitive("pong") }

        val result = registry.invoke("ping", buildJsonObject {})
        result.shouldBeInstanceOf<DebugActionRegistry.InvokeResult.Ok>()
        (result.result as JsonPrimitive).content shouldBe "pong"
    }

    @Test
    fun `stale Registration does not clobber fresh handler with same name`() = runTest {
        val registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default)
        val first = registry.register(ping) { JsonPrimitive("first") }
        // simulate hot remount registering the same name BEFORE the old DisposableEffect runs:
        registry.register(ping) { JsonPrimitive("second") }
        first.unregister() // stale unregister; must NOT remove the fresh handler.

        val result = registry.invoke("ping", buildJsonObject {})
        result.shouldBeInstanceOf<DebugActionRegistry.InvokeResult.Ok>()
        (result.result as JsonPrimitive).content shouldBe "second"
    }

    @Test
    fun `matching Registration unregisters the handler`() = runTest {
        val registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default)
        val handle = registry.register(ping) { JsonPrimitive("x") }
        handle.unregister()
        registry.invoke("ping", buildJsonObject {})
            .shouldBeInstanceOf<DebugActionRegistry.InvokeResult.NotFound>()
    }

    @Test
    fun `slow action returns Timeout`() = runTest {
        val registry = DebugActionRegistry(
            actionTimeout = 50.milliseconds,
            uiDispatcher = Dispatchers.Default,
        )
        registry.register(ping) {
            delay(5_000) // far over the 50ms cap
            JsonPrimitive("never")
        }
        val result = registry.invoke("ping", buildJsonObject {})
        result.shouldBeInstanceOf<DebugActionRegistry.InvokeResult.Timeout>()
    }

    @Test
    fun `handler exception becomes Failed`() = runTest {
        val registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default)
        registry.register(ping) { error("boom") }
        val result = registry.invoke("ping", buildJsonObject {})
        result.shouldBeInstanceOf<DebugActionRegistry.InvokeResult.Failed>()
        result.cause.message shouldBe "boom"
    }

    @Test
    fun `per-action Mutex serializes two concurrent invocations`() = runTest {
        val registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default)
        val firstCall = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var concurrent = 0
        var maxConcurrent = 0
        registry.register(ping) {
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            firstCall.complete(Unit)
            release.await()
            concurrent--
            JsonPrimitive("done")
        }
        val a = async { registry.invoke("ping", buildJsonObject {}) }
        firstCall.await()
        val b = async { registry.invoke("ping", buildJsonObject {}) }
        delay(50) // give b a chance to enter if the mutex weren't there
        release.complete(Unit)
        a.await().shouldBeInstanceOf<DebugActionRegistry.InvokeResult.Ok>()
        // b is now released too — by then the second call also needs the release signal, but
        // since release is already complete, awaiting it returns immediately.
        b.await()
        maxConcurrent shouldBe 1
    }

    @Test
    fun `list returns sorted metadata`() = runTest {
        val registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default)
        registry.register(DebugActionRegistry.Metadata(name = "zeta", description = "z")) {
            JsonPrimitive("z")
        }
        registry.register(DebugActionRegistry.Metadata(name = "alpha", description = "a")) {
            JsonPrimitive("a")
        }
        val names = registry.list().map { it.name }
        names shouldBe listOf("alpha", "zeta")
    }

    @Test
    fun `params are forwarded to the handler`() = runTest {
        val registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default)
        var observed: JsonObject? = null
        registry.register(ping) { params ->
            observed = params
            JsonPrimitive("ok")
        }
        registry.invoke("ping", buildJsonObject { put("k", JsonPrimitive("v")) })
        (observed!!["k"] as JsonPrimitive).content shouldBe "v"
    }
}
