package com.github.gregwhitaker.catnap.examples.ktor

import java.math.BigDecimal
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CatalogSerializationTest {
    @Test
    fun pricesKeepExactDecimalValuesWhenLoadingAndWriting() {
        val source = """{"amount":1234567890.1234567890123456789,"currency":"USD"}"""
        val price = projectionJson.decodeFromString<CatalogPrice>(source)
        assertEquals(BigDecimal("1234567890.1234567890123456789"), price.amount)
        assertEquals(source, projectionJson.encodeToString(price))
    }

    @Test
    fun pricesRequireJsonNumbers() {
        for (invalid in listOf("\"12.50\"", "true", "null", "[]")) {
            assertThrows(SerializationException::class.java) {
                projectionJson.decodeFromString<CatalogPrice>(
                    """{"amount":$invalid,"currency":"USD"}"""
                )
            }
        }
    }
}
