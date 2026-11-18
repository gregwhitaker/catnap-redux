package com.github.gregwhitaker.catnap.kotlinx

import com.github.gregwhitaker.catnap.core.JsonScalar
import com.github.gregwhitaker.catnap.core.JsonTreeAdapter
import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Native Kotlin serialization nodes; factories defensively own their container collections. */
object KotlinxTreeAdapter : JsonTreeAdapter<JsonElement> {
    override fun kind(node: JsonElement): JsonTreeAdapter.Kind =
        when (node) {
            is JsonObject -> JsonTreeAdapter.Kind.OBJECT
            is JsonArray -> JsonTreeAdapter.Kind.ARRAY
            else -> JsonTreeAdapter.Kind.SCALAR
        }

    override fun fields(node: JsonElement): Iterable<Map.Entry<String, JsonElement>> =
        node.jsonObject.entries

    override fun elements(node: JsonElement): Iterable<JsonElement> = node.jsonArray

    override fun `object`(fields: Map<String, JsonElement>): JsonElement =
        JsonObject(LinkedHashMap(fields))

    override fun array(elements: List<JsonElement>): JsonElement = JsonArray(ArrayList(elements))

    override fun scalarValue(node: JsonElement): JsonScalar {
        PreciseJsonElementSerializer.validateScalar(node)
        if (node === JsonNull) return JsonScalar.NULL
        val value = node.jsonPrimitive
        return JsonScalar(
            when {
                value.isString -> value.content
                value.content == "true" -> true
                value.content == "false" -> false
                else -> BigDecimal(value.content)
            }
        )
    }

    override fun copyScalar(node: JsonElement): JsonElement {
        PreciseJsonElementSerializer.validateScalar(node)
        return node
    }
}
