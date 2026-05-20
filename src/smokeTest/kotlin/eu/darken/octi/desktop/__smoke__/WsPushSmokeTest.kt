package eu.darken.octi.desktop.__smoke__

import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.protocol.octiserver.ws.EventPayload
import eu.darken.octi.desktop.protocol.serialization.Serialization
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class WsPushSmokeTest {

    @Test
    fun `WebSocket pushes ModuleChanged to a linked peer`() = smokeTest {
        SmokeFixture.withLinkedDevices { deviceA, deviceB ->
            val received = Channel<EventPayload.Event.ModuleChanged>(capacity = Channel.UNLIMITED)
            val session = deviceB.client.openWebSocketSession()
            try {
                coroutineScope {
                    val consumer = launch {
                        try {
                            for (frame in session.incoming) {
                                if (frame !is Frame.Text) continue
                                val text = frame.readText()
                                val payload = runCatching {
                                    Serialization.json.decodeFromString(EventPayload.serializer(), text)
                                }.getOrNull() ?: continue
                                for (event in payload.events) {
                                    if (event !is EventPayload.Event.ModuleChanged) continue
                                    if (event.moduleId != ModuleIds.META.id) continue
                                    if (event.deviceId != deviceA.deviceId.id) continue
                                    if (event.sourceDeviceId != deviceA.deviceId.id) continue
                                    received.send(event)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            // Smoke is asserting the happy-path frame; let the timeout below
                            // surface the failure instead of swallowing it as a thrown exception.
                        }
                    }

                    // The server registers the WS subscription synchronously during the upgrade;
                    // the brief sleep guards against a debounce window where a fast write could
                    // land before the subscription is fully wired into the per-account notifier.
                    delay(250)

                    deviceA.client.writeModule(
                        moduleId = ModuleIds.META,
                        payload = encryptModulePayload(
                            credentials = deviceA.credentials.encryptionKeyset,
                            ownerDeviceId = deviceA.deviceId.id,
                            moduleId = ModuleIds.META,
                            plaintextJson = """{"deviceLabel":"smoke-ws","note":"hello"}""",
                        ),
                    )

                    val event = withTimeout(15.seconds) { received.receive() }
                    event.moduleId shouldBe ModuleIds.META.id
                    event.deviceId shouldBe deviceA.deviceId.id
                    event.sourceDeviceId shouldBe deviceA.deviceId.id

                    consumer.cancel()
                }
            } finally {
                runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "smoke done")) }
                received.close()
            }
        }
    }
}
