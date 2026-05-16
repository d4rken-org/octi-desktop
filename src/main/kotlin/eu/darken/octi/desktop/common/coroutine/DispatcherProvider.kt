package eu.darken.octi.desktop.common.coroutine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing

/**
 * Mirror of `app-common`'s `DispatcherProvider`. Test code can swap in test dispatchers without
 * touching production wiring.
 *
 * `Main` is the Swing dispatcher on desktop — Compose Multiplatform's renderer expects it. The
 * Android app uses `Dispatchers.Main` (a different implementation under the hood); the abstraction
 * keeps callers source-compatible.
 */
@Suppress("PropertyName", "VariableNaming")
interface DispatcherProvider {
    val Default: CoroutineDispatcher get() = Dispatchers.Default
    val Main: CoroutineDispatcher get() = Dispatchers.Swing
    val MainImmediate: CoroutineDispatcher get() = Dispatchers.Swing
    val Unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
    val IO: CoroutineDispatcher get() = Dispatchers.IO
}

object DefaultDispatcherProvider : DispatcherProvider
