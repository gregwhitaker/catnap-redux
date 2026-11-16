package com.github.gregwhitaker.catnap.kotlinx

import com.github.gregwhitaker.catnap.core.Catnap
import com.github.gregwhitaker.catnap.core.QueryDialect
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class KotlinxBindingTest {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test
    fun preciseEncodingHonorsPrettyPrintingAndRejectsDeferredRawObjects() {
        val configured = KotlinxJson(Json { prettyPrint = true })
        val input = configured.parse("""{"n":123456789012345678901234567890,"b":false,"v":null}""")
        val output = configured.write(input)
        assertTrue(output.contains('\n'))
        assertTrue(output.contains("123456789012345678901234567890"))
        assertEquals(input, configured.parse(output))
        val raw = JsonUnquotedLiteral("""{"secret":1}""")
        assertThrows(com.github.gregwhitaker.catnap.core.EvaluationException::class.java) {
            Catnap.compile("missing", QueryDialect.GOOGLE).evaluate(raw, KotlinxTreeAdapter)
        }
    }

    private val binding = KotlinxJson(Json)

    @TestFactory
    fun conformance(): List<DynamicTest> =
        listOf("projection.json", "streams.json").flatMap { resource ->
            val raw =
                requireNotNull(
                        javaClass.getResourceAsStream(
                            "/com/github/gregwhitaker/catnap/conformance/v1/$resource"
                        )
                    )
                    .use { it.readBytes().toString(Charsets.UTF_8) }
            binding.parse(raw).jsonObject.getValue("cases").jsonArray.flatMap { entry ->
                val fixture = entry.jsonObject
                fixture.getValue("queries").jsonArray.map { variant ->
                    val query = variant.jsonObject
                    val expression = query.getValue("expression").jsonPrimitive.content
                    val dialect =
                        QueryDialect.valueOf(query.getValue("dialect").jsonPrimitive.content)
                    DynamicTest.dynamicTest(
                        "$resource:${fixture.getValue("id")}:$dialect:$expression"
                    ) {
                        val plan = Catnap.compile(expression, dialect)
                        val input = fixture.getValue("input")
                        val before = input.toString()
                        val result =
                            if (resource == "projection.json")
                                plan.evaluate(input, KotlinxTreeAdapter)
                            else
                                JsonArray(
                                    input.jsonArray.map { record ->
                                        if (
                                            fixture.getValue("format").jsonPrimitive.content ==
                                                "NDJSON"
                                        )
                                            plan.evaluate(record, KotlinxTreeAdapter)
                                        else {
                                            val event = record.jsonObject
                                            if (
                                                event.getValue("kind").jsonPrimitive.content ==
                                                    "json"
                                            )
                                                JsonObject(
                                                    event +
                                                        ("data" to
                                                            plan.evaluate(
                                                                event.getValue("data"),
                                                                KotlinxTreeAdapter,
                                                            ))
                                                )
                                            else event
                                        }
                                    }
                                )
                        assertEquals(fixture.getValue("expected"), result)
                        assertEquals(before, input.toString())
                    }
                }
            }
        }

    data class Money(val value: String)

    @Serializable
    data class Quote(
        @SerialName("display_name") val name: String,
        @Contextual val amount: Money,
        @Transient val ignored: String = "hidden",
    )

    @Serializable data class Envelope<T>(val payload: T)

    class MoneySerializer(private val currency: String) : KSerializer<Money> {
        override val descriptor = PrimitiveSerialDescriptor("Money", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: Money) =
            encoder.encodeString("$currency:${value.value}")

        override fun deserialize(decoder: Decoder) = Money(decoder.decodeString())
    }

    @Test
    fun configuredGenericSerializerKeepsApplicationRules() {
        val json = Json {
            serializersModule = SerializersModule { contextual(MoneySerializer("EUR")) }
        }
        val native = KotlinxJson(json)
        val serializer = Envelope.serializer(Quote.serializer())
        val value = Envelope(Quote("ACME", Money("12.50")))
        assertEquals(
            native.parse("""{"payload":{"display_name":"ACME","amount":"EUR:12.50"}}"""),
            native.toTree(value, serializer),
        )
        val query = Catnap.compile("payload:(amount,ignored)", QueryDialect.LINKEDIN)
        assertEquals(
            native.parse("""{"payload":{"amount":"EUR:12.50"}}"""),
            native.project(value, serializer, query),
        )
    }

    @Test
    fun mutableBackingCollectionsCannotChangeProjectedResults() {
        val backing = linkedMapOf<String, JsonElement>("value" to JsonPrimitive(1))
        val array = mutableListOf<JsonElement>(JsonObject(backing))
        val input = JsonObject(mapOf("items" to JsonArray(array)))
        val result = Catnap.compile("*", QueryDialect.GOOGLE).evaluate(input, KotlinxTreeAdapter)
        backing["value"] = JsonPrimitive(2)
        array.add(JsonNull)
        assertEquals(binding.parse("""{"items":[{"value":1}]}"""), result)
    }

    @Test
    fun numericLiteralsKeepExactPrecisionAndTypes() {
        val source =
            """{"n":0.123456789012345678901234567890123456789,"big":123456789012345678901234567890,"s":"123"}"""
        val result =
            Catnap.compile("n,big,s", QueryDialect.GOOGLE)
                .evaluate(binding.parse(source), KotlinxTreeAdapter)
        assertEquals(source, binding.write(result))
    }
}
