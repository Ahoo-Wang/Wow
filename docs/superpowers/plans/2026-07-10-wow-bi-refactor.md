# Wow BI Complete Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `wow-bi` into a deterministic, configurable, diagnosable ClickHouse script generator, fix proven SQL correctness defects, and wire the options through WebFlux and Spring Boot without breaking legacy Kotlin or HTTP contracts.

**Architecture:** `BiScriptGenerator` sorts aggregates and orchestrates immutable `StateExpansionPlan` values. `StateExpansionPlanner` owns metadata traversal and diagnostics; `ClickHouseScriptRenderer` owns all ClickHouse syntax. WebFlux adapts its own nullable `BiScriptRouteOptions` to the BI domain privately, so `wow-bi` remains an implementation dependency.

**Tech Stack:** Kotlin 2.4/JVM 17, Gradle 9.6.1, JUnit Jupiter, FluentAssert, Spring Boot configuration properties, Spring WebFlux, VitePress.

## Global Constraints

- Preserve `ScriptEngine.generate(Set<NamedAggregate>, String, String): String` and all existing `ScriptTemplateEngine.render*` signatures.
- Preserve `GenerateBIScriptHandlerFunction(String, String)`, `GenerateBIScriptHandlerFunctionFactory()` and `GenerateBIScriptHandlerFunctionFactory(String, String)`.
- Preserve `GET /wow/bi/script -> 200 application/sql` with a string body.
- Keep `wow-webflux -> wow-bi` as `implementation`; no `BiScriptOptions` may appear in WebFlux public/protected ABI.
- Keep legacy expansion builder/column types available in 8.x, but remove them from the new main generation path.
- Every behavior change follows RED -> GREEN -> REFACTOR and ends with the narrowest relevant test.
- Do not add ClickHouse client, SQL parser, or Testcontainers dependencies.

---

### Task 1: Lock Legacy Contracts

**Files:**
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/ScriptEngineTest.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/StateExpansionScriptGeneratorTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/bi/GenerateBIScriptHandlerFunctionTest.kt`

**Interfaces:**
- Consumes: current legacy generators and route constructors.
- Produces: characterization tests used as the migration safety net.

- [ ] **Step 1: Strengthen the existing script and route assertions**

Assert complete section markers, default/custom Kafka text, `application/sql`, and response body content instead of only non-null/status.

```kotlin
syncScript.assert().contains("-- global --")
syncScript.assert().contains("ENGINE = Kafka('kafkaBootstrapServers'")
syncScript.assert().contains("'topicPrefix")
```

- [ ] **Step 2: Characterize the legacy expansion API**

```kotlin
@Test
fun `should keep the legacy expansion constructor`() {
    val constructor = StateExpansionScriptGenerator::class.java.getConstructor(
        Column::class.java,
        SqlBuilder::class.java,
    )
    constructor.assert().isNotNull()
}
```

- [ ] **Step 3: Run the characterization tests**

Run:

```bash
./gradlew :wow-bi:test --tests 'me.ahoo.wow.bi.ScriptEngineTest' \
  --tests 'me.ahoo.wow.bi.expansion.StateExpansionMetadataVisitorTest'
./gradlew :wow-webflux:test \
  --tests 'me.ahoo.wow.webflux.route.bi.GenerateBIScriptHandlerFunctionTest'
```

Expected: PASS on the current implementation.

- [ ] **Step 4: Commit**

```bash
git add wow-bi/src/test wow-webflux/src/test
git commit -m "test(bi): lock legacy script generation contracts"
```

---

### Task 2: Add Options, Results, Diagnostics, And SQL Syntax Primitives

**Files:**
- Create: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/BiScriptOptions.kt`
- Create: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/BiScriptResult.kt`
- Create: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/renderer/ClickHouseSqlSyntax.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/SqlTypeMapping.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/column/MetadataColumn.kt`
- Create: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/BiScriptOptionsTest.kt`
- Create: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/SqlTypeMappingTest.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/StateExpansionScriptGeneratorTest.kt`
- Create: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/renderer/ClickHouseSqlSyntaxTest.kt`

**Interfaces:**
- Produces: `BiScriptOptions.validate()`, `BiScriptResult`, `BiScriptDiagnostic`, `BiScriptDiagnosticCode`, `ClickHouseSqlSyntax`.

- [ ] **Step 1: Add failing tests for options and syntax**

```kotlin
BiScriptOptions(database = " ").runCatching { validate() }.isFailure.assert().isTrue()
ClickHouseSqlSyntax.stringLiteral("a\\b'c").assert().isEqualTo("'a\\\\b''c'")
ClickHouseSqlSyntax.quoteIdentifier("db\"x").assert().isEqualTo("\"db\\\"x\"")
SqlTypeMapping[Byte::class.java].assert().isEqualTo("Int8")
SqlTypeMapping[Short::class.java].assert().isEqualTo("Int16")
SqlTypeMapping[Char::class.java].assert().isEqualTo("String")

val sql = aggregateMetadata<BIAggregate, BIAggregateState>().toScriptGenerator().toString()
sql.windowed("space_id AS __space_id".length)
    .count { it == "space_id AS __space_id" }
    .assert().isEqualTo(6)
```

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :wow-bi:test --tests '*BiScriptOptionsTest' \
  --tests '*ClickHouseSqlSyntaxTest' \
  --tests '*SqlTypeMappingTest' \
  --tests '*StateExpansionMetadataVisitorTest.should include space metadata in every expansion view'
```

Expected: compile failure for new types plus assertion failures for old mappings/metadata.

- [ ] **Step 3: Implement the domain models**

Use the exact public shape from the design, including stable diagnostic codes:

```kotlin
enum class BiScriptDiagnosticCode {
    OBJECT_MAP_FALLBACK,
    UNSUPPORTED_TYPE_FALLBACK,
    MAX_DEPTH_REACHED,
}

data class BiScriptDiagnostic(
    val code: BiScriptDiagnosticCode,
    val severity: Severity,
    val aggregate: String,
    val path: String,
    val message: String,
)
```

Validation rejects blank required values, control characters, and `maxExpansionDepth < 1`.

- [ ] **Step 4: Implement SQL quoting and correctness mappings**

```kotlin
private fun requireSafe(value: String): String {
    require(value.none { it == '\u0000' || it.isISOControl() }) {
        "SQL token must not contain control characters."
    }
    return value
}

fun quoteIdentifier(value: String): String =
    "\"${requireSafe(value).replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun stringLiteral(value: String): String =
    "'${requireSafe(value).replace("\\", "\\\\").replace("'", "''")}'"
```

Set Byte/Short/Char to `Int8`/`Int16`/`String`; add `SPACE_ID_COLUMN`; change metadata version to `UInt32`.

- [ ] **Step 5: Run GREEN**

Run:

```bash
./gradlew :wow-bi:test --tests '*BiScriptOptionsTest' \
  --tests '*ClickHouseSqlSyntaxTest' \
  --tests '*SqlTypeMappingTest' \
  --tests '*StateExpansionMetadataVisitorTest'
```

Expected: PASS after updating the legacy expansion golden for the three type changes and `__space_id`.

- [ ] **Step 6: Commit**

```bash
git add wow-bi/src/main wow-bi/src/test
git commit -m "feat(bi): add script options and SQL primitives"
```

---

### Task 3: Build A Two-Phase State Expansion Planner

**Files:**
- Create: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/BiTableNaming.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/TableNaming.kt`
- Create: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/plan/ColumnPlan.kt`
- Create: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/plan/ExpansionViewPlan.kt`
- Create: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/plan/PropertyFilter.kt`
- Create: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/plan/StateExpansionPlanner.kt`
- Create: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/BiTableNamingTest.kt`
- Create: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/plan/StateExpansionPlannerTest.kt`
- Modify: `wow-bi/src/test/resources/META-INF/wow-metadata.json`

**Interfaces:**
- Produces: `StateExpansionPlanner.plan(NamedAggregate): StateExpansionPlan`.
- Produces structural `ColumnExtraction` and `ColumnPlacement`; no SQL string is built by the planner.

- [ ] **Step 1: Write planner RED tests**

Cover:

```kotlin
plan.views.first().columns.map { it.targetName }.assert().contains(
    "item__id", "nested__child__id"
)
plan.diagnostics.single().code.assert().isEqualTo(BiScriptDiagnosticCode.OBJECT_MAP_FALLBACK)
```

Add a fixture where one nested sibling owns a collection and a later nested sibling adds scalar columns; assert the child view inherits both siblings' final columns.

- [ ] **Step 2: Run RED**

```bash
./gradlew :wow-bi:test --tests '*StateExpansionPlannerTest' --tests '*BiTableNamingTest'
```

Expected: compile failure because planner/naming types are absent.

- [ ] **Step 3: Define structural plan types**

```kotlin
sealed interface ColumnExtraction {
    data class Source(val name: String) : ColumnExtraction
    data class JsonValue(val source: String, val property: String) : ColumnExtraction
    data class JsonString(val source: String, val property: String) : ColumnExtraction
    data class JsonArray(val source: String, val property: String) : ColumnExtraction
    data class ArrayJoin(val source: String, val property: String) : ColumnExtraction
}

enum class ColumnPlacement { WITH, SELECT }
```

- [ ] **Step 4: Implement two-phase planning**

For each view:

1. recursively collect all same-table nested columns into one mutable column list;
2. collect object-collection child requests without rendering them;
3. freeze the final inherited columns;
4. build child views from that final snapshot.

Sort properties by `name`. Use stable aggregate id `"${namedAggregate.contextName}.${namedAggregate.aggregateName}"`.

- [ ] **Step 5: Implement truncation and fallback semantics**

- root is not counted in `maxExpansionDepth`;
- truncated nested objects become SELECT `String` raw JSON columns;
- object arrays remain `Array(String)` and receive a depth warning when not expanded;
- object map diagnostics include the actual value type;
- strict strategies throw messages containing aggregate, path, and type.

- [ ] **Step 6: Run GREEN and commit**

```bash
./gradlew :wow-bi:test --tests '*StateExpansionPlannerTest' --tests '*BiTableNamingTest'
git add wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion \
  wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion \
  wow-bi/src/test/resources/META-INF/wow-metadata.json
git commit -m "feat(bi): add deterministic state expansion planner"
```

---

### Task 4: Render Plans And Switch The Main Generator

**Files:**
- Create: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/renderer/ClickHouseScriptRenderer.kt`
- Create: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/BiScriptGenerator.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/ScriptEngine.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/ScriptTemplateEngine.kt`
- Keep compatible: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/StateExpansionScriptGenerator.kt`
- Create: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/renderer/ClickHouseScriptRendererTest.kt`
- Create: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/BiScriptGeneratorTest.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/ScriptEngineTest.kt`
- Create: `wow-bi/src/test/resources/expected_bi_script.sql`

**Interfaces:**
- Produces: `BiScriptGenerator.generate`, `ScriptEngine.generate(options)`, `ScriptEngine.generateResult`.
- Preserves: all legacy facade signatures and the old expansion constructor.

- [ ] **Step 1: Write renderer/generator RED tests**

Test full default SQL, all options, metadata types, identical output/diagnostics from reversed aggregate sets, empty set, and legacy blank Kafka/topic behavior.

```kotlin
val forward = BiScriptGenerator().generate(linkedSetOf(first, second))
val reverse = BiScriptGenerator().generate(linkedSetOf(second, first))
forward.assert().isEqualTo(reverse)
```

- [ ] **Step 2: Run RED**

```bash
./gradlew :wow-bi:test --tests '*ClickHouseScriptRendererTest' --tests '*BiScriptGeneratorTest'
```

- [ ] **Step 3: Implement renderer**

All identifiers and literals must go through `ClickHouseSqlSyntax`. Renderer maps structural `ColumnExtraction` variants to `JSONExtract*`/`arrayJoin`, owns the single metadata list, and never mutates a plan.

- [ ] **Step 4: Implement deterministic generator**

```kotlin
val aggregates = namedAggregates.sortedWith(
    compareBy<NamedAggregate> { it.contextName }.thenBy { it.aggregateName }
)
```

Plan once, reuse the same ordered plans for clear/render/diagnostics, and add a non-defaulted legacy result overload with both string parameters.

- [ ] **Step 5: Convert facades without breaking ABI**

- `ScriptEngine` delegates to `BiScriptGenerator`.
- `ScriptTemplateEngine` delegates each old method to renderer.
- Do not replace `StateExpansionScriptGenerator(Column, SqlBuilder)`; it remains the legacy compatibility path.
- New main generator must have zero references to `SqlBuilder` or `expansion.column`.

- [ ] **Step 6: Run GREEN and ABI-oriented reflection tests**

```bash
./gradlew :wow-bi:test
javap -classpath wow-bi/build/classes/kotlin/main \
  me.ahoo.wow.bi.expansion.StateExpansionScriptGenerator \
  me.ahoo.wow.bi.ScriptEngine
```

Expected: tests pass and the old expansion constructor plus ScriptEngine overload remain visible.

- [ ] **Step 7: Commit**

```bash
git add wow-bi
git commit -m "refactor(bi): generate ClickHouse SQL from expansion plans"
```

---

### Task 5: Add A WebFlux-Owned BI Route Boundary

**Files:**
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/global/BiScriptRouteOptions.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/global/GenerateBIScriptHandlerFunction.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/bi/GenerateBIScriptHandlerFunctionTest.kt`
- Keep: `wow-webflux/build.gradle.kts` BI dependency as `implementation`.

**Interfaces:**
- Produces: public `BiScriptRouteOptions` and route-local strategy enums.
- Preserves: old handler/factory string and no-arg constructors.

- [ ] **Step 1: Add route RED tests**

Cover default, old custom strings, blank legacy values, full route options, invalid strict options, diagnostics generation, content type and SQL body.

- [ ] **Step 2: Run RED**

```bash
./gradlew :wow-webflux:test --tests 'me.ahoo.wow.webflux.route.bi.GenerateBIScriptHandlerFunctionTest'
```

- [ ] **Step 3: Implement the WebFlux DTO and private mapper**

All route option fields are nullable. `null` means use the BI domain default. Map route enums exhaustively to BI enums inside a private function; `BiScriptOptions` must not appear in public signatures.

- [ ] **Step 4: Preserve constructors and log diagnostics**

Keep the original two-string primary constructors. Add route-options secondary constructors. Both paths call a result API and log each warning; response stays SQL only.

- [ ] **Step 5: Verify ABI boundary and commit**

```bash
./gradlew :wow-webflux:test --tests 'me.ahoo.wow.webflux.route.bi.GenerateBIScriptHandlerFunctionTest'
javap -classpath wow-webflux/build/classes/kotlin/main \
  me.ahoo.wow.webflux.route.global.GenerateBIScriptHandlerFunctionFactory
git add wow-webflux
git commit -m "feat(webflux): configure BI script routes without ABI leakage"
```

---

### Task 6: Bind Spring Boot BI Configuration

**Files:**
- Create: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/bi/BiScriptProperties.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/GlobalRouteModule.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfiguration.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt`

**Interfaces:**
- Produces: `@ConfigurationProperties("wow.bi.script") BiScriptProperties`.
- Preserves: `GlobalRouteModule(KafkaProperties?)` JVM constructor.

- [ ] **Step 1: Add binding and precedence RED tests**

Cover:

1. explicit BI values override Kafka;
2. null BI Kafka fields inherit `KafkaProperties`;
3. explicit values equal to BI defaults still override Kafka;
4. no Kafka uses BI defaults;
5. explicit blank value fails context startup;
6. all strategy enum values bind.

- [ ] **Step 2: Run RED**

```bash
./gradlew :wow-spring-boot-starter:test \
  --tests 'me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest'
```

- [ ] **Step 3: Implement properties and module wiring**

Kafka/topic properties are nullable overrides. `GlobalRouteModule(KafkaProperties?)` uses the legacy route factory; the new two-argument constructor creates `BiScriptRouteOptions`. Auto-configuration always uses the new constructor.

- [ ] **Step 4: Run GREEN and dependency proof**

```bash
./gradlew :wow-spring-boot-starter:test \
  --tests 'me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest'
./gradlew :wow-spring-boot-starter:dependencyInsight \
  --configuration webfluxSupportRuntimeClasspath --dependency wow-bi
```

Expected: tests pass; `wow-bi` is reachable only transitively through WebFlux runtime/implementation behavior and is not a starter public API type.

- [ ] **Step 5: Commit**

```bash
git add wow-spring-boot-starter
git commit -m "feat(starter): bind BI script route options"
```

---

### Task 7: Remove Redundant Dependency And Update Documentation

**Files:**
- Modify: `wow-openapi/build.gradle.kts`
- Modify: `documentation/docs/en/guide/bi.md`
- Modify: `documentation/docs/zh/guide/bi.md`
- Modify: `documentation/docs/en/guide/configuration.md`
- Modify: `documentation/docs/zh/guide/configuration.md`
- Modify: OpenAPI snapshots only if the verified contract tool regenerates formatting without semantic change.

**Interfaces:**
- Produces: documented Kotlin/options/config/diagnostic/migration contract.

- [ ] **Step 1: Remove unused `wow-openapi -> wow-bi` dependency**

Verify first with `rg` that `wow-openapi/src` has no BI imports, then delete only the dependency line.

- [ ] **Step 2: Document configuration and migration**

Document every `wow.bi.script.*` property, precedence rules, result diagnostics, `__space_id`, `Byte -> Int8`, `Short -> Int16`, `Char -> String`, and the need to recreate expansion views.

- [ ] **Step 3: Run docs/OpenAPI verification**

```bash
./gradlew :wow-openapi:test --tests 'me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest'
cd documentation && pnpm docs:build
```

- [ ] **Step 4: Commit**

```bash
git add wow-openapi/build.gradle.kts documentation/docs
git commit -m "docs(bi): document configurable script generation"
```

---

### Task 8: Final Review And Verification

**Files:**
- Modify only defects discovered by review.

**Interfaces:**
- Consumes all prior tasks.
- Produces the final reviewable commit stack and evidence.

- [ ] **Step 1: Run focused tests from clean outputs**

```bash
./gradlew :wow-bi:clean :wow-bi:test
./gradlew :wow-webflux:test \
  --tests 'me.ahoo.wow.webflux.route.bi.GenerateBIScriptHandlerFunctionTest'
./gradlew :wow-spring-boot-starter:test \
  --tests 'me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest'
./gradlew :wow-openapi:test \
  --tests 'me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest'
```

- [ ] **Step 2: Run module checks and static checks**

```bash
./gradlew :wow-bi:check :wow-webflux:check :wow-spring-boot-starter:check :wow-openapi:check detekt
```

- [ ] **Step 3: Run documentation build**

```bash
cd documentation && pnpm docs:build
```

- [ ] **Step 4: Audit compatibility and scope**

```bash
git diff e20832939 --check
git diff e20832939 --stat
rg -n 'me\.ahoo\.wow\.bi\.BiScriptOptions' wow-webflux/src/main wow-spring-boot-starter/src/main
```

Expected: no public WebFlux/starter signature leaks BI options; only private WebFlux mapper imports it.

- [ ] **Step 5: Request two-stage code review and fix findings**

Run spec-compliance review first, then correctness/maintainability review. Re-run affected tests after every fix.

- [ ] **Step 6: Record final repository state**

```bash
git status --short --branch
git log --oneline e20832939..HEAD
```

Do not push or open a PR without a separate user request.
