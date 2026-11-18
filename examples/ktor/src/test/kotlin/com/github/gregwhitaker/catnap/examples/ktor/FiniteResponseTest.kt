package com.github.gregwhitaker.catnap.examples.ktor

import com.github.gregwhitaker.catnap.core.QueryDialect
import com.github.gregwhitaker.catnap.ktor.Catnap
import com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract
import com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.*
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.ContentConverter
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.util.reflect.TypeInfo
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.reflect.typeOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FiniteResponseTest {
    private val google =
        embeddedServer(CIO, port = 0) {
            exampleModule {
                dialect = QueryDialect.GOOGLE
                maxPayloadBytes = 4096
                maxQueryLength = 64
            }
        }
    private val linkedIn =
        embeddedServer(CIO, port = 0) {
            exampleModule {
                dialect = QueryDialect.LINKEDIN
                parameter = "select"
                cacheCapacity = 0
            }
        }
    private val disabled =
        embeddedServer(CIO, port = 0) {
            exampleModule {
                enabled = false
                maxPayloadBytes = 1
            }
        }
    private lateinit var googleBase: String
    private lateinit var linkedInBase: String
    private lateinit var disabledBase: String

    @BeforeAll
    fun start() = runBlocking {
        listOf(google, linkedIn, disabled).forEach { it.start(wait = false) }
        googleBase = "http://localhost:${google.engine.resolvedConnectors().first().port}"
        linkedInBase = "http://localhost:${linkedIn.engine.resolvedConnectors().first().port}"
        disabledBase = "http://localhost:${disabled.engine.resolvedConnectors().first().port}"
    }

    @AfterAll
    fun stop() {
        listOf(google, linkedIn, disabled).forEach { it.stop(0, 1000) }
    }

    @TestFactory
    fun sharedFiniteContract(): List<DynamicTest> =
        listOf(Triple(googleBase, "fields", false), Triple(linkedInBase, "select", true)).flatMap {
            (base, parameter, dialect) ->
            cases(parameter, dialect).map { test ->
                DynamicTest.dynamicTest("$parameter ${test.path()}") {
                    FiniteResponseContract.verify(base, test) { expected, actual ->
                        Json.parseToJsonElement(expected) == Json.parseToJsonElement(actual)
                    }
                }
            }
        }

    @Test
    fun declaredGenericTypesReachTheConfiguredSerializer() = runBlocking {
        for (dialect in QueryDialect.values()) {
            val receivedTypes = ConcurrentLinkedQueue<TypeInfo>()
            val nativeConverter = KotlinxSerializationConverter(projectionJson)
            val recordingConverter =
                object : ContentConverter by nativeConverter {
                    override suspend fun serialize(
                        contentType: ContentType,
                        charset: Charset,
                        typeInfo: TypeInfo,
                        value: Any?,
                    ): OutgoingContent? {
                        receivedTypes.add(typeInfo)
                        return nativeConverter.serialize(contentType, charset, typeInfo, value)
                    }
                }
            val server =
                embeddedServer(CIO, port = 0) {
                    install(ContentNegotiation) {
                        register(ContentType.Application.Json, recordingConverter)
                    }
                    routing {
                        install(Catnap) { this.dialect = dialect }
                        projectionRoutes()
                    }
                }
            try {
                server.start(wait = false)
                val base = "http://localhost:${server.engine.resolvedConnectors().first().port}"
                val selection =
                    if (dialect == QueryDialect.LINKEDIN) "payload:(amount)" else "payload(amount)"
                val responses =
                    listOf(
                        "quote" to typeOf<Envelope<ProjectionQuote>>(),
                        "quote-internal" to typeOf<Envelope<InternalQuote>>(),
                        "quotes" to typeOf<List<Envelope<ProjectionQuote>>>(),
                        "async" to typeOf<Envelope<ProjectionQuote>>(),
                    )
                for ((path, declaredType) in responses) {
                    for (suffix in listOf("", query("fields", selection))) {
                        receivedTypes.clear()
                        val response = get(base, path + suffix)
                        assertEquals(200, response.statusCode(), response.body())
                        assertTrue(response.body().contains("EUR:12.50"), response.body())
                        if (suffix.isEmpty()) {
                            assertTrue(response.body().contains("display_name"), response.body())
                        } else {
                            val item = """{"payload":{"amount":"EUR:12.50"}}"""
                            val expected = if (path == "quotes") "[$item,$item]" else item
                            assertEquals(
                                Json.parseToJsonElement(expected),
                                Json.parseToJsonElement(response.body()),
                            )
                        }
                        assertEquals(1, receivedTypes.size, "$dialect $path$suffix")
                        assertEquals(declaredType, receivedTypes.single().kotlinType)
                    }
                }
            } finally {
                server.stop(0, 1000)
            }
        }
    }

    @Test
    fun malformedDuplicateAndOversizedQueriesFailBeforeWriting() {
        listOf(query("fields", "payload("), "?fields=id&fields=id", query("fields", "a".repeat(65)))
            .forEach { suffix ->
                val response = get(googleBase, "quote$suffix")
                assertEquals(400, response.statusCode(), response.body())
                assertTrue(response.body().contains("Invalid partial response query"))
                assertFalse(response.body().contains("ACME"))
            }
    }

    @Test
    fun nonJsonAndControlResponsesKeepTheirNativePath() {
        val response = get(googleBase, "text?fields=id")
        assertEquals(200, response.statusCode())
        assertEquals("unchanged", response.body())
        assertEquals(204, get(googleBase, "empty?fields=id").statusCode())
    }

    @Test
    fun malformedJsonAndPayloadOverflowNeverFallBackToOriginalBody() {
        listOf("malformed", "oversized").forEach { path ->
            val response = get(googleBase, "$path?fields=id")
            assertEquals(500, response.statusCode(), response.body())
            assertFalse(response.body().contains("never leaked"))
            assertFalse(response.body().contains("xxxxx"))
        }
        assertEquals(200, get(googleBase, "oversized").statusCode())
        assertEquals("{\"secret\":\"never leaked\",", get(googleBase, "malformed").body())
    }

    @Test
    fun renamedParameterAndGlobalDisablementUseConfiguredSettings() {
        assertTrue(get(linkedInBase, "quote?fields=missing").body().contains("ACME"))
        assertEquals(
            400,
            get(linkedInBase, "quote" + query("select", "payload(amount)")).statusCode(),
        )
        val response = get(disabledBase, "quote" + query("fields", "("))
        assertEquals(200, response.statusCode(), response.body())
        assertTrue(response.body().contains("ACME"))
        assertTrue(response.body().contains("EUR:12.50"))
        assertFalse(response.body().contains("internal"))
    }

    @Test
    fun nativeReadWriteAndVendorJsonBodiesAreProjected() {
        listOf("read", "write", "vendor").forEach { path ->
            val response = get(googleBase, "$path?fields=id")
            assertEquals(200, response.statusCode(), response.body())
            assertEquals("{\"id\":1}", response.body())
        }
        assertTrue(
            get(googleBase, "vendor?fields=id")
                .headers()
                .firstValue("Content-Type")
                .orElseThrow()
                .startsWith("application/vnd.catnap+json")
        )
    }

    @Test
    fun finiteProducerStopsAtTheDocumentLimitBeforeCommitment() {
        val response = get(googleBase, "publisher?fields=id")
        assertEquals(500, response.statusCode(), response.body())
        assertFalse(response.body().contains("xxxxx"))
        assertTrue(PublisherProbe.finished)
        assertTrue(
            PublisherProbe.emitted.get() <= 5,
            "Read ${PublisherProbe.emitted.get()} source items",
        )
    }

    @Test
    fun missingQueryPreservesRepresentationHeaders() {
        val response = get(googleBase, "status")
        assertEquals(201, response.statusCode())
        assertTrue(response.body().contains("hidden"))
        assertEquals("\"original\"", response.headers().firstValue("ETag").orElseThrow())
        assertEquals(25, response.headers().firstValueAsLong("Content-Length").orElseThrow())
    }

    @Test
    fun charsetAndEscapedUnicodeSurviveProjection() {
        val response = get(googleBase, "charset?fields=label")
        assertEquals(200, response.statusCode(), response.body())
        assertEquals(
            Json.parseToJsonElement("{\"label\":\"😀\"}"),
            Json.parseToJsonElement(response.body()),
        )
        assertTrue(
            response
                .headers()
                .firstValue("Content-Type")
                .orElseThrow()
                .contains("ISO-8859-1", ignoreCase = true)
        )
    }
}
