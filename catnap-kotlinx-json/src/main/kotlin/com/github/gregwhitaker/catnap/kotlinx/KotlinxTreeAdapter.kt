package com.github.gregwhitaker.catnap.kotlinx

import com.github.gregwhitaker.catnap.core.JsonTreeAdapter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

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

    override fun copyScalar(node: JsonElement): JsonElement {
        PreciseJsonElementSerializer.validateScalar(node)
        return node
    }
}
