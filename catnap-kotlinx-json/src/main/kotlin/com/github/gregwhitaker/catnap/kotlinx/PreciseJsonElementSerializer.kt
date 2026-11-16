package com.github.gregwhitaker.catnap.kotlinx

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** Native tree encoding without the standard JsonElement serializer's Double coercion. */
@OptIn(ExperimentalSerializationApi::class)
object PreciseJsonElementSerializer : KSerializer<JsonElement> {
    override val descriptor = JsonElement.serializer().descriptor
    private val number = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    internal fun validateScalar(value: JsonElement) {
        if (value is JsonPrimitive && value !== JsonNull && !value.isString) {
            require(
                value.content == "true" || value.content == "false" || number.matches(value.content)
            ) {
                "Expected a materialized JSON scalar; raw objects, raw arrays, and nonfinite numbers must be parsed or normalized first"
            }
        }
    }

    override fun serialize(encoder: Encoder, value: JsonElement) {
        when (value) {
            is JsonObject -> MapSerializer(String.serializer(), this).serialize(encoder, value)
            is JsonArray -> ListSerializer(this).serialize(encoder, value)
            JsonNull -> encoder.encodeNull()
            is JsonPrimitive -> {
                validateScalar(value)
                when {
                    value.isString -> encoder.encodeString(value.content)
                    value.content == "true" -> encoder.encodeBoolean(true)
                    value.content == "false" -> encoder.encodeBoolean(false)
                    else ->
                        (encoder as JsonEncoder).encodeJsonElement(
                            JsonUnquotedLiteral(value.content)
                        )
                }
            }
        }
    }

    override fun deserialize(decoder: Decoder): JsonElement =
        JsonElement.serializer().deserialize(decoder)
}
