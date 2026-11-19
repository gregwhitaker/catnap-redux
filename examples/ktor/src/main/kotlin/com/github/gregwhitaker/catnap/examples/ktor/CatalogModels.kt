package com.github.gregwhitaker.catnap.examples.ktor

import java.math.BigDecimal
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class CatalogProduct(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val brand: String,
    val price: CatalogPrice,
    val images: List<CatalogImage>,
    val variants: List<CatalogVariant>,
    val tags: List<String>,
    val attributes: Map<String, String>,
)

@Serializable data class CatalogPrice(@Contextual val amount: BigDecimal, val currency: String)

@Serializable
data class CatalogImage(
    val url: String,
    val size: String,
    val format: String,
    val width: Int,
    val height: Int,
    val altText: String,
)

@Serializable
data class CatalogVariant(
    val sku: String,
    val color: String,
    val size: String,
    val price: CatalogPrice,
    val inStock: Boolean,
    val stock: Int,
)

@Serializable data class CatalogSearchResult(val items: List<CatalogProduct>, val page: CatalogPage)

@Serializable
data class CatalogPage(
    val number: Int,
    val size: Int,
    val totalItems: Int,
    val totalPages: Int,
    val hasNext: Boolean,
)

@Serializable data class CatalogError(val code: String, val message: String)
