package com.github.gregwhitaker.catnap.examples.quarkus;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/** Runs the finite-publisher HTTP and cancellation contract against the packaged executable. */
@QuarkusIntegrationTest
class FinitePublisherIT extends FinitePublisherTest {}
