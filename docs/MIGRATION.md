# Migrating from Catnap 2

Catnap 3 is a new major API. It keeps partial response field selection but evaluates the application's serialized JSON tree. Compiled selections are immutable and reusable; HTTP and streaming integrations live in separate modules. Existing Catnap 2 jars, configuration classes, and annotations are not binary-compatible with this implementation.

The legacy baseline is the original repository's [2.3.0 version declaration](https://github.com/gregwhitaker/catnap/blob/master/gradle.properties), [README](https://github.com/gregwhitaker/catnap/blob/master/README.md), and [Spring Boot example](https://github.com/gregwhitaker/catnap/blob/master/catnap-examples/catnap-examples-springboot/src/main/java/catnap/examples/springboot/WidgetApplication.java). The new development version is `3.0.0-SNAPSHOT`; no public release is implied by the examples below.

## Dependencies and runtime

Replace the legacy integration dependency and import `com.github.gregwhitaker:catnap-bom`:

| Catnap 2 integration | Catnap 3 choice |
|---|---|
| `catnap-springboot` | `catnap-spring-boot-starter` for MVC or `catnap-spring-boot-starter-webflux` for WebFlux |
| `catnap-spring` | `catnap-spring-webmvc`, with the explicit registration described in the Spring guide |
| `catnap-jaxrs` | `catnap-quarkus` for Quarkus REST; the first release has no generic Jersey/RESTEasy Classic adapter |
| Direct use of the old core/rendering classes | `catnap-core` with a native JSON binding (`catnap-jackson2`, `catnap-jackson3`, or `catnap-kotlinx-json`) |

Micronaut and Ktor use `catnap-micronaut` and `catnap-ktor`. Keep only the adapters used by your application. In particular, an MVC starter does not bring WebFlux or Reactor into the application.

```kotlin
dependencies {
    implementation(platform("com.github.gregwhitaker:catnap-bom:3.0.0-SNAPSHOT"))
    implementation("com.github.gregwhitaker:catnap-spring-boot-starter")
}
```

Core/general-purpose libraries require Java 17. The verified framework baselines are Boot 4.1.2 / Spring 7.0.10, Micronaut platform 5.2.6, Quarkus 3.40.1.2, and Ktor 3.6.1. Micronaut 5 requires Java 25. Existing Boot 1/2/3, Spring 4/5/6, and legacy JAX-RS applications need the corresponding framework migration before using these adapters. This project does not provide a compatibility layer for those versions. See [publication](PUBLICATION.md) for local artifact verification and [the adapter guides](../README.md#installation) for supported serialization paths.

## Spring configuration and annotations

Remove the old `@EnableCatnap` import and custom Catnap message converter registration when installing a new Boot starter. Boot auto-configuration registers the new adapter. Ordinary annotated controllers return their normal JSON domain object or response; an omitted `fields` parameter preserves the framework's normal serialization path.

The old `@CatnapResponseBody` annotation selected a value from a returned MVC model. The new projection layer does not select MVC model attributes or replace view rendering. Return the intended JSON value from a `@RestController`/`@ResponseBody` method. If the response needs a named envelope, construct that envelope in the returned DTO or serializer, then select its actual JSON fields.

Replace legacy endpoint disablement with `com.github.gregwhitaker.catnap.http.CatnapProjection`:

```java
@CatnapProjection(enabled = false)
@GetMapping("/unprojected")
public Quote unprojected() { return service.quote(); }
```

Controller-level settings are supported, with a method annotation taking precedence. `catnap.enabled=false` disables projection globally. It does not bypass bounds in explicit streaming helpers. See [Spring configuration](SPRING_INTEGRATION.md) for parameter names, query/cache limits, and MVC executor settings.

## Queries and response shape

The default parameter remains `fields`; the default dialect is `GOOGLE`. Configure `catnap.parameter` to rename it and `catnap.dialect=LINKEDIN` to use the other grammar. There is no automatic dialect detection.

| Selection | Google | LinkedIn |
|---|---|---|
| Name and list price | `name,prices(list)` | `name,prices:(list)` |
| URLs in every image | `images(url)` | `images:(url)` |
| A nested amount | `price/amount` or `price(amount)` | `price:(amount)` |

The legacy README's `images(url)[size=thumbnail]` predicate is not supported. Removing the predicate changes the result: `images(url)` returns URLs for **all** image elements. Move thumbnail filtering into the application's resource/query layer or expose a filtered resource before applying field selection. Do not silently rewrite predicate queries to field-only queries. Legacy JSONP callback wrapping is also outside the first-release scope; endpoints return JSON, NDJSON, or SSE.

Field names refer to the actual JSON representation, after naming policies, ignored properties, views, and custom serializers. Test renamed and custom-serialized fields against the configured mapper rather than Java property names. Projection cannot restore properties excluded by the native serializer.

Arrays preserve element order and count; missing fields are omitted; selected JSON nulls and primitive values remain present. An empty selected object stays `{}`, and selecting nothing from each array element yields `[{}, ...]`. Scalar and null root documents pass unchanged. A successful empty projection keeps its status rather than becoming HTTP 204. Review client assumptions about these shapes against the [query specification](QUERY_SPECIFICATION.md).

## Errors, limits, and streams

Duplicate parameters, invalid syntax, and query-compilation limits produce HTTP 400 before an enabled endpoint is invoked. Serialization, document-depth/work limits, and byte-limit failures produce an error before commitment; a committed stream terminates according to its adapter's policy. Original unprojected payloads are never used as an error fallback. Partial response validators, digests, lengths, and range metadata must describe the selected representation; adapters remove stale values. Ktor requires its documented deferred-header helper.

Finite JSON responses materialize one bounded document. An ordinary finite publisher becomes one JSON array only on the adapter's supported finite path; Quarkus provides an explicit `jsonArray` helper. A never-ending source must use NDJSON or SSE instead.

For streams, adopt the explicit APIs in the [Spring](SPRING_INTEGRATION.md), [Micronaut](MICRONAUT_INTEGRATION.md), [Quarkus](QUARKUS_INTEGRATION.md), or [Ktor](KTOR_INTEGRATION.md) guide. Supply the native serializer and its generic/view context to the stream encoder. Projection runs independently on each record or JSON event payload, with a fixed query for the connection. Mark JSON, encoded JSON, and intentional text explicitly. Event IDs, names, retry values, comments, and heartbeat envelopes remain outside the projection. Catnap does not add SSE replay storage or reconnection semantics.

Before switching an endpoint, compare its no-query JSON to the existing response, check both successful and invalid client queries, exercise configured serializers/views, and verify limits with realistic payloads. For streaming endpoints, also test early output, slow consumers, disconnect cleanup, and post-commit failures using the shared [extension contracts](EXTENDING.md).
