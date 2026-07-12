# BI Script Request Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bound every request-controlled BI SQL fragment at the domain boundary and publish the runtime `415 Unsupported Media Type` response in the generated OpenAPI contract.

**Architecture:** `wow-bi` owns field-specific string limits shared by direct callers, Spring configuration, and HTTP request mapping. `wow-openapi` owns a reusable `UnsupportedMediaType` response component, while the BI contributor references it; `wow-webflux` remains a pure merge boundary and keeps using the global error strategy.

**Tech Stack:** Kotlin 2.4.0, JVM 17, Spring WebFlux 7, Spring Boot 4.1.0, Swagger/OpenAPI 3.1, Jackson, Reactor, JUnit Jupiter, FluentAssert, Gradle, VitePress.

---

## Global Constraints

- Preserve the approved server-configured `maxExpansionDepth` safety ceiling; do not add a framework absolute depth cap.
- Do not move BI generation to a scheduler or add caching/concurrency machinery in this correction.
- Domain constructors are the only source of string length invariants; do not duplicate numeric limits in request DTOs or WebFlux mapping.
- Exact accepted limits are: database 128, consumer database 128, timezone 64, topic prefix 128, Kafka bootstrap servers 4096, and every Cluster value 128 characters.
- Values exactly at their maximum are valid; maximum plus one is invalid.
- Preserve existing non-blank, control-character, topology, and depth validations.
- `POST /wow/bi/script` declares `200`, `400`, and `415`; runtime status behavior stays unchanged.
- The OpenAPI component key is `wow.UnsupportedMediaType`; the runtime `Wow-Error-Code` value is `UnsupportedMediaType`.
- Do not modify or stage `docs/superpowers/specs/2026-07-10-wow-bi-clean-architecture-design.md`.
- Follow strict RED, GREEN, and REFACTOR for every production behavior change.

## File Map

- Modify `wow-bi/src/main/kotlin/me/ahoo/wow/bi/BiScriptOptions.kt`: publish and enforce option-specific maximum lengths.
- Modify `wow-bi/src/main/kotlin/me/ahoo/wow/bi/ClickHouseTopology.kt`: publish and enforce the Cluster value maximum.
- Modify `wow-bi/src/test/kotlin/me/ahoo/wow/bi/BiScriptOptionsTest.kt`: exact-boundary and over-boundary domain tests.
- Modify `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt`: real HTTP boundary acceptance/rejection and runtime `415` error-code header.
- Modify `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/Https.kt`: add status code `415`.
- Modify `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/CommonComponent.kt`: register the common unsupported-media response.
- Modify `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/CommonContractComponentSupport.kt`: expose the typed response reference.
- Modify `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/global/GenerateBIScriptRouteContributor.kt`: declare `415`.
- Modify `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/contributor/global/GenerateBIScriptRouteContributorTest.kt`: lock response codes and component shape.
- Modify both OpenAPI compatibility snapshots: lock final `415` materialization.
- Modify six English/Chinese BI, configuration, and OpenAPI guides: publish limits and response contract.

---

### Task 1: Enforce Domain String Budgets and Real HTTP Boundaries

**Files:**
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/BiScriptOptionsTest.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/BiScriptOptions.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/ClickHouseTopology.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt`

- [ ] **Step 1: Add failing exact-boundary domain tests**

Add tests that express the public limits before the constants exist:

```kotlin
@Test
fun `should accept BI option values at their maximum lengths`() {
    BiScriptOptions(
        database = "d".repeat(BiScriptOptions.MAX_DATABASE_LENGTH),
        consumerDatabase = "c".repeat(BiScriptOptions.MAX_CONSUMER_DATABASE_LENGTH),
        timezone = "t".repeat(BiScriptOptions.MAX_TIMEZONE_LENGTH),
        kafkaBootstrapServers = "k".repeat(BiScriptOptions.MAX_KAFKA_BOOTSTRAP_SERVERS_LENGTH),
        topicPrefix = "p".repeat(BiScriptOptions.MAX_TOPIC_PREFIX_LENGTH),
    )
}

@Test
fun `should reject BI option values above their maximum lengths`() {
    val invalidOptions = listOf<() -> BiScriptOptions>(
        { BiScriptOptions(database = "d".repeat(BiScriptOptions.MAX_DATABASE_LENGTH + 1)) },
        {
            BiScriptOptions(
                consumerDatabase = "c".repeat(BiScriptOptions.MAX_CONSUMER_DATABASE_LENGTH + 1)
            )
        },
        { BiScriptOptions(timezone = "t".repeat(BiScriptOptions.MAX_TIMEZONE_LENGTH + 1)) },
        {
            BiScriptOptions(
                kafkaBootstrapServers = "k".repeat(BiScriptOptions.MAX_KAFKA_BOOTSTRAP_SERVERS_LENGTH + 1)
            )
        },
        { BiScriptOptions(topicPrefix = "p".repeat(BiScriptOptions.MAX_TOPIC_PREFIX_LENGTH + 1)) },
    )
    invalidOptions.forEach { create -> runCatching(create).isFailure.assert().isTrue() }
}

@Test
fun `should enforce the cluster value maximum length`() {
    val boundary = "x".repeat(ClickHouseTopology.Cluster.MAX_VALUE_LENGTH)
    ClickHouseTopology.Cluster(boundary, boundary, boundary, boundary)

    val overLimit = "x".repeat(ClickHouseTopology.Cluster.MAX_VALUE_LENGTH + 1)
    listOf<() -> ClickHouseTopology.Cluster>(
        { ClickHouseTopology.Cluster(name = overLimit) },
        { ClickHouseTopology.Cluster(installation = overLimit) },
        { ClickHouseTopology.Cluster(shard = overLimit) },
        { ClickHouseTopology.Cluster(replica = overLimit) },
    ).forEach { create -> runCatching(create).isFailure.assert().isTrue() }
}
```

- [ ] **Step 2: Run the domain tests and verify RED**

Run:

```bash
./gradlew :wow-bi:test \
  --tests "me.ahoo.wow.bi.BiScriptOptionsTest" \
  --stacktrace --console=plain
```

Expected: test compilation fails because the maximum-length constants do not exist.

- [ ] **Step 3: Add the failing real HTTP boundary tests**

Add two registered-router tests to `WebFluxAutoConfigurationTest`:

```kotlin
@Test
fun `should accept BI request string at the domain length boundary`() {
    val database = "d".repeat(BiScriptOptions.MAX_DATABASE_LENGTH)
    webFluxContextRunner().run { context ->
        context.biScriptClient().post()
            .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"database":"$database"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .value { script -> script.assert().contains("CREATE DATABASE IF NOT EXISTS \"$database\"") }
    }
}

@Test
fun `should reject BI request string above the domain length boundary`() {
    val database = "d".repeat(BiScriptOptions.MAX_DATABASE_LENGTH + 1)
    webFluxContextRunner().run { context ->
        context.biScriptClient().post()
            .uri(BuiltInHttpRoutePaths.Global.BI_SCRIPT)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"database":"$database"}""")
            .exchange()
            .expectStatus().isBadRequest
    }
}
```

- [ ] **Step 4: Run the real HTTP tests and verify RED**

Run:

```bash
./gradlew :wow-spring-boot-starter:test \
  --tests "me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest.should accept BI request string at the domain length boundary" \
  --tests "me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest.should reject BI request string above the domain length boundary" \
  --stacktrace --console=plain
```

Expected: compilation fails before constants exist, or the over-limit request returns `200` after the constants are temporarily introduced by the domain-test cycle but before validation is implemented.

- [ ] **Step 5: Implement the domain limits**

In `BiScriptOptions`, publish constants and pass each field's budget into the shared validator:

```kotlin
companion object {
    const val MAX_DATABASE_LENGTH = 128
    const val MAX_CONSUMER_DATABASE_LENGTH = 128
    const val MAX_TIMEZONE_LENGTH = 64
    const val MAX_KAFKA_BOOTSTRAP_SERVERS_LENGTH = 4096
    const val MAX_TOPIC_PREFIX_LENGTH = 128

    private const val DEFAULT_KAFKA_BOOTSTRAP_SERVERS = "localhost:9093"
    private const val DEFAULT_TOPIC_PREFIX = Wow.WOW_PREFIX
}

private fun String.requireValidRequiredValue(name: String, maxLength: Int) {
    require(isNotBlank()) { "$name must not be blank" }
    require(none { it == '\u0000' || it.isISOControl() }) {
        "$name must not contain control characters"
    }
    require(length <= maxLength) {
        "$name length[$length] must be less than or equal to $maxLength"
    }
}
```

Call the validator with the matching constant for all five fields.

In `ClickHouseTopology.Cluster`, publish one shared Cluster limit and enforce it:

```kotlin
companion object {
    const val MAX_VALUE_LENGTH = 128
}

private fun String.requireTopologyValue(name: String) {
    require(isNotBlank()) { "$name must not be blank" }
    require(none { it == '\u0000' || it.isISOControl() }) {
        "$name must not contain control characters"
    }
    require(length <= ClickHouseTopology.Cluster.MAX_VALUE_LENGTH) {
        "$name length[$length] must be less than or equal to ${ClickHouseTopology.Cluster.MAX_VALUE_LENGTH}"
    }
}
```

- [ ] **Step 6: Run focused tests and verify GREEN**

Run:

```bash
./gradlew :wow-bi:test \
  --tests "me.ahoo.wow.bi.BiScriptOptionsTest" \
  :wow-spring-boot-starter:test \
  --tests "me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest" \
  --stacktrace --console=plain
```

Expected: both focused test classes pass; existing blank/control/depth/config tests remain green.

- [ ] **Step 7: Refactor and commit Task 1**

Keep one validator per domain type, avoid DTO annotations, run `git diff --check`, then commit only Task 1 files:

```bash
git add \
  wow-bi/src/main/kotlin/me/ahoo/wow/bi/BiScriptOptions.kt \
  wow-bi/src/main/kotlin/me/ahoo/wow/bi/ClickHouseTopology.kt \
  wow-bi/src/test/kotlin/me/ahoo/wow/bi/BiScriptOptionsTest.kt \
  wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt
git commit -m "fix(bi): bound script option values"
```

---

### Task 2: Declare the Common 415 OpenAPI Response

**Files:**
- Modify: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/contributor/global/GenerateBIScriptRouteContributorTest.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/Https.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/CommonComponent.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/CommonContractComponentSupport.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/global/GenerateBIScriptRouteContributor.kt`
- Modify: `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`
- Modify: `wow-openapi/src/test/resources/openapi/example-domain-contract.snapshot.json`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt`

- [ ] **Step 1: Extend the contributor test and verify RED**

Change the response assertion and add component-shape assertions:

```kotlin
contract.responses.map { it.statusCode }.assert()
    .containsExactly(Https.Code.OK, Https.Code.BAD_REQUEST, Https.Code.UNSUPPORTED_MEDIA_TYPE)

val unsupportedMediaType = componentContext.responses.getValue("wow.UnsupportedMediaType")
unsupportedMediaType.headers.assert().containsKey("Wow-Error-Code")
unsupportedMediaType.content.keys.assert().contains(Https.MediaType.APPLICATION_JSON)
```

Run:

```bash
./gradlew :wow-openapi:test \
  --tests "me.ahoo.wow.openapi.contributor.global.GenerateBIScriptRouteContributorTest" \
  --stacktrace --console=plain
```

Expected: compilation fails because `Https.Code.UNSUPPORTED_MEDIA_TYPE` does not exist and the contributor registers no component.

- [ ] **Step 2: Lock the existing runtime error-code value**

Extend the existing unsupported-media test after `.expectStatus()`:

```kotlin
.expectHeader()
.valueEquals(CommonComponent.Header.ERROR_CODE, "UnsupportedMediaType")
```

Run:

```bash
./gradlew :wow-spring-boot-starter:test \
  --tests "me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest.should reject unsupported BI request media type" \
  --stacktrace --console=plain
```

Expected: PASS against the existing runtime converter. This records the
runtime value before the OpenAPI component is added; no WebFlux production code
changes in this task.

- [ ] **Step 3: Add the common response and contributor reference**

Add the status code:

```kotlin
const val UNSUPPORTED_MEDIA_TYPE = "415"
```

Add the common response in `CommonComponent.Response`:

```kotlin
const val UNSUPPORTED_MEDIA_TYPE_ERROR_CODE = "UnsupportedMediaType"

fun OpenAPIComponentContext.unsupportedMediaTypeResponse(): ApiResponse =
    response("${Wow.WOW_PREFIX}$UNSUPPORTED_MEDIA_TYPE_ERROR_CODE") {
        withErrorCodeHeader(this@unsupportedMediaTypeResponse)
        description("Unsupported Media Type")
        content(schema = errorInfoSchema())
    }
```

Add the contract helper:

```kotlin
internal fun OpenAPIComponentContext.unsupportedMediaTypeResponseRef(): HttpResponse {
    unsupportedMediaTypeResponse()
    return HttpResponse(
        statusCode = Https.Code.UNSUPPORTED_MEDIA_TYPE,
        componentRef = "${Wow.WOW_PREFIX}${CommonComponent.Response.UNSUPPORTED_MEDIA_TYPE_ERROR_CODE}"
    )
}
```

Append `componentContext.unsupportedMediaTypeResponseRef()` after the existing bad-request response in `GenerateBIScriptRouteContributor`.

- [ ] **Step 4: Run the contributor test and verify GREEN**

Run the same focused contributor command. Expected: PASS with `200`, `400`, and `415`, and a registered reusable response component.

- [ ] **Step 5: Demonstrate snapshot RED**

Run without update mode:

```bash
./gradlew :wow-openapi:test \
  --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest.generated openapi should match example domain compatibility snapshot" \
  --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest.generated route contracts should match example domain compatibility snapshot" \
  --stacktrace --console=plain
```

Expected: both snapshots fail because the materialized operation and route shape now include `415` and the new response component.

- [ ] **Step 6: Regenerate snapshots after RED and verify GREEN**

Run:

```bash
./gradlew :wow-openapi:test \
  --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest" \
  -Dwow.snapshot.update=true --stacktrace --console=plain
./gradlew :wow-openapi:test \
  --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest" \
  --stacktrace --console=plain
```

Inspect the diff and confirm `/wow/bi/script` references `#/components/responses/wow.UnsupportedMediaType`, the component contains JSON error content and the shared header, and unrelated operations do not change.

- [ ] **Step 7: Commit Task 2**

```bash
git add \
  wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/Https.kt \
  wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/CommonComponent.kt \
  wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/CommonContractComponentSupport.kt \
  wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/global/GenerateBIScriptRouteContributor.kt \
  wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/contributor/global/GenerateBIScriptRouteContributorTest.kt \
  wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json \
  wow-openapi/src/test/resources/openapi/example-domain-contract.snapshot.json \
  wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt
git commit -m "fix(openapi): declare unsupported BI media type"
```

---

### Task 3: Publish Limits and Run the Final Gate

**Files:**
- Modify: `documentation/docs/en/guide/bi.md`
- Modify: `documentation/docs/zh/guide/bi.md`
- Modify: `documentation/docs/en/guide/configuration.md`
- Modify: `documentation/docs/zh/guide/configuration.md`
- Modify: `documentation/docs/en/guide/open-api.md`
- Modify: `documentation/docs/zh/guide/open-api.md`

- [ ] **Step 1: Update all six documents**

Add the exact limits table from the approved design. State that the limits apply equally to server configuration and non-null POST overrides. Preserve the existing `maxExpansionDepth` ceiling language and `200`/`400`/`415` table. Do not document scheduler changes or an absolute depth cap.

Use this exact value table in both languages, translating only the prose column:

```markdown
| Field | Maximum characters |
|---|---:|
| `database` | 128 |
| `consumerDatabase` | 128 |
| `timezone` | 64 |
| `topicPrefix` | 128 |
| `kafkaBootstrapServers` | 4096 |
| `topology.cluster.name` | 128 |
| `topology.cluster.installation` | 128 |
| `topology.cluster.shard` | 128 |
| `topology.cluster.replica` | 128 |
```

Use this English contract sentence and its faithful Chinese translation:

```markdown
These limits apply to both server configuration and non-null POST overrides;
values exactly at the limit are accepted and longer values return `400`.
```

- [ ] **Step 2: Run static documentation checks**

Run:

```bash
rg -n "4096|UnsupportedMediaType|415|maxExpansionDepth" \
  documentation/docs/en/guide/{bi,configuration,open-api}.md \
  documentation/docs/zh/guide/{bi,configuration,open-api}.md
git diff --check
```

Expected: both languages contain the exact budgets and existing depth-ceiling semantics; no whitespace errors.

- [ ] **Step 3: Build VitePress**

Run:

```bash
cd documentation && pnpm docs:build
```

Expected: build succeeds; the repository's existing chunk-size warning is non-blocking.

- [ ] **Step 4: Run the complete module and container gates**

Run:

```bash
./gradlew :wow-bi:check :wow-openapi:check :wow-webflux:check \
  :wow-spring-boot-starter:check detekt --stacktrace --console=plain
./gradlew :wow-bi:integrationTest --stacktrace --console=plain
```

Expected: both commands report `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run final contract scans**

Run:

```bash
rg -n '"415"|wow.UnsupportedMediaType' \
  wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json \
  wow-openapi/src/test/resources/openapi/example-domain-contract.snapshot.json
git diff --check
git status --short
```

Expected: BI route snapshots include `415`; the only unrelated dirty file remains `docs/superpowers/specs/2026-07-10-wow-bi-clean-architecture-design.md`.

- [ ] **Step 6: Commit Task 3**

```bash
git add \
  documentation/docs/en/guide/bi.md \
  documentation/docs/zh/guide/bi.md \
  documentation/docs/en/guide/configuration.md \
  documentation/docs/zh/guide/configuration.md \
  documentation/docs/en/guide/open-api.md \
  documentation/docs/zh/guide/open-api.md
git commit -m "docs(bi): publish script request limits"
```

After Task 3, run independent specification and code-quality reviews over all task commits. Resolve every Critical, Important, and Minor finding before pushing the branch and updating PR #2757.
