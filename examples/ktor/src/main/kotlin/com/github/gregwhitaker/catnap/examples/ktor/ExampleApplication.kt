package com.github.gregwhitaker.catnap.examples.ktor

import com.github.gregwhitaker.catnap.ktor.Catnap
import com.github.gregwhitaker.catnap.ktor.CatnapConfiguration
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE

fun Application.exampleModule(configureCatnap: CatnapConfiguration.() -> Unit = {}) {
    val catalog = CatalogService(CatalogRepository(projectionJson))
    install(ContentNegotiation) { json(projectionJson) }
    install(SSE)
    routing {
        install(Catnap, configureCatnap)
        catalogRoutes(catalog)
        projectionRoutes()
        streamingRoutes()
    }
}

fun main() {
    embeddedServer(CIO, port = 8080) { exampleModule() }.start(wait = true)
}
