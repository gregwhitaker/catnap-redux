package com.github.gregwhitaker.catnap.examples.ktor

import com.github.gregwhitaker.catnap.testkit.probe.CatalogResponseContract
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogResponseTest {
    private val server = embeddedServer(CIO, port = 0) { exampleModule() }
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
    fun catalogOverHttpWithDefaultDialect() =
        CatalogResponseContract.cases().map { test ->
            DynamicTest.dynamicTest(test.name()) {
                CatalogResponseContract.verify(base, test, ::sameJson)
            }
        }

    @Test
    fun filteringDoesNotMutateTheCatalog() {
        CatalogResponseContract.verifySourceUnchanged(base, ::sameJson)
    }
}
