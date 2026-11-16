package com.github.gregwhitaker.catnap.ktor

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StreamFlowTest {
    @Test
    fun rendezvousBoundsProductionWhileTheConsumerIsSuspended() = runBlocking {
        val produced = AtomicInteger()
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val work = launch {
            withFirst(
                flow {
                    try {
                        repeat(100_000) {
                            produced.incrementAndGet()
                            emit(it)
                        }
                    } finally {
                        finished.complete(Unit)
                    }
                }
            ) { first, rest ->
                assertEquals(0, first)
                ready.complete(Unit)
                release.await()
                assertEquals(1, rest.receive())
            }
        }
        withTimeout(5000) { ready.await() }
        repeat(10) { yield() }
        assertEquals(2, produced.get(), "One sending item and one prepared item")
        release.complete(Unit)
        withTimeout(5000) {
            work.join()
            finished.await()
        }
        assertTrue(produced.get() <= 3)
    }

    @Test
    fun cancellationJoinsProducerCleanupAndDoesNotRetainTheConnection() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val work = launch {
            withFirst(
                flow {
                    try {
                        emit(1)
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            yield()
                            finished.complete(Unit)
                        }
                    }
                }
            ) { _, _ ->
                ready.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(5000) {
            ready.await()
            work.cancelAndJoin()
        }
        assertTrue(finished.isCompleted)
    }

    @Test
    fun failuresBeforeAndAfterTheFirstItemReachTheConsumer() = runBlocking {
        for (before in listOf(true, false)) {
            val failure = IOException("source")
            var seen: Int? = null
            try {
                withFirst(
                    flow<Int> {
                        if (!before) emit(1)
                        throw failure
                    }
                ) { first, rest ->
                    seen = first
                    for (item in rest) fail<Unit>("Unexpected item $item")
                }
                fail("Failure was swallowed")
            } catch (actual: IOException) {
                assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
            }
            assertEquals(if (before) null else 1, seen)
        }
    }
}
