package com.github.gregwhitaker.catnap.examples.quarkus;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/** Reuses the catalog HTTP contract against the packaged JVM or native executable. */
@QuarkusIntegrationTest
class CatalogResponseIT extends CatalogResponseTest {}
