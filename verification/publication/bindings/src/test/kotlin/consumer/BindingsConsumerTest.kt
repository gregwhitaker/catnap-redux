package consumer

import com.github.gregwhitaker.catnap.core.Catnap
import com.github.gregwhitaker.catnap.core.QueryDialect
import com.github.gregwhitaker.catnap.jackson2.Jackson2Json
import com.github.gregwhitaker.catnap.jackson3.Jackson3Json
import com.github.gregwhitaker.catnap.kotlinx.KotlinxJson
import com.github.gregwhitaker.catnap.kotlinx.KotlinxTreeAdapter
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BindingsConsumerTest {
    @Test
    fun projectsWithEveryPublishedJsonBinding() {
        val input =
            """{"name":"ACME","price":{"amount":12345678901234567890.125,"currency":"USD"}}"""
        val expected = """{"price":{"amount":12345678901234567890.125}}"""
        val jackson2 =
            Jackson2Json(com.fasterxml.jackson.databind.json.JsonMapper.builder().build())
        val jackson3 = Jackson3Json(tools.jackson.databind.json.JsonMapper.builder().build())
        val kotlin = KotlinxJson(Json)
        for ((dialect, fields) in
            listOf(
                QueryDialect.GOOGLE to "price(amount)",
                QueryDialect.LINKEDIN to "price:(amount)",
            )) {
            val query = Catnap.compile(fields, dialect)
            assertEquals(
                expected,
                jackson2.write(query.evaluate(jackson2.parse(input), jackson2.adapter())),
            )
            assertEquals(
                expected,
                jackson3.write(query.evaluate(jackson3.parse(input), jackson3.adapter())),
            )
            assertEquals(
                expected,
                kotlin.write(query.evaluate(kotlin.parse(input), KotlinxTreeAdapter)),
            )
        }
    }
}
