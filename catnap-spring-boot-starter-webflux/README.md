# Catnap for Spring Boot WebFlux

Add client-selected JSON fields to annotated Spring WebFlux controllers. This starter installs the reactive adapter and Boot auto-configuration, preserving the application's configured JSON encoding and reactive request lifecycle.

The starter targets **Spring Boot 4 / Jackson 3 on Java 17+**. See the [compatibility matrix](../docs/COMPATIBILITY.md) for tested versions. Servlet applications should use the [MVC starter](../catnap-spring-boot-starter/README.md).

## Install

The development version is `3.0.0-SNAPSHOT`. Configure a repository containing that version using the [local installation instructions](../README.md#installation) or the [GitHub Packages guide](../docs/PUBLICATION.md#consume-packages-from-github).

Gradle Kotlin DSL:

```kotlin
dependencies {
    implementation(platform("com.github.gregwhitaker:catnap-bom:3.0.0-SNAPSHOT"))
    implementation("com.github.gregwhitaker:catnap-spring-boot-starter-webflux")
}
```

Maven, with the repository configured separately:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.github.gregwhitaker</groupId>
            <artifactId>catnap-bom</artifactId>
            <version>3.0.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
<dependencies>
    <dependency>
        <groupId>com.github.gregwhitaker</groupId>
        <artifactId>catnap-spring-boot-starter-webflux</artifactId>
    </dependency>
</dependencies>
```

This dependency-only module includes `catnap-spring-webflux`, `catnap-spring-boot-autoconfigure`, and the Boot WebFlux starter. Catnap's reactive dependencies do not pull in MVC or the Servlet API. API implementation and source documentation live in the adapter and auto-configuration modules.

## Try it

From the repository root:

```bash
./gradlew :examples:spring-boot-webflux:bootRun
```

In another terminal:

```bash
curl --get 'http://localhost:8080/catnap/quote' \
  --data-urlencode 'fields=payload(amount)'
```

```json
{"payload":{"amount":"EUR:12.50"}}
```

Selection uses the JSON field names produced by the application's configured encoder, including naming rules, views, ignored properties, and custom serializers. An omitted or ASCII-blank `fields` value preserves the normal response. Invalid queries and repeated query parameters produce HTTP 400 before the controller runs. Automatic finite-response binding applies to annotated controllers; functional streaming routes use the helpers below.

The default `CATNAP` dialect also filters array items. With the same example running:

```bash
curl --get 'http://localhost:8080/catnap/catalog' \
  --data-urlencode 'fields=payload(amount)[display_name==ACME]'
```

```json
{"payload":[{"amount":"EUR:12.50"}]}
```

The predicate reads the original serialized `display_name` before selecting `amount`. See the [RSQL subset](../docs/QUERY_SPECIFICATION.md#catnap-array-predicates) for equality, numeric comparisons, membership, and AND/OR conditions.

## Configure

These `application.properties` entries show common defaults:

```properties
catnap.enabled=true
catnap.parameter=fields
# CATNAP is the default; GOOGLE and LINKEDIN are also available.
catnap.dialect=CATNAP
catnap.max-payload-bytes=1048576
catnap.cache-capacity=256
```

Set `catnap.dialect=GOOGLE` for Google-style field selection such as `payload(amount)`, or `catnap.dialect=LINKEDIN` for `payload:(amount)`. These two dialects reject array predicates. Remove the override or set `catnap.dialect=CATNAP` to use the default. Use `@CatnapProjection(enabled = false)` from `com.github.gregwhitaker.catnap.http` on a controller or method to opt out. See the [full property reference](../docs/SPRING_INTEGRATION.md#configuration) for query, depth, selector, and evaluation limits.

## NDJSON and SSE

Inject `CatnapWebFluxStreams`, create an encoder per request, and return the `Mono<Void>` from `writeNdjson`, `writeEncodedNdjson`, or `writeSse` to Spring. Supply the application's configured serializer and full writer context to the encoder callback. Functional routes can use the same helpers through a `ServerResponse` body writer.

The helpers propagate demand and cancellation, bound each payload, and preserve native SSE metadata and flushing. Use a demand-aware source; application queues and native transport buffers have their own bounds. Arbitrary publisher or native stream writes are not automatically projected. See the [WebFlux streaming guide](../docs/SPRING_INTEGRATION.md#webflux-ndjson-and-sse) for examples and lifecycle behavior.

## Verify and explore

```bash
./gradlew :catnap-spring-webflux:check :catnap-spring-boot-autoconfigure:test
./gradlew :examples:spring-boot-webflux:test
```

Continue with the [WebFlux example source](../examples/spring-boot-webflux/src/main/java/com/github/gregwhitaker/catnap/examples/webflux), [runnable examples](../examples/README.md), or [full Spring integration guide](../docs/SPRING_INTEGRATION.md).
