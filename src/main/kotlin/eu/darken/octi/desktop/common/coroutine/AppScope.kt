package eu.darken.octi.desktop.common.coroutine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-wide [CoroutineScope]. Built once in [eu.darken.octi.desktop.Main] and threaded
 * through the manual DI graph.
 */
class AppScope(
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    fun dispatchers(): DispatcherProvider = dispatchers
}
