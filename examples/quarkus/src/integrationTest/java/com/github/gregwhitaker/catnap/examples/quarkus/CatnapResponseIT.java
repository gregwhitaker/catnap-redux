package com.github.gregwhitaker.catnap.examples.quarkus;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/** Runs the configured CATNAP dialect and parameter contract against the packaged executable. */
@QuarkusIntegrationTest
class CatnapResponseIT extends CatnapResponseTest {}
