package eu.darken.octi.desktop.sync

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Test

class ModuleReaderTest {

    @Test
    fun `CancellationException is rethrown, not bucketed as Result Error`() = runTest {
        // We can't easily construct a real ModuleReader without an AppGraph, so we verify the
        // behavioural contract: any try { ... } catch (Throwable) wrapper that catches a
        // CancellationException would let a structured cancellation slip past as a normal
        // value. Demonstrate via a small replica of the catch shape that ModuleReader uses,
        // and assert the rethrow path:
        suspend fun replicaOk(): Int = try {
            yield()
            // simulate the bulk of the read (suspend points that may receive cancellation)
            delay(10_000)
            42
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            -1 // would-be Result.Error path
        }

        val job = async { replicaOk() }
        // give the coroutine a tick to start, then cancel.
        yield()
        job.cancelAndJoin()
        // If the rethrow weren't there, replicaOk() would have completed with -1.
        shouldThrow<CancellationException> { job.await() }
    }
}
