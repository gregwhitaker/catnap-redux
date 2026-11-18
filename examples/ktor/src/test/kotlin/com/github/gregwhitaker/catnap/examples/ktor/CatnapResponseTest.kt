package com.github.gregwhitaker.catnap.examples.ktor

import com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract
import com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.*
import com.github.gregwhitaker.catnap.testkit.probe.StreamingResponseContract
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatnapResponseTest {
    private val server = embeddedServer(CIO, port = 0) { exampleModule { parameter = "select" } }
    private lateinit var base: String

    @BeforeAll
    fun start() = runBlocking {
        server.start(wait = false)
        base = "http://localhost:${server.engine.resolvedConnectors().first().port}"
    }

    @AfterAll
    fun stop() {
        server.stop(0, 1000)
    }

    private fun sameJson(a: String, b: String) =
        Json.parseToJsonElement(a) == Json.parseToJsonElement(b)

    @TestFactory
    fun serializedPredicatesUseTheDefaultDialect() =
        catnapCases("select").map { test ->
            DynamicTest.dynamicTest(test.path()) {
                FiniteResponseContract.verify(base, test, ::sameJson)
            }
        }

    @Test
    fun malformedPredicatesFailBeforeWriting() {
        val response = get(base, "catalog" + query("select", "payload(amount)[display_name=ACME]"))
        assertEquals(400, response.statusCode(), response.body())
        assertFalse(response.body().contains("EUR:12.50"))
    }

    @Test
    fun streamingUsesDefaultDialect() {
        for (test in StreamingResponseContract.ndjsonCases("select", false)) {
            StreamingResponseContract.verifyNdjson(base, test, ::sameJson)
        }
        StreamingResponseContract.verifySse(
            base,
            "select",
            "payload(amount)",
            """{"payload":{"amount":"EUR:12.50"}}""",
            ::sameJson,
        )
    }
}
