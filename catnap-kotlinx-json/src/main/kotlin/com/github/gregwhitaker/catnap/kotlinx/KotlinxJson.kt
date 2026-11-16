package com.github.gregwhitaker.catnap.kotlinx

import com.github.gregwhitaker.catnap.core.CompiledQuery
import com.github.gregwhitaker.catnap.core.EvaluationOptions
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Uses the application's Json instance and explicit serializer, including generic type context. */
class KotlinxJson(val json: Json) {
    fun parse(document: String): JsonElement = json.parseToJsonElement(document)

    fun <T> toTree(value: T, serializer: SerializationStrategy<T>): JsonElement =
        json.encodeToJsonElement(serializer, value)

    @JvmOverloads
    fun <T> project(
        value: T,
        serializer: SerializationStrategy<T>,
        query: CompiledQuery,
        options: EvaluationOptions = EvaluationOptions.DEFAULT,
    ): JsonElement = query.evaluate(toTree(value, serializer), KotlinxTreeAdapter, options)

    fun write(document: JsonElement): String =
        json.encodeToString(PreciseJsonElementSerializer, document)
}
