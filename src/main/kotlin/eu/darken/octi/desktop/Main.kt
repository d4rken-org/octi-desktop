package eu.darken.octi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.useResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.skia.Image as SkImage
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import eu.darken.octi.desktop.common.log.Logging.Priority.ERROR
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.debug.rpc.DebugRpcConfig
import eu.darken.octi.desktop.debug.rpc.DebugRpcServer
import eu.darken.octi.desktop.debug.rpc.DebugStateProvider
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.modules.meta.DeviceMetadataProvider
import eu.darken.octi.desktop.protocol.encryption.CryptoBootstrap
import eu.darken.octi.desktop.storage.keystore.KeystoreUnavailableException
import eu.darken.octi.desktop.ui.LocalAppGraph
import eu.darken.octi.desktop.ui.clipboard.ClipboardScreen
import eu.darken.octi.desktop.ui.dashboard.DashboardScreen
import eu.darken.octi.desktop.ui.files.FilesScreen
import eu.darken.octi.desktop.ui.linking.LinkingScreen
import eu.darken.octi.desktop.ui.nav.Screen
import eu.darken.octi.desktop.ui.settings.SettingsScreen
import eu.darken.octi.desktop.ui.startup.PassphrasePromptCanceledException
import eu.darken.octi.desktop.ui.startup.PassphrasePromptUnavailableException
import eu.darken.octi.desktop.ui.startup.StartupPassphrasePrompt
import eu.darken.octi.desktop.ui.theme.OctiTheme
import kotlin.system.exitProcess

private val TAG = logTag("Main")

fun main(args: Array<String>) {
    // `--version` / `-v`: print the version and exit 0. Used by release CI's per-OS smoke step
    // to confirm the packaged binary actually launches with its bundled JRE before publishing.
    if (args.any { it == "--version" || it == "-v" }) {
        println("Octi ${DeviceMetadataProvider.APP_VERSION}")
        exitProcess(0)
    }

    val parsedArgs = try {
        DebugRpcConfig.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("Invalid CLI argument: ${e.message}")
        exitProcess(2)
    }

    // Register Tink (incl. the AES-GCM-SIV Aead primitive) on the main thread before any
    // background coroutine touches PayloadEncryption. On an already-linked launch the writer +
    // dashboard loops fire concurrent encrypt/decrypt immediately on start(), and racing Tink's
    // first registration intermittently threw "No PrimitiveConstructor for AesGcmSivKey". Forcing
    // the (idempotent, class-init-serialized) bootstrap up front removes that race.
    CryptoBootstrap.ensureInitialized()

    val graph = try {
        createAppGraph()
    } catch (e: PassphrasePromptCanceledException) {
        System.err.println("Octi startup canceled.")
        exitProcess(0)
    } catch (e: PassphrasePromptUnavailableException) {
        System.err.println("Octi cannot ask for the fallback passphrase: ${e.message}")
        exitProcess(2)
    } catch (e: KeystoreUnavailableException) {
        System.err.println("Octi cannot unlock stored credentials: ${e.message}")
        exitProcess(2)
    }
    log(TAG) { "Octi Desktop ready (deviceId=${graph.deviceId.logLabel})" }
    graph.webSocketClient.start()
    graph.metaWriter.start()
    graph.clipboardSync.start()
    graph.fileShareRepo.start()
    graph.dashboardModuleRepo.start()

    val debugServer = parsedArgs.config?.let { config ->
        val server = DebugRpcServer(
            config = config,
            stateProvider = DebugStateProvider(graph),
            registry = graph.debugActions,
        )
        try {
            server.start()
        } catch (e: Throwable) {
            log(TAG, ERROR, e) { "Failed to start debug RPC server; aborting" }
            exitProcess(2)
        }
        server
    }

    // Load the window icon once at startup. Used for the title bar (Linux/Windows) and the
    // app switcher / cmd-tab list (macOS — though macOS prefers .icns from the .app bundle for
    // the actual dock icon; this is mainly for development runs via `./gradlew run`).
    val windowIcon = useResource("icons/Octi.png") { stream ->
        BitmapPainter(SkImage.makeFromEncoded(stream.readAllBytes()).toComposeImageBitmap())
    }

    application {
        Window(
            onCloseRequest = {
                debugServer?.stop()
                exitApplication()
            },
            title = "Octi ${DeviceMetadataProvider.APP_VERSION}",
            icon = windowIcon,
            state = rememberWindowState(width = 1024.dp, height = 720.dp),
        ) {
            CompositionLocalProvider(LocalAppGraph provides graph) {
                OctiDesktopApp()
            }
        }
    }
}

private fun createAppGraph(): AppGraph {
    var retryMessage: String? = null
    while (true) {
        var fallbackPassphrase: CharArray? = null
        var usedPassphraseFallback = false
        try {
            return AppGraph.create(
                passphrasePrompt = {
                    usedPassphraseFallback = true
                    StartupPassphrasePrompt.show(retryMessage).also { fallbackPassphrase = it }
                },
            )
        } catch (e: KeystoreUnavailableException) {
            fallbackPassphrase?.fill('\u0000')
            if (!usedPassphraseFallback) throw e
            retryMessage = "Passphrase incorrect or saved credentials could not be decrypted."
        } catch (e: Throwable) {
            fallbackPassphrase?.fill('\u0000')
            throw e
        }
    }
}

@Composable
private fun OctiDesktopApp() {
    val graph = LocalAppGraph.current
    val settings by graph.settings.flow.collectAsState()
    OctiTheme(themeMode = settings.themeMode) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val current by graph.navigator.current.collectAsState()
            ScreenRouter(screen = current)
        }
    }
}

@Composable
private fun ScreenRouter(screen: Screen) {
    when (screen) {
        Screen.Linking -> LinkingScreen()
        Screen.Dashboard -> DashboardScreen()
        Screen.Clipboard -> ClipboardScreen()
        Screen.Settings -> SettingsScreen()
        is Screen.Files -> FilesScreen(deviceId = screen.deviceId)
    }
}
