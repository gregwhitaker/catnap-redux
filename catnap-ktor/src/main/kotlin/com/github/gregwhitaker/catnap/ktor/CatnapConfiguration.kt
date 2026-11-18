package com.github.gregwhitaker.catnap.ktor

import com.github.gregwhitaker.catnap.core.CompileOptions
import com.github.gregwhitaker.catnap.core.EvaluationOptions
import com.github.gregwhitaker.catnap.core.QueryDialect
import com.github.gregwhitaker.catnap.http.ProjectionService
import com.github.gregwhitaker.catnap.http.ProjectionSettings
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig

/** Native Ktor configuration, overridable when installed on an application or route. */
class CatnapConfiguration(private val config: ApplicationConfig = MapApplicationConfig()) {
    private fun value(name: String): String? = config.propertyOrNull(name)?.getString()

    var enabled = value("enabled")?.toBooleanStrict() ?: true
    var parameter = value("parameter") ?: "fields"
    var dialect =
        value("dialect")?.let { QueryDialect.valueOf(it.uppercase()) } ?: QueryDialect.CATNAP
    var maxQueryLength = value("max-query-length")?.toInt() ?: 16384
    var maxQueryDepth = value("max-query-depth")?.toInt() ?: 64
    var maxSelectors = value("max-selectors")?.toInt() ?: 4096
    var maxJsonDepth = value("max-json-depth")?.toInt() ?: 128
    var maxWork = value("max-work")?.toLong() ?: 1_000_000L
    var cacheCapacity = value("cache-capacity")?.toInt() ?: 256
    var maxPayloadBytes = value("max-payload-bytes")?.toInt() ?: 1_048_576

    /**
     * Optional shared service; its immutable settings take precedence over the properties above.
     */
    var service: ProjectionService? = null

    internal fun createService() =
        service
            ?: ProjectionService(
                ProjectionSettings(
                    enabled,
                    parameter,
                    dialect,
                    CompileOptions(maxQueryLength, maxQueryDepth, maxSelectors),
                    EvaluationOptions(maxJsonDepth, maxWork),
                    cacheCapacity,
                    maxPayloadBytes,
                )
            )
}
