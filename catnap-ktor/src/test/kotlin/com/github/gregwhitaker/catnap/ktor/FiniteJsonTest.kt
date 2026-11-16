package com.github.gregwhitaker.catnap.ktor

import com.github.gregwhitaker.catnap.http.ProjectionService
import com.github.gregwhitaker.catnap.http.ProjectionSettings
import io.ktor.http.content.OutgoingContent
import io.ktor.server.config.MapApplicationConfig
import io.ktor.utils.io.*
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FiniteJsonTest {
    @Test
    fun nativeConfigurationUsesSharedDefaultsAndRejectsInvalidSettings() {
        assertEquals(ProjectionSettings.DEFAULT, CatnapConfiguration().createService().settings())
        assertThrows(IllegalArgumentException::class.java) {
            CatnapConfiguration(MapApplicationConfig("max-payload-bytes" to "0")).createService()
        }
    }

    @Test
    fun malformedScalarsAndDeepDocumentsFailBeforeProjectionEvenInOmittedFields() {
        val query = ProjectionService(ProjectionSettings.DEFAULT).bind(listOf("id"))
        listOf(
                "{\"id\":1,\"hidden\":NaN}",
                "{\"id\":1,\"hidden\":01}",
                "[".repeat(3000) + "0" + "]".repeat(3000),
            )
            .forEach { input ->
                assertThrows(Exception::class.java) {
                    FiniteJson.project(input.toByteArray(), query, Charsets.UTF_8)
                }
            }
    }

    @Test
    fun readChannelIsCancelledOnOverflow() = runBlocking {
        var cancelled = false
        val original = ByteReadChannel("x".repeat(100).toByteArray())
        val source =
            object : ByteReadChannel by original {
                override fun cancel(cause: Throwable?) {
                    cancelled = true
                    original.cancel(cause)
                }
            }
        val body =
            object : OutgoingContent.ReadChannelContent() {
                override fun readFrom() = source
            }
        val failure = runCatching { FiniteJson.capture(body, 10) }.exceptionOrNull()
        assertInstanceOf(IOException::class.java, failure)
        assertTrue(cancelled)
    }

    @Test
    fun cancellationStopsSuspendingWriters() = runBlocking {
        var finished = false
        val body =
            object : OutgoingContent.WriteChannelContent() {
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    try {
                        channel.writeStringUtf8("[")
                        channel.flush()
                        awaitCancellation()
                    } finally {
                        finished = true
                    }
                }
            }
        val failure =
            runCatching { withTimeout(100) { FiniteJson.capture(body, 100) } }.exceptionOrNull()
        assertInstanceOf(TimeoutCancellationException::class.java, failure)
        assertTrue(finished)
    }

    @Test
    fun readChannelFailuresAreNotTreatedAsTruncatedSuccess() = runBlocking {
        val channel = ByteChannel()
        channel.cancel(IOException("upstream failed"))
        val body =
            object : OutgoingContent.ReadChannelContent() {
                override fun readFrom() = channel
            }
        assertInstanceOf(
            IOException::class.java,
            runCatching { FiniteJson.capture(body, 100) }.exceptionOrNull(),
        )
        Unit
    }
}
