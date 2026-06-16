# wow-openapi Contract Kernel Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `wow-openapi` around a clean HTTP contract kernel while preserving the existing RESTful API and OpenAPI operation contract.

**Architecture:** Add a pure `HttpRouteContract` model and `RouteCatalog`, render OpenAPI from the catalog, then switch WebFlux route binding from concrete `RouteSpec` classes to explicit handler keys. Existing `RouteSpec` generation is adapted only as a temporary migration bridge and deleted before completion.

**Tech Stack:** Kotlin 2.3, Gradle, JUnit Jupiter, `me.ahoo.test:fluent-assert-core`, Swagger OpenAPI models, Spring WebFlux functional routing.

---

## Reference Spec

Read this first:

- `docs/superpowers/specs/2026-06-16-wow-openapi-contract-kernel-refactor-design.md`

Do not refactor `wow-schema` in this plan. Preserve current schema naming behavior.

## File Structure

Create these new units:

- `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/HttpRouteContract.kt`  
  Pure REST route contract model. No Swagger `Operation`, `PathItem`, or WebFlux imports.

- `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/HttpRouteHandlerMetadata.kt`  
  Handler metadata carried by route contracts. It may refer to Wow metadata, but not WebFlux.

- `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/catalog/RouteCategory.kt`  
  Route category enum for ordering and diagnostics.

- `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/catalog/RouteContributor.kt`  
  Contributor SPI replacing order-sensitive route factory loading.

- `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/catalog/RouteCatalog.kt`  
  Immutable catalog plus conflict validation.

- `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/catalog/RouteCatalogBuilder.kt`  
  Builder that collects contributors and produces a validated catalog.

- `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/migration/RouteSpecContractAdapter.kt`  
  Temporary adapter from old `RouteSpec` instances to `HttpRouteContract`.

- `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/render/OpenApiRenderer.kt`  
  Renderer from `RouteCatalog` to Swagger `OpenAPI`.

- `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/snapshot/OpenApiSnapshotSupport.kt`  
  Snapshot normalization and update/assert helpers.

- `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/snapshot/OpenApiCompatibilitySnapshotTest.kt`  
  Example-domain compatibility snapshot test.

- `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`  
  Generated current OpenAPI compatibility snapshot.

- `wow-openapi/src/test/resources/openapi/example-domain-contract.snapshot.json`  
  Generated current contract compatibility snapshot.

Modify these existing units:

- `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/RouterSpecs.kt`  
  Add bridge methods to produce `RouteCatalog` and render through `OpenApiRenderer` during migration.

- `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/openapi/OpenAPIAutoConfiguration.kt`  
  Wire catalog creation once the catalog becomes the main path.

- `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/openapi/WowOpenApiCustomizer.kt`  
  Render OpenAPI from `RouteCatalog`.

- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionFactory.kt`  
  Add handler-key based route factory API.

- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionRegistrar.kt`  
  Register factories by handler key.

- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouterFunctionBuilder.kt`  
  Build routes from `RouteCatalog`.

- Existing WebFlux handler factory files under `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/**`  
  Move factory matching from `supportedSpec: Class<out RouteSpec>` to handler key plus handler metadata.

## Task 1: Add OpenAPI Compatibility Snapshots

**Files:**
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/snapshot/OpenApiSnapshotSupport.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/snapshot/OpenApiCompatibilitySnapshotTest.kt`
- Create by command: `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`
- Create by command: `wow-openapi/src/test/resources/openapi/example-domain-contract.snapshot.json`

- [ ] **Step 1: Write snapshot support**

Create `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/snapshot/OpenApiSnapshotSupport.kt`:

```kotlin
package me.ahoo.wow.openapi.snapshot

import io.swagger.v3.core.util.ObjectMapperFactory
import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.test.asserts.assert
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal object OpenApiSnapshotSupport {
    private val mapper = ObjectMapperFactory.createJson()
    private val updateSnapshots: Boolean
        get() = System.getProperty("wow.snapshot.update").equals("true", ignoreCase = true)

    fun assertOpenApiSnapshot(openAPI: OpenAPI, snapshotPath: Path) {
        val canonical = mapper.writeValueAsString(canonicalize(mapper.valueToTree<JsonNode>(openAPI)))
        assertSnapshot(canonical, snapshotPath)
    }

    fun assertContractSnapshot(contractJson: String, snapshotPath: Path) {
        val canonical = mapper.writeValueAsString(canonicalize(mapper.readTree(contractJson)))
        assertSnapshot(canonical, snapshotPath)
    }

    private fun assertSnapshot(canonical: String, snapshotPath: Path) {
        snapshotPath.parent.createDirectories()
        if (updateSnapshots || !snapshotPath.exists()) {
            snapshotPath.writeText(pretty(canonical))
            return
        }
        val expected = snapshotPath.readText()
        pretty(canonical).assert().isEqualTo(expected)
    }

    private fun pretty(json: String): String {
        val prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(json))
        return prettyJson + System.lineSeparator()
    }

    private fun canonicalize(node: JsonNode): JsonNode {
        return when {
            node is ObjectNode -> canonicalizeObject(node)
            node is ArrayNode -> canonicalizeArray(node)
            else -> node
        }
    }

    private fun canonicalizeObject(node: ObjectNode): ObjectNode {
        val result = mapper.createObjectNode()
        node.fieldNames().asSequence().sorted().forEach { fieldName ->
            val value = node.get(fieldName)
            if (!isNoisyField(fieldName)) {
                result.set<JsonNode>(fieldName, canonicalize(value))
            }
        }
        return result
    }

    private fun canonicalizeArray(node: ArrayNode): ArrayNode {
        val result = mapper.createArrayNode()
        val values = node.map { canonicalize(it) }
        val sortedValues = if (values.all { it.isObject && sortableObject(it as ObjectNode) }) {
            values.sortedBy { sortableKey(it as ObjectNode) }
        } else {
            values
        }
        sortedValues.forEach { result.add(it) }
        return result
    }

    private fun isNoisyField(fieldName: String): Boolean {
        return fieldName == "description" || fieldName == "summary"
    }

    private fun sortableObject(node: ObjectNode): Boolean {
        return node.has("name") || node.has("operationId") || node.has("\$ref") || node.has("in")
    }

    private fun sortableKey(node: ObjectNode): String {
        return listOf("name", "operationId", "\$ref", "in")
            .mapNotNull { fieldName -> node.get(fieldName)?.asText() }
            .joinToString("|")
    }

    fun resourcePath(relativePath: String): Path {
        return Path.of("wow-openapi/src/test/resources").resolve(relativePath)
    }

    fun deleteIfExists(path: Path) {
        Files.deleteIfExists(path)
    }
}
```

- [ ] **Step 2: Write the failing OpenAPI snapshot test**

Create `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/snapshot/OpenApiCompatibilitySnapshotTest.kt`:

```kotlin
package me.ahoo.wow.openapi.snapshot

import io.swagger.v3.core.util.ObjectMapperFactory
import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.snapshot.OpenApiSnapshotSupport.assertContractSnapshot
import me.ahoo.wow.openapi.snapshot.OpenApiSnapshotSupport.assertOpenApiSnapshot
import me.ahoo.wow.openapi.snapshot.OpenApiSnapshotSupport.resourcePath
import org.junit.jupiter.api.Test

internal class OpenApiCompatibilitySnapshotTest {
    private val mapper = ObjectMapperFactory.createJson()
    private val currentContext = MaterializedNamedBoundedContext("example-service")

    @Test
    fun `generated openapi should match example domain compatibility snapshot`() {
        val openAPI = OpenAPI()
        RouterSpecs(currentContext).build().mergeOpenAPI(openAPI)

        assertOpenApiSnapshot(
            openAPI = openAPI,
            snapshotPath = resourcePath("openapi/example-domain-openapi.snapshot.json")
        )
    }

    @Test
    fun `generated route contracts should match example domain compatibility snapshot`() {
        val routerSpecs = RouterSpecs(currentContext).build()
        val routeShape = routerSpecs.map { route ->
            mapOf(
                "id" to route.id,
                "path" to route.path,
                "method" to route.method,
                "accept" to route.accept,
                "parameterNames" to route.parameters.map { "${it.`in`}:${it.name}:${it.required}" },
                "requestBody" to (route.requestBody != null),
                "responseCodes" to route.responses.keys.sorted(),
                "tagNames" to route.tags.map { it.name }.sorted()
            )
        }.sortedWith(compareBy({ it["path"].toString() }, { it["method"].toString() }, { it["id"].toString() }))

        assertContractSnapshot(
            contractJson = mapper.writeValueAsString(routeShape),
            snapshotPath = resourcePath("openapi/example-domain-contract.snapshot.json")
        )
    }
}
```

- [ ] **Step 3: Run the snapshot tests to create the baseline**

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest" -Dwow.snapshot.update=true
```

Expected: `BUILD SUCCESSFUL`, and two snapshot files are created under `wow-openapi/src/test/resources/openapi/`.

- [ ] **Step 4: Run snapshot tests without update mode**

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/snapshot/OpenApiSnapshotSupport.kt \
        wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/snapshot/OpenApiCompatibilitySnapshotTest.kt \
        wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json \
        wow-openapi/src/test/resources/openapi/example-domain-contract.snapshot.json
git commit -m "test(openapi): add REST compatibility snapshots"
```

## Task 2: Add Contract Model

**Files:**
- Create: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/HttpRouteContract.kt`
- Create: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/HttpRouteHandlerMetadata.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/contract/HttpRouteContractTest.kt`

- [ ] **Step 1: Write contract model tests**

Create `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/contract/HttpRouteContractTest.kt`:

```kotlin
package me.ahoo.wow.openapi.contract

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class HttpRouteContractTest {
    @Test
    fun `should expose route key as method and path`() {
        val contract = HttpRouteContract(
            routeId = "wow.command.send",
            method = "POST",
            path = "/wow/command/send",
            handlerKey = "wow.command.send"
        )

        contract.routeKey.assert().isEqualTo("POST /wow/command/send")
    }

    @Test
    fun `should keep parameter model independent from swagger`() {
        val parameter = HttpParameter(
            name = "Command-Type",
            location = HttpParameterLocation.HEADER,
            required = true,
            schema = HttpSchema.String
        )

        parameter.name.assert().isEqualTo("Command-Type")
        parameter.location.assert().isEqualTo(HttpParameterLocation.HEADER)
        parameter.schema.assert().isEqualTo(HttpSchema.String)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.contract.HttpRouteContractTest"
```

Expected: FAIL with unresolved references to `HttpRouteContract`, `HttpParameter`, `HttpParameterLocation`, and `HttpSchema`.

- [ ] **Step 3: Add handler metadata model**

Create `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/HttpRouteHandlerMetadata.kt`:

```kotlin
package me.ahoo.wow.openapi.contract

import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.CommandRouteMetadata

sealed interface HttpRouteHandlerMetadata {
    data object None : HttpRouteHandlerMetadata

    data class Aggregate(
        val aggregateRouteMetadata: AggregateRouteMetadata<*>
    ) : HttpRouteHandlerMetadata

    data class Command(
        val aggregateRouteMetadata: AggregateRouteMetadata<*>,
        val commandRouteMetadata: CommandRouteMetadata<*>
    ) : HttpRouteHandlerMetadata
}
```

- [ ] **Step 4: Add contract model**

Create `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/HttpRouteContract.kt`:

```kotlin
package me.ahoo.wow.openapi.contract

import java.lang.reflect.Type

data class HttpRouteContract(
    val routeId: String,
    val method: String,
    val path: String,
    val handlerKey: String,
    val category: String = "",
    val accept: List<String> = listOf("application/json"),
    val produce: List<String> = emptyList(),
    val parameters: List<HttpParameter> = emptyList(),
    val requestBody: HttpRequestBody? = null,
    val responses: List<HttpResponse> = emptyList(),
    val tags: List<HttpTag> = emptyList(),
    val handlerMetadata: HttpRouteHandlerMetadata = HttpRouteHandlerMetadata.None,
    val resourceScope: String = ""
) {
    val routeKey: String
        get() = "$method $path"
}

data class HttpTag(
    val name: String,
    val description: String? = null
)

data class HttpParameter(
    val name: String,
    val location: HttpParameterLocation,
    val required: Boolean = false,
    val schema: HttpSchema = HttpSchema.String,
    val description: String? = null,
    val example: Any? = null
)

enum class HttpParameterLocation {
    PATH,
    QUERY,
    HEADER
}

data class HttpRequestBody(
    val required: Boolean = false,
    val description: String? = null,
    val content: List<HttpContent> = emptyList()
)

data class HttpResponse(
    val statusCode: String,
    val description: String? = null,
    val headers: List<HttpHeader> = emptyList(),
    val content: List<HttpContent> = emptyList()
)

data class HttpHeader(
    val name: String,
    val schema: HttpSchema = HttpSchema.String,
    val description: String? = null
)

data class HttpContent(
    val mediaType: String,
    val schema: HttpSchema
)

sealed interface HttpSchema {
    data object String : HttpSchema
    data object Integer : HttpSchema
    data object Boolean : HttpSchema
    data object Long : HttpSchema
    data object Object : HttpSchema
    data class TypeRef(val mainTargetType: Type, val typeParameters: List<Type> = emptyList()) : HttpSchema
    data class Array(val item: HttpSchema) : HttpSchema
    data class ComponentRef(val key: String) : HttpSchema
}
```

- [ ] **Step 5: Run contract model tests**

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.contract.HttpRouteContractTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/HttpRouteContract.kt \
        wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/HttpRouteHandlerMetadata.kt \
        wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/contract/HttpRouteContractTest.kt
git commit -m "feat(openapi): add HTTP route contract model"
```

## Task 3: Add Route Catalog

**Files:**
- Create: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/catalog/RouteCategory.kt`
- Create: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/catalog/RouteContributor.kt`
- Create: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/catalog/RouteCatalog.kt`
- Create: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/catalog/RouteCatalogBuilder.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/catalog/RouteCatalogTest.kt`

- [ ] **Step 1: Write catalog tests**

Create `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/catalog/RouteCatalogTest.kt`:

```kotlin
package me.ahoo.wow.openapi.catalog

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.openapi.contract.HttpRouteContract
import org.junit.jupiter.api.Test

internal class RouteCatalogTest {
    @Test
    fun `should sort routes deterministically`() {
        val catalog = RouteCatalog(
            listOf(
                HttpRouteContract(routeId = "b", method = "POST", path = "/b", handlerKey = "b"),
                HttpRouteContract(routeId = "a", method = "GET", path = "/a", handlerKey = "a")
            )
        )

        catalog.routes.map { it.routeId }.assert().isEqualTo(listOf("a", "b"))
    }

    @Test
    fun `should reject duplicate path and method`() {
        assertThrownBy<IllegalArgumentException> {
            RouteCatalog(
                listOf(
                    HttpRouteContract(routeId = "first", method = "GET", path = "/same", handlerKey = "first"),
                    HttpRouteContract(routeId = "second", method = "GET", path = "/same", handlerKey = "second")
                )
            )
        }
    }

    @Test
    fun `should reject missing handler key`() {
        assertThrownBy<IllegalArgumentException> {
            RouteCatalog(listOf(HttpRouteContract(routeId = "route", method = "GET", path = "/route", handlerKey = "")))
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.catalog.RouteCatalogTest"
```

Expected: FAIL with unresolved references to `RouteCatalog`.

- [ ] **Step 3: Add catalog types**

Create `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/catalog/RouteCategory.kt`:

```kotlin
package me.ahoo.wow.openapi.catalog

enum class RouteCategory {
    GLOBAL,
    COMMAND,
    STATE,
    SNAPSHOT,
    EVENT
}
```

Create `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/catalog/RouteContributor.kt`:

```kotlin
package me.ahoo.wow.openapi.catalog

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata

interface RouteContributor {
    val id: String
    val category: RouteCategory
    val order: Int

    fun contributeGlobal(
        currentContext: NamedBoundedContext,
        componentContext: OpenAPIComponentContext
    ): List<HttpRouteContract> = emptyList()

    fun contributeAggregate(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): List<HttpRouteContract> = emptyList()
}
```

Create `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/catalog/RouteCatalog.kt`:

```kotlin
package me.ahoo.wow.openapi.catalog

import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpRouteContract

class RouteCatalog(routes: List<HttpRouteContract>) : Iterable<HttpRouteContract> {
    val routes: List<HttpRouteContract> = routes
        .sortedWith(compareBy<HttpRouteContract> { it.path }.thenBy { it.method }.thenBy { it.routeId })
        .also(::validate)

    override fun iterator(): Iterator<HttpRouteContract> {
        return routes.iterator()
    }

    private fun validate(routes: List<HttpRouteContract>) {
        routes.forEach { route ->
            require(route.routeId.isNotBlank()) {
                "routeId must not be blank for [${route.method} ${route.path}]."
            }
            require(route.handlerKey.isNotBlank()) {
                "handlerKey must not be blank for route [${route.routeId}]."
            }
            validatePathVariables(route)
        }

        routes.groupBy { it.routeKey }
            .filterValues { it.size > 1 }
            .forEach { (routeKey, duplicates) ->
                error("Duplicate route [$routeKey]: ${duplicates.joinToString { it.routeId }}.")
            }
    }

    private fun validatePathVariables(route: HttpRouteContract) {
        val templateVariables = Regex("\\{([^}]+)}")
            .findAll(route.path)
            .map { it.groupValues[1] }
            .toSet()
        val parameterVariables = route.parameters
            .filter { it.location == HttpParameterLocation.PATH }
            .map { it.name }
            .toSet()
        val missingParameters = templateVariables - parameterVariables
        require(missingParameters.isEmpty()) {
            "Route [${route.routeId}] path variables missing parameters: $missingParameters."
        }
        val missingTemplates = parameterVariables - templateVariables
        require(missingTemplates.isEmpty()) {
            "Route [${route.routeId}] path parameters missing path variables: $missingTemplates."
        }
    }
}
```

Create `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/catalog/RouteCatalogBuilder.kt`:

```kotlin
package me.ahoo.wow.openapi.catalog

import me.ahoo.wow.openapi.contract.HttpRouteContract

class RouteCatalogBuilder {
    private val routes = mutableListOf<HttpRouteContract>()

    fun add(route: HttpRouteContract): RouteCatalogBuilder {
        routes.add(route)
        return this
    }

    fun addAll(routes: Iterable<HttpRouteContract>): RouteCatalogBuilder {
        routes.forEach { add(it) }
        return this
    }

    fun build(): RouteCatalog {
        return RouteCatalog(routes)
    }
}
```

- [ ] **Step 4: Run catalog tests**

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.catalog.RouteCatalogTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/catalog \
        wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/catalog/RouteCatalogTest.kt
git commit -m "feat(openapi): add route catalog validation"
```

## Task 4: Adapt Existing RouteSpec Into RouteCatalog

**Files:**
- Create: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/migration/RouteSpecContractAdapter.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/RouterSpecs.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/migration/RouteSpecContractAdapterTest.kt`

- [ ] **Step 1: Write adapter test**

Create `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/migration/RouteSpecContractAdapterTest.kt`:

```kotlin
package me.ahoo.wow.openapi.migration

import me.ahoo.test.asserts.assert
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.RouterSpecs
import org.junit.jupiter.api.Test

internal class RouteSpecContractAdapterTest {
    @Test
    fun `should adapt router specs into route catalog`() {
        val routerSpecs = RouterSpecs(MaterializedNamedBoundedContext("example-service")).build()
        val catalog = routerSpecs.toRouteCatalog()

        catalog.routes.assert().hasSize(routerSpecs.count())
        catalog.routes.first().handlerKey.assert().isNotBlank()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.migration.RouteSpecContractAdapterTest"
```

Expected: FAIL with unresolved reference `toRouteCatalog`.

- [ ] **Step 3: Add temporary adapter**

Create `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/migration/RouteSpecContractAdapter.kt`:

```kotlin
package me.ahoo.wow.openapi.migration

import io.swagger.v3.oas.annotations.enums.ParameterIn
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.aggregate.command.CommandRouteSpec
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.contract.HttpSchema
import me.ahoo.wow.openapi.contract.HttpTag

object RouteSpecContractAdapter {
    fun adapt(routeSpec: RouteSpec): HttpRouteContract {
        return HttpRouteContract(
            routeId = routeSpec.id,
            method = routeSpec.method,
            path = routeSpec.path,
            accept = routeSpec.accept,
            handlerKey = routeSpec::class.java.name,
            parameters = routeSpec.parameters.map { parameter ->
                HttpParameter(
                    name = parameter.name,
                    location = parameter.`in`.toHttpParameterLocation(),
                    required = parameter.required ?: false,
                    schema = HttpSchema.ComponentRef(parameter.schema?.`$ref`.orEmpty()),
                    description = parameter.description,
                    example = parameter.example
                )
            },
            requestBody = routeSpec.requestBody?.let {
                HttpRequestBody(
                    required = it.required ?: false,
                    description = it.description,
                    content = it.content.orEmpty().map { (mediaType, content) ->
                        HttpContent(mediaType = mediaType, schema = HttpSchema.ComponentRef(content.schema?.`$ref`.orEmpty()))
                    }
                )
            },
            responses = routeSpec.responses.orEmpty().map { (statusCode, response) ->
                HttpResponse(
                    statusCode = statusCode,
                    description = response.description,
                    content = response.content.orEmpty().map { (mediaType, content) ->
                        HttpContent(mediaType = mediaType, schema = HttpSchema.ComponentRef(content.schema?.`$ref`.orEmpty()))
                    }
                )
            },
            tags = routeSpec.tags.map { HttpTag(name = it.name, description = it.description) },
            handlerMetadata = routeSpec.toHandlerMetadata(),
            resourceScope = routeSpec::class.java.simpleName
        )
    }

    private fun String?.toHttpParameterLocation(): HttpParameterLocation {
        return when (this) {
            ParameterIn.PATH.toString() -> HttpParameterLocation.PATH
            ParameterIn.QUERY.toString() -> HttpParameterLocation.QUERY
            ParameterIn.HEADER.toString() -> HttpParameterLocation.HEADER
            else -> HttpParameterLocation.HEADER
        }
    }

    private fun RouteSpec.toHandlerMetadata(): HttpRouteHandlerMetadata {
        return when (this) {
            is CommandRouteSpec -> HttpRouteHandlerMetadata.Command(aggregateRouteMetadata, commandRouteMetadata)
            is me.ahoo.wow.openapi.aggregate.AggregateRouteSpec -> HttpRouteHandlerMetadata.Aggregate(aggregateRouteMetadata)
            else -> HttpRouteHandlerMetadata.None
        }
    }
}
```

- [ ] **Step 4: Add RouterSpecs bridge**

Modify `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/RouterSpecs.kt` by adding imports:

```kotlin
import me.ahoo.wow.openapi.catalog.RouteCatalog
import me.ahoo.wow.openapi.catalog.RouteCatalogBuilder
import me.ahoo.wow.openapi.migration.RouteSpecContractAdapter
```

Add this method inside `class RouterSpecs` before `fun mergeOpenAPI(openAPI: OpenAPI)`:

```kotlin
    fun toRouteCatalog(): RouteCatalog {
        return RouteCatalogBuilder()
            .addAll(routes.map { RouteSpecContractAdapter.adapt(it) })
            .build()
    }
```

- [ ] **Step 5: Run adapter test**

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.migration.RouteSpecContractAdapterTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run snapshot tests**

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/migration/RouteSpecContractAdapter.kt \
        wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/RouterSpecs.kt \
        wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/migration/RouteSpecContractAdapterTest.kt
git commit -m "refactor(openapi): adapt route specs to route catalog"
```

## Task 5: Render OpenAPI From RouteCatalog

**Files:**
- Create: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/render/OpenApiRenderer.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/RouterSpecs.kt`
- Create: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/render/OpenApiRendererTest.kt`

- [ ] **Step 1: Write renderer test**

Create `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/render/OpenApiRendererTest.kt`:

```kotlin
package me.ahoo.wow.openapi.render

import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.catalog.RouteCatalog
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteContract
import org.junit.jupiter.api.Test

internal class OpenApiRendererTest {
    @Test
    fun `should render route catalog to open api paths`() {
        val catalog = RouteCatalog(
            listOf(
                HttpRouteContract(
                    routeId = "test.route",
                    method = "GET",
                    path = "/test",
                    handlerKey = "test",
                    responses = listOf(HttpResponse(statusCode = "200", description = "OK"))
                )
            )
        )
        val openAPI = OpenAPI()

        OpenApiRenderer().render(catalog, openAPI)

        openAPI.paths["/test"].get.operationId.assert().isEqualTo("test.route")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.render.OpenApiRendererTest"
```

Expected: FAIL with unresolved reference `OpenApiRenderer`.

- [ ] **Step 3: Add renderer**

Create `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/render/OpenApiRenderer.kt`:

```kotlin
package me.ahoo.wow.openapi.render

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.SpecVersion
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.openapi.catalog.RouteCatalog
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpSchema

class OpenApiRenderer {
    fun render(catalog: RouteCatalog, openAPI: OpenAPI): OpenAPI {
        openAPI.specVersion(SpecVersion.V31)
        if (openAPI.paths == null) {
            openAPI.paths = Paths()
        }
        catalog.routes.groupBy { it.path }.forEach { (path, routes) ->
            openAPI.paths.addPathItem(path, routes.toPathItem())
        }
        return openAPI
    }

    private fun List<HttpRouteContract>.toPathItem(): PathItem {
        val pathItem = PathItem()
        forEach { route ->
            val operation = route.toOperation()
            when (route.method) {
                "GET" -> pathItem.get(operation)
                "POST" -> pathItem.post(operation)
                "PUT" -> pathItem.put(operation)
                "DELETE" -> pathItem.delete(operation)
                "PATCH" -> pathItem.patch(operation)
                "OPTIONS" -> pathItem.options(operation)
                "HEAD" -> pathItem.head(operation)
                "TRACE" -> pathItem.trace(operation)
                else -> error("Unsupported method: ${route.method}")
            }
        }
        return pathItem
    }

    private fun HttpRouteContract.toOperation(): Operation {
        return Operation()
            .operationId(routeId)
            .tags(tags.map { it.name })
            .parameters(parameters.map { it.toParameter() })
            .requestBody(requestBody?.toRequestBody())
            .responses(responses.toResponses())
    }

    private fun HttpParameter.toParameter(): Parameter {
        return Parameter()
            .name(name)
            .`in`(
                when (location) {
                    HttpParameterLocation.PATH -> "path"
                    HttpParameterLocation.QUERY -> "query"
                    HttpParameterLocation.HEADER -> "header"
                }
            )
            .required(required)
            .description(description)
            .schema(schema.toOpenApiSchema())
    }

    private fun HttpRequestBody.toRequestBody(): RequestBody {
        return RequestBody()
            .required(required)
            .description(description)
            .content(content.toContent())
    }

    private fun List<HttpResponse>.toResponses(): ApiResponses {
        return ApiResponses().also { responses ->
            forEach { response ->
                responses.addApiResponse(response.statusCode, response.toApiResponse())
            }
        }
    }

    private fun HttpResponse.toApiResponse(): ApiResponse {
        return ApiResponse()
            .description(description)
            .content(content.toContent())
    }

    private fun List<HttpContent>.toContent(): Content {
        return Content().also { content ->
            forEach { httpContent ->
                content.addMediaType(httpContent.mediaType, MediaType().schema(httpContent.schema.toOpenApiSchema()))
            }
        }
    }

    private fun HttpSchema.toOpenApiSchema(): Schema<*> {
        return when (this) {
            HttpSchema.Boolean -> Schema<Boolean>().types(setOf("boolean"))
            HttpSchema.Integer -> Schema<Int>().types(setOf("integer"))
            HttpSchema.Long -> Schema<Long>().types(setOf("integer")).format("int64")
            HttpSchema.Object -> ObjectSchema()
            HttpSchema.String -> Schema<String>().types(setOf("string"))
            is HttpSchema.Array -> io.swagger.v3.oas.models.media.ArraySchema().items(item.toOpenApiSchema())
            is HttpSchema.ComponentRef -> Schema<Any>().also {
                if (key.isNotBlank()) {
                    it.`$ref` = key
                }
            }
            is HttpSchema.TypeRef -> ObjectSchema()
        }
    }
}
```

- [ ] **Step 4: Run renderer test**

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.render.OpenApiRendererTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Keep current merge path intact**

Do not replace `RouterSpecs.mergeOpenAPI` yet. The first renderer version exists beside the current renderer so snapshots remain stable.

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/render/OpenApiRenderer.kt \
        wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/render/OpenApiRendererTest.kt
git commit -m "feat(openapi): render OpenAPI from route catalog"
```

## Task 6: Switch Spring OpenAPI Customizer To RouteCatalog Renderer

**Files:**
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/RouterSpecs.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/openapi/WowOpenApiCustomizer.kt`
- Test: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/snapshot/OpenApiCompatibilitySnapshotTest.kt`

- [ ] **Step 1: Add catalog merge method to RouterSpecs**

Modify `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/RouterSpecs.kt` by adding this import:

```kotlin
import me.ahoo.wow.openapi.render.OpenApiRenderer
```

Add this method beside `mergeOpenAPI`:

```kotlin
    fun mergeOpenAPIFromCatalog(openAPI: OpenAPI) {
        openAPI.apply {
            specVersion(SpecVersion.V31)
            ensureInfo()
            if (components == null) {
                components = Components()
            }
        }
        OpenApiRenderer().render(toRouteCatalog(), openAPI)
        componentContext.finish()
        componentContext.schemas.forEach { (name, schema) ->
            openAPI.components.addSchemas(name, schema)
        }
        componentContext.parameters.forEach { (name, parameter) ->
            openAPI.components.addParameters(name, parameter)
        }
        componentContext.headers.forEach { (name, header) ->
            openAPI.components.addHeaders(name, header)
        }
        componentContext.requestBodies.forEach { (name, requestBody) ->
            openAPI.components.addRequestBodies(name, requestBody)
        }
        componentContext.responses.forEach { (name, response) ->
            openAPI.components.addResponses(name, response)
        }
    }
```

- [ ] **Step 2: Point Spring customizer at the catalog path**

Modify `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/openapi/WowOpenApiCustomizer.kt`:

```kotlin
package me.ahoo.wow.spring.boot.starter.openapi

import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.wow.openapi.RouterSpecs
import org.springdoc.core.customizers.OpenApiCustomizer

class WowOpenApiCustomizer(private var routerSpecs: RouterSpecs) : OpenApiCustomizer {
    override fun customise(openApi: OpenAPI) {
        routerSpecs.mergeOpenAPIFromCatalog(openApi)
    }
}
```

- [ ] **Step 3: Run OpenAPI snapshot tests**

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest"
```

Expected: `BUILD SUCCESSFUL`. If the snapshot diff shows only sorted field order differences, update the normalizer in Task 1 instead of accepting a REST contract change.

- [ ] **Step 4: Run starter compile test**

Run:

```bash
./gradlew :wow-spring-boot-starter:compileKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/RouterSpecs.kt \
        wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/openapi/WowOpenApiCustomizer.kt
git commit -m "refactor(openapi): render springdoc output from route catalog"
```

## Task 7: Add Handler-Key WebFlux Factory API

**Files:**
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionFactory.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionRegistrar.kt`
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionRegistrarContractTest.kt`

- [ ] **Step 1: Write registrar contract test**

Create `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionRegistrarContractTest.kt`:

```kotlin
package me.ahoo.wow.webflux.route

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerResponse

internal class RouteHandlerFunctionRegistrarContractTest {
    @Test
    fun `should resolve factory by handler key`() {
        val factory = object : HttpRouteHandlerFunctionFactory {
            override val handlerKey: String = "test.handler"
            override fun create(
                contract: HttpRouteContract,
                metadata: HttpRouteHandlerMetadata
            ): HandlerFunction<ServerResponse> {
                return HandlerFunction { ServerResponse.ok().build() }
            }
        }

        val registrar = RouteHandlerFunctionRegistrar(httpFactories = listOf(factory))

        registrar.getHttpFactory("test.handler").assert().isSameAs(factory)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.RouteHandlerFunctionRegistrarContractTest"
```

Expected: FAIL with unresolved reference `HttpRouteHandlerFunctionFactory`.

- [ ] **Step 3: Add handler-key factory API**

Modify `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionFactory.kt`:

```kotlin
package me.ahoo.wow.webflux.route

import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerResponse

interface RouteHandlerFunctionFactory<R : RouteSpec> {
    val supportedSpec: Class<R>
    fun create(spec: R): HandlerFunction<ServerResponse>
}

interface HttpRouteHandlerFunctionFactory {
    val handlerKey: String
    fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata = contract.handlerMetadata
    ): HandlerFunction<ServerResponse>
}
```

- [ ] **Step 4: Add handler-key registry support**

Modify `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionRegistrar.kt`:

```kotlin
package me.ahoo.wow.webflux.route

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.openapi.RouteSpec

class RouteHandlerFunctionRegistrar(
    factories: Collection<RouteHandlerFunctionFactory<*>> = emptyList(),
    httpFactories: Collection<HttpRouteHandlerFunctionFactory> = emptyList()
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val factories: MutableMap<Class<out RouteSpec>, RouteHandlerFunctionFactory<*>> =
        factories.associateBy { it.supportedSpec }.toMutableMap()
    private val httpFactories: MutableMap<String, HttpRouteHandlerFunctionFactory> =
        httpFactories.associateBy { it.handlerKey }.toMutableMap()

    fun register(routeHandlerFunctionFactory: RouteHandlerFunctionFactory<*>) {
        val previous = factories.put(routeHandlerFunctionFactory.supportedSpec, routeHandlerFunctionFactory)
        log.info {
            "Register - supportedSpec:[${routeHandlerFunctionFactory.supportedSpec}] - previous:[$previous],current:[$routeHandlerFunctionFactory]."
        }
    }

    fun register(httpRouteHandlerFunctionFactory: HttpRouteHandlerFunctionFactory) {
        val previous = httpFactories.put(httpRouteHandlerFunctionFactory.handlerKey, httpRouteHandlerFunctionFactory)
        log.info {
            "Register - handlerKey:[${httpRouteHandlerFunctionFactory.handlerKey}] - previous:[$previous],current:[$httpRouteHandlerFunctionFactory]."
        }
    }

    fun getFactory(spec: RouteSpec): RouteHandlerFunctionFactory<*>? {
        return factories[spec::class.java]
    }

    fun getHttpFactory(handlerKey: String): HttpRouteHandlerFunctionFactory? {
        return httpFactories[handlerKey]
    }
}
```

- [ ] **Step 5: Run registrar test**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.RouteHandlerFunctionRegistrarContractTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionFactory.kt \
        wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionRegistrar.kt \
        wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/RouteHandlerFunctionRegistrarContractTest.kt
git commit -m "feat(webflux): add handler-key route factory API"
```

## Task 8: Switch RouterFunctionBuilder To RouteCatalog

**Files:**
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouterFunctionBuilder.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfiguration.kt`
- Test: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/RouterFunctionBuilderTest.kt`

- [ ] **Step 1: Update RouterFunctionBuilder constructor and build loop**

Modify `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouterFunctionBuilder.kt`:

```kotlin
package me.ahoo.wow.webflux.route

import me.ahoo.wow.openapi.RouterSpecs
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RequestPredicates
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse

@Suppress("LongParameterList")
class RouterFunctionBuilder(
    private val routerSpecs: RouterSpecs,
    private val routeHandlerFunctionRegistrar: RouteHandlerFunctionRegistrar
) {

    fun build(): RouterFunction<ServerResponse> {
        val routerFunctionBuilder = RouterFunctions.route()
        for (contract in routerSpecs.toRouteCatalog()) {
            val acceptMediaTypes = MediaType.parseMediaTypes(contract.accept).toTypedArray()
            val acceptPredicate = RequestPredicates.accept(*acceptMediaTypes)
            val httpMethod = HttpMethod.valueOf(contract.method)
            val requestPredicate = RequestPredicates.path(contract.path)
                .and(RequestPredicates.method(httpMethod))
                .and(acceptPredicate)

            val factory = requireNotNull(routeHandlerFunctionRegistrar.getHttpFactory(contract.handlerKey)) {
                "HttpRouteHandlerFunctionFactory not found - handlerKey:[${contract.handlerKey}], " +
                    "method:[${contract.method}], path:[${contract.path}], routeId:[${contract.routeId}]."
            }
            val handlerFunction = factory.create(contract, contract.handlerMetadata)
            routerFunctionBuilder.route(
                requestPredicate,
                handlerFunction
            )
        }
        return routerFunctionBuilder.build()
    }
}
```

- [ ] **Step 2: Update auto-configuration registrar wiring**

Modify the `routeHandlerFunctionRegistrar` bean in `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfiguration.kt` so it collects both old and new factory lists:

```kotlin
    @Bean
    fun routeHandlerFunctionRegistrar(
        routeModules: ObjectProvider<WebFluxRouteModule>,
        factories: ObjectProvider<RouteHandlerFunctionFactory<*>>,
        httpFactories: ObjectProvider<HttpRouteHandlerFunctionFactory>
    ): RouteHandlerFunctionRegistrar {
        val mergedFactories = mutableListOf<RouteHandlerFunctionFactory<*>>()
        routeModules.orderedStream().forEach { routeModule ->
            mergedFactories.addAll(routeModule.factories)
        }
        factories.orderedStream().forEach { factory ->
            mergedFactories.add(factory)
        }
        val mergedHttpFactories = mutableListOf<HttpRouteHandlerFunctionFactory>()
        httpFactories.orderedStream().forEach { factory ->
            mergedHttpFactories.add(factory)
        }
        return RouteHandlerFunctionRegistrar(
            factories = mergedFactories,
            httpFactories = mergedHttpFactories
        )
    }
```

Add this import:

```kotlin
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
```

- [ ] **Step 3: Run compile to expose missing handler-key factories**

Run:

```bash
./gradlew :wow-webflux:compileKotlin :wow-spring-boot-starter:compileKotlin
```

Expected: compile may pass, but runtime tests that build routes fail until Task 9 registers handler-key factories.

- [ ] **Step 4: Commit only if compile passes**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/RouterFunctionBuilder.kt \
        wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfiguration.kt
git commit -m "refactor(webflux): build routes from route catalog"
```

## Task 9: Add Handler-Key Factories For Existing WebFlux Handlers

**Files:**
- Modify all existing handler factory files under:
  - `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/command/`
  - `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/wait/`
  - `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/state/`
  - `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/`
  - `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/event/`
  - `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/global/`

- [ ] **Step 1: Update each factory to implement HttpRouteHandlerFunctionFactory**

For each factory, keep the old `RouteHandlerFunctionFactory<SpecificRouteSpec>` implementation temporarily and also implement `HttpRouteHandlerFunctionFactory`.

For `CommandHandlerFunctionFactory`, use this pattern:

```kotlin
class CommandHandlerFunctionFactory(
    private val commandGateway: CommandGateway,
    private val commandMessageExtractor: CommandMessageExtractor,
    private val exceptionHandler: RequestExceptionHandler,
    private val commandWaitPolicy: CommandWaitPolicy
) : RouteHandlerFunctionFactory<CommandRouteSpec>, HttpRouteHandlerFunctionFactory {
    override val supportedSpec: Class<CommandRouteSpec>
        get() = CommandRouteSpec::class.java

    override val handlerKey: String
        get() = CommandRouteSpec::class.java.name

    @Suppress("UNCHECKED_CAST")
    override fun create(spec: CommandRouteSpec): HandlerFunction<ServerResponse> {
        return CommandHandlerFunction(
            aggregateRouteMetadata = spec.aggregateRouteMetadata,
            commandRouteMetadata = spec.commandRouteMetadata as CommandRouteMetadata<Any>,
            commandGateway = commandGateway,
            commandMessageExtractor = commandMessageExtractor,
            exceptionHandler = exceptionHandler,
            commandWaitPolicy = commandWaitPolicy
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata
    ): HandlerFunction<ServerResponse> {
        val commandMetadata = metadata as HttpRouteHandlerMetadata.Command
        return CommandHandlerFunction(
            aggregateRouteMetadata = commandMetadata.aggregateRouteMetadata,
            commandRouteMetadata = commandMetadata.commandRouteMetadata as CommandRouteMetadata<Any>,
            commandGateway = commandGateway,
            commandMessageExtractor = commandMessageExtractor,
            exceptionHandler = exceptionHandler,
            commandWaitPolicy = commandWaitPolicy
        )
    }
}
```

Required imports for the pattern:

```kotlin
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
```

For aggregate-only handlers, cast metadata as:

```kotlin
val aggregateMetadata = metadata as HttpRouteHandlerMetadata.Aggregate
```

For global handlers, ignore metadata:

```kotlin
class GlobalIdHandlerFunctionFactory :
    RouteHandlerFunctionFactory<GenerateGlobalIdRouteSpec>,
    HttpRouteHandlerFunctionFactory {
    override val supportedSpec: Class<GenerateGlobalIdRouteSpec>
        get() = GenerateGlobalIdRouteSpec::class.java

    override val handlerKey: String
        get() = GenerateGlobalIdRouteSpec::class.java.name

    override fun create(spec: GenerateGlobalIdRouteSpec): HandlerFunction<ServerResponse> {
        return GlobalIdHandlerFunction()
    }

    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata
    ): HandlerFunction<ServerResponse> {
        return GlobalIdHandlerFunction()
    }
}
```

Use the same direct-instantiation pattern for `GetWowMetadataHandlerFunctionFactory` and
`GenerateBIScriptHandlerFunctionFactory`; the BI factory must pass its existing
`kafkaBootstrapServers` and `topicPrefix` constructor properties to
`GenerateBIScriptHandlerFunction`.

- [ ] **Step 2: Register route module factories as both old and new factories**

Modify `WebFluxRouteModule` so it exposes `httpFactories`.

Use this shape:

```kotlin
interface WebFluxRouteModule {
    val factories: List<RouteHandlerFunctionFactory<*>>
    val httpFactories: List<HttpRouteHandlerFunctionFactory>
        get() = factories.filterIsInstance<HttpRouteHandlerFunctionFactory>()
}
```

Update `WebFluxAutoConfiguration.routeHandlerFunctionRegistrar` to include `routeModule.httpFactories` in `mergedHttpFactories`.

- [ ] **Step 3: Run route builder tests**

Run:

```bash
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.RouterFunctionBuilderTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run WebFlux tests**

Run:

```bash
./gradlew :wow-webflux:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux \
        wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux
git commit -m "refactor(webflux): resolve handlers by route contract key"
```

## Task 10: Replace ServiceLoader Semantics With Explicit Contributors

**Files:**
- Create contributor classes under `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/RouterSpecs.kt`
- Test: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/catalog/RouteCatalogTest.kt`

- [ ] **Step 1: Add contributor ordering test**

Append to `RouteCatalogTest`:

```kotlin
    @Test
    fun `builder should collect contributors in explicit order`() {
        val first = object : RouteContributor {
            override val id: String = "first"
            override val category: RouteCategory = RouteCategory.GLOBAL
            override val order: Int = 20
            override fun contributeGlobal(
                currentContext: me.ahoo.wow.api.naming.NamedBoundedContext,
                componentContext: me.ahoo.wow.openapi.context.OpenAPIComponentContext
            ) = listOf(HttpRouteContract(routeId = "second", method = "GET", path = "/second", handlerKey = "second"))
        }
        val second = object : RouteContributor {
            override val id: String = "second"
            override val category: RouteCategory = RouteCategory.GLOBAL
            override val order: Int = 10
            override fun contributeGlobal(
                currentContext: me.ahoo.wow.api.naming.NamedBoundedContext,
                componentContext: me.ahoo.wow.openapi.context.OpenAPIComponentContext
            ) = listOf(HttpRouteContract(routeId = "first", method = "GET", path = "/first", handlerKey = "first"))
        }

        val contributors = listOf(first, second).sortedWith(compareBy<RouteContributor> { it.order }.thenBy { it.id })

        contributors.map { it.id }.assert().isEqualTo(listOf("second", "first"))
    }
```

- [ ] **Step 2: Run test**

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.catalog.RouteCatalogTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Create explicit contributor list**

Create `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/DefaultRouteContributors.kt`:

```kotlin
package me.ahoo.wow.openapi.contributor

import me.ahoo.wow.openapi.catalog.RouteContributor

object DefaultRouteContributors {
    fun all(): List<RouteContributor> {
        return listOf(
            LegacyRouteContributor
        )
    }
}
```

Create `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/LegacyRouteContributor.kt`:

```kotlin
package me.ahoo.wow.openapi.contributor

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.catalog.RouteCategory
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata

object LegacyRouteContributor : RouteContributor {
    override val id: String = "legacy-route-spec-adapter"
    override val category: RouteCategory = RouteCategory.GLOBAL
    override val order: Int = Int.MAX_VALUE

    override fun contributeGlobal(
        currentContext: NamedBoundedContext,
        componentContext: OpenAPIComponentContext
    ): List<HttpRouteContract> = emptyList()

    override fun contributeAggregate(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): List<HttpRouteContract> = emptyList()
}
```

This legacy contributor is a named marker only. Existing `RouterSpecs.toRouteCatalog()` still adapts old routes until route families are migrated.

- [ ] **Step 4: Run openapi tests**

Run:

```bash
./gradlew :wow-openapi:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor \
        wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/catalog/RouteCatalogTest.kt
git commit -m "refactor(openapi): introduce explicit route contributors"
```

## Task 11: Migrate Route Families To Contributors

**Files:**
- Modify or replace route family files under:
  - `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/global/`
  - `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/aggregate/command/`
  - `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/aggregate/state/`
  - `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/aggregate/snapshot/`
  - `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/aggregate/event/`

- [ ] **Step 1: Migrate global routes**

Create contributor classes for:

```text
CommandWaitRouteContributor
CommandFacadeRouteContributor
GetWowMetadataRouteContributor
GenerateGlobalIdRouteContributor
GenerateBIScriptRouteContributor
```

Each contributor must emit the same route id, path, method, parameters, request body, responses, tags, and handler key as the current corresponding route spec.

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest"
```

Expected: `BUILD SUCCESSFUL`.

Commit:

```bash
git add wow-openapi/src/main/kotlin/me/ahoo/wow/openapi
git commit -m "refactor(openapi): migrate global routes to contributors"
```

- [ ] **Step 2: Migrate command routes**

Replace `CommandRouteSpecFactory` generation with a command contributor that iterates:

```kotlin
aggregateMetadata.command.registeredCommands
DefaultDeleteAggregate
DefaultRecoverAggregate
DefaultApplyResourceTags
```

Keep current rules for:

```text
appendTenantPath
appendOwnerPath
appendIdPath
path variable filtering
header variable generation
Command common headers
request body schema
command response statuses
```

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest"
./gradlew :wow-webflux:test --tests "me.ahoo.wow.webflux.route.command.CommandHandlerFunctionTest"
```

Expected: both commands pass.

Commit:

```bash
git add wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/aggregate/command \
        wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor
git commit -m "refactor(openapi): migrate command routes to contributors"
```

- [ ] **Step 3: Migrate state routes**

Create contributors for:

```text
AggregateTracing
LoadAggregate
LoadVersionedAggregate
LoadTimeBasedAggregate
```

Preserve the current aggregate tracing path suffix `state/tracing` and query parameters `headVersion`, `tailVersion`, and `limit`.

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest"
./gradlew :wow-webflux:test --tests "*Aggregate*"
```

Expected: both commands pass.

Commit:

```bash
git add wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/aggregate/state \
        wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor
git commit -m "refactor(openapi): migrate state routes to contributors"
```

- [ ] **Step 4: Migrate snapshot routes**

Create contributors for:

```text
LoadSnapshot
RegenerateSnapshot
BatchRegenerateSnapshot
CountSnapshot
ListQuerySnapshot
ListQuerySnapshotState
PagedQuerySnapshot
PagedQuerySnapshotState
SingleSnapshot
SingleSnapshotState
```

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest"
./gradlew :wow-webflux:test --tests "*Snapshot*"
```

Expected: both commands pass.

Commit:

```bash
git add wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/aggregate/snapshot \
        wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor
git commit -m "refactor(openapi): migrate snapshot routes to contributors"
```

- [ ] **Step 5: Migrate event routes**

Create contributors for:

```text
LoadEventStream
ListQueryEventStream
PagedQueryEventStream
CountEventStream
EventCompensate
ResendStateEvent
```

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest"
./gradlew :wow-webflux:test --tests "*Event*"
```

Expected: both commands pass.

Commit:

```bash
git add wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/aggregate/event \
        wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor
git commit -m "refactor(openapi): migrate event routes to contributors"
```

## Task 12: Delete Old RouteSpec Main Path

**Files:**
- Delete or simplify obsolete files:
  - `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/RouteSpec.kt`
  - `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/RouteSpecFactory.kt`
  - `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/aggregate/AggregateRouteSpecFactory.kt`
  - `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/aggregate/AggregateRouteSpecFactoryProvider.kt`
  - `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/global/GlobalRouteSpecFactory.kt`
  - `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/global/GlobalRouteSpecFactoryProvider.kt`
  - `wow-openapi/src/main/resources/META-INF/services/me.ahoo.wow.openapi.aggregate.AggregateRouteSpecFactory`
  - `wow-openapi/src/main/resources/META-INF/services/me.ahoo.wow.openapi.global.GlobalRouteSpecFactory`
  - `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/context/CurrentOpenAPIComponentContext.kt`

- [ ] **Step 1: Search old architecture references**

Run:

```bash
rg -n "RouteSpec|RouteSpecFactory|CurrentOpenAPIComponentContext|ServiceLoader.load\\(.*RouteSpecFactory|supportedSpec" wow-openapi wow-webflux wow-spring-boot-starter
```

Expected: references still exist before deletion.

- [ ] **Step 2: Remove old ServiceLoader files**

Run:

```bash
git rm wow-openapi/src/main/resources/META-INF/services/me.ahoo.wow.openapi.aggregate.AggregateRouteSpecFactory
git rm wow-openapi/src/main/resources/META-INF/services/me.ahoo.wow.openapi.global.GlobalRouteSpecFactory
```

- [ ] **Step 3: Remove old current context**

Run:

```bash
git rm wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/context/CurrentOpenAPIComponentContext.kt
```

Update `WowSchemaConverter` so it no longer uses `CurrentOpenAPIComponentContext`. If Springdoc still needs a converter, make the converter fall through to the next converter and keep schema generation owned by `OpenApiRenderer`.

- [ ] **Step 4: Remove old class matching**

Update `RouteHandlerFunctionFactory.kt`, `RouteHandlerFunctionRegistrar.kt`, and all WebFlux factories to remove:

```kotlin
val supportedSpec: Class<R>
fun create(spec: R): HandlerFunction<ServerResponse>
```

Keep only:

```kotlin
interface HttpRouteHandlerFunctionFactory {
    val handlerKey: String
    fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata = contract.handlerMetadata
    ): HandlerFunction<ServerResponse>
}
```

- [ ] **Step 5: Remove old route spec hierarchy**

Delete route spec classes only after every contributor and handler-key factory is green.

Run:

```bash
rg -n "RouteSpec|RouteSpecFactory|CurrentOpenAPIComponentContext|supportedSpec" wow-openapi wow-webflux wow-spring-boot-starter
```

Expected: no production-code references remain. Test references may remain only if they test removed compatibility paths; delete those tests in the same commit.

- [ ] **Step 6: Run verification**

Run:

```bash
./gradlew :wow-openapi:test
./gradlew :wow-webflux:test
./gradlew :wow-spring-boot-starter:compileKotlin
```

Expected: all commands pass.

- [ ] **Step 7: Commit**

```bash
git add -u wow-openapi wow-webflux wow-spring-boot-starter
git commit -m "refactor(openapi): remove legacy route spec architecture"
```

## Task 13: Final Compatibility Verification

**Files:**
- Modify only if verification reveals intentional documentation updates:
  - `documentation/docs/zh/guide/open-api.md`
  - `documentation/docs/en/guide/open-api.md`
  - `documentation/docs/zh/guide/extensions/webflux.md`
  - `documentation/docs/en/guide/extensions/webflux.md`

- [ ] **Step 1: Run full focused checks**

Run:

```bash
./gradlew :wow-openapi:test :wow-webflux:test :wow-spring-boot-starter:compileKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run snapshot comparison**

Run:

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest"
```

Expected: `BUILD SUCCESSFUL` with no snapshot update.

- [ ] **Step 3: Confirm old architecture is gone**

Run:

```bash
rg -n "CurrentOpenAPIComponentContext|ServiceLoader.load\\(.*RouteSpecFactory|supportedSpec|RouteSpecFactoryProvider" wow-openapi wow-webflux wow-spring-boot-starter
```

Expected: no matches in production code.

- [ ] **Step 4: Review generated diff**

Run:

```bash
git diff --stat
git diff -- wow-openapi wow-webflux wow-spring-boot-starter
```

Expected: only contract-kernel, renderer, WebFlux handler-key binding, tests, and optional docs are changed.

- [ ] **Step 5: Commit final docs when documentation changed**

If documentation was updated, run:

```bash
git add documentation/docs/zh/guide/open-api.md \
        documentation/docs/en/guide/open-api.md \
        documentation/docs/zh/guide/extensions/webflux.md \
        documentation/docs/en/guide/extensions/webflux.md
git commit -m "docs(openapi): document contract kernel architecture"
```

If no documentation changed, skip this commit.

## Execution Notes

- Use an isolated worktree at execution time if this is not already isolated.
- Keep commits focused by task.
- Do not update snapshots to accept REST behavior changes unless the user explicitly approves the diff.
- Do not introduce a new Gradle module.
- Do not refactor `wow-schema`.
- Do not preserve old `RouteSpec` compatibility facades at the end.
