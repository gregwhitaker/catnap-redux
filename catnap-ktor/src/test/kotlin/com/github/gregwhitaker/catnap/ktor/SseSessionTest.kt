package com.github.gregwhitaker.catnap.ktor

import com.github.gregwhitaker.catnap.http.stream.JsonStreamEncoder
import com.github.gregwhitaker.catnap.http.stream.StreamPayload
import io.ktor.server.application.ApplicationCall
import io.ktor.server.sse.ServerSSESession
import io.ktor.sse.ServerSentEvent
import io.ktor.sse.TypedServerSentEvent
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SseSessionTest {
    private class Session(private val emit: suspend (ServerSentEvent) -> Unit) : ServerSSESession {
        override val coroutineContext = EmptyCoroutineContext
        override val call: ApplicationCall
            get() = error("Encoding does not require request-thread state")

        var closed = false

        override suspend fun send(event: ServerSentEvent) = emit(event)

        override suspend fun close() {
            closed = true
        }
    }

    private fun encoder() =
        JsonStreamEncoder<String>(
            { value, output -> output.write(value.toByteArray()) },
            {
                if (it.toString(Charsets.UTF_8) == "invalid") throw IOException("private input")
                else "{}".toByteArray()
            },
            true,
            1024,
        )

    @Test
    fun concurrentPushesAreRejectedImmediatelyWithoutQueuingOrDiscarding() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var sent = 0
        val native = Session {
            entered.complete(Unit)
            release.await()
            sent++
        }
        val session = native.projecting(encoder())
        val first = launch { session.send(TypedServerSentEvent(StreamPayload.json("{}"))) }
        withTimeout(5000) { entered.await() }
        val failure =
            runCatching {
                    withTimeout(1000) {
                        session.send(TypedServerSentEvent(StreamPayload.json("{}")))
                    }
                }
                .exceptionOrNull()
        assertInstanceOf(IllegalStateException::class.java, failure)
        assertEquals(0, sent)
        release.complete(Unit)
        withTimeout(5000) { first.join() }
        assertEquals(1, sent)
        assertFalse(
            native.closed,
            "Overflow rejects the caller; the existing accepted send completes",
        )
    }

    @Test
    fun malformedPayloadClosesSessionAndNoLaterControlEventCanContinue() = runBlocking {
        var sent = 0
        val native = Session { sent++ }
        val session = native.projecting(encoder())
        val failure =
            runCatching { session.send(TypedServerSentEvent(StreamPayload.encodedJson("invalid"))) }
                .exceptionOrNull()
        assertInstanceOf(IOException::class.java, failure)
        assertEquals("JSON stream response failed", failure!!.message)
        assertTrue(native.closed)
        assertTrue(
            runCatching { session.send(TypedServerSentEvent(comments = "heartbeat")) }.isFailure
        )
        assertEquals(0, sent)
    }

    @Test
    fun cancellationPropagatesAndClosesTheNativeSession() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val native = Session {
            entered.complete(Unit)
            awaitCancellation()
        }
        val session = native.projecting(encoder())
        val sender = launch { session.send(TypedServerSentEvent(StreamPayload.json("{}"))) }
        withTimeout(5000) {
            entered.await()
            sender.cancelAndJoin()
        }
        assertTrue(sender.isCancelled)
        assertTrue(native.closed)
    }
}
