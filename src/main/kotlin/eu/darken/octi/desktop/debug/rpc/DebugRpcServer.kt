package eu.darken.octi.desktop.debug.rpc

import eu.darken.octi.desktop.common.log.Logging.Priority.INFO
import eu.darken.octi.desktop.common.log.Logging.Priority.WARN
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.modules.meta.DeviceMetadataProvider
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration

private val TAG = logTag("Debug", "RpcServer")

private const val LOOPBACK_HOST = "127.0.0.1"
private const val MAX_BODY_BYTES = 64L * 1024
private const val GRACE_PERIOD_MS = 500L
private const val TIMEOUT_MS = 2_000L

/**
 * Loopback-only HTTP server that exposes [DebugStateProvider] snapshots and lets a caller invoke
 * named verbs from [DebugActionRegistry]. Off unless [DebugRpcConfig] is provided.
 *
 * Security model:
 *
 * - Binds to `127.0.0.1` only (CIO's `host` parameter — never `0.0.0.0`).
 * - One-time random token, logged once at startup. Required on every authenticated route via
 *   `X-Debug-Token`. Token missing/wrong → `401`.
 * - No CORS configured. Custom-header auth means a browser drive-by from another origin can't
 *   forge a request without preflight, which it can't pass.
 *
 * Lifecycle:
 *
 * - [start] is one-shot; call once per instance. Returns the bound port. Throws on bind failure.
 * - [stop] gracefully shuts the engine down. Safe to call multiple times.
 */
class DebugRpcServer(
    private val config: DebugRpcConfig,
    private val stateProvider: DebugStateSource,
    private val registry: DebugActionRegistry,
    private val screenshot: () -> Result<ByteArray> = DebugScreenshot::capture,
    tokenGenerator: () -> String = ::generateToken,
) {

    val token: String = tokenGenerator()
    private var engine: EmbeddedServer<*, *>? = null

    fun start(): Int {
        val requestedPort = config.explicitPort ?: 0
        val server = embeddedServer(
            factory = CIO,
            host = LOOPBACK_HOST,
            port = requestedPort,
            module = {
                debugRpcModule(
                    token = token,
                    stateProvider = stateProvider,
                    registry = registry,
                    screenshot = screenshot,
                )
            },
        )
        try {
            server.start(wait = false)
        } catch (e: Throwable) {
            log(TAG, WARN, e) { "Failed to start debug RPC server on port=$requestedPort" }
            throw e
        }
        val actualPort = runBlocking { server.engine.resolvedConnectors() }
            .firstOrNull()?.port
            ?: error("CIO engine did not expose a resolved connector port")
        engine = server
        log(TAG, INFO) {
            "DEBUG_RPC url=http://$LOOPBACK_HOST:$actualPort token=$token"
        }
        return actualPort
    }

    fun stop() {
        engine?.stop(GRACE_PERIOD_MS, TIMEOUT_MS)
        engine = null
    }
}

/**
 * Module function exposed for both production and tests. Configures error mapping and registers
 * the debug routes against the provided dependencies.
 */
internal fun Application.debugRpcModule(
    token: String,
    stateProvider: DebugStateSource,
    registry: DebugActionRegistry,
    screenshot: () -> Result<ByteArray>,
) {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log(TAG, WARN, cause) { "Unhandled exception in debug RPC route" }
            call.respondJsonError(
                HttpStatusCode.InternalServerError,
                "internal_error",
                cause.message ?: cause.javaClass.simpleName,
            )
        }
    }
    routing {
        registerDebugRoutes(token, stateProvider, registry, screenshot)
    }
}

internal fun Routing.registerDebugRoutes(
    token: String,
    stateProvider: DebugStateSource,
    registry: DebugActionRegistry,
    screenshot: () -> Result<ByteArray>,
) {
    // Unauthenticated liveness probe — useful for "is the server up?" checks.
    get("/dev/health") {
        call.respondJson(
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("version", JsonPrimitive(DeviceMetadataProvider.APP_VERSION))
            },
        )
    }

    get("/dev/state") {
        if (!call.authorize(token)) return@get
        call.respondJson(stateProvider.snapshot())
    }

    get("/dev/actions") {
        if (!call.authorize(token)) return@get
        val payload = buildJsonObject {
            put(
                "actions",
                buildJsonArray {
                    registry.list().forEach { metadata ->
                        add(
                            buildJsonObject {
                                put("name", JsonPrimitive(metadata.name))
                                put("description", JsonPrimitive(metadata.description))
                                put(
                                    "params",
                                    buildJsonObject {
                                        metadata.params.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                                    },
                                )
                                put("example", JsonPrimitive(metadata.example))
                            },
                        )
                    }
                },
            )
        }
        call.respondJson(payload)
    }

    post("/dev/action/{name}") {
        if (!call.authorize(token)) return@post
        val name = call.parameters["name"]
            ?: return@post call.respondJsonError(
                HttpStatusCode.BadRequest,
                "invalid_action_name",
                "action name missing",
            )
        val bodyText = call.receiveLimited(MAX_BODY_BYTES)
            ?: return@post call.respondJsonError(
                HttpStatusCode.PayloadTooLarge,
                "payload_too_large",
                "request body exceeds 64 KiB",
            )
        val params = parseParams(bodyText)
            ?: return@post call.respondJsonError(
                HttpStatusCode.BadRequest,
                "invalid_params",
                "request body must be a JSON object",
            )
        when (val result = registry.invoke(name, params)) {
            is DebugActionRegistry.InvokeResult.Ok ->
                call.respondJson(buildJsonObject { put("result", result.result) })

            is DebugActionRegistry.InvokeResult.NotFound ->
                call.respondJsonError(
                    HttpStatusCode.NotFound,
                    "action_not_registered",
                    "no action named ${result.name}",
                )

            is DebugActionRegistry.InvokeResult.Timeout ->
                call.respondJsonError(
                    HttpStatusCode.GatewayTimeout,
                    "action_timeout",
                    "action ${result.name} exceeded ${result.after.formatHuman()}",
                )

            is DebugActionRegistry.InvokeResult.Failed -> {
                log(TAG, WARN, result.cause) { "Action ${result.name} failed" }
                call.respondJsonError(
                    HttpStatusCode.InternalServerError,
                    "action_failed",
                    result.cause.message ?: result.cause.javaClass.simpleName,
                )
            }
        }
    }

    get("/dev/screen.png") {
        if (!call.authorize(token)) return@get
        screenshot().fold(
            onSuccess = { bytes ->
                call.respondBytes(bytes, contentType = ContentType.Image.PNG)
            },
            onFailure = { cause ->
                call.respondJsonError(
                    HttpStatusCode.ServiceUnavailable,
                    "screenshot_unavailable",
                    cause.message ?: cause.javaClass.simpleName,
                )
            },
        )
    }
}

private suspend fun ApplicationCall.authorize(expected: String): Boolean {
    val provided = request.headers["X-Debug-Token"]
    if (provided == expected) return true
    respondJsonError(HttpStatusCode.Unauthorized, "unauthorized", "missing or invalid X-Debug-Token")
    return false
}

private suspend fun ApplicationCall.respondJson(payload: JsonObject) {
    respondText(payload.toString(), contentType = ContentType.Application.Json)
}

private suspend fun ApplicationCall.respondJsonError(
    status: HttpStatusCode,
    code: String,
    message: String,
) {
    val payload = buildJsonObject {
        put("error", JsonPrimitive(code))
        put("message", JsonPrimitive(message))
    }
    respondText(payload.toString(), contentType = ContentType.Application.Json, status = status)
}

private suspend fun ApplicationCall.receiveLimited(maxBytes: Long): String? {
    val channel = receiveChannel()
    val baos = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (!channel.isClosedForRead) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read <= 0) break
        if (baos.size() + read > maxBytes) return null
        baos.write(buffer, 0, read)
    }
    return baos.toString(Charsets.UTF_8)
}

private fun parseParams(text: String): JsonObject? {
    if (text.isBlank()) return JsonObject(emptyMap())
    return try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: Throwable) {
        null
    }
}

private fun Duration.formatHuman(): String = "${this.inWholeMilliseconds} ms"

private fun generateToken(): String {
    val bytes = ByteArray(24)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
