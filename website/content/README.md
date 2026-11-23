# Catnap documentation

Catnap lets clients select fields and filter array items from the JSON your application produces. Start with a working example, then add the integration for your framework.

## Start here

1. [Quickstart](GETTING_STARTED.md): run the catalog example and request your first projection.
2. [Installation](INSTALLATION.md): configure Gradle or Maven and choose a module.
3. [Query syntax](QUERY_SPECIFICATION.md): select nested fields and filter images or variants with the default CATNAP dialect.

The HTTP default is `CATNAP` with the `fields` parameter. Google and LinkedIn remain configurable options. Standalone core compilation takes an explicit dialect.

## Choose your framework

| Framework | Integration guide | Catalog walkthrough |
|---|---|---|
| Spring MVC | [Spring](SPRING_INTEGRATION.md) | [MVC example](examples/spring-boot-mvc/README.md) |
| Spring WebFlux | [Spring](SPRING_INTEGRATION.md) | [WebFlux example](examples/spring-boot-webflux/README.md) |
| Micronaut | [Micronaut](MICRONAUT_INTEGRATION.md) | [Micronaut example](examples/micronaut/README.md) |
| Quarkus | [Quarkus](QUARKUS_INTEGRATION.md) | [Quarkus example](examples/quarkus/README.md) |
| Ktor | [Ktor](KTOR_INTEGRATION.md) | [Ktor example](examples/ktor/README.md) |

Each walkthrough covers product detail, paginated search, and image/variant filtering with the same seed data. The [examples overview](examples/README.md) also covers standalone projection and streaming endpoints.

## APIs and reference

| Guide | Use it to |
|---|---|
| [JSON bindings](JSON_INTEGRATIONS.md) | Project Jackson 2, Jackson 3, or Kotlin serialization trees |
| [Core API](CORE_API.md) | Compile reusable queries, configure limits, and supply a tree adapter |
| [Streaming](STREAMING.md) | Project NDJSON records and JSON payloads in SSE events |
| [Compatibility](COMPATIBILITY.md) | Check Java, framework, and serializer requirements |
| [Migration from Catnap 2](MIGRATION.md) | Update dependencies, APIs, configuration, and legacy predicates |
| [Performance and operating bounds](PERFORMANCE.md) | Budget payloads, evaluation work, caching, and stream resources |
| [Native images](NATIVE_IMAGES.md) | Configure and verify Quarkus and Micronaut native applications |

## Contribute and maintain

Start with the [contributor guide](https://github.com/gregwhitaker/catnap-redux/blob/main/AGENTS.md) and [build conventions](https://github.com/gregwhitaker/catnap-redux/blob/main/build-logic/README.md). The [extension guide](EXTENDING.md), [testkit](https://github.com/gregwhitaker/catnap-redux/blob/main/catnap-testkit/README.md), and [conformance corpus](https://github.com/gregwhitaker/catnap-redux/blob/main/catnap-testkit/src/main/resources/com/github/gregwhitaker/catnap/conformance/v1/README.md) describe how to add integrations.

For shipping Catnap, use the [release checklist](RELEASING.md) and [publication guide](PUBLICATION.md). The checklist separates current verification from [historical evidence](RELEASING.md#historical-evidence). Benchmark reproduction belongs in the [benchmark guide](https://github.com/gregwhitaker/catnap-redux/blob/main/benchmarks/README.md).
