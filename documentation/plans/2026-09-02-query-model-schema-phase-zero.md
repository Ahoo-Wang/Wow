# QueryModelSchema Phase 0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 QueryField、富 QueryFieldSchema、富 QueryModelSchema、Projection/Sort 新合同与分层零改写快速路径，同时保持 Query Runtime 与 QueryBackend/存储编译职责边界。

**Architecture:** 公共 Query 只承载合法 QueryField；QueryModelSchema 负责准入、逻辑语义与确有必要的 Filter/Sort 节点改写，Projection 通过同一实例完成校验后原样透传。Backend 使用同一 QueryModelSchema 将 Projection、聚合字段与物理绑定编译为 MongoDB/Elasticsearch 本地语法；本阶段不引入 ResolvedQuery、不改变 QueryBackend 公共签名，也不增加兼容层或并行 Schema 模型。

**Tech Stack:** Kotlin 2.4.10、JVM 17、Reactor、Jackson、JUnit Jupiter、FluentAssert、MongoDB Reactive Streams、Elasticsearch Java Client、JMH、Gradle、VitePress

**Spec:** `documentation/docs/zh/guide/query/query-model-schema-phase-zero-design.md`

## Global Constraints

- `LogicalField → QueryField`、`Sort.field: String → QueryField`、`Projection: List<String> → List<QueryField>` 是明确的 source/binary breaking change。
- 合法字段的 JSON 仍是字符串；Projection/Sort 中的 `state.*` 等 pattern 变为非法 wire 输入。
- EventStream Projection 选择 `body.body` 或其子节点时，必须包含且不得排除 `body.bodyType`。
- Projection 只校验并透传，不参与 Field/Model rewriteMode；Backend pattern 不得回写公共 Query。
- Filter、Sort、Element、temporal 只有在真实变化时才重建节点；identity dynamic path 允许保持 `NONE`。
- Schema、Field Schema、Binding 发布后不可变；不做无证据的深复制或防御性包装。
- 不增加 LogicalField typealias、兼容类、Pattern 类型、Backend escape hatch、resolved aggregation IR、无界 dynamic cache、Schema Service、Resolver SPI 或 Capability Registry。
- 不改变 QueryBackend 公共方法签名，不增加 QueryContext.schema 或 ResolvedQuery；这些属于阶段一。
- 保持 Reactor 路径非阻塞；测试使用 FluentAssert `.assert()`，异常测试使用 JUnit `assertThrows`。
- 每次只暂存当前任务列出的路径，保留工作区中用户的其他修改。

## Change Map

- `wow-api`: QueryField、Filter/RelativeTime/Aggregation、Projection、Sort、Schema Metadata 与 JSON/OpenAPI 注解。
- `wow-query`: DSL、Cursor、Schema 领域模型、Resolver、Provider 委托、Mask 与快速路径。
- `wow-schema` / `wow-openapi`: QueryField Definition Provider、组件引用与快照。
- `wow-mongo`: Schema Adapter、Projection/Sort/Filter Converter、Cursor 与 Aggregation Compiler。
- `wow-elasticsearch`: Mapping Adapter、Projection/Sort/Filter Converter、Cursor 与 Aggregation Compiler。
- `test/wow-tck`, `wow-webflux`, compensation tests: 公共 API 调用点与 Backend 合同。
- `wow-benchmarks`: NONE/INFER/REQUIRED、identity/rewrite dynamic path 与分配率基准。

---

### Task 1: Freeze the Confirmed Design Baseline

**Files:**
- Modify: `documentation/docs/zh/guide/query/query-model-schema-phase-zero-design.md`
- Create: `documentation/plans/2026-09-02-query-model-schema-phase-zero.md`

**Interfaces:**
- Consumes: 已确认的 Phase 0 设计。
- Produces: 后续任务唯一规格来源与逐任务检查清单。

- [ ] **Step 1: Verify only the design and plan are pending**

Run:

```bash
git status --short
git diff --check
```

Expected: only the design document and this plan are modified/untracked; `git diff --check` exits 0.

- [ ] **Step 2: Build the documentation**

Run:

```bash
cd documentation
pnpm docs:build
```

Expected: VitePress reports `build complete`.

- [ ] **Step 3: Commit the design baseline**

```bash
git add documentation/docs/zh/guide/query/query-model-schema-phase-zero-design.md \
  documentation/plans/2026-09-02-query-model-schema-phase-zero.md
git commit -m "docs(query): finalize schema phase zero plan"
```

---

### Task 2: Replace LogicalField with QueryField Repository-Wide

**Files:**
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/FilterExpression.kt`
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/PredicateFilters.kt`
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/RelativeTimeFilters.kt`
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt`
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/LegacyConditionAdapter.kt`
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/schema/QuerySchemaMetadata.kt`
- Create: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/QueryFieldTest.kt`
- Delete: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/typed/query/LogicalFieldDefinitionProvider.kt`
- Create: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/typed/query/QueryFieldDefinitionProvider.kt`
- Modify: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/WowDefinitionProviderRegistry.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/QueryComponent.kt`
- Modify: every Kotlin/Java production, test, integrationTest and JMH source returned by `rg -l '\bLogicalField\b' --glob '*.kt' --glob '*.java'`.
- Test: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/FilterExpressionTest.kt`
- Test: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/AggregationQueryTest.kt`
- Test: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/schema/QuerySchemaMetadataTest.kt`
- Test: `wow-api/src/test/java/me/ahoo/wow/api/query/schema/QuerySchemaMetadataJavaTest.java`
- Test: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/WowDefinitionProviderRegistryTest.kt`
- Test: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt`
- Test: `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`

**Interfaces:**
- Consumes: none.
- Produces: `QueryField(path: String)`, `append`, `absoluteTo`, `relativeTo`, JsonValue string contract; no LogicalField symbol remains in executable source.

- [ ] **Step 1: Add failing QueryField contract tests**

Create focused tests with these assertions:

```kotlin
class QueryFieldTest {
    private val jsonMapper = jacksonObjectMapper()

    @Test
    fun `should compose absolute and relative paths`() {
        val state = QueryField("state")
        val name = QueryField("name")
        val absolute = state.append(name)

        absolute.assert().isEqualTo(QueryField("state.name"))
        name.absoluteTo(state).assert().isEqualTo(absolute)
        absolute.relativeTo(state).assert().isEqualTo(name)
        state.relativeTo(state).assert().isNull()
    }

    @Test
    fun `should serialize as a string and reject backend patterns`() {
        val field = QueryField("state.name")
        jsonMapper.writeValueAsString(field).assert().isEqualTo("\"state.name\"")
        jsonMapper.readValue("\"state.name\"", QueryField::class.java).assert().isEqualTo(field)
        assertThrows<IllegalArgumentException> { QueryField("state.*") }
    }
}
```

- [ ] **Step 2: Run the focused test to prove the new type is absent**

Run:

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.QueryFieldTest"
```

Expected: Kotlin compilation fails because `QueryField` does not exist.

- [ ] **Step 3: Implement the minimal QueryField value object**

Replace LogicalField with the confirmed contract:

```kotlin
data class QueryField(
    @get:JsonValue val path: String,
) {
    init {
        require(QUERY_FIELD_PATTERN.matches(path)) { "Query field is invalid: [$path]." }
    }

    fun append(relative: QueryField): QueryField = QueryField("$path.${relative.path}")

    fun absoluteTo(parent: QueryField?): QueryField =
        if (parent == null || this == parent || path.startsWith("${parent.path}.")) this else parent.append(this)

    fun relativeTo(parent: QueryField): QueryField? =
        path.removePrefix("${parent.path}.")
            .takeIf { it != path && it.isNotEmpty() }
            ?.let(::QueryField)

    override fun toString(): String = path

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun from(path: String): QueryField = QueryField(path)
    }
}
```

- [ ] **Step 4: Perform the breaking symbol migration**

For every source returned by the discovery command:

```bash
rg -l '\bLogicalField\b' --glob '*.kt' --glob '*.java'
```

apply these exact changes:

- type/import/constructor `LogicalField` → `QueryField`;
- QueryField receiver property `.value` → `.path`;
- helper names `resolveLogicalField` → `resolveQueryField` where the result type is QueryField;
- retain `.value` on QueryModel, QueryCapability, QueryValueType and other identifier value objects;
- rename Definition Provider and registry expectation to `QueryFieldDefinitionProvider`;
- change OpenAPI component/ref from `wow.api.query.LogicalField` to `wow.api.query.QueryField`.

- [ ] **Step 5: Verify the migration and focused modules**

Run:

```bash
rg -n '\bLogicalField\b' --glob '*.kt' --glob '*.java'
./gradlew :wow-api:check :wow-schema:check :wow-openapi:check \
  :wow-query:compileKotlin :wow-mongo:compileKotlin :wow-elasticsearch:compileKotlin \
  :wow-benchmarks:compileJmhKotlin
```

Expected: `rg` returns no executable-source matches; all Gradle tasks succeed.

- [ ] **Step 6: Commit**

```bash
git add wow-api wow-query wow-schema wow-openapi wow-mongo wow-elasticsearch \
  wow-webflux wow-core wow-benchmarks compensation test
git commit -m "refactor(query): replace logical field with query field"
```

---

### Task 3: Make Projection and Sort Fully Typed Public Contracts

**Files:**
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/Queryable.kt`
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/CursorQueries.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/ProjectionDsl.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/SortDsl.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/converter/AbstractProjectionConverter.kt`
- Modify: MongoDB and Elasticsearch Sort/Projection/Cursor callers returned by `rg -l 'Projection\(|Sort\(' --glob '*.kt' --glob '*.java'`.
- Test: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/AggregationQueryTest.kt`
- Test: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/CursorQueryTest.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/CursorQueriesTest.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/ProjectionDslTest.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/SortDslTest.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/converter/ProjectionConverterTest.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/converter/SortConverterTest.kt`

**Interfaces:**
- Consumes: `QueryField` from Task 2.
- Produces: `Projection(List<QueryField>, List<QueryField>)`, `Sort(QueryField, Direction)`, `withUniqueSort(QueryField)`; aggregation aliases remain String.

- [ ] **Step 1: Add failing API and wire tests**

Add these contract assertions:

```kotlin
@Test
fun `projection and sort should keep string json shapes`() {
    val projection = Projection(include = listOf(QueryField("state.name")))
    val sort = Sort(QueryField("state.createdAt"), Sort.Direction.DESC)

    jsonMapper.writeValueAsString(projection).assert()
        .isEqualTo("{\"include\":[\"state.name\"]}")
    jsonMapper.writeValueAsString(sort).assert()
        .isEqualTo("{\"field\":\"state.createdAt\",\"direction\":\"DESC\"}")
    assertThrows<JacksonException> {
        jsonMapper.readValue("{\"include\":[\"state.*\"]}", Projection::class.java)
    }
}

@Test
fun `aggregation sort should compare query field paths with string aliases`() {
    val query = AggregationQuery(
        groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
        metrics = listOf(AggregationMetric.Count("count")),
        sort = listOf(Sort(QueryField("status"), Sort.Direction.DESC)),
    )

    query.effectiveSort().assert().containsExactly(Sort(QueryField("status"), Sort.Direction.DESC))
}
```

- [ ] **Step 2: Run tests to prove String contracts are still present**

Run:

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.AggregationQueryTest" \
  :wow-query:test --tests "me.ahoo.wow.query.dsl.ProjectionDslTest" \
  --tests "me.ahoo.wow.query.dsl.SortDslTest"
```

Expected: compilation fails on QueryField lists/Sort field types.

- [ ] **Step 3: Implement the typed contracts**

Use these signatures:

```kotlin
data class Sort(val field: QueryField, val direction: Direction)

data class Projection(
    val include: List<QueryField> = emptyList(),
    val exclude: List<QueryField> = emptyList(),
) {
    companion object {
        val ALL = Projection()
    }
}

internal val FORBIDDEN_CURSOR_SORTS =
    setOf(QueryField("_score"), QueryField("_doc"), QueryField("_shard_doc"))

fun ICursorQuery.withUniqueSort(uniqueField: QueryField): ICursorQuery
```

Keep aggregation aliases as String and update only their boundary:

```kotlin
val sortFields = sort.map { it.field.path }
require(sortFields.all(aliases::contains))

groupBy.map(AggregationGroup::alias)
    .filterNot(sortFields::contains)
    .forEach { add(Sort(QueryField(it), Sort.Direction.ASC)) }
```

- [ ] **Step 4: Update DSL and converter boundaries**

Use QueryField internally while keeping the existing String DSL surface:

```kotlin
private val include = mutableListOf<QueryField>()
private val exclude = mutableListOf<QueryField>()

fun include(vararg fields: String) {
    fields.mapTo(include) { QueryField(it.withNestedField()) }
}

fun String.asc() = sort(Sort(QueryField(withNestedField()), Sort.Direction.ASC))
fun String.desc() = sort(Sort(QueryField(withNestedField()), Sort.Direction.DESC))
```

At backend-native boundaries read `field.path`; when a converter returns `convertedPath: String`, wrap it once with `QueryField(convertedPath)`. Do not add String overloads to Sort or Projection.

- [ ] **Step 5: Update every constructor call and verify**

Run:

```bash
rg -n 'Sort\("|Projection\([^\n]*(listOf\(")' --glob '*.kt' --glob '*.java'
./gradlew :wow-api:check :wow-query:check :wow-mongo:compileTestKotlin \
  :wow-elasticsearch:compileTestKotlin :wow-webflux:compileTestKotlin :wow-tck:compileKotlin
```

Expected: constructor grep returns no raw String fields; Gradle tasks succeed.

- [ ] **Step 6: Commit**

```bash
git add wow-api wow-query wow-mongo wow-elasticsearch wow-webflux wow-benchmarks test compensation
git commit -m "refactor(query): type projection and sort fields"
```

---

### Task 4: Build the Rich Schema Domain Model and Backend Bindings

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchema.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/schema/MongoQuerySchemaAdapter.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchQuerySchemaAdapter.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation/MongoAggregationCompiler.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationCompiler.kt`
- Modify: every production/test/JMH constructor returned by `rg -l 'QueryFieldSchema\(|QueryFieldBinding\(' --glob '*.kt' --glob '*.java'`.
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QueryModelSchemaTest.kt`
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/schema/MongoQuerySchemaAdapterTest.kt`
- Test: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/schema/ElasticsearchQuerySchemaAdapterTest.kt`
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt`
- Test: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt`

**Interfaces:**
- Consumes: QueryField, Projection and Sort contracts from Tasks 2-3.
- Produces: QueryRewriteMode; QueryFieldBinding(resolvedField, physicalField, storageType); rich QueryFieldSchema; QueryModelSchema.field and exact-field value admission.

- [ ] **Step 1: Add failing Schema model tests**

Cover these exact cases in `QueryModelSchemaTest`:

```kotlin
@Test
fun `identity dynamic child should keep none rewrite mode`() {
    val parent = QueryField("state.dynamic")
    val child = QueryField("state.dynamic.code")
    val fieldSchema = fieldSchema(
        dynamicChildren = true,
        bindings = mapOf(
            QueryCapability.EXACT_MATCH to QueryFieldBinding(parent, parent, null),
        ),
        projectionField = parent,
        rewriteMode = QueryRewriteMode.NONE,
    )
    val schema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), mapOf(parent to fieldSchema))

    schema.field(child)!!.binding(QueryCapability.EXACT_MATCH)!!.resolvedField.assert().isEqualTo(child)
    schema.field(child)!!.rewriteMode.assert().isEqualTo(QueryRewriteMode.NONE)
}

@Test
fun `nearest dynamic ancestor should derive every field and drop element scope`() {
    val root = QueryField("state")
    val customer = QueryField("state.customer")
    val requested = QueryField("state.customer.name")
    val schema = QueryModelSchema(
        QueryModel.SNAPSHOT,
        emptySet(),
        mapOf(
            root to fieldSchema(
                dynamicChildren = true,
                bindings = mapOf(
                    QueryCapability.EXACT_MATCH to QueryFieldBinding(
                        QueryField("root"),
                        QueryField("root"),
                        null,
                    ),
                ),
                projectionField = QueryField("root"),
                rewriteMode = QueryRewriteMode.REQUIRED,
            ),
            customer to fieldSchema(
                dynamicChildren = true,
                bindings = mapOf(
                    QueryCapability.EXACT_MATCH to QueryFieldBinding(
                        customer,
                        QueryField("document.customer"),
                        null,
                    ),
                    QueryCapability.ELEMENT_SCOPE to QueryFieldBinding(
                        customer,
                        QueryField("document.customer"),
                        null,
                    ),
                ),
                projectionField = QueryField("source.customer"),
                rewriteMode = QueryRewriteMode.INFER,
            ),
        ),
    )

    val resolved = schema.field(requested)!!
    resolved.binding(QueryCapability.EXACT_MATCH)!!.resolvedField.assert()
        .isEqualTo(QueryField("state.customer.name"))
    resolved.binding(QueryCapability.EXACT_MATCH)!!.physicalField.assert()
        .isEqualTo(QueryField("document.customer.name"))
    resolved.projectionField.assert().isEqualTo(QueryField("source.customer.name"))
    resolved.bindings.assert().doesNotContainKey(QueryCapability.ELEMENT_SCOPE)
}
```

- [ ] **Step 2: Run the focused tests and observe constructor/signature failures**

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QueryModelSchemaTest"
```

Expected: compilation fails because QueryRewriteMode and the new binding fields do not exist.

- [ ] **Step 3: Implement the minimal rich models**

Use these public properties and behaviors:

```kotlin
enum class QueryRewriteMode { NONE, INFER, REQUIRED }

data class QueryFieldBinding(
    val resolvedField: QueryField,
    val physicalField: QueryField,
    val storageType: QueryStorageType?,
)

data class QueryFieldSchema(
    val title: String?,
    val description: String?,
    val enumValues: List<JsonNode>?,
    val valueTypes: Set<QueryValueType>,
    val nullable: Boolean,
    val required: Boolean,
    val cardinality: QueryCardinality,
    val semanticType: QuerySemanticType?,
    val dynamicChildren: Boolean,
    val bindings: Map<QueryCapability, QueryFieldBinding>,
    val projectionField: QueryField? = bindings[QueryCapability.PRESENCE]?.resolvedField,
    val rewriteMode: QueryRewriteMode,
    @get:JsonIgnore internal val maskRule: MaskRule? = null,
) {
    val capabilities: Set<QueryCapability> get() = bindings.keys
    val masked: Boolean get() = maskRule != null
    fun binding(capability: QueryCapability): QueryFieldBinding? = bindings[capability]
    internal fun resolveDynamic(source: QueryField, relative: QueryField): QueryFieldSchema
    internal fun matchesValueTypes(values: Iterable<JsonNode>): Boolean
}
```

`resolveDynamic` must construct a complete new QueryFieldSchema, append `relative` to every resolved/physical/projection field, remove ELEMENT_SCOPE, and recalculate rewriteMode. It must not call `copy(bindings = ...)` while inheriting the old projectionField/rewriteMode.

Move the existing built-in JsonNode/QueryValueType comparison from QueryFilterSchemaResolver into QueryFieldSchema's file and implement:

```kotlin
internal fun matchesValueTypes(values: Iterable<JsonNode>): Boolean =
    valueTypes.isEmpty() ||
        valueTypes.any { it !in BUILT_IN_QUERY_VALUE_TYPES } ||
        values.all { value -> value.isNull || valueTypes.any(value::matches) }
```

Do not add enum/required/nullable/cardinality validation to this method.

- [ ] **Step 4: Implement rewrite modes without a speculative registry/helper hierarchy**

For each Adapter field source, compute path mode with this rule before constructing QueryFieldSchema:

```kotlin
val rewrites = bindings.values.map { it.resolvedField != source }.distinct()
val rewriteMode = when {
    semanticType is Temporal || QueryCapability.ELEMENT_SCOPE in bindings -> QueryRewriteMode.INFER
    bindings.isEmpty() || rewrites == listOf(false) -> QueryRewriteMode.NONE
    rewrites == listOf(true) -> QueryRewriteMode.REQUIRED
    else -> QueryRewriteMode.INFER
}
```

Dynamic by itself does not force INFER. Recalculate with the derived source and bindings; identity dynamic children remain NONE.

Update existing test fixture helpers to accept `rewriteMode: QueryRewriteMode = QueryRewriteMode.NONE` and typed resolved/physical QueryFields. Direct production constructors in Adapters must always pass an explicitly calculated mode; do not add a production default for rewriteMode.

- [ ] **Step 5: Implement QueryModelSchema caches and field lookup**

Keep Model computation inline:

```kotlin
@get:JsonIgnore
internal val maskedFields = fields.filterValues(QueryFieldSchema::masked)

@get:JsonIgnore
internal val hasMaskedFields = maskedFields.isNotEmpty()

val rewriteMode = when {
    fields.values.any { it.rewriteMode != QueryRewriteMode.NONE } -> QueryRewriteMode.INFER
    else -> QueryRewriteMode.NONE
}

fun supports(capability: QueryCapability): Boolean = capability in capabilities
fun field(field: QueryField): QueryFieldSchema?

internal fun matchesValueTypes(field: QueryField, values: Iterable<JsonNode>): Boolean =
    fields[field]?.matchesValueTypes(values) ?: true

fun toMetadata(): QueryModelSchemaMetadata
```

`field` checks exact fields first, then walks parent segments from nearest to farthest and calls `resolveDynamic`; it must not cache unbounded child paths.
Move the existing metadata projection body behind `QueryModelSchema.toMetadata()` and keep field ordering by QueryField.path.

- [ ] **Step 6: Update MongoDB/Elasticsearch Adapters and Aggregation Compilers**

- MongoDB bindings keep `resolvedField = source` and use `physicalField = QueryField(fieldConverter.convert(source.path))`.
- Elasticsearch bindings use the selected mapped QueryField for both resolvedField and physicalField.
- projectionField stays a QueryField node root; it never contains `*`.
- Aggregation Compilers call `schema.field(logical)` and `fieldSchema.binding(capability)`; they use physicalField.path only at the native driver boundary.
- Replace manual `"$parent.$field"` concatenation with QueryField.absoluteTo/relativeTo/append.

- [ ] **Step 7: Run Schema and compiler checks**

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QueryModelSchemaTest" \
  :wow-mongo:test --tests "me.ahoo.wow.mongo.query.schema.MongoQuerySchemaAdapterTest" \
  --tests "me.ahoo.wow.mongo.query.snapshot.MongoAggregationCompilerTest" \
  :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapterTest" \
  --tests "me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchAggregationCompilerTest"
```

Expected: all tests pass.

- [ ] **Step 8: Commit**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchema.kt \
  wow-query/src/test \
  wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/schema \
  wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation \
  wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/schema \
  wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/schema \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/schema \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt
git commit -m "refactor(query): enrich query schema models"
```

---

### Task 5: Make QueryModelSchema the Runtime Entry and Preserve Identity Fast Paths

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryFieldSchemaResolver.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryFilterSchemaResolver.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchema.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchemaProviderResolution.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolverTest.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidationModeTest.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QueryModelSchemaTest.kt`

**Interfaces:**
- Consumes: rich QueryModelSchema/QueryFieldSchema from Task 4.
- Produces: six QueryModelSchema.resolve overloads; internal QuerySchemaResolver; exact/dynamic admission and Filter/Sort identity-preserving rewrite.

- [ ] **Step 1: Add failing model-entry and identity tests**

Add assertions for all of these cases:

```kotlin
@Test
fun `identity schema should return the same query graph`() {
    val field = QueryField("state.name")
    val query = ListQuery(
        filter = AndFilter(listOf(EqualFilter(field, json("A")))),
        projection = Projection(include = listOf(field)),
        sort = listOf(Sort(field, Sort.Direction.ASC)),
        limit = 10,
    )

    val resolution = identitySchema().resolve(query)
    resolution.value.assert().isSameAs(query)
    resolution.value.filter.assert().isSameAs(query.filter)
    resolution.value.projection.assert().isSameAs(query.projection)
    resolution.value.sort.assert().isSameAs(query.sort)
}

@Test
fun `multi level element should keep absolute parents and emit relative children`() {
    val orders = QueryField("state.orders")
    val lines = QueryField("state.orders.lines")
    val price = QueryField("state.orders.lines.price")
    val input = ElementMatchFilter(
        QueryField("state.orders"),
        ElementMatchFilter(
            QueryField("lines"),
            EqualFilter(QueryField("price"), json(10)),
        ),
    )

    fun model(mapped: Boolean): QueryModelSchema {
        val resolvedRoot = if (mapped) QueryField("document.orders") else orders
        val resolvedLines = if (mapped) QueryField("document.orders.lines") else lines
        val resolvedPrice = if (mapped) QueryField("document.orders.lines.price.keyword") else price
        return schema(
            mapOf(
                orders to fieldSchema(
                    bindings = mapOf(
                        QueryCapability.ELEMENT_SCOPE to QueryFieldBinding(
                            resolvedRoot,
                            QueryField("document.orders"),
                            null,
                        ),
                    ),
                    rewriteMode = if (mapped) QueryRewriteMode.REQUIRED else QueryRewriteMode.NONE,
                ),
                lines to fieldSchema(
                    bindings = mapOf(
                        QueryCapability.ELEMENT_SCOPE to QueryFieldBinding(
                            resolvedLines,
                            QueryField("document.orders.lines"),
                            null,
                        ),
                    ),
                    rewriteMode = QueryRewriteMode.INFER,
                ),
                price to fieldSchema(
                    bindings = mapOf(
                        QueryCapability.EXACT_MATCH to QueryFieldBinding(
                            resolvedPrice,
                            QueryField("document.orders.lines.price.keyword"),
                            null,
                        ),
                    ),
                    rewriteMode = if (mapped) QueryRewriteMode.REQUIRED else QueryRewriteMode.NONE,
                ),
            ),
        )
    }

    val mongo = model(mapped = false).resolve(input).value as ElementMatchFilter
    mongo.field.assert().isEqualTo(QueryField("state.orders"))
    (mongo.predicate as ElementMatchFilter).field.assert().isEqualTo(QueryField("lines"))
    ((mongo.predicate as ElementMatchFilter).predicate as EqualFilter).field.assert()
        .isEqualTo(QueryField("price"))

    val elasticsearch = model(mapped = true).resolve(input).value as ElementMatchFilter
    elasticsearch.field.assert().isEqualTo(QueryField("document.orders"))
    (elasticsearch.predicate as ElementMatchFilter).field.assert().isEqualTo(QueryField("lines"))
    ((elasticsearch.predicate as ElementMatchFilter).predicate as EqualFilter).field.assert()
        .isEqualTo(QueryField("price.keyword"))
}
```

- [ ] **Step 2: Run the focused tests and observe the missing model methods**

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QuerySchemaResolverTest" \
  --tests "me.ahoo.wow.query.schema.QueryModelSchemaTest"
```

Expected: tests fail because QueryModelSchema.resolve does not own the entry point and current resolver always copies nodes.

- [ ] **Step 3: Add the six QueryModelSchema resolve methods**

Use the confirmed signatures and keep the implementation delegate private:

```kotlin
private val resolver = QuerySchemaResolver(this)

fun resolve(query: ISingleQuery): QuerySchemaResolution<ISingleQuery> = resolver.resolve(query)
fun resolve(query: IListQuery): QuerySchemaResolution<IListQuery> = resolver.resolve(query)
fun resolve(query: IPagedQuery): QuerySchemaResolution<IPagedQuery> = resolver.resolve(query)
fun resolve(query: ICursorQuery): QuerySchemaResolution<ICursorQuery> = resolver.resolve(query)
fun resolve(filter: FilterExpression): QuerySchemaResolution<FilterExpression> = resolver.resolve(filter)
fun resolve(query: AggregationQuery): QuerySchemaResolution<AggregationQuery> = resolver.resolve(query)
```

Mark QuerySchemaResolver `internal`; remove external production call sites and migrate tests to `schema.resolve(...)` except tests directly exercising an internal algorithm branch.

- [ ] **Step 4: Replace String/physical parent logic with QueryField relations**

Track these three absolute parents independently during Element resolution:

```kotlin
logicalParent: QueryField?
resolvedParent: QueryField?
physicalParent: QueryField?
```

Emit children with `binding.resolvedField.relativeTo(resolvedParent)` and validate physical ancestry with `binding.physicalField.relativeTo(physicalParent)`. After resolving an Element container, update parents with the container's absolute logical field and absolute Binding fields, never with emitted relative fields.

- [ ] **Step 5: Preserve unchanged nodes during the single traversal**

- MatchAll/MatchNone and empty Sort return O(1).
- A leaf returns the original instance when the field and temporal parameters are unchanged.
- Composite filters allocate only if at least one child changes.
- Projection admission uses `schema.field(field)`: unknown fields remain COMPATIBLE, an exact/derived field with `projectionField == null` is INCOMPATIBLE, and an available projectionField is EXACT; the original Projection and both lists are always reused.
- Query objects allocate only if Filter or Sort changes; Projection is validated but always reused.
- Model `NONE` skips rewrite comparisons/allocation but still performs capability, Mask, Cursor, cardinality and value admission.
- Value types are checked through `QueryModelSchema.matchesValueTypes`, never through a dynamic schema returned by `field()`.

- [ ] **Step 6: Make Provider methods delegate to the Model**

Replace every `it.resolver.resolve(...)` with `it.resolve(...)`. Preserve unavailable fallback and system-tags protection exactly; do not create a second Provider/Resolver interface.

- [ ] **Step 7: Run runtime resolver checks**

Run:

```bash
./gradlew :wow-query:check
```

Expected: all wow-query tests pass, including identity reference assertions.

- [ ] **Step 8: Commit**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/schema \
  wow-query/src/test/kotlin/me/ahoo/wow/query/schema
git commit -m "refactor(query): make model schema resolve queries"
```

---

### Task 6: Enforce EventStream Projection Contract and Delete Mask Projection Patches

**Files:**
- Delete: `wow-query/src/main/kotlin/me/ahoo/wow/query/mask/EventMaskProjection.kt`
- Delete: `wow-query/src/test/kotlin/me/ahoo/wow/query/mask/EventMaskProjectionTest.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/mask/SchemaMaskQueryFilter.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/event/EventStreamQueryGateway.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolverTest.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/event/DefaultEventStreamQueryGatewayTest.kt`

**Interfaces:**
- Consumes: Projection pass-through and QueryField path relations.
- Produces: EventStream payload/bodyType compatibility rule; no internal bodyType projection flag, no query patch, no delivery cleanup.

- [ ] **Step 1: Replace wildcard tests with a failing compatibility matrix**

Use a parameterized or table-driven test with these exact outcomes:

```kotlin
val cases = listOf(
    Projection.ALL to QueryCompatibilityLevel.EXACT,
    Projection(include = listOf(QueryField("body"))) to QueryCompatibilityLevel.EXACT,
    Projection(include = listOf(QueryField("body.body"))) to QueryCompatibilityLevel.INCOMPATIBLE,
    Projection(
        include = listOf(QueryField("body.body"), QueryField("body.bodyType")),
    ) to QueryCompatibilityLevel.EXACT,
    Projection(
        include = listOf(QueryField("body.body.secret"), QueryField("body.bodyType")),
    ) to QueryCompatibilityLevel.EXACT,
    Projection(exclude = listOf(QueryField("body.bodyType"))) to QueryCompatibilityLevel.INCOMPATIBLE,
    Projection(exclude = listOf(QueryField("body.body"))) to QueryCompatibilityLevel.EXACT,
)
```

For every case also assert `resolution.value === projection`.

- [ ] **Step 2: Run the matrix and verify current auto-patching fails the contract**

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QuerySchemaResolverTest"
```

Expected: payload-only projections are currently rewritten/accepted instead of rejected.

- [ ] **Step 3: Implement logical selection checks inside Schema resolution**

Use only QueryField relations:

```kotlin
private val EVENT_BODY_PAYLOAD_FIELD = QueryField("body.body")
private val EVENT_BODY_TYPE_FIELD = QueryField("body.bodyType")

private fun QueryField.selects(target: QueryField): Boolean =
    this == target || target.relativeTo(this) != null

private fun QueryField.intersects(target: QueryField): Boolean =
    selects(target) || target.selects(this)
```

Calculate the accepted predicate exactly as specified and combine INCOMPATIBLE with the Projection field admission level. Return the original Projection object for accepted and rejected resolutions.

- [ ] **Step 4: Delete internal bodyType patch plumbing**

- Delete `internalEventBodyTypeProjected`, `requiresInternalEventBodyType`, `withInternalEventBodyType`, `removeInternalEventBodyType`, wildcard regex matching and unrecoverable-exclusion logic.
- Remove bodyType flag calculation from SchemaMaskQueryFilter; keep only result masking.
- Remove EventStreamQueryGateway imports and `prepareDynamicResult` override.
- Keep SchemaMasker event-type selection; public Projection now guarantees bodyType whenever payload is selected.

- [ ] **Step 5: Run query and gateway tests**

Run:

```bash
./gradlew :wow-query:check
```

Expected: all tests pass and `rg -n 'internalEventBodyTypeProjected|requiresInternalEventBodyType|withInternalEventBodyType|removeInternalEventBodyType|matchesProjectionPattern' wow-query/src` returns no matches.

- [ ] **Step 6: Commit**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/mask \
  wow-query/src/main/kotlin/me/ahoo/wow/query/event/EventStreamQueryGateway.kt \
  wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query
git commit -m "refactor(query): require event body type projection"
```

---

### Task 7: Compile Projection Inside MongoDB and Elasticsearch Backends

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/converter/ProjectionConverter.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/converter/AbstractProjectionConverter.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/MongoProjectionConverter.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/MongoCollections.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackend.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryBackend.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryBackend.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchProjectionConverter.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryBackend.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryBackend.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/event/ElasticsearchEventStreamQueryBackend.kt`
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/MongoProjectionConverterTest.kt`
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackendTest.kt`
- Test: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryBackendTest.kt`
- Test: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchQueryBackendTest.kt`
- Test: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryBackendTest.kt`

**Interfaces:**
- Consumes: pass-through Projection and QueryFieldSchema.projectionField.
- Produces: Backend-native Projection compilation using the same QueryModelSchema instance as admission; QueryBackend public signatures stay unchanged.

- [ ] **Step 1: Add failing compiler tests for scalar/object include and exclude**

MongoDB assertions:

```kotlin
val include = listOf(QueryField("state"), QueryField("state.name"))
val includeProjection = Projection(include = include)
converter.convert(
    includeProjection,
    schema,
).assert().isEqualTo(
    Projections.include("document", "document.name"),
)
includeProjection.include.assert().isSameAs(include)

converter.convert(
    Projection(exclude = listOf(QueryField("state"), QueryField("state.name"))),
    schema,
).assert().isEqualTo(
    Projections.exclude("document", "document.name"),
)
```

Elasticsearch assertions:

```kotlin
ElasticsearchProjectionConverter.convert(
    Projection(include = listOf(QueryField("state"), QueryField("state.name"))),
    schema,
).includes().assert().containsExactly(
    "document", "document.*", "document.name", "document.name.*",
)

ElasticsearchProjectionConverter.convert(
    Projection(exclude = listOf(QueryField("state"), QueryField("state.name"))),
    schema,
).excludes().assert().containsExactly(
    "document", "document.*", "document.name", "document.name.*",
)
```

- [ ] **Step 2: Run converter tests and observe missing Schema-aware signatures**

Run:

```bash
./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.MongoProjectionConverterTest" \
  :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryBackendTest"
```

Expected: compilation fails because converters do not accept QueryModelSchema.

- [ ] **Step 3: Add Schema-aware compiler methods without a new execution wrapper**

Change the shared interface exactly once:

```kotlin
interface ProjectionConverter<T> {
    fun convert(projection: Projection, schema: QueryModelSchema?): T
}
```

Use this shared resolution rule at Backend compiler boundaries:

```kotlin
private fun QueryField.projectionField(schema: QueryModelSchema?): QueryField =
    schema?.field(this)?.projectionField ?: this
```

- MongoDB maps the resolved projectionField through its FieldConverter and emits exact node paths.
- Elasticsearch emits each resolved node as `path` and `path.*`; de-duplicate while preserving request order.
- Empty Projection returns the backend's ALL/no-filter representation without field traversal.
- Keep a nullable Schema only for the existing COMPATIBLE unavailable-schema fallback; never fetch a second Schema after successful admission.
- Change `MongoProjectionConverter.cursorProjection` to `cursorProjection(projection, sortFields, schema)` and use the same Schema for query projection and cursor-internal fields.

- [ ] **Step 4: Make each Backend acquire Schema once per execution chain**

In each concrete MongoDB/Elasticsearch Backend operation, replace `schemaProvider.resolve(query, mode)` followed by separate compilation with one `schemaProvider.schema().flatMap/flatMapMany` closure:

```kotlin
schemaProvider.schema().flatMap { schema ->
    val resolved = schema.resolve(query).requireAccepted(validationMode)
    execute(resolved, schema)
}
```

For list/aggregation use `flatMapMany`. Preserve the existing COMPATIBLE unavailable fallback by executing the original query with `schema = null`; Cursor remains strict. Do not introduce Pair, PreparedQuery, ResolvedQuery or another runtime holder.

- [ ] **Step 5: Thread Schema only through private/native helpers**

- Add Schema parameters to private `findDocument`, cursor projection and Elasticsearch request/source-filter helpers.
- Keep QueryBackend `single/list/paged/cursor/count/aggregate` signatures unchanged.
- Filter/Sort use the resolved public Query; Projection Compiler receives the original Projection reference plus Schema.
- Aggregation continues using its existing query+schema execution path until stage one removes ResolvedAggregationQuery.

- [ ] **Step 6: Run unit and Backend integration tests**

Run:

```bash
./gradlew :wow-mongo:check :wow-elasticsearch:check
./gradlew :wow-mongo:integrationTest \
  --tests "me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryBackendTest"
./gradlew :wow-elasticsearch:integrationTest \
  --tests "me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryBackendTest"
```

Expected: unit checks and both focused integration suites pass; scalar/object Projection behavior is identical at the public contract.

- [ ] **Step 7: Commit**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/converter \
  wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query \
  wow-mongo/src/test wow-mongo/src/integrationTest \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query \
  wow-elasticsearch/src/test wow-elasticsearch/src/integrationTest
git commit -m "refactor(query): compile projection in backends"
```

---

### Task 8: Update OpenAPI, TCK, Benchmarks, Documentation and Run Final Gates

**Files:**
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/QueryContractComponentSupport.kt`
- Modify: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt`
- Modify: `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryBackendSpec.kt`
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/EventStreamQueryBackendSpec.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuardFilterTest.kt`
- Modify: `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/query/QuerySchemaResolverBenchmark.kt`
- Modify: query documentation under `documentation/docs/zh/guide/query/` that constructs Projection, Sort or QueryField.
- Test: all module checks listed below.

**Interfaces:**
- Consumes: completed Phase 0 runtime and Backend behavior.
- Produces: exact OpenAPI contract, Backend-neutral TCK coverage, JMH evidence and release-ready local verification.

- [ ] **Step 1: Add/refresh contract assertions before snapshots**

Assert OpenAPI contains:

```text
#/components/schemas/wow.api.query.QueryField
Projection.include.items -> QueryField ref
Projection.exclude.items -> QueryField ref
Sort.field -> QueryField ref
QueryField schema type -> string
```

Add TCK cases for:

- scalar Projection include/exclude;
- object subtree Projection include/exclude;
- Sort QueryField execution;
- EventStream payload Projection with bodyType;
- rejection of EventStream payload Projection without bodyType at the Schema/runtime layer.

- [ ] **Step 2: Run OpenAPI and TCK tests before updating snapshots**

Run:

```bash
./gradlew :wow-openapi:test :wow-tck:check
```

Expected: OpenAPI snapshot and any unupdated contract assertion fail with old component/type content.

- [ ] **Step 3: Update snapshot, HTTP tests and documentation examples**

- Regenerate or deliberately patch the OpenAPI snapshot only after assertions describe the new contract.
- Replace Projection/Sort String constructors with QueryField in Kotlin/Java examples.
- Remove public Projection/Sort wildcard examples.
- Document the EventStream bodyType requirement and wire semantic break.
- Preserve prose references to the historical name LogicalField only in migration/design explanation.

- [ ] **Step 4: Expand JMH cases**

Keep benchmarks on `QueryModelSchema.resolve`, not external QuerySchemaResolver. Add named benchmarks for:

```text
modelNoneIdentityQuery
fieldInferMappedSort
fieldRequiredMappedFilter
identityDynamicFilter
rewriteDynamicFilter
projectionValidationPassThrough
```

Each Projection benchmark must assert/consume the same Projection reference. Configure the JMH run with GC profiler so `gc.alloc.rate.norm` is captured.

- [ ] **Step 5: Compile and smoke-run the benchmark**

Run:

```bash
./gradlew :wow-benchmarks:jmhJar
java -jar wow-benchmarks/build/libs/wow-benchmarks-9.0.4-jmh.jar \
  'me.ahoo.wow.benchmark.query.QuerySchemaResolverBenchmark.*' \
  -prof gc -wi 3 -w 200ms -i 5 -r 200ms -f 1 -t 1
```

Expected: all six benchmark methods execute; output includes average time and `gc.alloc.rate.norm`. Record medians in the implementation handoff; do not claim improvement without before/after evidence.

- [ ] **Step 6: Run the narrow module gates**

Run:

```bash
./gradlew :wow-api:check :wow-query:check :wow-schema:check :wow-openapi:check \
  :wow-mongo:check :wow-elasticsearch:check :wow-webflux:check :wow-tck:check
```

Expected: all tasks succeed with zero test failures.

- [ ] **Step 7: Run repository static and documentation gates**

Run:

```bash
./gradlew detekt
cd documentation
pnpm docs:build
```

Expected: detekt succeeds and VitePress reports `build complete`.

- [ ] **Step 8: Verify final source and worktree invariants**

Run:

```bash
rg -n '\bLogicalField\b' --glob '*.kt' --glob '*.java'
rg -n 'state\.\*' wow-api wow-query wow-mongo wow-elasticsearch test --glob '*.kt' --glob '*.java'
rg -n 'internalEventBodyTypeProjected|withInternalEventBodyType|removeInternalEventBodyType|matchesProjectionPattern' wow-query/src
git diff --check
git status --short
```

Expected:

- no executable-source LogicalField matches;
- no Projection/Sort wildcard contract tests remain;
- no internal bodyType patch plumbing remains;
- diff check succeeds;
- status contains only the planned Phase 0 changes.

- [ ] **Step 9: Commit the verification and contract updates**

```bash
git add wow-openapi wow-webflux wow-benchmarks test documentation
git commit -m "test(query): verify schema phase zero contracts"
```

## Final Review Checklist

- [ ] Every confirmed design requirement maps to at least one task and executable gate.
- [ ] Projection remains the same object through Runtime resolution.
- [ ] Backend obtains one Schema instance for successful admission and Projection compilation.
- [ ] No public Query contains Backend pattern or physical-only path syntax.
- [ ] EventStream payload cannot be selected without bodyType.
- [ ] Dynamic identity and rewrite paths have separate tests and benchmarks.
- [ ] No compatibility wrapper, parallel model, cache or speculative interface was introduced.
- [ ] Source, wire and OpenAPI breaking changes are separately documented.
