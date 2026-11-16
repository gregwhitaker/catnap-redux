plugins {
    id("catnap.publishing")
    id("catnap.testing")
}

// Fixture inspection is test-only; packaged conformance resources have no JSON-library dependency.
dependencies { testImplementation(libs.fixture.json) }
