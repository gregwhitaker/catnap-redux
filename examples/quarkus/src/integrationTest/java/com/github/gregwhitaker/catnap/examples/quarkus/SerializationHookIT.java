package com.github.gregwhitaker.catnap.examples.quarkus;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/** Runs the same HTTP contract against the packaged JVM or native executable. */
@QuarkusIntegrationTest
class SerializationHookIT extends SerializationHookTest {}
