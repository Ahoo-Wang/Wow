# Elasticsearch Snapshot Index Auto-Loading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically create conventionally named Elasticsearch snapshot indexes from application-provided native Create Index JSON while preserving the generic template fallback.

**Architecture:** Add one JDK-only resource locator in `wow-core`, reuse it from Query Schema and a new Elasticsearch snapshot index initializer, then invoke that initializer during Spring Boot SnapshotStore wiring. Resource presence is the creation contract; JSON validation and storage routing remain outside Wow's responsibility.

**Tech Stack:** Kotlin 2.4.10, JVM 17, Reactor, Elasticsearch Java Client 9.4.x through Spring Data Elasticsearch, Spring Boot 4.1, JUnit Jupiter/JUnit 6, MockK, `me.ahoo.test:fluent-assert-core`.

**Spec:** `documentation/designs/2026-09-02-elasticsearch-snapshot-index-auto-loading-design.md`

## Global Constraints

- Do not add dependencies or move storage responsibilities across existing module boundaries.
- Keep `wow.elasticsearch.auto-init-template=true` and the generic `wow-snapshot-template` behavior unchanged.
- Use `META-INF/wow/{feature}/{resourceKey}.json` for classpath resources and `config/wow/{feature}/{resourceKey}.json` for working-directory overrides.
- Treat `.` as the existing reserved named-aggregate delimiter; do not add escaping or encoding.
- Elasticsearch configuration is native Create Index JSON; use the Elasticsearch client's `withJson(InputStream)` behavior without adding Wow-owned field validation.
- Concrete index initialization is create-only: existing indexes are skipped and never reconciled or updated.
- Do not inspect storage routing; a matching Elasticsearch resource is the explicit creation declaration.
- A working-directory Elasticsearch resource replaces classpath resources; without it, zero or one classpath resource is allowed.
- Preserve Query Schema source priorities, classpath merging, refresh behavior, and old resource paths as fallback.
- Keep reactive library APIs; blocking remains limited to the existing Spring Boot startup wiring.
- Use FluentAssert `.assert()` in Kotlin tests and run the narrowest relevant Gradle task after each task.
- Every new Kotlin source file must carry the repository's existing Apache 2.0 header.

---

## File Map

- `wow-core/src/main/kotlin/me/ahoo/wow/configuration/WowResourceLocator.kt`: shared path validation, working-directory lookup, classpath enumeration, resource reading, and diagnostic locations.
- `wow-core/src/test/kotlin/me/ahoo/wow/configuration/WowResourceLocatorTest.kt`: locator contract and failure coverage.
- `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaSources.kt`: new unified Query Schema paths with per-source legacy fallback.
- `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaSourcesTest.kt`: new-path precedence, old-path fallback, merging, refresh, and scheduler coverage.
- `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/ElasticsearchSnapshotIndexInitializer.kt`: resource selection, request parsing, exists/create flow, acknowledgment, and concurrent-create handling.
- `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/ElasticsearchSnapshotIndexInitializerTest.kt`: initializer unit coverage.
- `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/ElasticsearchSnapshotIndexInitializerTest.kt`: real-cluster creation and mapping capability verification.
- `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchEventSourcingAutoConfiguration.kt`: initializer bean and startup ordering.
- `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchEventSourcingAutoConfigurationTest.kt`: Boot ordering, disabled-template behavior, and failure propagation.
- `compensation/wow-compensation-server/src/main/resources/META-INF/wow/elasticsearch/wow.compensation.execution_failed.snapshot.json`: conventionally located real index definition.
- `compensation/wow-compensation-server/src/test/kotlin/me/ahoo/wow/compensation/server/ExecutionFailedQuerySchemaTest.kt`: compensation resource-name and parse contract.
- `documentation/docs/{zh,en}/guide/extensions/elasticsearch.md`: generic-template versus query-index guidance and create-only lifecycle.
- `documentation/docs/{zh,en}/guide/query/query-model-schema.md`: unified path and legacy fallback documentation.
- `documentation/docs/{zh,en}/reference/config/infrastructure.md`: startup behavior under existing Elasticsearch properties.

---

### Task 1: Add the Unified Wow Resource Locator

**Files:**
- Create: `wow-core/src/main/kotlin/me/ahoo/wow/configuration/WowResourceLocator.kt`
- Create: `wow-core/src/test/kotlin/me/ahoo/wow/configuration/WowResourceLocatorTest.kt`

**Interfaces:**
- Consumes: JDK `Path`, `Files`, `ClassLoader`, `URL`, and `Collections.list`.
- Produces:
  - `class WowResourceLocator(configDirectory: Path = Path.of("config"), classLoader: ClassLoader = Thread.currentThread().contextClassLoader ?: WowResourceLocator::class.java.classLoader, pathReader: (Path) -> String = Files::readString)`
  - `fun findWorkingDirectory(feature: String, resourceKey: String): WowResource?`
  - `fun findClasspath(feature: String, resourceKey: String): List<WowResource>`
  - `class WowResource` with `val location: String` and `fun readText(): String`

- [ ] **Step 1: Write failing locator tests**

Create `WowResourceLocatorTest.kt` with the existing Apache header and these tests:

```kotlin
package me.ahoo.wow.configuration

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

class WowResourceLocatorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `working directory resource should use unified path`() {
        val file = tempDir.resolve("wow/query-schema/sales.order.snapshot.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, "working")

        val resource = WowResourceLocator(configDirectory = tempDir)
            .findWorkingDirectory("query-schema", "sales.order.snapshot")

        resource.assert().isNotNull()
        resource!!.location.assert().isEqualTo(file.toString())
        resource.readText().assert().isEqualTo("working")
    }

    @Test
    fun `classpath resources should use unified path and stable order`() {
        val firstRoot = tempDir.resolve("a")
        val secondRoot = tempDir.resolve("z")
        val relative = "META-INF/wow/query-schema/sales.order.snapshot.json"
        listOf(firstRoot to "first", secondRoot to "second").forEach { (root, value) ->
            val file = root.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, value)
        }

        URLClassLoader(arrayOf(secondRoot.toUri().toURL(), firstRoot.toUri().toURL()), null).use { loader ->
            WowResourceLocator(configDirectory = tempDir, classLoader = loader)
                .findClasspath("query-schema", "sales.order.snapshot")
                .map(WowResource::readText)
                .assert().containsExactly("first", "second")
        }
    }

    @Test
    fun `invalid feature and resource keys should be rejected`() {
        val locator = WowResourceLocator(configDirectory = tempDir)
        listOf("", ".", "..", "query/schema", "query\\schema").forEach { invalid ->
            assertThrows<IllegalArgumentException> {
                locator.findWorkingDirectory(invalid, "sales.order.snapshot")
            }
            assertThrows<IllegalArgumentException> {
                locator.findWorkingDirectory("query-schema", invalid)
            }
        }
    }

    @Test
    fun `classpath listing failure should retain its cause`() {
        val expected = IOException("unavailable")
        val loader = object : ClassLoader(null) {
            override fun getResources(name: String): java.util.Enumeration<java.net.URL> = throw expected
        }

        val actual = assertThrows<IllegalStateException> {
            WowResourceLocator(configDirectory = tempDir, classLoader = loader)
                .findClasspath("query-schema", "sales.order.snapshot")
        }

        actual.cause.assert().isSameAs(expected)
    }

    @Test
    fun `resource read failure should include location and retain cause`() {
        val file = tempDir.resolve("wow/elasticsearch/wow.order.snapshot.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, "{}")
        val expected = IOException("unreadable")
        val resource = WowResourceLocator(
            configDirectory = tempDir,
            pathReader = { throw expected },
        ).findWorkingDirectory("elasticsearch", "wow.order.snapshot")!!

        val actual = assertThrows<IllegalStateException>(resource::readText)

        actual.message.assert().contains(resource.location)
        actual.cause.assert().isSameAs(expected)
    }
}
```

- [ ] **Step 2: Run the test and verify the missing API failure**

Run:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.configuration.WowResourceLocatorTest"
```

Expected: compilation fails because `WowResourceLocator` and `WowResource` do not exist.

- [ ] **Step 3: Implement the minimal JDK-only locator**

Create `WowResourceLocator.kt`:

```kotlin
package me.ahoo.wow.configuration

import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

class WowResource internal constructor(
    val location: String,
    private val reader: () -> String,
) {
    fun readText(): String = try {
        reader()
    } catch (error: Exception) {
        throw IllegalStateException("Unable to read Wow resource [$location].", error)
    }
}

class WowResourceLocator(
    private val configDirectory: Path = Path.of("config"),
    private val classLoader: ClassLoader =
        Thread.currentThread().contextClassLoader ?: WowResourceLocator::class.java.classLoader,
    private val pathReader: (Path) -> String = Files::readString,
) {
    fun findWorkingDirectory(feature: String, resourceKey: String): WowResource? {
        val file = configDirectory.resolve("wow").resolve(relativePath(feature, resourceKey))
        if (Files.notExists(file)) return null
        return WowResource(file.toString()) { pathReader(file) }
    }

    fun findClasspath(feature: String, resourceKey: String): List<WowResource> {
        val resourcePath = "META-INF/wow/${relativePath(feature, resourceKey)}"
        val resources = try {
            Collections.list(classLoader.getResources(resourcePath)).sortedBy(URL::toExternalForm)
        } catch (error: Exception) {
            throw IllegalStateException("Unable to list Wow resources [$resourcePath].", error)
        }
        return resources.map { resource ->
            WowResource(resource.toExternalForm()) {
                resource.openStream().bufferedReader().use { it.readText() }
            }
        }
    }

    private fun relativePath(feature: String, resourceKey: String): String {
        listOf(feature, resourceKey).forEach { segment ->
            require(
                segment.isNotBlank() && '/' !in segment && '\\' !in segment &&
                    segment != "." && segment != ".."
            ) { "Wow resource path segment is invalid: [$segment]." }
        }
        return "$feature/$resourceKey.json"
    }
}
```

Add the repository Apache header before `package`.

- [ ] **Step 4: Run focused and module tests**

Run:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.configuration.WowResourceLocatorTest"
./gradlew :wow-core:check
```

Expected: both commands pass.

- [ ] **Step 5: Commit Task 1**

```bash
git add wow-core/src/main/kotlin/me/ahoo/wow/configuration/WowResourceLocator.kt wow-core/src/test/kotlin/me/ahoo/wow/configuration/WowResourceLocatorTest.kt
git commit -m "feat(core): add convention resource locator"
```

---

### Task 2: Move Query Schema Discovery onto the Unified Convention

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaSources.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaSourcesTest.kt`

**Interfaces:**
- Consumes: `WowResourceLocator.findWorkingDirectory`, `WowResourceLocator.findClasspath`, and `WowResource.readText` from Task 1.
- Produces: unchanged public `WorkingDirectoryQuerySchemaSource` and `ClasspathQuerySchemaSource` behavior with new paths preferred and old paths retained as fallback.

- [ ] **Step 1: Add failing new-path and fallback tests**

Extend `QuerySchemaSourcesTest.kt` with these helpers:

```kotlin
private fun QuerySchemaContext.unifiedResourcePathForTest() =
    "META-INF/wow/query-schema/" +
        "${namedAggregate.contextName}.${namedAggregate.aggregateName}.${model.value.lowercase()}.json"

private fun QuerySchemaContext.unifiedWorkingPathForTest() =
    "wow/query-schema/" +
        "${namedAggregate.contextName}.${namedAggregate.aggregateName}.${model.value.lowercase()}.json"

private fun writeUnifiedWorkingFile(json: String): Path {
    val file = tempDir.resolve(ORDER_CONTEXT.unifiedWorkingPathForTest())
    Files.createDirectories(file.parent)
    return Files.writeString(file, json)
}

private fun writeUnifiedClasspathFile(
    root: Path,
    json: String,
    context: QuerySchemaContext = ORDER_CONTEXT,
): Path {
    val file = root.resolve(context.unifiedResourcePathForTest())
    Files.createDirectories(file.parent)
    return Files.writeString(file, json)
}
```

Add the following tests:

```kotlin
@Test
fun `working directory source should prefer unified path over legacy path`() {
    writeWorkingFile(conventionJson("Legacy"))
    writeUnifiedWorkingFile(conventionJson("Unified"))

    WorkingDirectoryQuerySchemaSource(basePath = tempDir).load(ORDER_CONTEXT)
        .single().block()!!.title().assert().isEqualTo(DeclarationValue.Set("Unified"))
}

@Test
fun `working directory source should fall back to legacy path`() {
    writeWorkingFile(conventionJson("Legacy"))

    WorkingDirectoryQuerySchemaSource(basePath = tempDir).load(ORDER_CONTEXT)
        .single().block()!!.title().assert().isEqualTo(DeclarationValue.Set("Legacy"))
}

@Test
fun `classpath source should prefer unified resources over legacy resources`() {
    val root = tempDir.resolve("root")
    writeClasspathFile(root, conventionJson("Legacy"))
    writeUnifiedClasspathFile(root, conventionJson("Unified"))

    URLClassLoader(arrayOf(root.toUri().toURL()), null).use { loader ->
        ClasspathQuerySchemaSource(loader).load(ORDER_CONTEXT)
            .single().block()!!.title().assert().isEqualTo(DeclarationValue.Set("Unified"))
    }
}

@Test
fun `classpath source should fall back to legacy resources`() {
    val root = tempDir.resolve("root")
    writeClasspathFile(root, conventionJson("Legacy"))

    URLClassLoader(arrayOf(root.toUri().toURL()), null).use { loader ->
        ClasspathQuerySchemaSource(loader).load(ORDER_CONTEXT)
            .single().block()!!.title().assert().isEqualTo(DeclarationValue.Set("Legacy"))
    }
}

@Test
fun `unified classpath resources should preserve same-priority merge behavior`() {
    val firstRoot = tempDir.resolve("a")
    val secondRoot = tempDir.resolve("z")
    writeUnifiedClasspathFile(firstRoot, conventionJson("Same"))
    writeUnifiedClasspathFile(secondRoot, conventionJson("Same"))

    URLClassLoader(arrayOf(secondRoot.toUri().toURL(), firstRoot.toUri().toURL()), null).use { loader ->
        ClasspathQuerySchemaSource(loader).load(ORDER_CONTEXT).collectList().block()!!
            .assert().hasSize(2)
    }
}
```

Change the existing classpath refresh test to write the unified path with `writeUnifiedClasspathFile`; keep the existing caller-thread tests unchanged so they verify the locator is still called under `boundedElastic`.

- [ ] **Step 2: Run the focused test and verify new-path failures**

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QuerySchemaSourcesTest"
```

Expected: the unified-path precedence tests fail because the current sources only inspect legacy paths.

- [ ] **Step 3: Implement per-source new-path preference and legacy fallback**

In `QuerySchemaSources.kt`, import `WowResourceLocator` and add:

```kotlin
private const val QUERY_SCHEMA_FEATURE = "query-schema"

private fun QuerySchemaContext.resourceKey(): String {
    val segments = listOf(namedAggregate.contextName, namedAggregate.aggregateName, model.value)
    segments.forEach { segment ->
        require(segment.isNotBlank() && '/' !in segment && '\\' !in segment && segment != "." && segment != "..") {
            "Query schema path segment is invalid: [$segment]."
        }
    }
    return "${segments[0]}.${segments[1]}.${segments[2].lowercase(Locale.ROOT)}"
}

private fun QuerySchemaContext.legacyResourcePath(): String =
    "wow-query-schema/${namedAggregate.contextName}/${namedAggregate.aggregateName}/" +
        "${model.value.lowercase(Locale.ROOT)}.json"
```

Refactor `WorkingDirectoryQuerySchemaSource` without changing its constructor:

```kotlin
class WorkingDirectoryQuerySchemaSource(
    private val basePath: Path = Path.of("config"),
    private val readText: (Path) -> String = Files::readString,
) : QuerySchemaSource {
    private val resources = WowResourceLocator(configDirectory = basePath, pathReader = readText)

    override val priority: Int = QuerySchemaSourcePriority.WORKING_DIRECTORY

    override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.defer {
        resources.findWorkingDirectory(QUERY_SCHEMA_FEATURE, context.resourceKey())?.let { resource ->
            return@defer Flux.just(readConventionDeclaration(resource.location, resource::readText))
        }
        val legacy = basePath.resolve(context.legacyResourcePath())
        if (Files.notExists(legacy)) Flux.empty()
        else Flux.just(readConventionDeclaration(legacy.toString()) { readText(legacy) })
    }.subscribeOn(Schedulers.boundedElastic())
}
```

Refactor `ClasspathQuerySchemaSource.readDeclarations` so only one classpath convention is used per load:

```kotlin
private val resources = WowResourceLocator(classLoader = classLoader)

private fun readDeclarations(context: QuerySchemaContext): List<QuerySchemaDeclaration> {
    val unified = try {
        resources.findClasspath(QUERY_SCHEMA_FEATURE, context.resourceKey())
    } catch (error: Exception) {
        throw QuerySchemaUnavailableException(
            "Unable to list query schema resources [${context.resourceKey()}].",
            error,
        )
    }
    if (unified.isNotEmpty()) {
        return unified.map { resource ->
            readConventionDeclaration(resource.location, resource::readText)
        }
    }
    val legacyPath = context.legacyResourcePath()
    val legacy = try {
        Collections.list(classLoader.getResources(legacyPath)).sortedBy(URL::toExternalForm)
    } catch (error: Exception) {
        throw QuerySchemaUnavailableException("Unable to list query schema resources [$legacyPath].", error)
    }
    return legacy.map { resource ->
        readConventionDeclaration(resource.toExternalForm()) {
            resource.openStream().bufferedReader().use { it.readText() }
        }
    }
}
```

Remove the old `resourcePath()` function after all callers use `resourceKey()` or `legacyResourcePath()`.

- [ ] **Step 4: Run Query Schema tests and module check**

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QuerySchemaSourcesTest"
./gradlew :wow-query:check
```

Expected: both commands pass; existing legacy resource tests and new unified-path tests pass together.

- [ ] **Step 5: Commit Task 2**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaSources.kt wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaSourcesTest.kt
git commit -m "feat(query): load schema from unified resources"
```

---

### Task 3: Implement Elasticsearch Snapshot Index Initialization

**Files:**
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/ElasticsearchSnapshotIndexInitializer.kt`
- Create: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/ElasticsearchSnapshotIndexInitializerTest.kt`
- Create: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/ElasticsearchSnapshotIndexInitializerTest.kt`

**Interfaces:**
- Consumes: Task 1 `WowResourceLocator`, `MetadataSearcher.namedAggregateType.keys`, `NamedAggregate.toSnapshotIndexName()`, and `ReactiveElasticsearchClient.indices()`.
- Produces:
  - `class ElasticsearchSnapshotIndexInitializer(elasticsearchClient: ReactiveElasticsearchClient, resourceLocator: WowResourceLocator = WowResourceLocator(), namedAggregates: Iterable<NamedAggregate> = MetadataSearcher.namedAggregateType.keys)`
  - `fun ensureAll(): Mono<Void>`

- [ ] **Step 1: Write failing resource-selection and create-only unit tests**

Create `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/ElasticsearchSnapshotIndexInitializerTest.kt`. Use `MOCK_AGGREGATE_METADATA` as the single aggregate so the expected index name follows existing alias rules.

```kotlin
package me.ahoo.wow.elasticsearch

import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse
import co.elastic.clients.elasticsearch.indices.ExistsRequest
import co.elastic.clients.transport.endpoints.BooleanResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.configuration.WowResourceLocator
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchIndicesClient
import reactor.core.publisher.Mono
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

class ElasticsearchSnapshotIndexInitializerTest {
    @TempDir
    lateinit var tempDir: Path

    private val client = mockk<ReactiveElasticsearchClient>()
    private val indices = mockk<ReactiveElasticsearchIndicesClient>()

    init {
        every { client.indices() } returns indices
    }

    @Test
    fun `missing resource should not call Elasticsearch`() {
        initializer().ensureAll().block()

        verify(exactly = 0) { client.indices() }
    }

    @Test
    fun `existing index should skip create`() {
        writeWorkingResource(indexJson())
        every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(true))

        initializer().ensureAll().block()

        verify(exactly = 0) { indices.create(any<CreateIndexRequest>()) }
    }

    @Test
    fun `missing index should be created from native json`() {
        writeWorkingResource(indexJson())
        every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(false))
        val request = slot<CreateIndexRequest>()
        every { indices.create(capture(request)) } returns Mono.just(response(acknowledged = true))

        initializer().ensureAll().block()

        request.captured.index().assert().isEqualTo(INDEX)
        request.captured.settings()!!.numberOfShards().assert().isEqualTo("1")
        request.captured.aliases().assert().containsKey("snapshot-read")
        request.captured.mappings()!!.properties()["state"]!!.`object`()
            .properties()["status"]!!._kind().jsonValue().assert().isEqualTo("keyword")
    }

    @Test
    fun `working resource should suppress duplicate classpath resources`() {
        writeWorkingResource(indexJson())
        val first = tempDir.resolve("a")
        val second = tempDir.resolve("z")
        writeClasspathResource(first, indexJson())
        writeClasspathResource(second, indexJson())
        every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(true))

        URLClassLoader(arrayOf(first.toUri().toURL(), second.toUri().toURL()), null).use { loader ->
            initializer(WowResourceLocator(configDirectory = tempDir, classLoader = loader)).ensureAll().block()
        }
    }

    @Test
    fun `duplicate classpath resources should fail before Elasticsearch`() {
        val first = tempDir.resolve("a")
        val second = tempDir.resolve("z")
        writeClasspathResource(first, indexJson())
        writeClasspathResource(second, indexJson())

        URLClassLoader(arrayOf(first.toUri().toURL(), second.toUri().toURL()), null).use { loader ->
            val error = assertThrows<IllegalStateException> {
                initializer(WowResourceLocator(configDirectory = tempDir, classLoader = loader)).ensureAll().block()
            }
            error.message.assert().contains(INDEX).contains("2")
        }
        verify(exactly = 0) { client.indices() }
    }

    private fun initializer(
        locator: WowResourceLocator = WowResourceLocator(
            configDirectory = tempDir,
            classLoader = object : ClassLoader(null) {},
        ),
    ) = ElasticsearchSnapshotIndexInitializer(client, locator, listOf(MOCK_AGGREGATE_METADATA))

    private fun writeWorkingResource(json: String) {
        val file = tempDir.resolve("wow/elasticsearch/$INDEX.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, json)
    }

    private fun writeClasspathResource(root: Path, json: String) {
        val file = root.resolve("META-INF/wow/elasticsearch/$INDEX.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, json)
    }

    private fun indexJson() = """
        {
          "settings":{"number_of_shards":"1"},
          "mappings":{"properties":{"state":{"properties":{"status":{"type":"keyword"}}}}},
          "aliases":{"snapshot-read":{}}
        }
    """.trimIndent()

    private fun response(acknowledged: Boolean) = CreateIndexResponse.of {
        it.acknowledged(acknowledged).shardsAcknowledged(acknowledged).index(INDEX)
    }

    companion object {
        private val INDEX = MOCK_AGGREGATE_METADATA.toSnapshotIndexName()
    }
}
```

- [ ] **Step 2: Add failing error and concurrency tests**

In the same test class add:

```kotlin
@Test
fun `malformed resource should retain index and location`() {
    writeWorkingResource("{not-json")

    val error = assertThrows<IllegalStateException> { initializer().ensureAll().block() }

    error.message.assert().contains(INDEX).contains("config")
    error.cause.assert().isNotNull()
}

@Test
fun `empty create response should fail`() {
    writeWorkingResource(indexJson())
    every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(false))
    every { indices.create(any<CreateIndexRequest>()) } returns Mono.empty()

    assertThrows<IllegalStateException> { initializer().ensureAll().block() }
}

@Test
fun `unacknowledged create response should fail`() {
    writeWorkingResource(indexJson())
    every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(false))
    every { indices.create(any<CreateIndexRequest>()) } returns Mono.just(response(acknowledged = false))

    assertThrows<IllegalStateException> { initializer().ensureAll().block() }
}

@Test
fun `concurrent resource already exists should complete`() {
    writeWorkingResource(indexJson())
    every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(false))
    every { indices.create(any<CreateIndexRequest>()) } returns Mono.error(
        co.elastic.clients.elasticsearch._types.ElasticsearchException(
            "indices.create",
            co.elastic.clients.elasticsearch._types.ErrorResponse.of { response ->
                response.status(400).error { error ->
                    error.type("resource_already_exists_exception").reason("already exists")
                }
            },
        ),
    )

    initializer().ensureAll().block()
}

@Test
fun `other Elasticsearch failures should retain cause`() {
    writeWorkingResource(indexJson())
    val expected = IllegalStateException("cluster unavailable")
    every { indices.exists(any<ExistsRequest>()) } returns Mono.just(BooleanResponse(false))
    every { indices.create(any<CreateIndexRequest>()) } returns Mono.error(expected)

    val actual = assertThrows<IllegalStateException> { initializer().ensureAll().block() }

    actual.message.assert().contains(INDEX)
    actual.cause.assert().isSameAs(expected)
}
```

- [ ] **Step 3: Run unit tests and verify the initializer is missing**

```bash
./gradlew :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.ElasticsearchSnapshotIndexInitializerTest"
```

Expected: compilation fails because `ElasticsearchSnapshotIndexInitializer` does not exist.

- [ ] **Step 4: Implement resource resolution and create-only initialization**

Create `ElasticsearchSnapshotIndexInitializer.kt` with this implementation shape:

```kotlin
package me.ahoo.wow.elasticsearch

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.configuration.WowResource
import me.ahoo.wow.configuration.WowResourceLocator
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class ElasticsearchSnapshotIndexInitializer(
    private val elasticsearchClient: ReactiveElasticsearchClient,
    private val resourceLocator: WowResourceLocator = WowResourceLocator(),
    private val namedAggregates: Iterable<NamedAggregate> = MetadataSearcher.namedAggregateType.keys,
) {
    fun ensureAll(): Mono<Void> = Flux.defer {
        Flux.fromIterable(namedAggregates.sortedBy { it.toSnapshotIndexName() })
    }.concatMap(::ensureIndex).then()

    private fun ensureIndex(namedAggregate: NamedAggregate): Mono<Void> = Mono.defer {
        val indexName = namedAggregate.toSnapshotIndexName()
        val resource = findResource(indexName) ?: return@defer Mono.empty()
        val request = parseRequest(indexName, resource)
        elasticsearchClient.indices().exists { it.index(indexName) }
            .flatMap { exists ->
                if (exists.value()) Mono.empty()
                else create(request, resource)
            }.onErrorMap { error ->
                if (error is IndexInitializationException) error
                else failure(indexName, resource, error)
            }
    }

    private fun findResource(indexName: String): WowResource? {
        resourceLocator.findWorkingDirectory(FEATURE, indexName)?.let { return it }
        val resources = resourceLocator.findClasspath(FEATURE, indexName)
        check(resources.size <= 1) {
            "Elasticsearch snapshot index [$indexName] has ${resources.size} classpath resources: " +
                resources.joinToString { it.location }
        }
        return resources.singleOrNull()
    }

    private fun parseRequest(indexName: String, resource: WowResource): CreateIndexRequest = try {
        resource.readText().byteInputStream().use { input ->
            CreateIndexRequest.Builder().withJson(input).index(indexName).build()
        }
    } catch (error: Exception) {
        throw failure(indexName, resource, error)
    }

    private fun create(request: CreateIndexRequest, resource: WowResource): Mono<Void> =
        elasticsearchClient.indices().create(request)
            .switchIfEmpty(Mono.error(failure(request.index(), resource, null)))
            .flatMap { response ->
                if (response.acknowledged()) Mono.empty()
                else Mono.error(failure(request.index(), resource, null))
            }.onErrorResume { error ->
                if (error.isResourceAlreadyExists()) Mono.empty()
                else Mono.error(
                    if (error is IndexInitializationException) error
                    else failure(request.index(), resource, error)
                )
            }

    private fun Throwable.isResourceAlreadyExists(): Boolean =
        this is ElasticsearchException && error().type() == RESOURCE_ALREADY_EXISTS

    private fun failure(indexName: String, resource: WowResource, cause: Throwable?): IndexInitializationException =
        IndexInitializationException(
            "Unable to initialize Elasticsearch snapshot index [$indexName] from [${resource.location}].",
            cause,
        )

    private class IndexInitializationException(message: String, cause: Throwable?) :
        IllegalStateException(message, cause)

    companion object {
        private const val FEATURE = "elasticsearch"
        private const val RESOURCE_ALREADY_EXISTS = "resource_already_exists_exception"
    }
}
```

Keep the private exception only to prevent double-wrapping; do not expose a new exception API.

- [ ] **Step 5: Run unit tests and module check**

```bash
./gradlew :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.ElasticsearchSnapshotIndexInitializerTest"
./gradlew :wow-elasticsearch:check
```

Expected: all unit tests and module checks pass.

- [ ] **Step 6: Add a real-cluster integration test**

Create `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/ElasticsearchSnapshotIndexInitializerTest.kt`:

```kotlin
package me.ahoo.wow.elasticsearch

import me.ahoo.test.asserts.assert
import me.ahoo.wow.configuration.WowResourceLocator
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ElasticsearchSnapshotIndexInitializerTest {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should create configured index with queryable mapping`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        val indexName = MOCK_AGGREGATE_METADATA.toSnapshotIndexName()
        val file = tempDir.resolve("wow/elasticsearch/$indexName.json")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """{"mappings":{"properties":{"state":{"properties":{"status":{"type":"keyword"}}}}}}""",
        )

        ElasticsearchSnapshotIndexInitializer(
            client,
            WowResourceLocator(configDirectory = tempDir),
            listOf(MOCK_AGGREGATE_METADATA),
        ).ensureAll().block()

        ElasticsearchIndexMappingResolver(client).refresh(indexName).block()!!
            .fields.getValue("state.status").aggregatable.assert().isTrue()
    }
}
```

- [ ] **Step 7: Run the real Elasticsearch integration test**

```bash
./gradlew :wow-elasticsearch:integrationTest --tests "me.ahoo.wow.elasticsearch.ElasticsearchSnapshotIndexInitializerTest"
```

Expected: the Testcontainers-backed test passes and proves the configured keyword mapping is aggregatable.

- [ ] **Step 8: Commit Task 3**

```bash
git add wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/ElasticsearchSnapshotIndexInitializer.kt wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/ElasticsearchSnapshotIndexInitializerTest.kt wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/ElasticsearchSnapshotIndexInitializerTest.kt
git commit -m "feat(elasticsearch): initialize snapshot indexes from resources"
```

---

### Task 4: Wire Initialization into Spring Boot Startup

**Files:**
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchEventSourcingAutoConfiguration.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchEventSourcingAutoConfigurationTest.kt`

**Interfaces:**
- Consumes: `ElasticsearchSnapshotIndexInitializer.ensureAll()` from Task 3 and existing `IndexTemplateInitializer.ensureSnapshotTemplate()`.
- Produces: a conditional `ElasticsearchSnapshotIndexInitializer` bean and guaranteed template-then-index-then-store startup ordering.

- [ ] **Step 1: Add failing startup-order tests**

Add `ElasticsearchSnapshotIndexInitializer` to test imports and add:

```kotlin
@Test
fun `snapshot store should initialize template then concrete indexes`() {
    val order = mutableListOf<String>()
    val template = mockk<IndexTemplateInitializer> {
        every { ensureSnapshotTemplate() } returns Mono.fromRunnable { order += "template" }
    }
    val indexes = mockk<ElasticsearchSnapshotIndexInitializer> {
        every { ensureAll() } returns Mono.fromRunnable { order += "indexes" }
    }

    ElasticsearchEventSourcingAutoConfiguration(
        ElasticsearchProperties(autoInitTemplate = true),
        ElasticsearchEventStoreBatchProperties(),
        ElasticsearchSnapshotStoreBatchProperties(),
    ).elasticsearchSnapshotStore(
        mock(ReactiveElasticsearchClient::class.java),
        template,
        indexes,
        metricsProvider,
    ).close()

    order.assert().containsExactly("template", "indexes")
}

@Test
fun `snapshot index initialization should run when template initialization is disabled`() {
    val template = mockk<IndexTemplateInitializer>()
    val indexes = mockk<ElasticsearchSnapshotIndexInitializer> {
        every { ensureAll() } returns Mono.empty()
    }

    ElasticsearchEventSourcingAutoConfiguration(
        ElasticsearchProperties(autoInitTemplate = false),
        ElasticsearchEventStoreBatchProperties(),
        ElasticsearchSnapshotStoreBatchProperties(),
    ).elasticsearchSnapshotStore(
        mock(ReactiveElasticsearchClient::class.java),
        template,
        indexes,
        metricsProvider,
    ).close()

    verify(exactly = 0) { template.ensureSnapshotTemplate() }
    verify(exactly = 1) { indexes.ensureAll() }
}

@Test
fun `snapshot index initialization failure should fail store creation`() {
    val expected = IllegalStateException("index initialization failed")
    val indexes = mockk<ElasticsearchSnapshotIndexInitializer> {
        every { ensureAll() } returns Mono.error(expected)
    }

    val actual = assertThrows<IllegalStateException> {
        ElasticsearchEventSourcingAutoConfiguration(
            ElasticsearchProperties(autoInitTemplate = false),
            ElasticsearchEventStoreBatchProperties(),
            ElasticsearchSnapshotStoreBatchProperties(),
        ).elasticsearchSnapshotStore(
            mock(ReactiveElasticsearchClient::class.java),
            mockk(),
            indexes,
            metricsProvider,
        )
    }

    actual.assert().isSameAs(expected)
}
```

Update the existing direct `elasticsearchSnapshotStore` invocation in `default batch properties should be used` to pass a mock `ElasticsearchSnapshotIndexInitializer` whose `ensureAll()` returns `Mono.empty()`.

- [ ] **Step 2: Run the focused starter test and verify signature failures**

```bash
./gradlew :wow-spring-boot-starter:test --tests "me.ahoo.wow.spring.boot.starter.elasticsearch.ElasticsearchEventSourcingAutoConfigurationTest"
```

Expected: compilation fails because the auto-configuration does not expose or invoke the new initializer.

- [ ] **Step 3: Add the conditional initializer bean and startup call**

In `ElasticsearchEventSourcingAutoConfiguration.kt`, add:

```kotlin
@Bean
@ConditionalOnSnapshotEnabled
@ConditionalOnSnapshotStoreStorage(StorageType.ELASTICSEARCH)
fun elasticsearchSnapshotIndexInitializer(
    elasticsearchClient: ReactiveElasticsearchClient,
): ElasticsearchSnapshotIndexInitializer = ElasticsearchSnapshotIndexInitializer(elasticsearchClient)
```

Change the SnapshotStore factory signature and body:

```kotlin
fun elasticsearchSnapshotStore(
    elasticsearchClient: ReactiveElasticsearchClient,
    indexTemplateInitializer: IndexTemplateInitializer,
    snapshotIndexInitializer: ElasticsearchSnapshotIndexInitializer,
    metrics: ObjectProvider<WowMetrics>,
): ElasticsearchSnapshotStore {
    if (elasticsearchProperties.autoInitTemplate) {
        indexTemplateInitializer.ensureSnapshotTemplate().block()
    }
    snapshotIndexInitializer.ensureAll().block()
    return ElasticsearchSnapshotStore(
        elasticsearchClient = elasticsearchClient,
        batchOptions = snapshotStoreBatchProperties.toOptions(),
        metrics = metrics.getIfAvailable { WowMetrics.NONE },
    )
}
```

Import `ElasticsearchSnapshotIndexInitializer`. Do not add a new property or `@ConditionalOnMissingBean` extension point.

- [ ] **Step 4: Run starter tests and module check**

```bash
./gradlew :wow-spring-boot-starter:test --tests "me.ahoo.wow.spring.boot.starter.elasticsearch.ElasticsearchEventSourcingAutoConfigurationTest"
./gradlew :wow-spring-boot-starter:check
```

Expected: startup ordering, disabled-template initialization, failure propagation, and all existing Elasticsearch auto-configuration scenarios pass.

- [ ] **Step 5: Commit Task 4**

```bash
git add wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchEventSourcingAutoConfiguration.kt wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchEventSourcingAutoConfigurationTest.kt
git commit -m "feat(starter): initialize Elasticsearch snapshot indexes"
```

---

### Task 5: Adopt the Convention in Compensation and Document It

**Files:**
- Move: `compensation/wow-compensation-server/src/main/resources/indexs/execution_failed_index.json`
- Create: `compensation/wow-compensation-server/src/main/resources/META-INF/wow/elasticsearch/wow.compensation.execution_failed.snapshot.json`
- Modify: `compensation/wow-compensation-server/src/test/kotlin/me/ahoo/wow/compensation/server/ExecutionFailedQuerySchemaTest.kt`
- Modify: `documentation/docs/zh/guide/extensions/elasticsearch.md`
- Modify: `documentation/docs/en/guide/extensions/elasticsearch.md`
- Modify: `documentation/docs/zh/guide/query/query-model-schema.md`
- Modify: `documentation/docs/en/guide/query/query-model-schema.md`
- Modify: `documentation/docs/zh/reference/config/infrastructure.md`
- Modify: `documentation/docs/en/reference/config/infrastructure.md`

**Interfaces:**
- Consumes: the Task 1 resource convention, Task 2 Query Schema fallback, and Task 3 native Create Index parsing.
- Produces: one real packaged index definition and the public operational contract in both documentation languages.

- [ ] **Step 1: Add a failing compensation resource contract test**

Extend `ExecutionFailedQuerySchemaTest.kt` with imports for `CreateIndexRequest`, `WowResourceLocator`, and `toSnapshotIndexName`, then add:

```kotlin
@Test
fun `should package Elasticsearch snapshot index config under its final index name`() {
    val namedAggregate = ExecutionFailed::class.java.aggregateMetadata<Any, Any>().namedAggregate
    val indexName = namedAggregate.toSnapshotIndexName()
    val resources = WowResourceLocator().findClasspath("elasticsearch", indexName)

    resources.assert().hasSize(1)
    val request = resources.single().readText().byteInputStream().use { input ->
        CreateIndexRequest.Builder().withJson(input).index(indexName).build()
    }
    request.index().assert().isEqualTo("wow.compensation.execution_failed.snapshot")
    request.mappings()!!.properties()["state"]!!.`object`()
        .properties()["status"]!!._kind().jsonValue().assert().isEqualTo("keyword")
}
```

- [ ] **Step 2: Run the focused compensation test and verify the resource is absent**

```bash
./gradlew :wow-compensation-server:test --tests "me.ahoo.wow.compensation.server.ExecutionFailedQuerySchemaTest"
```

Expected: the new test fails because the old `indexs/execution_failed_index.json` path is not discoverable.

- [ ] **Step 3: Move the existing JSON unchanged to the unified path**

Perform the mechanical move without editing generated files:

```bash
mkdir -p compensation/wow-compensation-server/src/main/resources/META-INF/wow/elasticsearch
git mv compensation/wow-compensation-server/src/main/resources/indexs/execution_failed_index.json compensation/wow-compensation-server/src/main/resources/META-INF/wow/elasticsearch/wow.compensation.execution_failed.snapshot.json
```

- [ ] **Step 4: Run the compensation test and module check**

```bash
./gradlew :wow-compensation-server:test --tests "me.ahoo.wow.compensation.server.ExecutionFailedQuerySchemaTest"
./gradlew :wow-compensation-server:check
```

Expected: both commands pass and the native JSON parses under the computed final index name.

- [ ] **Step 5: Update Query Schema documentation in both languages**

Replace the existing resource-path bullet in both `query-model-schema.md` files with equivalent localized text that states these exact contracts:

```text
ClasspathQuerySchemaSource reads META-INF/wow/query-schema/{context}.{aggregate}.{model}.json.
WorkingDirectoryQuerySchemaSource reads config/wow/query-schema/{context}.{aggregate}.{model}.json.
The model segment is lowercase: snapshot or event_stream.
The dot is the reserved Wow named-aggregate delimiter.
Each source falls back to wow-query-schema/{context}/{aggregate}/{model}.json only when its new path has no resource.
Source priorities, classpath merging, and refresh behavior are unchanged.
```

- [ ] **Step 6: Update Elasticsearch extension documentation in both languages**

In the snapshot-template section of both Elasticsearch guides, add a native JSON example at:

```text
META-INF/wow/elasticsearch/wow.sales.order.snapshot.json
config/wow/elasticsearch/wow.sales.order.snapshot.json
```

Document these exact rules:

```text
The generic snapshot template is the fallback for storage-only snapshots.
Queryable snapshots should provide a concrete index definition with business mappings.
The resource key is the final index name computed by Wow.
The working-directory file replaces classpath files.
Without a working file, duplicate classpath files fail startup.
Missing resources keep the generic-template behavior.
Existing indexes are skipped; mapping changes require explicit reindex or migration.
Resource JSON follows Elasticsearch client/cluster validation semantics.
Resource presence requests creation regardless of storage-routing configuration.
```

Update the complete configuration discussion without adding a new property.

- [ ] **Step 7: Update infrastructure references in both languages**

After the existing `auto-init-template` startup paragraph, add the localized equivalent of:

```text
When Elasticsearch is selected for SnapshotStore, Wow also looks for concrete index resources under
META-INF/wow/elasticsearch/{indexName}.json or config/wow/elasticsearch/{indexName}.json.
Concrete resources are processed after the generic template and before SnapshotStore creation.
This mechanism is independent of auto-init-template; missing resources remain a no-op.
```

- [ ] **Step 8: Build the documentation site**

```bash
cd documentation
pnpm docs:build
```

Expected: the VitePress build passes with valid internal links and code fences.

- [ ] **Step 9: Commit Task 5**

```bash
git add compensation/wow-compensation-server/src/main/resources compensation/wow-compensation-server/src/test/kotlin/me/ahoo/wow/compensation/server/ExecutionFailedQuerySchemaTest.kt documentation/docs/zh/guide/extensions/elasticsearch.md documentation/docs/en/guide/extensions/elasticsearch.md documentation/docs/zh/guide/query/query-model-schema.md documentation/docs/en/guide/query/query-model-schema.md documentation/docs/zh/reference/config/infrastructure.md documentation/docs/en/reference/config/infrastructure.md
git commit -m "docs: adopt snapshot index resource convention"
```

---

### Task 6: Run Final Cross-Module Verification

**Files:**
- Verify only; no planned source changes.

**Interfaces:**
- Consumes: all deliverables from Tasks 1–5.
- Produces: evidence that the unified convention, query fallback, index initialization, Boot wiring, compensation adoption, and docs build work together.

- [ ] **Step 1: Run all affected JVM checks**

```bash
./gradlew :wow-core:check :wow-query:check :wow-elasticsearch:check :wow-spring-boot-starter:check :wow-compensation-server:check
```

Expected: all tasks pass.

- [ ] **Step 2: Run the Elasticsearch integration suite**

```bash
./gradlew :wow-elasticsearch:integrationTest --stacktrace
```

Expected: the complete Elasticsearch integration suite passes against its managed test container.

- [ ] **Step 3: Rebuild documentation from a clean working directory state**

```bash
cd documentation
pnpm docs:build
```

Expected: the documentation build passes.

- [ ] **Step 4: Inspect the final diff and repository state**

```bash
git diff --check
git status --short
git log --oneline -8
```

Expected: `git diff --check` is silent, only intended files are modified or committed, and Tasks 1–5 appear as focused commits.

- [ ] **Step 5: Commit any verification-only correction**

Only if verification required a source or documentation correction, stage the exact corrected files and commit:

```bash
git commit -m "fix: close snapshot index verification gaps"
```

If no correction was needed, do not create an empty commit.
