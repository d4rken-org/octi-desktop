package eu.darken.octi.desktop.modules.meta

import eu.darken.octi.desktop.common.log.Logging.Priority.DEBUG
import eu.darken.octi.desktop.common.log.Logging.Priority.WARN
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.module.ModuleIds
import eu.darken.octi.desktop.protocol.modules.meta.MetaInfo
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.DeviceId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import eu.darken.octi.desktop.protocol.collections.toGzip
import okio.ByteString.Companion.toByteString
import java.lang.ProcessHandle
import java.net.InetAddress
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

private val TAG = logTag("Module", "Meta", "Writer")

/**
 * Periodically writes this desktop's [MetaInfo] to `/v1/module/eu.darken.octi.module.core.meta`
 * so peers can see us in the device list with a label, OS, uptime, and version.
 *
 * Schema follows app-main PR #306: `deviceType=DESKTOP`, generic `osType`/`osVersionName`, all
 * Android-only fields left null. Android peers without #306 see the payload as malformed and
 * skip the meta tile for this device — accepted transitional cost while #306 rolls out.
 *
 * Cadence: write on transition to a fresh active client (cold-start a peer can see us
 * immediately on link), then every [WRITE_INTERVAL]. We skip writes if the data hasn't changed
 * — saves rate-limit budget and avoids triggering a no-op WS broadcast across peers.
 */
class MetaWriter(private val graph: AppGraph) {

    private var lastWrittenPayload: ByteArray? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun start() {
        graph.activeClient
            .flatMapLatest<OctiServerHttpClient?, Unit> { client ->
                if (client == null) {
                    lastWrittenPayload = null
                    flowOf(Unit)
                } else {
                    writeLoop(client)
                }
            }
            .launchIn(graph.appScope)
    }

    private fun writeLoop(client: OctiServerHttpClient): Flow<Unit> = flow {
        while (true) {
            try {
                val info = buildMetaInfo()
                val credentials = graph.credentialsStore.load()
                if (credentials == null) {
                    log(TAG, WARN) { "No credentials loaded during meta write; skipping" }
                } else {
                    val crypto = PayloadEncryption(keySet = credentials.encryptionKeyset)
                    val plaintext = Serialization.json.encodeToString(MetaInfo.serializer(), info)
                        .toByteArray(Charsets.UTF_8)
                    if (plaintext.contentEquals(lastWrittenPayload)) {
                        log(TAG, DEBUG) { "Meta payload unchanged since last write; skipping" }
                    } else {
                        // Android wire format: gzip BEFORE encrypt, AAD = "${deviceId}:${moduleId}".
                        // See ModuleReader.buildAad for the rationale.
                        val aad = "${graph.deviceId.id}:${ModuleIds.META.id}".toByteArray(Charsets.UTF_8)
                        val gzipped = plaintext.toByteString().toGzip()
                        val ciphertext = crypto.encrypt(gzipped, aad).toByteArray()
                        client.writeModule(ModuleIds.META, ciphertext)
                        lastWrittenPayload = plaintext
                        log(TAG, DEBUG) { "Meta payload written (${plaintext.size}B plaintext, ${ciphertext.size}B ciphertext)" }
                    }
                }
            } catch (e: Throwable) {
                log(TAG, WARN, e) { "Meta write failed; will retry on next tick" }
            }
            emit(Unit)
            delay(WRITE_INTERVAL.inWholeMilliseconds)
        }
    }

    private fun buildMetaInfo(): MetaInfo = MetaInfo(
        deviceLabel = graph.settings.data.deviceLabel,
        deviceId = DeviceId(graph.deviceId.id),
        octiVersionName = DeviceMetadataProvider.APP_VERSION,
        octiGitSha = OCTI_GIT_SHA_PLACEHOLDER,
        deviceManufacturer = (System.getProperty("java.vendor")?.takeIf { it.isNotBlank() }
            ?: "JVM Desktop"),
        deviceName = hostnameOrUnknown(),
        deviceType = MetaInfo.DeviceType.DESKTOP,
        deviceBootedAt = processStartInstant(),
        // Generic OS fields (PR #306). os.name on the JVM is "Linux"/"Mac OS X"/"Windows 11"
        // etc.; os.version is the kernel/build version. Together they give Android peers enough
        // to render a sensible "Linux 6.8" or "macOS 14.4" label.
        osType = System.getProperty("os.name"),
        osVersionName = System.getProperty("os.version"),
        // Android-only fields stay null — non-Android clients have no meaningful value here.
    )

    private fun processStartInstant(): Instant {
        return try {
            val started = ProcessHandle.current().info().startInstant().orElse(null)
            started?.toKotlinInstant() ?: Clock.System.now()
        } catch (_: Exception) {
            Clock.System.now()
        }
    }

    private fun hostnameOrUnknown(): String = try {
        InetAddress.getLocalHost().hostName.takeIf { it.isNotBlank() } ?: "octi-desktop"
    } catch (_: Exception) {
        "octi-desktop"
    }

    companion object {
        private val WRITE_INTERVAL = 5.minutes

        // The Android source generates this from git via CommitHashValueSource at build time.
        // Desktop doesn't have that wiring yet — slot in a placeholder; replace when the
        // packaging phase (H) adds a similar BuildInfo generator.
        private const val OCTI_GIT_SHA_PLACEHOLDER = "desktop-dev"
    }
}
