# Catnap for Spring Boot MVC

Add client-selected JSON fields to annotated Spring MVC controllers. This starter installs the MVC adapter and Boot auto-configuration, so controllers can keep returning their normal domain objects, `ResponseEntity` values, and supported asynchronous responses.

The starter targets **Spring Boot 4 / Jackson 3 on Java 17+**. See the [compatibility matrix](../website/content/COMPATIBILITY.md) for tested versions. Reactive applications should use the [WebFlux starter](../catnap-spring-boot-starter-webflux/README.md).

## Install

Configure GitHub Packages for Gradle or Maven and choose a published BOM version. For a candidate ahead of publication, use artifacts built from the checkout. [Installation guide](https://gregwhitaker.github.io/catnap-redux/installation/)

Gradle Kotlin DSL:

```kotlin
dependencies {
    implementation(platform("com.github.gregwhitaker:catnap-bom:3.0.0-rc.1"))
    implementation("com.github.gregwhitaker:catnap-spring-boot-starter")
}
```

Maven, with the repository configured separately:

```xml
<properties>
    <catnap.version>3.0.0-rc.1</catnap.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.github.gregwhitaker</groupId>
            <artifactId>catnap-bom</artifactId>
            <version>${catnap.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
<dependencies>
    <dependency>
        <groupId>com.github.gregwhitaker</groupId>
        <artifactId>catnap-spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

This dependency-only module includes `catnap-spring-webmvc`, `catnap-spring-boot-autoconfigure`, and the Boot MVC starter. Catnap's MVC dependencies do not pull in WebFlux or Reactor. API implementation and source documentation live in the adapter and auto-configuration modules.

## Try it

From the repository root:

```bash
./gradlew :examples:spring-boot-mvc:bootRun
```

In another terminal:

```bash
curl --get 'http://localhost:8080/api/products/trail-pack-28' \
  --data-urlencode 'fields=id,name,price'
```

```json
{"id":"trail-pack-28","name":"Trail Pack 28L","price":{"amount":119.95,"currency":"USD"}}
```

Selection uses the JSON field names produced by the application's configured converter, including naming rules, views, ignored properties, and custom serializers. An omitted or ASCII-blank `fields` value preserves the normal response. Invalid queries and repeated query parameters produce HTTP 400 before the controller runs.

The default `CATNAP` dialect also filters array items. With the same example running:

```bash
curl --get 'http://localhost:8080/api/products/trail-pack-28' \
  --data-urlencode 'fields=images(url)[size==medium;format==webp]'
```

```json
{"images":[{"url":"https://cdn.example.com/catalog/trail-pack-28/front-medium.webp"}]}
```

The predicate reads the original serialized `display_name` before selecting `amount`. See the [RSQL subset](../website/content/QUERY_SPECIFICATION.md#catnap-array-predicates) for equality, numeric comparisons, membership, and AND/OR conditions.

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

Set `catnap.dialect=GOOGLE` for Google-style field selection such as `payload(amount)`, or `catnap.dialect=LINKEDIN` for `payload:(amount)`. These two dialects reject array predicates. Remove the override or set `catnap.dialect=CATNAP` to use the default. Use `@CatnapProjection(enabled = false)` from `com.github.gregwhitaker.catnap.http` on a controller or method to opt out. See the [full property reference](../website/content/SPRING_INTEGRATION.md#configuration) for query, depth, selector, and evaluation limits.

## NDJSON and SSE

Inject `CatnapMvcStreams` and use its explicit emitter helpers for streaming endpoints. They support imperative sends, Flow/Reactive Streams publishers, and owned NDJSON input streams. Create an encoder per request with the application's configured serializer, then return the emitter's native `response()` to Spring.

The helpers bound pending work and payloads, preserve native SSE metadata, and cancel upstream on termination. Bare emitters and publisher return values are not automatically projected. See the [MVC streaming guide](../website/content/SPRING_INTEGRATION.md#mvc-ndjson-and-sse) for send acknowledgements, executor limits, timeouts, and cleanup.

## Verify and explore

```bash
./gradlew :catnap-spring-webmvc:check :catnap-spring-boot-autoconfigure:test
./gradlew :examples:spring-boot-mvc:test
```

Continue with the [MVC example source](../examples/spring-boot-mvc/src/main/java/com/github/gregwhitaker/catnap/examples/mvc), [runnable examples](../website/content/examples/README.md), or [full Spring integration guide](../website/content/SPRING_INTEGRATION.md).
