package eu.darken.octi.desktop.common.flow

import eu.darken.octi.desktop.common.log.Logging.Priority.ERROR
import eu.darken.octi.desktop.common.log.Logging.Priority.VERBOSE
import eu.darken.octi.desktop.common.log.asLog
import eu.darken.octi.desktop.common.log.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformWhile

/**
 * Ported from `app-common/.../FlowExtensions.kt`. Same semantics so copied call sites Just Work.
 */
fun <T : Any> Flow<T>.shareLatest(
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.WhileSubscribed(replayExpirationMillis = 0L),
    tag: String? = null,
): Flow<T> = this
    .onStart { if (tag != null) log(tag, VERBOSE) { "shareLatest(...) start" } }
    .onEach { if (tag != null) log(tag, VERBOSE) { "shareLatest(...) emission: $it" } }
    .onCompletion { if (tag != null) log(tag, VERBOSE) { "shareLatest(...) completed." } }
    .catch {
        if (tag != null) log(tag, VERBOSE) { "shareLatest(...) catch(): ${it.asLog()}" }
        throw it
    }
    .stateIn(scope = scope, started = started, initialValue = null)
    .filterNotNull()

fun <T : Any?> Flow<T>.replayingShare(scope: CoroutineScope): Flow<T> = this.shareIn(
    scope = scope,
    replay = 1,
    started = SharingStarted.WhileSubscribed(replayExpirationMillis = 0L),
)

fun <T> Flow<T>.withPrevious(): Flow<Pair<T?, T>> = this
    .scan(Pair<T?, T?>(null, null)) { previous, current -> Pair(previous.second, current) }
    .drop(1)
    .map {
        @Suppress("UNCHECKED_CAST")
        it as Pair<T?, T>
    }

fun <T> Flow<T>.onError(block: suspend (Throwable) -> Unit): Flow<T> = this.catch {
    block(it)
    throw it
}

fun <T> Flow<T>.takeUntilAfter(predicate: suspend (T) -> Boolean): Flow<T> = transformWhile {
    val fulfilled = predicate(it)
    emit(it)
    !fulfilled
}

fun <T> Flow<T>.setupCommonEventHandlers(
    tag: String,
    logValues: Boolean = true,
    identifier: () -> String,
): Flow<T> = this
    .onStart { log(tag, VERBOSE) { "${identifier()}.onStart()" } }
    .let { flow ->
        if (logValues) flow.onEach { log(tag, VERBOSE) { "${identifier()}.onEach(): $it" } } else flow
    }
    .onCompletion { log(tag, VERBOSE) { "${identifier()}.onCompletion()" } }
    .catch {
        if (it is CancellationException || it.cause is CancellationException) {
            log(tag, VERBOSE) { "${identifier()} cancelled" }
        } else {
            log(tag, ERROR) { "${identifier()} failed: ${it.asLog()}" }
            throw it
        }
    }
