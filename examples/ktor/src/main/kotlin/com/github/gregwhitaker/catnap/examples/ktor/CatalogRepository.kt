package com.github.gregwhitaker.catnap.examples.ktor

import kotlinx.serialization.json.Json

/** Load the snapshot once, with the same Json configuration used by ContentNegotiation. */
class CatalogRepository(json: Json) {
    val products: List<CatalogProduct> =
        checkNotNull(
                javaClass.getResourceAsStream(
                    "/com/github/gregwhitaker/catnap/testkit/catalog/products.json"
                )
            ) {
                "Missing catalog seed data"
            }
            .bufferedReader(Charsets.UTF_8)
            .use { json.decodeFromString<List<CatalogProduct>>(it.readText()) }
            .sortedBy { it.id }
}
