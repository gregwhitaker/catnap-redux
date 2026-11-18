package com.github.gregwhitaker.catnap.examples.ktor

import com.github.gregwhitaker.catnap.core.QueryDialect
import com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.*
import com.github.gregwhitaker.catnap.testkit.probe.StreamingResponseContract
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.BufferedReader
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(20)
class StreamingResponseTest {
    private val google =
        embeddedServer(CIO, port = 0) {
            exampleModule {
                dialect = QueryDialect.GOOGLE
                maxPayloadBytes = 4096
            }
        }
    private val linkedIn =
        embeddedServer(CIO, port = 0) {
            exampleModule {
                dialect = QueryDialect.LINKEDIN
                parameter = "select"
            }
        }
    private lateinit var googleBase: String
    private lateinit var linkedInBase: String
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    @BeforeAll
    fun start() = runBlocking {
        listOf(google, linkedIn).forEach { it.start(wait = false) }
        googleBase = "http://localhost:${google.engine.resolvedConnectors().first().port}"
        linkedInBase = "http://localhost:${linkedIn.engine.resolvedConnectors().first().port}"
    }

    @AfterAll
    fun stop() {
        listOf(google, linkedIn).forEach { it.stop(0, 1000) }
    }

    private fun equivalent(expected: String, actual: String) =
        Json.parseToJsonElement(expected) == Json.parseToJsonElement(actual)

    @TestFactory
    fun sharedNdjsonContract(): List<DynamicTest> =
        listOf(Triple(googleBase, "fields", false), Triple(linkedInBase, "select", true)).flatMap {
            (base, parameter, dialect) ->
            StreamingResponseContract.ndjsonCases(parameter, dialect).map { test ->
                DynamicTest.dynamicTest("$parameter ${test.path()}") {
                    StreamingResponseContract.verifyNdjson(base, test, ::equivalent)
                }
            }
        }

    @TestFactory
    fun sharedSseContract(): List<DynamicTest> =
        listOf(Triple(googleBase, "fields", false), Triple(linkedInBase, "select", true)).flatMap {
            (base, parameter, dialect) ->
            listOf(
                    null to "{\"payload\":{\"display_name\":\"ACME\",\"amount\":\"EUR:12.50\"}}",
                    (if (dialect) "payload:(amount,ignored,internal)"
                    else "payload(amount,ignored,internal)") to
                        "{\"payload\":{\"amount\":\"EUR:12.50\"}}",
                    "missing" to "{}",
                )
                .map { (query, expected) ->
                    DynamicTest.dynamicTest("$parameter SSE $query") {
                        StreamingResponseContract.verifySse(
                            base,
                            parameter,
                            query,
                            expected,
                            ::equivalent,
                        )
                    }
                }
        }

    @Test
    fun encodedNdjsonFramesSplitUnicodeCrLfAndMultipleRecordsPerChunk() {
        val response = get(googleBase, "stream/encoded?fields=label")
        assertEquals(200, response.statusCode())
        assertEquals(
            listOf("{\"label\":\"é😀\"}", "{\"label\":\"second\"}"),
            response.body().lines().filter { it.isNotEmpty() },
        )
    }

    @Test
    fun invalidQueriesFailBeforeStreamCommitment() {
        for (path in listOf("ndjson", "sse", "encoded")) {
            for (suffix in listOf(query("fields", "("), "?fields=id&fields=id")) {
                val response = get(googleBase, "stream/$path$suffix")
                assertEquals(400, response.statusCode(), response.body())
                assertFalse(response.body().contains("ACME"))
            }
        }
    }

    @Test
    fun invalidFirstPayloadsFailWithoutEmittingOriginalDataEvenWithoutAQuery() {
        for (fields in listOf("", "&fields=label")) {
            for (mode in
                listOf("blank", "bare-cr", "tail", "malformed", "utf8", "oversized", "depth")) {
                val response = get(googleBase, "stream/encoded?mode=$mode$fields")
                assertEquals(500, response.statusCode(), "$mode: ${response.body()}")
                assertFalse(response.body().contains("never leaked"))
            }
            for (mode in listOf("malformed", "oversized", "metadata")) {
                val response = get(googleBase, "stream/invalid-event?mode=$mode$fields")
                assertEquals(500, response.statusCode(), "$mode: ${response.body()}")
                assertFalse(response.body().contains("never leaked"))
            }
        }
    }

    @Test
    fun streamingHeadersAreInvalidatedOnlyWhenProjectionIsActive() {
        assertEquals(
            "\"original\"",
            get(googleBase, "stream/ndjson").headers().firstValue("ETag").orElseThrow(),
        )
        val projected = get(googleBase, "stream/ndjson?fields=missing")
        assertTrue(projected.headers().firstValue("ETag").isEmpty())
        assertEquals("preserved", projected.headers().firstValue("X-Application").orElseThrow())
    }

    @Test
    fun firstOutputArrivesBeforeCompletionAndQueriesAreIsolatedAcrossConnections() = runBlocking {
        for (protocol in listOf("ndjson", "sse")) {
            val a = StreamProbe()
            val b = StreamProbe()
            val keyA = UUID.randomUUID().toString()
            val keyB = UUID.randomUUID().toString()
            StreamProbe.connections[keyA] = a
            StreamProbe.connections[keyB] = b
            try {
                open("$protocol?key=$keyA&fields=id").body().bufferedReader().use { readerA ->
                    open("$protocol?key=$keyB&fields=other").body().bufferedReader().use { readerB
                        ->
                        assertEquals("{\"id\":0}", payload(readerA, protocol))
                        assertEquals("{\"other\":\"private\"}", payload(readerB, protocol))
                        assertFalse(a.finished.isCompleted)
                        assertFalse(b.finished.isCompleted)
                        a.release.complete(Unit)
                        b.release.complete(Unit)
                        assertEquals("{\"id\":1}", payload(readerA, protocol))
                        assertEquals("{\"other\":\"private\"}", payload(readerB, protocol))
                        assertNull(payload(readerA, protocol))
                        assertNull(payload(readerB, protocol))
                        withTimeout(5000) {
                            a.finished.await()
                            b.finished.await()
                        }
                    }
                }
            } finally {
                a.release.complete(Unit)
                b.release.complete(Unit)
                StreamProbe.connections.remove(keyA)
                StreamProbe.connections.remove(keyB)
            }
        }
    }

    @Test
    fun upstreamFailureBeforeAndAfterCommitmentClosesTheStream() = runBlocking {
        for (protocol in listOf("ndjson", "sse")) {
            for (mode in listOf("before", "serialize-before")) {
                val before = open("$protocol?mode=$mode&fields=id")
                before.body().use {
                    assertEquals(500, before.statusCode())
                    assertFalse(it.readAllBytes().toString(Charsets.UTF_8).contains("never leaked"))
                }
            }
            for (mode in listOf("after", "serialize", "parse", "limit", "depth")) {
                val probe = StreamProbe()
                val key = UUID.randomUUID().toString()
                StreamProbe.connections[key] = probe
                try {
                    val response = open("$protocol?mode=$mode&key=$key&fields=id")
                    response.body().bufferedReader().use { reader ->
                        assertEquals(200, response.statusCode())
                        assertEquals("{\"id\":0}", payload(reader, protocol))
                        probe.release.complete(Unit)
                        // A committed HTTP/1 stream may end cleanly or report a truncated transfer.
                        try {
                            assertNull(payload(reader, protocol))
                        } catch (failure: ExecutionException) {
                            assertInstanceOf(IOException::class.java, failure.cause)
                        }
                        withTimeout(5000) { probe.finished.await() }
                    }
                } finally {
                    probe.release.complete(Unit)
                    StreamProbe.connections.remove(key)
                }
            }
        }
    }

    @Test
    fun explicitlyConfiguredSseErrorEventEndsTheStreamWithoutLeakingTheFailedPayload() =
        runBlocking {
            for (mode in listOf("after", "serialize", "parse", "limit")) {
                val probe = StreamProbe()
                val key = UUID.randomUUID().toString()
                StreamProbe.connections[key] = probe
                try {
                    open("sse?mode=$mode&key=$key&fields=id&recover=true")
                        .body()
                        .bufferedReader()
                        .use { reader ->
                            assertEquals("{\"id\":0}", payload(reader, "sse"))
                            probe.release.complete(Unit)
                            assertEquals("temporary failure", payload(reader, "sse"))
                            assertNull(payload(reader, "sse"))
                            withTimeout(5000) { probe.finished.await() }
                        }
                } finally {
                    probe.release.complete(Unit)
                    StreamProbe.connections.remove(key)
                }
            }
        }

    @Test
    fun imperativeNativeSessionProjectsSendsAndPreservesHeartbeats() {
        val response = get(googleBase, "stream/send" + query("fields", "payload(amount)"))
        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("ETag").isEmpty())
        val events = StreamingResponseContract.events(response.body())
        assertEquals(2, events.size)
        assertTrue(equivalent("{\"payload\":{\"amount\":\"EUR:12.50\"}}", events[0].data()))
        assertEquals("42", events[0].id())
        assertEquals("quote", events[0].event())
        assertEquals(listOf("heartbeat"), events[1].comments())
        assertNull(events[1].data())
    }

    @Test
    fun slowConsumersBoundProductionAndDisconnectCancelsTheSource() = runBlocking {
        for (protocol in listOf("ndjson", "sse")) {
            val probe = StreamProbe()
            val key = UUID.randomUUID().toString()
            StreamProbe.connections[key] = probe
            try {
                val response = open("$protocol?mode=infinite&key=$key&fields=id,other")
                response.body().bufferedReader().use { reader ->
                    assertTrue(payload(reader, protocol)!!.contains("\"id\":0"))
                    // Let native transport buffers fill while the client intentionally stops
                    // reading.
                    var previous = -1
                    withTimeout(5000) {
                        while (true) {
                            delay(100)
                            val count = probe.emitted.get()
                            assertTrue(count < 10_000, "Unbounded production: $count")
                            if (count == previous) break
                            previous = count
                        }
                    }
                    val bound = probe.emitted.get()
                    delay(200)
                    assertEquals(
                        bound,
                        probe.emitted.get(),
                        "Production must stop at the transport's bound",
                    )
                    assertFalse(probe.finished.isCompleted)
                }
                withTimeout(5000) { probe.finished.await() }
            } finally {
                StreamProbe.connections.remove(key)
            }
        }
    }

    private fun open(path: String): HttpResponse<java.io.InputStream> =
        client
            .sendAsync(
                HttpRequest.newBuilder(URI.create("$googleBase/catnap/stream/lifecycle/$path"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            .get(5, TimeUnit.SECONDS)

    private fun payload(reader: BufferedReader, protocol: String): String? =
        CompletableFuture.supplyAsync {
                if (protocol == "ndjson") reader.readLine()
                else {
                    val lines = mutableListOf<String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        lines.add(line)
                    }
                    if (lines.isEmpty()) null
                    else
                        StreamingResponseContract.events(lines.joinToString("\n") + "\n\n")
                            .single()
                            .data()
                }
            }
            .get(5, TimeUnit.SECONDS)
}
