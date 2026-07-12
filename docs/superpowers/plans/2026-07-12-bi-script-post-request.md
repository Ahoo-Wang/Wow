# BI Script POST Request Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `GET /wow/bi/script` with a required-body `POST` endpoint whose JSON request can override every server-configured `BiScriptOptions` value before generating SQL.

**Architecture:** `wow-openapi` owns a typed, transport-only BI request contract shared by OpenAPI rendering and WebFlux decoding. `wow-webflux` maps that nullable request over the base `BiScriptOptions` supplied by `wow-spring-boot-starter`, while `wow-bi` remains unchanged and receives only complete validated domain options.

**Tech Stack:** Kotlin 2.4.0, JVM 17, Spring WebFlux 7.0.8, Spring Boot 4.0.6, Jackson, Reactor, JUnit Jupiter, MockK, FluentAssert, Swagger/OpenAPI, Gradle, VitePress.

## Global Constraints

- `POST /wow/bi/script` is the only BI script HTTP route; no GET compatibility route is retained.
- The JSON request body is required, while `{}` is valid and retains all server options.
- Every current `BiScriptOptions` property is available as an optional request override.
- Request topology is nested, requires `mode` when present, and rejects `cluster` in `STANDALONE` mode.
- Partial Cluster fields inherit from a Cluster base; when the base is Standalone they inherit from `ClickHouseTopology.Cluster()` defaults.
- Successful responses remain `200 application/sql` and contain SQL only.
- Missing or malformed bodies and invalid option values propagate through existing WebFlux error handling as `400`; unsupported media types produce `415`.
- A GET request receives `404` because no GET route is registered; do not change generic router behavior.
- `wow-bi` must not depend on HTTP, OpenAPI, Spring, or the request DTO.
- Do not duplicate the request schema manually in `wow-webflux`.
- Do not modify or stage `docs/superpowers/specs/2026-07-10-wow-bi-clean-architecture-design.md`.
- Follow RED, GREEN, and REFACTOR for every behavior change.

---

## File Map

- Create `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/bi/BiScriptRequest.kt`: typed JSON/OpenAPI request contract only.
- Create `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/contributor/global/GenerateBIScriptRouteContributorTest.kt`: focused POST route contract and typed schema assertions.
- Modify `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/global/GenerateBIScriptRouteContributor.kt`: switch to POST, require typed JSON body, and declare bad request.
- Create `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/global/BiScriptRequestMapper.kt`: pure request-over-base mapping.
- Create `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/bi/BiScriptRequestMapperTest.kt`: exhaustive merge and topology validation contracts.
- Modify `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/global/GenerateBIScriptHandlerFunction.kt`: decode the required request and generate with merged options.
- Modify `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/bi/GenerateBIScriptHandlerFunctionTest.kt`: request-driven SQL, empty-body failure, and diagnostics behavior.
- Modify `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt`: POST routing, property precedence, request precedence, and removed GET behavior.
- Modify `wow-openapi/src/test/resources/openapi/example-domain-contract.snapshot.json`: POST route contract snapshot.
- Modify `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`: typed request schema and POST operation snapshot.
- Modify `documentation/docs/en/guide/bi.md`, `documentation/docs/zh/guide/bi.md`: POST usage and topology request examples.
- Modify `documentation/docs/en/guide/configuration.md`, `documentation/docs/zh/guide/configuration.md`: base configuration versus request override precedence.
- Modify `documentation/docs/en/guide/open-api.md`, `documentation/docs/zh/guide/open-api.md`: final HTTP contract and curl examples.

---

### Task 1: Define the Typed Request and POST OpenAPI Contract

**Files:**
- Create: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/bi/BiScriptRequest.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/contributor/global/GenerateBIScriptRouteContributorTest.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/global/GenerateBIScriptRouteContributor.kt`

**Interfaces:**
- Produces: `data class BiScriptRequest(...)` with nullable request overrides.
- Produces: `data class BiScriptTopologyRequest(mode, cluster)`.
- Produces: `data class BiScriptClusterRequest(name, installation, shard, replica)`.
- Produces: `enum class BiScriptTopologyMode { CLUSTER, STANDALONE }`.
- Produces: `enum class BiScriptUnsupportedTypeStrategy { FAIL, RAW_JSON }`.
- Produces: one `HttpRouteContract` for POST with required typed JSON body, SQL success, and common bad-request response.
- Consumes: existing `HttpRouteContract`, `HttpRequestBody`, `HttpSchema.TypeRef`, and `OpenAPIComponentContext.badRequestResponseRef()`.

- [ ] **Step 1: Write the failing route contract test**

Create a focused test that specifies the method, required body, schema type, response media type, and bad-request response before the request classes exist:

```kotlin
internal class GenerateBIScriptRouteContributorTest {
    private val currentContext = MaterializedNamedBoundedContext("example-service")
    private val componentContext = OpenAPIComponentContext.default(false)

    @Test
    fun `should contribute parameterized BI script POST contract`() {
        val contract = GenerateBIScriptRouteContributor
            .contributeGlobal(currentContext, componentContext)
            .single()

        contract.method.assert().isEqualTo(Https.Method.POST)
        contract.path.assert().isEqualTo(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
        contract.accept.assert().containsExactly(Https.MediaType.APPLICATION_SQL)
        contract.requestBody!!.run {
            required.assert().isTrue()
            content.assert().containsExactly(
                HttpContent(
                    Https.MediaType.APPLICATION_JSON,
                    HttpSchema.TypeRef(BiScriptRequest::class.java),
                )
            )
        }
        contract.responses.map { it.statusCode }.assert()
            .containsExactly(Https.Code.OK, Https.Code.BAD_REQUEST)
        contract.responses.first().content.assert().containsExactly(
            HttpContent(Https.MediaType.APPLICATION_SQL, HttpSchema.String)
        )
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew :wow-openapi:test \
  --tests "me.ahoo.wow.openapi.contributor.global.GenerateBIScriptRouteContributorTest" \
  --console=plain
```

Expected: test compilation fails because `BiScriptRequest` does not exist and the current contributor still emits GET without a request body or `400` response.

- [ ] **Step 3: Add the transport-only request types**

Create `BiScriptRequest.kt` without imports from `wow-bi`, Spring, or WebFlux:

```kotlin
package me.ahoo.wow.openapi.contract.bi

data class BiScriptRequest(
    val database: String? = null,
    val consumerDatabase: String? = null,
    val topology: BiScriptTopologyRequest? = null,
    val timezone: String? = null,
    val kafkaBootstrapServers: String? = null,
    val topicPrefix: String? = null,
    val maxExpansionDepth: Int? = null,
    val unsupportedTypeStrategy: BiScriptUnsupportedTypeStrategy? = null,
)

data class BiScriptTopologyRequest(
    val mode: BiScriptTopologyMode? = null,
    val cluster: BiScriptClusterRequest? = null,
)

data class BiScriptClusterRequest(
    val name: String? = null,
    val installation: String? = null,
    val shard: String? = null,
    val replica: String? = null,
)

enum class BiScriptTopologyMode {
    CLUSTER,
    STANDALONE,
}

enum class BiScriptUnsupportedTypeStrategy {
    FAIL,
    RAW_JSON,
}
```

Include the repository's Apache 2.0 header in the actual file.

- [ ] **Step 4: Implement the POST contributor**

Change `GenerateBIScriptRouteContributor` to use the typed request and existing common error response:

```kotlin
HttpRouteContract(
    routeId = wowRouteId("bi_script", "generate"),
    method = Https.Method.POST,
    path = BuiltInHttpRoutePaths.Global.BI_SCRIPT,
    handlerKey = BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT,
    summary = "Generate BI Sync Script",
    accept = listOf(Https.MediaType.APPLICATION_SQL),
    requestBody = HttpRequestBody(
        required = true,
        description = "BI script option overrides.",
        content = listOf(
            HttpContent(
                Https.MediaType.APPLICATION_JSON,
                HttpSchema.TypeRef(BiScriptRequest::class.java),
            )
        ),
    ),
    responses = listOf(
        HttpResponse(
            statusCode = Https.Code.OK,
            description = "The generated BI synchronization script.",
            content = listOf(HttpContent(Https.MediaType.APPLICATION_SQL, HttpSchema.String)),
        ),
        componentContext.badRequestResponseRef(),
    ),
    tags = wowTags(),
)
```

- [ ] **Step 5: Run focused and module tests and verify GREEN**

Run:

```bash
./gradlew :wow-openapi:test \
  --tests "me.ahoo.wow.openapi.contributor.global.GenerateBIScriptRouteContributorTest" \
  --console=plain
./gradlew :wow-openapi:compileKotlin :wow-openapi:compileTestKotlin --console=plain
```

Expected: both commands pass. Compatibility snapshot tests remain red until Task 3 intentionally updates the snapshots.

- [ ] **Step 6: Commit the typed request contract**

```bash
git add \
  wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/bi/BiScriptRequest.kt \
  wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/global/GenerateBIScriptRouteContributor.kt \
  wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/contributor/global/GenerateBIScriptRouteContributorTest.kt
git commit -m "feat(openapi): define BI script request contract"
```

---

### Task 2: Map Request Overrides and Generate Per-Request SQL

**Files:**
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/global/BiScriptRequestMapper.kt`
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/bi/BiScriptRequestMapperTest.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/global/GenerateBIScriptHandlerFunction.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/bi/GenerateBIScriptHandlerFunctionTest.kt`

**Interfaces:**
- Consumes: Task 1 request types and the factory-provided base `BiScriptOptions`.
- Produces: `internal fun BiScriptRequest.toBiScriptOptions(base: BiScriptOptions): BiScriptOptions`.
- Produces: handler behavior that requires and decodes `BiScriptRequest`, then generates using the merged options.
- Preserves: diagnostic WARN logging and SQL-only responses.

- [ ] **Step 1: Write failing mapper tests for scalar and Cluster inheritance**

Create `BiScriptRequestMapperTest` with a complete base and assert exact resulting options:

```kotlin
@Test
fun `should retain base options for an empty request`() {
    BiScriptRequest().toBiScriptOptions(BASE_OPTIONS).assert().isEqualTo(BASE_OPTIONS)
}

@Test
fun `should override scalar options and inherit partial cluster values`() {
    val result = BiScriptRequest(
        database = "request_db",
        consumerDatabase = "request_consumer",
        topology = BiScriptTopologyRequest(
            mode = BiScriptTopologyMode.CLUSTER,
            cluster = BiScriptClusterRequest(name = "request-cluster"),
        ),
        timezone = "UTC",
        kafkaBootstrapServers = "request-kafka:9092",
        topicPrefix = "request.",
        maxExpansionDepth = 7,
        unsupportedTypeStrategy = BiScriptUnsupportedTypeStrategy.FAIL,
    ).toBiScriptOptions(BASE_OPTIONS)

    result.assert().isEqualTo(
        BiScriptOptions(
            database = "request_db",
            consumerDatabase = "request_consumer",
            topology = ClickHouseTopology.Cluster(
                name = "request-cluster",
                installation = "base-installation",
                shard = "base-shard",
                replica = "base-replica",
            ),
            timezone = "UTC",
            kafkaBootstrapServers = "request-kafka:9092",
            topicPrefix = "request.",
            maxExpansionDepth = 7,
            unsupportedTypeStrategy = UnsupportedTypeStrategy.FAIL,
        )
    )
}
```

Define `BASE_OPTIONS` in the test companion with a Cluster topology and non-default values so every inheritance assertion is observable.

- [ ] **Step 2: Write failing mapper tests for topology switching and validation**

Add these focused contracts:

```kotlin
@Test
fun `should use cluster defaults when switching from standalone`() {
    val base = BASE_OPTIONS.copy(topology = ClickHouseTopology.Standalone)
    BiScriptRequest(
        topology = BiScriptTopologyRequest(
            mode = BiScriptTopologyMode.CLUSTER,
            cluster = BiScriptClusterRequest(name = "request-cluster"),
        )
    ).toBiScriptOptions(base).topology.assert().isEqualTo(
        ClickHouseTopology.Cluster(name = "request-cluster")
    )
}

@Test
fun `should require topology mode`() {
    runCatching {
        BiScriptRequest(topology = BiScriptTopologyRequest()).toBiScriptOptions(BASE_OPTIONS)
    }.exceptionOrNull()!!.message.assert().isEqualTo("topology.mode must be configured")
}

@Test
fun `should reject cluster details in standalone mode`() {
    runCatching {
        BiScriptRequest(
            topology = BiScriptTopologyRequest(
                mode = BiScriptTopologyMode.STANDALONE,
                cluster = BiScriptClusterRequest(name = "unused"),
            )
        ).toBiScriptOptions(BASE_OPTIONS)
    }.exceptionOrNull()!!.message.assert()
        .isEqualTo("topology.cluster must not be configured in STANDALONE mode")
}
```

- [ ] **Step 3: Run mapper tests and verify RED**

Run:

```bash
./gradlew :wow-webflux:test \
  --tests "me.ahoo.wow.webflux.route.bi.BiScriptRequestMapperTest" \
  --console=plain
```

Expected: test compilation fails because `toBiScriptOptions` does not exist.

- [ ] **Step 4: Implement the pure mapper**

Create `BiScriptRequestMapper.kt` with exhaustive enum mapping and domain validation delegated to constructors:

```kotlin
internal fun BiScriptRequest.toBiScriptOptions(base: BiScriptOptions): BiScriptOptions =
    BiScriptOptions(
        database = database ?: base.database,
        consumerDatabase = consumerDatabase ?: base.consumerDatabase,
        topology = topology?.toTopology(base.topology) ?: base.topology,
        timezone = timezone ?: base.timezone,
        kafkaBootstrapServers = kafkaBootstrapServers ?: base.kafkaBootstrapServers,
        topicPrefix = topicPrefix ?: base.topicPrefix,
        maxExpansionDepth = maxExpansionDepth ?: base.maxExpansionDepth,
        unsupportedTypeStrategy = unsupportedTypeStrategy?.toDomain()
            ?: base.unsupportedTypeStrategy,
    )

private fun BiScriptTopologyRequest.toTopology(base: ClickHouseTopology): ClickHouseTopology {
    return when (requireNotNull(mode) { "topology.mode must be configured" }) {
        BiScriptTopologyMode.CLUSTER -> {
            val baseCluster = base as? ClickHouseTopology.Cluster ?: ClickHouseTopology.Cluster()
            ClickHouseTopology.Cluster(
                name = cluster?.name ?: baseCluster.name,
                installation = cluster?.installation ?: baseCluster.installation,
                shard = cluster?.shard ?: baseCluster.shard,
                replica = cluster?.replica ?: baseCluster.replica,
            )
        }

        BiScriptTopologyMode.STANDALONE -> {
            require(cluster == null) {
                "topology.cluster must not be configured in STANDALONE mode"
            }
            ClickHouseTopology.Standalone
        }
    }
}

private fun BiScriptUnsupportedTypeStrategy.toDomain(): UnsupportedTypeStrategy = when (this) {
    BiScriptUnsupportedTypeStrategy.FAIL -> UnsupportedTypeStrategy.FAIL
    BiScriptUnsupportedTypeStrategy.RAW_JSON -> UnsupportedTypeStrategy.RAW_JSON
}
```

- [ ] **Step 5: Run mapper tests and verify GREEN**

Run:

```bash
./gradlew :wow-webflux:test \
  --tests "me.ahoo.wow.webflux.route.bi.BiScriptRequestMapperTest" \
  --console=plain
```

Expected: all merge, switching, and validation tests pass.

- [ ] **Step 6: Write failing handler tests for body-driven generation and required body**

Update `GenerateBIScriptHandlerFunctionTest` so the configured base and request override are both visible:

```kotlin
@Test
fun `should generate BI script from request overrides`() {
    val handler = GenerateBIScriptHandlerFunction(BASE_OPTIONS)
    val request = MockServerRequest.builder()
        .body(
            BiScriptRequest(
                database = "request_db",
                topology = BiScriptTopologyRequest(mode = BiScriptTopologyMode.STANDALONE),
            )
        )

    handler.handle(request).test()
        .consumeNextWith { response ->
            response.statusCode().assert().isEqualTo(HttpStatus.OK)
            response.writeBody().assert()
                .contains("CREATE DATABASE IF NOT EXISTS \"request_db\"")
                .doesNotContain("ON CLUSTER", "Replicated", "Distributed", "_local")
        }
        .verifyComplete()
}

@Test
fun `should reject a missing request body`() {
    GenerateBIScriptHandlerFunction(BASE_OPTIONS)
        .handle(MockServerRequest.builder().build())
        .test()
        .expectErrorMatches {
            it is IllegalArgumentException &&
                it.message == "BI script request body must not be empty"
        }
        .verify()
}
```

Update the existing diagnostics test to supply an empty override request while preserving its exact SQL and warning assertions:

```kotlin
response = GenerateBIScriptHandlerFunction(options)
    .handle(MockServerRequest.builder().body(BiScriptRequest()).build())
    .block()!!
```

- [ ] **Step 7: Run handler tests and verify RED**

Run:

```bash
./gradlew :wow-webflux:test \
  --tests "me.ahoo.wow.webflux.route.bi.GenerateBIScriptHandlerFunctionTest" \
  --console=plain
```

Expected: body-driven generation fails because the current handler ignores the body, and the missing-body test completes successfully instead of raising the required error.

- [ ] **Step 8: Decode and merge the required request body**

Refactor only the generation entry in `GenerateBIScriptHandlerFunction`:

```kotlin
override fun handle(request: ServerRequest): Mono<ServerResponse> {
    return request.bodyToMono(BiScriptRequest::class.java)
        .switchIfEmpty(Mono.error(IllegalArgumentException("BI script request body must not be empty")))
        .map { it.toBiScriptOptions(options) }
        .flatMap(::generateResponse)
}

private fun generateResponse(requestOptions: BiScriptOptions): Mono<ServerResponse> {
    val result = BiScriptGenerator(requestOptions).generate(MetadataSearcher.localAggregates)
    logDiagnostics(result.diagnostics)
    return ServerResponse.ok()
        .contentType(APPLICATION_SQL_MEDIA_TYPE)
        .bodyValue(result.script)
}
```

Keep `generateResponse` and `logDiagnostics` private so `handle` remains the only public handler method.

- [ ] **Step 9: Run handler and module tests and verify GREEN**

Run:

```bash
./gradlew :wow-webflux:test \
  --tests "me.ahoo.wow.webflux.route.bi.BiScriptRequestMapperTest" \
  --tests "me.ahoo.wow.webflux.route.bi.GenerateBIScriptHandlerFunctionTest" \
  --console=plain
./gradlew :wow-webflux:check --console=plain
```

Expected: focused tests and the complete module check pass.

- [ ] **Step 10: Commit request mapping and handler behavior**

```bash
git add \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/global/BiScriptRequestMapper.kt \
  wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/global/GenerateBIScriptHandlerFunction.kt \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/bi/BiScriptRequestMapperTest.kt \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/bi/GenerateBIScriptHandlerFunctionTest.kt
git commit -m "feat(webflux): generate BI scripts from POST options"
```

---

### Task 3: Prove Starter Routing, Error Boundaries, and OpenAPI Snapshots

**Files:**
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt`
- Modify: `wow-openapi/src/test/resources/openapi/example-domain-contract.snapshot.json`
- Modify: `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`

**Interfaces:**
- Consumes: Task 1 POST contract and Task 2 handler.
- Produces: verified server-property base plus request override precedence.
- Produces: compatibility snapshots containing only the POST operation and typed request schema.
- Preserves: existing application property and Kafka property precedence before request overrides are applied.

- [ ] **Step 1: Convert the Starter helper to POST an explicit request**

Change `generateBiScript` to accept a request body and materialize a POST router:

```kotlin
private fun AssertableApplicationContext.generateBiScript(
    request: BiScriptRequest = BiScriptRequest(),
): String {
    val factory = biScriptRouteFactory()
    val contract = HttpRouteContract(
        routeId = "bi-script",
        method = Https.Method.POST,
        path = BuiltInHttpRoutePaths.Global.BI_SCRIPT,
        handlerKey = BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT,
        accept = listOf(Https.MediaType.APPLICATION_SQL),
    )
    val routerFunction = RouterFunctions.route()
        .POST(contract.path, factory.create(contract))
        .build()
    return WebTestClient.bindToRouterFunction(routerFunction)
        .build()
        .post()
        .uri(contract.path)
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.parseMediaType(Https.MediaType.APPLICATION_SQL))
        .bodyValue(request)
        .exchange()
        .expectStatus().isOk
        .expectHeader().contentType(Https.MediaType.APPLICATION_SQL)
        .expectBody(String::class.java)
        .returnResult()
        .responseBody!!
}
```

Run existing BI configuration tests after this mechanical test-harness change; their expected scripts must remain unchanged for the default empty request.

- [ ] **Step 2: Write failing request-precedence and removed-GET tests**

Add integration-level assertions:

```kotlin
@Test
fun `should apply BI request options after application and Kafka options`() {
    webFluxContextRunner()
        .withPropertyValues(
            "${BiScriptProperties.PREFIX}.database=config_db",
            "${BiScriptProperties.PREFIX}.topology.mode=CLUSTER",
            "${BiScriptProperties.PREFIX}.topology.cluster.name=config-cluster",
        )
        .withBean(KafkaProperties::class.java) {
            KafkaProperties(bootstrapServers = listOf("config-kafka:9092"), topicPrefix = "config.")
        }
        .run { context ->
            context.generateBiScript(
                BiScriptRequest(
                    database = "request_db",
                    topology = BiScriptTopologyRequest(mode = BiScriptTopologyMode.STANDALONE),
                    kafkaBootstrapServers = "request-kafka:9092",
                    topicPrefix = "request.",
                )
            ).assert()
                .contains(
                    "CREATE DATABASE IF NOT EXISTS \"request_db\"",
                    "ENGINE = Kafka('request-kafka:9092'",
                    "'request.example.order.command'",
                )
                .doesNotContain("config_db", "config-cluster", "config-kafka:9092", "ON CLUSTER")
        }
}

@Test
fun `should not expose the removed BI GET route`() {
    webFluxContextRunner().run { context ->
        WebTestClient.bindToApplicationContext(context.sourceApplicationContext)
            .build()
            .get()
            .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
            .accept(MediaType.parseMediaType(Https.MediaType.APPLICATION_SQL))
            .exchange()
            .expectStatus().isNotFound
    }
}
```

- [ ] **Step 3: Write failing HTTP decoding and validation tests**

Using the application-context-bound `WebTestClient`, add one test per boundary and a shared client helper:

```kotlin
private fun AssertableApplicationContext.biScriptClient(): WebTestClient =
    WebTestClient.bindToApplicationContext(sourceApplicationContext).build()

@Test
fun `should reject a missing BI request body`() {
    webFluxContextRunner().run { context ->
        context.biScriptClient().post()
            .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
            .contentType(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isBadRequest
    }
}

@Test
fun `should reject malformed BI request JSON`() {
    webFluxContextRunner().run { context ->
        context.biScriptClient().post()
            .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{")
            .exchange()
            .expectStatus().isBadRequest
    }
}

@Test
fun `should reject domain-invalid BI request values`() {
    webFluxContextRunner().run { context ->
        context.biScriptClient().post()
            .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"maxExpansionDepth":0}""")
            .exchange()
            .expectStatus().isBadRequest
    }
}

@Test
fun `should reject cluster details in a standalone BI request`() {
    webFluxContextRunner().run { context ->
        context.biScriptClient().post()
            .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"topology":{"mode":"STANDALONE","cluster":{}}}""")
            .exchange()
            .expectStatus().isBadRequest
    }
}

@Test
fun `should reject a BI topology without mode`() {
    webFluxContextRunner().run { context ->
        context.biScriptClient().post()
            .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"topology":{}}""")
            .exchange()
            .expectStatus().isBadRequest
    }
}

@Test
fun `should reject unsupported BI request media type`() {
    webFluxContextRunner().run { context ->
        context.biScriptClient()
            .post()
            .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    }
}
```

- [ ] **Step 4: Run Starter tests and verify RED**

Run:

```bash
./gradlew :wow-spring-boot-starter:test \
  --tests "me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest" \
  --console=plain
```

Expected before Tasks 1 and 2 are present: POST requests cannot decode overrides and the old contributor still registers GET. After Tasks 1 and 2, the new tests pass; any test-harness issue must be corrected without changing the HTTP contract.

- [ ] **Step 5: Update OpenAPI snapshots from the generated contract**

Run the repository's snapshot update mode:

```bash
./gradlew :wow-openapi:test \
  --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest" \
  -Dwow.snapshot.update=true \
  --console=plain
```

Inspect both diffs and require all of these changes:

```text
/wow/bi/script: get -> post
requestBody: required application/json
request schema: BiScriptRequest with nested topology and enum values
responses: 200 application/sql and 400 common error response
route contract method: POST
route contract requestBody: true
route contract responseCodes: [200, 400]
```

Reject unrelated snapshot churn.

- [ ] **Step 6: Run snapshots and integration tests and verify GREEN**

Run without update mode:

```bash
./gradlew :wow-openapi:test \
  --tests "me.ahoo.wow.openapi.contributor.global.GenerateBIScriptRouteContributorTest" \
  --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest" \
  --console=plain
./gradlew :wow-spring-boot-starter:test \
  --tests "me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest" \
  --console=plain
```

Expected: the focused route, complete snapshots, POST route behavior, error boundaries, and configuration/request precedence all pass.

- [ ] **Step 7: Commit routing integration and snapshots**

```bash
git add \
  wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt \
  wow-openapi/src/test/resources/openapi/example-domain-contract.snapshot.json \
  wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json
git commit -m "test(bi): lock parameterized POST route"
```

---

### Task 4: Publish the Contract and Run the Final Quality Gate

**Files:**
- Modify: `documentation/docs/en/guide/bi.md`
- Modify: `documentation/docs/zh/guide/bi.md`
- Modify: `documentation/docs/en/guide/configuration.md`
- Modify: `documentation/docs/zh/guide/configuration.md`
- Modify: `documentation/docs/en/guide/open-api.md`
- Modify: `documentation/docs/zh/guide/open-api.md`

**Interfaces:**
- Consumes: final request JSON shape and merge semantics from Tasks 1-3.
- Produces: English and Chinese documentation with identical semantics.
- Produces: final verification evidence across BI, WebFlux, Starter, OpenAPI, static analysis, and VitePress.

- [ ] **Step 1: Replace GET examples with POST examples**

In both BI guides, replace the GET curl with the empty override form:

```bash
curl -X POST 'http://localhost:8080/wow/bi/script' \
  -H 'content-type: application/json' \
  -H 'accept: application/sql' \
  --data '{}'
```

Add a Standalone example:

```json
{
  "database": "analytics",
  "topology": {
    "mode": "STANDALONE"
  }
}
```

Add a Cluster example showing partial inheritance:

```json
{
  "topology": {
    "mode": "CLUSTER",
    "cluster": {
      "name": "production"
    }
  },
  "kafkaBootstrapServers": "kafka:9092",
  "topicPrefix": "analytics."
}
```

- [ ] **Step 2: Document precedence and errors in both languages**

State the exact precedence in configuration guides:

```text
BiScriptOptions domain defaults
  < Kafka properties for bootstrap servers and topic prefix
  < wow.bi.script.* application properties
  < non-null POST request fields
```

Document required body, `{}` semantics, mandatory `topology.mode`, Cluster inheritance, Standalone cluster rejection, `400`, `415`, SQL-only success, and removed GET behavior.

- [ ] **Step 3: Update the OpenAPI guide in both languages**

Replace every `GET /wow/bi/script` statement with POST. Show the required request body and retain the `200 application/sql` response example. Explicitly state that the OpenAPI schema lists all request fields and enum values.

- [ ] **Step 4: Scan documentation and source for stale route assumptions**

Run:

```bash
rg -n "GET /wow/bi/script|curl -X GET.*wow/bi/script|method = Https.Method.GET" \
  documentation wow-openapi/src wow-webflux/src wow-spring-boot-starter/src
```

Expected: no hits associated with the BI script endpoint. Unrelated GET routes are excluded by inspecting each remaining match if the broad method search finds them.

- [ ] **Step 5: Build documentation**

Run:

```bash
cd documentation
pnpm docs:build
```

Expected: VitePress build succeeds without broken links or syntax errors.

- [ ] **Step 6: Run the final JVM quality gate**

Run:

```bash
./gradlew \
  :wow-bi:check \
  :wow-openapi:check \
  :wow-webflux:check \
  :wow-spring-boot-starter:check \
  detekt \
  --stacktrace \
  --console=plain
```

Expected: all module tests, compilation, coverage checks, and static analysis pass.

- [ ] **Step 7: Verify the final diff and preserve unrelated work**

Run:

```bash
git diff --check
git status --short
git diff --name-only origin/main...HEAD
```

Expected: no whitespace errors; only planned BI endpoint files and previously committed branch work appear. `docs/superpowers/specs/2026-07-10-wow-bi-clean-architecture-design.md` remains modified but unstaged and unchanged by this implementation.

- [ ] **Step 8: Commit documentation**

```bash
git add \
  documentation/docs/en/guide/bi.md \
  documentation/docs/zh/guide/bi.md \
  documentation/docs/en/guide/configuration.md \
  documentation/docs/zh/guide/configuration.md \
  documentation/docs/en/guide/open-api.md \
  documentation/docs/zh/guide/open-api.md
git commit -m "docs(bi): document parameterized script requests"
```

- [ ] **Step 9: Review the whole feature before push**

Review the complete branch diff for correctness, request/schema drift, public API surface, error semantics, hidden GET compatibility, module dependency direction, and test coverage. Re-run any affected focused command after corrections, then push only when the review has no Critical or Important findings.
