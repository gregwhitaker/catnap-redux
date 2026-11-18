package consumer

import com.github.gregwhitaker.catnap.core.Catnap
import com.github.gregwhitaker.catnap.core.QueryDialect
import com.github.gregwhitaker.catnap.http.ProjectionService
import com.github.gregwhitaker.catnap.http.ProjectionSettings
import com.github.gregwhitaker.catnap.jackson2.Jackson2Json
import com.github.gregwhitaker.catnap.jackson3.Jackson3Json
import com.github.gregwhitaker.catnap.kotlinx.KotlinxJson
import com.github.gregwhitaker.catnap.kotlinx.KotlinxTreeAdapter
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class BindingsConsumerTest {
    @Test
    fun publishedHttpDefaultsEnableCatnapPredicates() {
        assertEquals(QueryDialect.CATNAP, ProjectionSettings.DEFAULT.dialect())
        val request =
            ProjectionService(ProjectionSettings.DEFAULT).bind(listOf("images(url)[size==medium]"))
        val json = Jackson3Json(tools.jackson.databind.json.JsonMapper.builder().build())
        val input =
            json.parse(
                """{"images":[{"url":"m.jpg","size":"medium"},{"url":"s.jpg","size":"small"}]}"""
            )
        assertEquals(
            json.parse("""{"images":[{"url":"m.jpg"}]}"""),
            request.evaluate(input, json.adapter()),
        )
    }

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
                QueryDialect.CATNAP to "price(amount)",
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

    @TestFactory
    fun catnapPredicatesFromPublishedCorpus(): List<DynamicTest> {
        val resource =
            javaClass.getResource("/com/github/gregwhitaker/catnap/conformance/v1/projection.json")
        assertNotNull(resource)
        assertEquals(
            "jar",
            resource!!.protocol,
            "Conformance data must come from the published testkit",
        )
        val jackson2 =
            Jackson2Json(com.fasterxml.jackson.databind.json.JsonMapper.builder().build())
        val jackson3 = Jackson3Json(tools.jackson.databind.json.JsonMapper.builder().build())
        val kotlin = KotlinxJson(Json)
        val fixtures = resource.openStream().use { jackson3.parse(it.readAllBytes()) }.path("cases")
        val tests = mutableListOf<DynamicTest>()
        for (fixture in fixtures) {
            for (variant in fixture.path("queries")) {
                if (variant.path("dialect").asString() != "CATNAP") continue
                val expression = variant.path("expression").asString()
                val input = fixture.path("input").toString()
                val expected = fixture.path("expected").toString()
                for (binding in listOf("jackson2", "jackson3", "kotlinx")) {
                    tests.add(
                        DynamicTest.dynamicTest(
                            "$binding:${fixture.path("id").asString()}:$expression"
                        ) {
                            val query = Catnap.compile(expression, QueryDialect.CATNAP)
                            when (binding) {
                                "jackson2" ->
                                    assertEquals(
                                        jackson2.parse(expected),
                                        query.evaluate(jackson2.parse(input), jackson2.adapter()),
                                    )
                                "jackson3" ->
                                    assertEquals(
                                        jackson3.parse(expected),
                                        query.evaluate(jackson3.parse(input), jackson3.adapter()),
                                    )
                                "kotlinx" ->
                                    assertEquals(
                                        kotlin.parse(expected),
                                        query.evaluate(kotlin.parse(input), KotlinxTreeAdapter),
                                    )
                            }
                        }
                    )
                }
            }
        }
        assertTrue(tests.isNotEmpty(), "The published testkit must contain CATNAP cases")
        return tests
    }
}
