package com.github.gregwhitaker.catnap.examples.ktor

import com.github.gregwhitaker.catnap.testkit.probe.HttpProbe
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class SerializationHookTest {
    @Test
    fun configuredSerializationSurvivesAllResponseModes() = runBlocking {
        val server =
            embeddedServer(CIO, port = 0, module = Application::probeModule).start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            HttpProbe.verify("http://localhost:$port")
        } finally {
            server.stop(0, 1000)
        }
    }
}
