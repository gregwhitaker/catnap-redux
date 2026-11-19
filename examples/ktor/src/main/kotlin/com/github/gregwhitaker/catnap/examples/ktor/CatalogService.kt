package com.github.gregwhitaker.catnap.examples.ktor

import java.util.Locale

/** Apply business filters and pagination before serialization and Catnap field selection. */
class CatalogService(private val repository: CatalogRepository) {
    fun product(id: String): CatalogProduct =
        repository.products.find { it.id == id }
            ?: throw CatalogException(404, "product_not_found", "No product with id: $id")

    fun search(parameters: Map<String, List<String>>): CatalogSearchResult {
        val page = integer(parameters, "page", 0, 0, Int.MAX_VALUE)
        val size = integer(parameters, "size", 3, 1, 20)
        val query =
            single(parameters, "q", "").trim { Character.isWhitespace(it) }.lowercase(Locale.ROOT)
        val category =
            single(parameters, "category", "")
                .trim { Character.isWhitespace(it) }
                .lowercase(Locale.ROOT)
        val matches =
            repository.products.filter { product ->
                (category.isEmpty() || product.category == category) &&
                    (query.isEmpty() || searchableText(product).contains(query))
            }
        // Use Long so a large valid page number cannot overflow into page zero.
        val start = page.toLong() * size
        val total = matches.size
        val items =
            if (start >= total) emptyList()
            else matches.subList(start.toInt(), minOf(start + size, total.toLong()).toInt())
        return CatalogSearchResult(
            items,
            CatalogPage(page, size, total, (total + size - 1) / size, start + size < total),
        )
    }

    private fun searchableText(product: CatalogProduct): String =
        (product.name +
                " " +
                product.description +
                " " +
                product.brand +
                " " +
                product.tags.joinToString(" "))
            .lowercase(Locale.ROOT)

    private fun single(
        parameters: Map<String, List<String>>,
        name: String,
        fallback: String,
    ): String {
        val values = parameters[name]
        if (values.isNullOrEmpty()) return fallback
        if (values.size != 1)
            throw CatalogException(400, "invalid_search", "$name must appear at most once")
        return values.single()
    }

    private fun integer(
        parameters: Map<String, List<String>>,
        name: String,
        fallback: Int,
        min: Int,
        max: Int,
    ): Int {
        val value = single(parameters, name, fallback.toString())
        val number = if (value.matches(Regex("[0-9]+"))) value.toIntOrNull() else null
        if (number == null || number !in min..max)
            throw CatalogException(
                400,
                "invalid_search",
                "$name must be an integer between $min and $max",
            )
        return number
    }
}

class CatalogException(val status: Int, code: String, message: String) : RuntimeException(message) {
    val error = CatalogError(code, message)
}
