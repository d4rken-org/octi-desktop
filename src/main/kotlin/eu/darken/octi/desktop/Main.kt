package eu.darken.octi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag

private val TAG = logTag("Main")

fun main() = application {
    Logging.install()
    log(TAG) { "Octi Desktop starting" }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Octi",
        state = rememberWindowState(width = 1024.dp, height = 720.dp),
    ) {
        OctiDesktopApp()
    }
}

@Composable
private fun OctiDesktopApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "Octi Desktop — scaffold")
            }
        }
    }
}
