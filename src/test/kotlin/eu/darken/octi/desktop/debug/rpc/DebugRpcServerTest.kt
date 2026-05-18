package eu.darken.octi.desktop.debug.rpc

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class DebugRpcServerTest {

    private val testToken = "test-token-xyz"

    private val fakeStateProvider: DebugStateSource = object : DebugStateSource {
        override fun snapshot(): JsonObject = buildJsonObject {
            put("test", JsonPrimitive("yes"))
        }
    }

    @Test
    fun `health endpoint is unauthenticated and returns ok`() = testApplication {
        application {
            debugRpcModule(
                token = testToken,
                stateProvider = fakeStateProvider,
                registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default),
                screenshot = { Result.success(ByteArray(0)) },
            )
        }
        val response = client.get("/dev/health")
        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        body["ok"]!!.jsonPrimitive.content shouldBe "true"
    }

    @Test
    fun `state without token returns 401`() = testApplication {
        application {
            debugRpcModule(
                token = testToken,
                stateProvider = fakeStateProvider,
                registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default),
                screenshot = { Result.success(ByteArray(0)) },
            )
        }
        val response = client.get("/dev/state")
        response.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `state with token returns snapshot`() = testApplication {
        application {
            debugRpcModule(
                token = testToken,
                stateProvider = fakeStateProvider,
                registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default),
                screenshot = { Result.success(ByteArray(0)) },
            )
        }
        val response = client.get("/dev/state") { header("X-Debug-Token", testToken) }
        response.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        body["test"]!!.jsonPrimitive.content shouldBe "yes"
    }

    @Test
    fun `unknown action returns 404 with structured error`() = testApplication {
        application {
            debugRpcModule(
                token = testToken,
                stateProvider = fakeStateProvider,
                registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default),
                screenshot = { Result.success(ByteArray(0)) },
            )
        }
        val response = client.post("/dev/action/no-such-thing") {
            header("X-Debug-Token", testToken)
            setBody("{}")
        }
        response.status shouldBe HttpStatusCode.NotFound
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        body["error"]!!.jsonPrimitive.content shouldBe "action_not_registered"
    }

    @Test
    fun `malformed json returns 400 invalid_params`() = testApplication {
        val registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default).also {
            it.register(DebugActionRegistry.Metadata("ping", "echo")) { JsonPrimitive("pong") }
        }
        application {
            debugRpcModule(
                token = testToken,
                stateProvider = fakeStateProvider,
                registry = registry,
                screenshot = { Result.success(ByteArray(0)) },
            )
        }
        val response = client.post("/dev/action/ping") {
            header("X-Debug-Token", testToken)
            setBody("not json")
        }
        response.status shouldBe HttpStatusCode.BadRequest
        Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!
            .jsonPrimitive.content shouldBe "invalid_params"
    }

    @Test
    fun `non-object json returns 400 invalid_params`() = testApplication {
        val registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default).also {
            it.register(DebugActionRegistry.Metadata("ping", "echo")) { JsonPrimitive("pong") }
        }
        application {
            debugRpcModule(
                token = testToken,
                stateProvider = fakeStateProvider,
                registry = registry,
                screenshot = { Result.success(ByteArray(0)) },
            )
        }
        val response = client.post("/dev/action/ping") {
            header("X-Debug-Token", testToken)
            setBody("[1,2,3]")
        }
        response.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `empty body is treated as empty object`() = testApplication {
        val registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default).also {
            it.register(DebugActionRegistry.Metadata("ping", "echo")) { JsonPrimitive("pong") }
        }
        application {
            debugRpcModule(
                token = testToken,
                stateProvider = fakeStateProvider,
                registry = registry,
                screenshot = { Result.success(ByteArray(0)) },
            )
        }
        val response = client.post("/dev/action/ping") {
            header("X-Debug-Token", testToken)
            // no setBody
        }
        response.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `body exceeding 64 KiB returns 413`() = testApplication {
        val registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default).also {
            it.register(DebugActionRegistry.Metadata("ping", "echo")) { JsonPrimitive("pong") }
        }
        application {
            debugRpcModule(
                token = testToken,
                stateProvider = fakeStateProvider,
                registry = registry,
                screenshot = { Result.success(ByteArray(0)) },
            )
        }
        val huge = buildString {
            append('{')
            append('"').append('k').append('"').append(':')
            append('"').append("x".repeat(70_000)).append('"')
            append('}')
        }
        val response = client.post("/dev/action/ping") {
            header("X-Debug-Token", testToken)
            setBody(huge)
        }
        response.status shouldBe HttpStatusCode.PayloadTooLarge
    }

    @Test
    fun `action exception returns 500 action_failed`() = testApplication {
        val registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default).also {
            it.register(DebugActionRegistry.Metadata("boom", "fails")) { error("nope") }
        }
        application {
            debugRpcModule(
                token = testToken,
                stateProvider = fakeStateProvider,
                registry = registry,
                screenshot = { Result.success(ByteArray(0)) },
            )
        }
        val response = client.post("/dev/action/boom") {
            header("X-Debug-Token", testToken)
            setBody("{}")
        }
        response.status shouldBe HttpStatusCode.InternalServerError
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        body["error"]!!.jsonPrimitive.content shouldBe "action_failed"
        body["message"]!!.jsonPrimitive.content shouldContain "nope"
    }

    @Test
    fun `action timeout returns 504`() = testApplication {
        val registry = DebugActionRegistry(
            actionTimeout = 30.milliseconds,
            uiDispatcher = Dispatchers.Default,
        ).also {
            it.register(DebugActionRegistry.Metadata("slow", "stalls")) {
                delay(5_000)
                JsonPrimitive("never")
            }
        }
        application {
            debugRpcModule(
                token = testToken,
                stateProvider = fakeStateProvider,
                registry = registry,
                screenshot = { Result.success(ByteArray(0)) },
            )
        }
        val response = client.post("/dev/action/slow") {
            header("X-Debug-Token", testToken)
            setBody("{}")
        }
        response.status shouldBe HttpStatusCode.GatewayTimeout
    }

    @Test
    fun `actions endpoint exposes metadata`() = testApplication {
        val registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default).also {
            it.register(
                DebugActionRegistry.Metadata(
                    name = "dashboard.openDevice",
                    description = "Navigate to a device's detail screen",
                    params = mapOf("deviceId" to "DeviceId (UUID)"),
                    example = """{"deviceId":"abc-123"}""",
                ),
            ) { JsonPrimitive("ok") }
        }
        application {
            debugRpcModule(
                token = testToken,
                stateProvider = fakeStateProvider,
                registry = registry,
                screenshot = { Result.success(ByteArray(0)) },
            )
        }
        val response = client.get("/dev/actions") { header("X-Debug-Token", testToken) }
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        body shouldContain "dashboard.openDevice"
        body shouldContain "Navigate to a device"
        body shouldContain "deviceId"
    }

    @Test
    fun `screenshot failure returns 503`() = testApplication {
        application {
            debugRpcModule(
                token = testToken,
                stateProvider = fakeStateProvider,
                registry = DebugActionRegistry(uiDispatcher = Dispatchers.Default),
                screenshot = { Result.failure(RuntimeException("headless")) },
            )
        }
        val response = client.get("/dev/screen.png") { header("X-Debug-Token", testToken) }
        response.status shouldBe HttpStatusCode.ServiceUnavailable
        Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!
            .jsonPrimitive.content shouldBe "screenshot_unavailable"
    }

}
