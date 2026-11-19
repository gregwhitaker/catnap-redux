package com.github.gregwhitaker.catnap.examples.ktor

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.catalogRoutes(catalog: CatalogService) {
    route("/api/products") {
        get {
            call.respondCatalog {
                catalog.search(call.request.queryParameters.entries().associate { it.toPair() })
            }
        }
        get("/{id}") {
            call.respondCatalog { catalog.product(checkNotNull(call.parameters["id"])) }
        }
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.respondCatalog(load: () -> T) {
    try {
        respond(load())
    } catch (failure: CatalogException) {
        respond(HttpStatusCode.fromValue(failure.status), failure.error)
    }
}
