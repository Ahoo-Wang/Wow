# Wow BI Lossless Corrections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `wow-bi` trust the actual Jackson wire shape, preserve every supported scalar without silent conversion, and execute the complete generated DDL on the minimum ClickHouse version.

**Architecture:** `JsonPropertyTypeResolver` remains declaration-type/nullability introspection. A new `JacksonWireShapeInspector` is the only gate deciding whether an object is structurally expandable. `ClickHouseTypeMapping` models JSON token shape together with the ClickHouse scalar. `BiScriptGenerator` builds immutable executable statements first and derives the human-readable script from the same statements.

**Tech Stack:** Kotlin 2.3.20, JVM 17, Jackson 3.1.4, ClickHouse 24.8 LTS, JUnit Jupiter, FluentAssert, Testcontainers, Gradle.

## Global Constraints

- Forward compatibility, migration, and rollback are out of scope; do not add adapters or migration documentation.
- `JsonSerializer` is the only authority for the Kafka JSON wire format.
- If typed projection cannot be proven lossless, preserve the whole nearest addressable JSON value with `RAW_JSON` or fail under `UnsupportedTypeStrategy.FAIL`.
- Do not scan runtime subtypes, instantiate domain objects to guess schemas, reverse-engineer serializers, or hard-code framework serializer fields.
- Production code changes follow RED -> GREEN -> REFACTOR and every RED failure must be observed before implementation.
- The full `BiScriptGenerator` statement sequence must execute on `clickhouse/clickhouse-server:24.8.14.39-alpine` without parsing the rendered SQL string in test code.
- Do not add dependencies or public compatibility APIs.

---

## Task 1: Gate Object Expansion By Jackson Wire Shape

**Files:**
- Create: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/type/JsonWireShape.kt`
- Create: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/type/JacksonWireShapeInspector.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/type/JsonPropertyTypeResolver.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/plan/StateExpansionPlanner.kt`
- Modify: `wow-bi/src/test/java/me/ahoo/wow/bi/expansion/type/JavaNullabilityFixture.java`
- Create: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/type/JacksonWireShapeInspectorTest.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/type/JsonPropertyTypeResolverTest.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/plan/StateExpansionPlannerTest.kt`

**Interfaces:**
- Consumes: `JsonSerializer`, `ResolvedType`, `ResolvedJsonProperty`, `UnsupportedTypeStrategy`.
- Produces:

```kotlin
internal sealed interface JsonWireShape {
    data class ExpandableObject(
        val properties: List<ResolvedJsonProperty>,
    ) : JsonWireShape

    data class Opaque(val reason: JsonWireShapeReason) : JsonWireShape
}

internal enum class JsonWireShapeReason {
    ABSTRACT_OR_POLYMORPHIC,
    NON_OBJECT_FORMAT,
    CUSTOM_SERIALIZATION,
    PROPERTY_SIGNATURE_MISMATCH,
}

internal object JacksonWireShapeInspector {
    fun inspect(type: ResolvedType): JsonWireShape
}
```

- `JsonPropertyTypeResolver` keeps `resolve(ResolvedType)` and adds Java inherited override evidence; it does not decide expandability.

- [ ] **Step 1: Write failing inspector and planner tests**

Add tests proving:

```kotlin
@Test
fun `should mark sealed interface as opaque`() {
    val type = resolvedType<Payment>()
    JacksonWireShapeInspector.inspect(type)
        .assert().isInstanceOf(JsonWireShape.Opaque::class.java)
}

@Test
fun `should mark aggregate id custom serializer as opaque`() {
    val type = resolvedType<AggregateId>()
    JacksonWireShapeInspector.inspect(type)
        .assert().isInstanceOf(JsonWireShape.Opaque::class.java)
}

@Test
fun `should preserve an opaque nested value as one raw column`() {
    val plan = plan<OpaquePropertyState>(UnsupportedTypeStrategy.RAW_JSON)
    plan.rootColumns().single { it.path == "payment" }.apply {
        type.assert().isEqualTo(ClickHouseType.String)
        extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(ColumnReference.Input("state"), "payment"))
    }
    plan.diagnostics.single { it.path == "payment" }
        .decision.assert().isEqualTo(BiScriptMappingDecision.RAW_JSON)
}

@Test
fun `should fail for an opaque nested value in strict mode`() {
    assertThrownBy<IllegalArgumentException> {
        plan<OpaquePropertyState>(UnsupportedTypeStrategy.FAIL)
    }.hasMessageContaining("payment")
}
```

Cover ordinary concrete beans, interface/abstract/sealed types, `@JsonValue`, `@JsonUnwrapped`, `@JsonAnyGetter`, class custom serializer, property `using/contentUsing/keyUsing/converter`, `AggregateId`, opaque collection elements, opaque root state, and unaffected ordinary siblings.

- [ ] **Step 2: Write a failing Java override contract test**

Add a Java parent contract with `Map<@NonNull String, @NonNull Integer>` and an unannotated implementation override. Assert the resolver returns `NON_NULL` for the property and both generic arguments. Run:

```bash
./gradlew :wow-bi:test --tests '*JsonPropertyTypeResolverTest' --tests '*JacksonWireShapeInspectorTest' --tests '*StateExpansionPlannerTest'
```

Expected: FAIL because no wire-shape gate exists and Java inherited annotations are not collected.

- [ ] **Step 3: Implement the inspector and Java override evidence**

Use `JsonSerializer.acceptJsonFormatVisitor(type.javaType, visitor)` to record the selected root format and object property signatures. Reject non-object formats and signature mismatches. Reject `Modifier.isAbstract`, `Class.isInterface`, `Class.isSealed`, Jackson polymorphic type metadata, Json value/any/unwrapped accessors, and serializer/converter annotations before returning `ExpandableObject`.

Add `Member.inheritedJavaEvidence(rootClass, javaRootBindings)` parallel to `inheritedKotlinEvidence`. Select only the most-specific overridden Java contracts and apply `findJavaTypeParameterBindings` before reading `annotatedReturnType`.

Planner rules:

```kotlin
when (val shape = JacksonWireShapeInspector.inspect(type)) {
    is JsonWireShape.ExpandableObject -> collectResolvedProperties(shape.properties, ...)
    is JsonWireShape.Opaque -> collectRawFallback(...)
}
```

For an opaque root, emit a SELECT `ColumnExtraction.Reference(ColumnReference.Input("state"))` named `state` plus one `RAW_JSON_FALLBACK` diagnostic at path `$`. For opaque collection elements, preserve the whole collection as raw; do not create an array-join child view.

- [ ] **Step 4: Run the focused tests and refactor**

```bash
./gradlew :wow-bi:test --tests '*JsonPropertyTypeResolverTest' --tests '*JacksonWireShapeInspectorTest' --tests '*StateExpansionPlannerTest'
```

Expected: PASS. Refactor only duplication inside shape inspection/evidence selection, then rerun the same command.

- [ ] **Step 5: Commit**

```bash
git add wow-bi/src/main wow-bi/src/test
git commit -m "fix(bi): gate expansion by Jackson wire shape"
```

---

## Task 2: Model And Verify Lossless Scalar Wire Mappings

**Files:**
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/type/ClickHouseTypeMapping.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/type/JacksonWireShapeInspector.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/plan/StateExpansionPlanner.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/type/ClickHouseTypeMappingTest.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/plan/StateExpansionPlannerTest.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/BiTestFixtures.kt`
- Modify: `wow-bi/src/test/resources/expected_bi_script.sql`

**Interfaces:**
- Produces:

```kotlin
internal enum class JsonTokenShape {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
}

internal data class ScalarMapping(
    val tokenShape: JsonTokenShape,
    val clickHouseType: ClickHouseType.Scalar,
)

internal object ClickHouseTypeMapping {
    fun Class<*>.scalarMapping(): ScalarMapping?
}
```

- `JacksonWireShapeInspector.matches(type: ResolvedType, expected: JsonTokenShape): Boolean` validates the serializer-selected format.

- [ ] **Step 1: Replace the mapping expectation with lossless cases**

Write tests expecting:

```kotlin
BigDecimal::class.java.scalarMapping().assert().isNull()
Duration::class.java.scalarMapping().assert().isEqualTo(ScalarMapping(STRING, ClickHouseType.String))
Date::class.java.scalarMapping().assert().isEqualTo(ScalarMapping(STRING, ClickHouseType.String))
java.sql.Date::class.java.scalarMapping().assert().isEqualTo(ScalarMapping(STRING, ClickHouseType.String))
Instant::class.java.scalarMapping().assert().isEqualTo(ScalarMapping(STRING, ClickHouseType.String))
Year::class.java.scalarMapping().assert().isEqualTo(ScalarMapping(INTEGER, ClickHouseType.Int32))
kotlin.time.Duration::class.java.scalarMapping().assert().isNull()
```

Also assert a default enum maps only when its visitor format is string and an enum using `@JsonValue` becomes raw/fail.

- [ ] **Step 2: Add planner failures for every lossy current mapping**

Assert the generated plan uses:

- `BigDecimal`: `JsonRaw` + `String` + `RAW_JSON_FALLBACK` diagnostic.
- `Duration`, `Date`, `java.sql.Date`, `Instant`: typed `String` via `JsonValue`.
- `Year`: typed `Int32`.
- Kotlin Duration property: the resolver-provided `Long` wire representation and `Int64`, without a duration registry entry.

Run:

```bash
./gradlew :wow-bi:test --tests '*ClickHouseTypeMappingTest' --tests '*StateExpansionPlannerTest' --tests '*BiScriptGeneratorTest'
```

Expected: FAIL with the old Decimal/Decimal64/UInt mappings.

- [ ] **Step 3: Implement the immutable token/type registry**

Delete `BigDecimal` and explicit Kotlin Duration entries. Change Duration/Date/sql.Date/Instant to `STRING -> String`, Year to `INTEGER -> Int32`, and retain exact primitive/UUID/java-time string entries. Before typed scalar projection, require the inspector format to match the mapping token; a mismatch follows the same raw/fail path as an opaque object. Collections/maps are typed only when their element/value mapping is present and wire-compatible.

- [ ] **Step 4: Update the canonical script and run focused tests**

```bash
./gradlew :wow-bi:test --tests '*ClickHouseTypeMappingTest' --tests '*StateExpansionPlannerTest' --tests '*BiScriptGeneratorTest'
```

Expected: PASS and the canonical SQL contains raw BigDecimal, String temporal values, and signed Year.

- [ ] **Step 5: Commit**

```bash
git add wow-bi/src/main wow-bi/src/test
git commit -m "fix(bi): preserve scalar JSON wire values"
```

---

## Task 3: Execute The Complete Generated Statement Graph

**Files:**
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/BiScriptResult.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/BiScriptGenerator.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/renderer/ClickHouseScriptRenderer.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/BiScriptGeneratorTest.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/renderer/ClickHouseScriptRendererTest.kt`
- Modify: `wow-bi/src/integrationTest/kotlin/me/ahoo/wow/bi/ClickHouseExpansionIntegrationTest.kt`

**Interfaces:**
- `BiScriptResult` keeps the public `script` and immutable `diagnostics`, has an internal constructor, and stores `internal val statements: List<String>`.
- Renderer provides `renderGlobalStatements`, `renderClearStatements`, `renderCommandStatements`, `renderStateEventStatements`, `renderStateLastStatements`, and existing `renderExpansionStatements`, all returning immutable statement lists without section comments.
- Existing text render methods, where still useful to unit tests, are derived with `joinToString("\n\n")` from the statement methods.

- [ ] **Step 1: Write failing generator source-of-truth tests**

Assert `result.statements` is immutable, contains every global/clear/command/stateEvent/stateLast/expansion statement in dependency order, contains no section comments, and that every SQL statement in `result.script` originates from this list. Assert reversed aggregate input produces equal `BiScriptResult` values.

- [ ] **Step 2: Write a failing complete ClickHouse smoke test**

Change the integration test to:

```kotlin
val result = BiScriptGenerator(options).generate(setOf(namedAggregate))
result.statements.forEach(connection::executeSql)
```

Do not call `StateExpansionPlanner` or renderer directly and do not split `result.script`. Assert `system.tables` contains representative command, state, state-last, Kafka queue, consumer view, root expansion, and child expansion objects.

Insert state rows into the generated state-last table. Build each state JSON using `JsonSerializer.writeValueAsString`, including a high-precision BigDecimal, `Date(-1)`, `Year.of(-1)`, nanosecond Duration, and nanosecond Instant. Query typed/raw columns and compare with the exact serialized subtree.

Run:

```bash
./gradlew :wow-bi:integrationTest --tests '*ClickHouseExpansionIntegrationTest'
```

Expected: FAIL because the result has no statements and current scalar projections are not validated end-to-end.

- [ ] **Step 3: Refactor renderer and generator around statement lists**

Build the ordered statement list first, freeze it with `Collections.unmodifiableList(ArrayList(...))`, and render section comments/script from the same list. Remove decorative divider comments inside clear SQL. Do not add a SQL splitter or expose renderer internals publicly.

- [ ] **Step 4: Run unit and integration tests**

```bash
./gradlew :wow-bi:test --tests '*BiScriptGeneratorTest' --tests '*ClickHouseScriptRendererTest'
./gradlew :wow-bi:integrationTest --tests '*ClickHouseExpansionIntegrationTest'
```

Expected: PASS; Docker absence is a hard environment failure, not a skip.

- [ ] **Step 5: Commit**

```bash
git add wow-bi/src/main wow-bi/src/test wow-bi/src/integrationTest
git commit -m "test(bi): execute complete generated ClickHouse DDL"
```

---

## Task 4: Remove Competing Architecture And Migration Documentation

**Files:**
- Delete: `docs/superpowers/specs/2026-07-10-wow-bi-refactor-design.md`
- Delete: `docs/superpowers/plans/2026-07-10-wow-bi-refactor.md`
- Delete: `docs/superpowers/plans/2026-07-10-wow-bi-clean-architecture.md`
- Modify: `documentation/docs/en/guide/bi.md`
- Modify: `documentation/docs/zh/guide/bi.md`
- Modify: `documentation/docs/en/guide/advanced/module-dependencies.md`
- Modify: `documentation/docs/zh/guide/advanced/module-dependencies.md`
- Modify: `docs/superpowers/specs/2026-07-10-wow-bi-clean-architecture-design.md`

**Interfaces:**
- Produces one architecture fact source and current-only user documentation.

- [ ] **Step 1: Add documentation search gates**

Run before editing and record that it finds stale content:

```bash
rg -n "Breaking Migration|破坏性迁移|BiScriptOptions\.validate|BiScriptRouteOptions|wow_openapi --> wow_bi|Depends on .*wow-bi|依赖.*wow-bi" documentation docs/superpowers/specs
```

- [ ] **Step 2: Delete stale sources and current-only migration sections**

Delete both obsolete refactor files. Remove the complete `Breaking Migration` / `破坏性迁移` sections rather than rewriting them. Document only current opaque/raw rules, lossless scalar decisions, the ClickHouse 24.8 LTS minimum, and the current seven-type public API.

Fix module dependencies to show:

```text
wow-api --api--> wow-bi --api--> wow-webflux
wow-core --implementation--> wow-bi
wow-bi --webfluxSupportApi--> wow-spring-boot-starter
```

Remove the nonexistent `wow-openapi -> wow-bi` edge and correct its prose.

- [ ] **Step 3: Run documentation gates**

```bash
rg -n "Breaking Migration|破坏性迁移|BiScriptOptions\.validate|BiScriptRouteOptions|wow_openapi --> wow_bi|Depends on .*wow-bi|依赖.*wow-bi" documentation docs/superpowers/specs
cd documentation && pnpm docs:build
```

Expected: `rg` returns no stale matches and VitePress build PASS.

- [ ] **Step 4: Commit**

```bash
git add -A docs/superpowers documentation
git commit -m "docs(bi): keep one current architecture contract"
```

---

## Task 5: Add An Authoritative State Recovery Channel

**Files:**
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/plan/ColumnPlan.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/plan/ExpansionViewPlan.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/plan/StateExpansionPlanner.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/renderer/ClickHouseScriptRenderer.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/plan/StateExpansionPlannerTest.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/renderer/ClickHouseScriptRendererTest.kt`
- Modify: `wow-bi/src/integrationTest/kotlin/me/ahoo/wow/bi/ClickHouseExpansionIntegrationTest.kt`
- Modify: `documentation/docs/en/guide/bi.md`
- Modify: `documentation/docs/zh/guide/bi.md`
- Modify: `docs/superpowers/specs/2026-07-10-wow-bi-clean-architecture-design.md`

**Interfaces:**
- Every `ExpansionViewPlan` owns an immutable recovery plan separate from domain `ColumnPlan` values.
- Every view projects `state_last.state` directly as `__state` without JSON parsing.
- Root `__path` is the empty RFC 6901 pointer.
- Every collection child row projects the current zero-based `__index` and a complete `__path`, for example `/orders/2/lines/5`.
- RFC 6901 property segments encode `~` as `~0` and `/` as `~1`.
- `__raw__*` and fallback columns use scoped `JSONExtractRaw` only as convenience projections; `__state` is the only lexical authority.

- [ ] **Step 1: Write failing planner and renderer recovery tests**

Add tests asserting:

```kotlin
rootView.recovery.pointer.assert().isEmpty()
childView.recovery.pointer.assert().containsExactly(
    JsonPointerSegment.Property("orders"),
    JsonPointerSegment.Index(childView.recovery.currentIndex),
)
```

Cover root authority, one-level and nested collections, duplicate elements, nullable elements, `@JsonProperty("a/b~c")` escaping, immutable recovery values, and collisions with `__state`, `__path`, `__index`, and the internal cursor namespace.

Renderer tests must require:

```sql
"__source"."state" AS "__state"
arrayJoin(arrayZip(arrayEnumerate(JSONExtractArrayRaw("__source"."state", 'orders')),
                   JSONExtractArrayRaw("__source"."state", 'orders'))) AS "__cursor__orders"
toUInt64(tupleElement("__cursor__orders", 1) - 1) AS "__index"
concat('/orders/', toString(tupleElement("__cursor__orders", 1) - 1)) AS "__path"
```

They must also require scoped `JSONExtractRaw` and reject `simpleJSONExtractRaw`.

Run:

```bash
./gradlew :wow-bi:test --tests '*StateExpansionPlannerTest' --tests '*ClickHouseScriptRendererTest'
```

Expected: FAIL because no recovery plan, index/path, or authoritative state projection exists and renderer still uses `simpleJSONExtractRaw`.

- [ ] **Step 2: Write the failing ClickHouse regression**

Build all JSON through `JsonSerializer` and add:

- a nested object containing the same property name before the top-level target;
- escaped serialized names containing quote, backslash, newline, `~`, and `/`;
- duplicate, null, empty, and high-precision collection elements;
- a nested collection requiring multiple indices.

Assert current raw convenience is scoped correctly, every view's `__state` equals the exact inserted state string, and every child occurrence has the expected zero-based `__index` and RFC 6901 `__path`.

Run:

```bash
./gradlew :wow-bi:integrationTest --tests '*ClickHouseExpansionIntegrationTest'
```

Expected: FAIL on the current `simpleJSONExtractRaw` wrong-value case and missing recovery columns.

- [ ] **Step 3: Implement the minimal structured recovery model**

Add internal immutable recovery types:

```kotlin
internal sealed interface JsonPointerSegment {
    data class Property(val encoded: String) : JsonPointerSegment
    data class Index(val reference: ColumnReference) : JsonPointerSegment
}

internal data class ExpansionRecoveryPlan(
    val pointer: List<JsonPointerSegment>,
    val currentIndex: ColumnReference?,
)
```

Keep collection cursor/index planning outside domain property columns. Render one zipped array join so the raw element and ordinal cannot drift. Carry inherited pointer segments through nested object and collection planning. Add recovery names to metadata collision validation. Replace `simpleJSONExtractRaw` with scoped `JSONExtractRaw`; do not claim the convenience value is lexical-authoritative.

- [ ] **Step 4: Run focused and real ClickHouse verification**

```bash
./gradlew :wow-bi:test --tests '*StateExpansionPlannerTest' --tests '*ClickHouseScriptRendererTest'
./gradlew :wow-bi:integrationTest --tests '*ClickHouseExpansionIntegrationTest'
./gradlew :wow-bi:detekt
```

Expected: PASS with exact state/path/index assertions and no `simpleJSONExtractRaw` production hit.

- [ ] **Step 5: Synchronize current documentation**

Document `__state` authority, scoped raw convenience, RFC 6901 `__path`, zero-based `__index`, and the consumer source-slice requirement in both BI guides. Remove every claim that a property raw column preserves arbitrary-precision lexical JSON.

- [ ] **Step 6: Commit**

```bash
git add wow-bi documentation docs/superpowers/specs/2026-07-10-wow-bi-clean-architecture-design.md
git commit -m "fix(bi): add authoritative state recovery coordinates"
```

---

## Task 6: Final Verification And Review

**Files:**
- Modify only files required by verified review findings.

- [ ] **Step 1: Run module and integration verification**

```bash
./gradlew :wow-bi:clean :wow-bi:test :wow-bi:integrationTest --rerun-tasks
./gradlew :wow-bi:check :wow-webflux:check :wow-spring-boot-starter:check :wow-openapi:check detekt
```

- [ ] **Step 2: Run documentation and architecture gates**

```bash
cd documentation && pnpm docs:build
rg -n "ScriptEngine|ScriptTemplateEngine|StateExpansionScriptGenerator|BiScriptRouteOptions|ObjectMapStrategy|Breaking Migration|破坏性迁移" \
  wow-bi wow-webflux wow-spring-boot-starter documentation docs/superpowers/specs
git diff --check
git status --short
```

Expected: builds PASS, stale-symbol search has no hits, diff check is clean, and only intentional files are present before the final commit.

- [ ] **Step 3: Run independent whole-branch review**

Review correctness, Jackson/JVM/ClickHouse wire semantics, immutable boundaries, dependency direction, test strength, and current-only documentation. Fix every Critical/Important finding and rerun its covering tests before completion.
