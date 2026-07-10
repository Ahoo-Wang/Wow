# Wow BI Clean Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to execute this plan
> task-by-task, with a specification review and a code-quality review after every task.

**Goal:** Remove every BI compatibility path, preserve nullable state without lossy ClickHouse coercion, and make the
single planner/renderer pipeline verifiable against a real ClickHouse server.

**Architecture:** `BiScriptGenerator` is the only public generation entry. Jackson defines the serialized property
surface, `JsonPropertyTypeResolver` restores Kotlin/Java nullability and generic arguments, `StateExpansionPlanner`
produces an immutable plan using structural `ClickHouseType`, and `ClickHouseScriptRenderer` is the only SQL serializer.
WebFlux receives `BiScriptOptions` directly; Spring keeps one nullable binding adapter and resolves it once.

**Tech Stack:** Kotlin/JVM 17, Jackson 3, Kotlin reflection, JUnit Jupiter, FluentAssert, Spring Boot 4,
Spring WebFlux, Gradle feature variants, Testcontainers 2.0.5, ClickHouse 24.8 LTS, VitePress.

**Design:** `docs/superpowers/specs/2026-07-10-wow-bi-clean-architecture-design.md`

## Global Constraints

- Breaking source/JVM/schema changes are intentional; do not add deprecated aliases or adapter constructors.
- Preserve `GET /wow/bi/script -> 200 application/sql`; Kotlin API cleanup must not change the HTTP contract.
- Every behavior/core change uses RED -> verify RED -> GREEN -> refactor -> focused verification.
- Test removals and visibility/dependency cleanup use compile checks plus explicit `rg`/`javap` gates.
- Never represent an unsupported value as `Map(String,String)` or another typed value that can lose JSON information.
- Kotlin nullability is exact; unannotated Java references are conservatively nullable.
- `test/check` stay Docker-free. `integrationTest` requires Docker and must not silently skip in CI.
- Implement production changes serially; use subagents only for read-only specification and code reviews.

---

## Task 1: Introduce A Structural ClickHouse Type Algebra

**Files:**

- Create: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/type/ClickHouseType.kt`
- Create: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/type/ClickHouseTypeMapping.kt`
- Create: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/type/ClickHouseTypeTest.kt`
- Create: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/type/ClickHouseTypeMappingTest.kt`

**Produces:** Internal immutable types and a closed JVM scalar mapping. This task does not migrate planner call sites yet.

- [ ] **Step 1: Add RED tests for legal SQL type rendering**

Cover exact rendering for primitives, decimal, nullable scalar, nullable array element, and nullable map value:

```kotlin
ClickHouseType.Nullable(ClickHouseType.Int32).toSql().assert().isEqualTo("Nullable(Int32)")
ClickHouseType.Array(ClickHouseType.Nullable(ClickHouseType.String)).toSql()
    .assert().isEqualTo("Array(Nullable(String))")
ClickHouseType.Map(ClickHouseType.String, ClickHouseType.Nullable(ClickHouseType.Int64)).toSql()
    .assert().isEqualTo("Map(String, Nullable(Int64))")
```

Assert every existing scalar mapping, including primitive and boxed Byte/Short/Boolean, enum, UUID and Java time values.
Use normal Kotlin type checking as the proof that `Nullable` cannot receive Array/Map and Map keys cannot be nullable;
do not write an impossible runtime test for a construction that the Kotlin compiler rejects. Runtime failure tests cover
value-level invariants such as invalid Decimal precision/scale.

- [ ] **Step 2: Run RED**

```bash
./gradlew :wow-bi:test --tests '*ClickHouseTypeTest' --tests '*ClickHouseTypeMappingTest'
```

Expected: test compilation fails because the new types do not exist.

- [ ] **Step 3: Implement the closed type algebra**

Use a sealed internal model. `Nullable` accepts only `Scalar`; `Map.key` accepts only a non-null scalar by type.
Validate decimal precision/scale during construction. Keep `toSql()` internal and deterministic.

`ClickHouseTypeMapping` owns an immutable `Map<Class<*>, ClickHouseType.Scalar>` and exposes:

```kotlin
internal fun Class<*>.isClickHouseScalar(): Boolean
internal fun Class<*>.toClickHouseScalar(): ClickHouseType.Scalar
```

No `MutableMap` delegation or public mutation hook is allowed.

- [ ] **Step 4: Run GREEN and module check**

```bash
./gradlew :wow-bi:test --tests '*ClickHouseTypeTest' --tests '*ClickHouseTypeMappingTest'
./gradlew :wow-bi:check
```

- [ ] **Step 5: Commit**

```bash
git add wow-bi/src/main/kotlin/me/ahoo/wow/bi/type wow-bi/src/test/kotlin/me/ahoo/wow/bi/type
git commit -m "refactor(bi): model ClickHouse types structurally"
```

---

## Task 2: Resolve The Actual JSON Property Type Without Losing Nullability

**Files:**

- Create: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/type/ResolvedType.kt`
- Create: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/type/JsonPropertyTypeResolver.kt`
- Create: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/type/JsonPropertyTypeResolverTest.kt`
- Create: `wow-bi/src/test/java/me/ahoo/wow/bi/expansion/type/JavaNullabilityFixture.java`

**Produces:** Jackson-aligned serialized property descriptors with recursive Kotlin/Java nullability.

- [ ] **Step 1: Add Kotlin RED fixtures**

Cover all of these in one focused resolver test class:

- `String?`
- `List<String?>?`
- `Map<String, Int?>?`
- `Child?` where `Child.name` is non-null
- `List<Child?>`
- `Base<T>` / `Derived : Base<String?>`
- `Box<List<String?>?>`
- inherited getter with `@get:JsonProperty("renamed")`

Assert serialized name, raw class, recursive argument nullability, origin and declaring member.

- [ ] **Step 2: Add Java RED fixtures**

Cover primitive, boxed/reference `@Nullable`, getter `@NotNull`, unannotated reference, direct generic type-use nullable,
`@JsonProperty` rename and conflicting field/getter annotations. Assert:

- primitive -> `NON_NULL`
- explicit nullable -> `NULLABLE`
- explicit non-null -> `NON_NULL`
- unannotated reference -> `UNKNOWN`
- conflict -> exception containing class and property

- [ ] **Step 3: Run RED**

```bash
./gradlew :wow-bi:test --tests '*JsonPropertyTypeResolverTest'
```

Expected: compile failure for the resolver API.

- [ ] **Step 4: Implement Jackson/member alignment**

Continue using `toBeanDescription().findProperties()` and `PropertyFilter` for the serialization surface.
Match Kotlin members by Jackson accessor `Method`/`Field` identity (`javaGetter`/`javaField`), never by serialized name.

Resolve Kotlin inherited generics by walking concrete `KType.supertypes`, substituting type parameters recursively, and
combining use-site nullability. Build argument `ResolvedType` values alongside Jackson `JavaType` arguments.

For Java, inspect declaration and type-use annotations on getter return, field and constructor parameter. Recognize
the repository-supported `Nullable`, `CheckForNull`, `NotNull`, `NonNull` names. Unknown or unresolvable references
remain `UNKNOWN`; do not guess non-null. Detect contradictory annotations before returning a descriptor.

- [ ] **Step 5: Run GREEN and regression tests**

```bash
./gradlew :wow-bi:test --tests '*JsonPropertyTypeResolverTest'
./gradlew :wow-bi:test --tests '*StateExpansionPlannerTest'
```

- [ ] **Step 6: Commit**

```bash
git add wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/type \
  wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/type \
  wow-bi/src/test/java/me/ahoo/wow/bi/expansion/type
git commit -m "feat(bi): resolve nullable JSON property types"
```

---

## Task 3: Make The Expansion Plan Typed And Lossless

**Files:**

- Modify: `wow-bi/build.gradle.kts`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/BiScriptGenerator.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/BiScriptOptions.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/BiScriptResult.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/plan/ColumnPlan.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/plan/ExpansionViewPlan.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/plan/StateExpansionPlanner.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/type/JsonPropertyTypeResolver.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/renderer/ClickHouseScriptRenderer.kt`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/type/ClickHouseType.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/BiScriptOptionsTest.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/BiScriptGeneratorTest.kt`
- Delete: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/ScriptEngineTest.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/plan/StateExpansionPlannerTest.kt`
- Create: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/plan/StateExpansionPlannerNullableTest.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/type/JsonPropertyTypeResolverTest.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/renderer/ClickHouseScriptRendererTest.kt`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/type/ClickHouseTypeTest.kt`

**Produces:** A planner whose plans cannot carry arbitrary SQL types and whose fallback is always lossless.

- [ ] **Step 1: Add planner nullable RED tests**

Create aggregate fixtures and assert structural types plus raw companions:

```text
String?                 -> Nullable(String) + __raw__name
List<String?>           -> Array(Nullable(String))
List<String>?           -> Array(String) + __raw__values
Map<String,Int?>        -> Map(String,Nullable(Int32))
Map<String,Int>?        -> Map(String,Int32) + __raw__values
Child?                  -> nullable descendant leaf + __raw__child
List<Child?>            -> child leaf nullable + child-view __raw__items
```

Add Java `AccountState` coverage: `id` stays non-null, unannotated `name` becomes nullable with a raw companion.
Add `testImplementation(project(":example-transfer-domain"))` for this real repository fixture; do not copy its semantics
into a Kotlin-only test double.

- [ ] **Step 2: Add fallback and collision RED tests**

- object map with string/number/bool/null/object/array values plans one whole-value raw String, never Map(String,String)
- unsupported collection and raw generic plan one whole-value `JSONExtractRaw`
- depth cutoff uses the same whole-value raw rule
- `UnsupportedTypeStrategy.FAIL` includes aggregate/path/type in the exception
- `RAW_JSON` emits `RAW_JSON_FALLBACK` with `sourceType` and `decision`
- duplicate target names, `__raw__*` property collision and metadata alias collision fail before rendering

- [ ] **Step 3: Run RED**

```bash
./gradlew :wow-bi:test --tests '*StateExpansionPlannerNullableTest' \
  --tests '*StateExpansionPlannerTest' --tests '*ClickHouseScriptRendererTest' \
  --tests '*BiScriptOptionsTest'
```

Expected: compile failures for structural `ColumnPlan.type`, new enum/diagnostic shape and resolver integration.

- [ ] **Step 4: Simplify options and diagnostics**

- Validate `BiScriptOptions` in `init`; remove `validate()`.
- Replace `STRING_WITH_DIAGNOSTIC` with `RAW_JSON`; default to `RAW_JSON`.
- Delete `ObjectMapStrategy`.
- Replace object/unsupported diagnostic split with `RAW_JSON_FALLBACK`; retain `MAX_DEPTH_REACHED`.
- Remove severity; add stable `sourceType` and `BiScriptMappingDecision`.
- Keep all returned lists Java-unmodifiable and deterministically ordered.

- [ ] **Step 5: Migrate planner and renderer**

- `PlanningNode` carries `ResolvedType`, not only `JavaType`.
- Recursive property discovery calls `JsonPropertyTypeResolver.resolve(parentResolvedType)`, seeding the raw class type
  parameters from `ResolvedType.arguments`; add a nested generic object test proving `Box<T>.value` keeps the concrete
  argument nullability after planner recursion.
- `ColumnPlan.sqlType: String` becomes `ColumnPlan.type: ClickHouseType`.
- Add a validated `ClickHouseType.DateTime(timezone)` scalar so source metadata columns retain their real structural type;
  do not assign dummy types merely because `Source` extraction does not render a JSON type argument.
- Effective leaf nullable = own nullable/unknown OR nullable object/element ancestor.
- Every declared nullable/unknown property emits a SELECT raw companion named `__raw__<targetName>`.
- A descendant made nullable only by its ancestor does not duplicate a leaf raw; the ancestor companion is authoritative.
- Raw companion/fallback use `JSONExtractRaw`; nullable object element companion projects the arrayJoin source raw value.
- Run collision validation after all same-view siblings and metadata aliases are known, before freezing the view.
- Renderer calls `ClickHouseType.toSql()` and owns every SQL spelling.
- Update `BiScriptGenerator` for init-validated options and `RAW_JSON`; retain its existing internal legacy construction
  hook only until Task 4 deletes `ScriptEngine`. Do not add a new compatibility path.
- Keep the old `SqlTypeMapping` only because the legacy expansion graph still compiles against it; the new planner must
  have zero references. Task 4 deletes that complete graph and the mapping atomically.

- [ ] **Step 6: Run GREEN and fresh BI tests**

```bash
./gradlew :wow-bi:clean :wow-bi:test --rerun-tasks
```

- [ ] **Step 7: Verify ClickHouse expressions against the read-only connected server**

Use the available ClickHouse connector for `SELECT` only to prove:

- `Array(Nullable(Int32))`
- `Map(String,Nullable(Int32))`
- scalar `Nullable(T)` extraction
- typed composite null/empty/missing collapse and raw companion distinction

Record the returned ClickHouse version and results as exploratory evidence; do not issue DDL/DML on this connection and
do not use its potentially newer version as the release acceptance baseline. The pinned 24.8 container in Task 7 is the
normative semantic gate.

- [ ] **Step 8: Commit**

```bash
git add wow-bi/build.gradle.kts wow-bi/src/main wow-bi/src/test
git commit -m "feat(bi): preserve nullable state projections"
```

---

## Task 4: Delete The Legacy Generation Graph And Shrink The ABI

**Files:**

- Modify: `wow-bi/build.gradle.kts`
- Modify: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/BiScriptGenerator.kt`
- Delete: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/ScriptEngine.kt`
- Delete: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/ScriptTemplateEngine.kt`
- Delete: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/StateExpansionScriptGenerator.kt`
- Delete: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/SqlBuilder.kt`
- Delete: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/SqlTypeMapping.kt`
- Delete: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/TableNaming.kt`
- Delete: `wow-bi/src/main/kotlin/me/ahoo/wow/bi/expansion/column/`
- Delete: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/SqlTypeMappingTest.kt`
- Delete: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/expansion/StateExpansionScriptGeneratorTest.kt`
- Delete: `wow-bi/src/test/resources/expected_bi_aggregate_script.sql`
- Modify: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/BiScriptGeneratorTest.kt`
- Create: `wow-bi/src/test/kotlin/me/ahoo/wow/bi/BiPublicApiTest.kt`
- Modify: `wow-bi/src/test/resources/expected_bi_script.sql`

**Produces:** One public generator and no dormant compatibility implementation.

- [ ] **Step 1: Rewrite generator contract tests first**

Keep tests for stable ordering, options, immutable diagnostics, empty aggregate set and complete section structure.
Update the canonical script snapshot for typed nullable/raw changes. Remove tests whose only purpose is legacy ABI retention.

Add a Kotlin API test using `KClass.visibility`/Kotlin metadata that allows only:

- `BiScriptGenerator`
- `BiScriptOptions`
- `UnsupportedTypeStrategy`
- `BiScriptResult`
- diagnostic/code/decision types

- [ ] **Step 2: Run the rewritten tests before deletion**

```bash
./gradlew :wow-bi:test --tests '*BiScriptGeneratorTest' --tests '*BiPublicApiTest'
```

Expected: the Kotlin visibility assertion fails because implementation and legacy types are still public.

- [ ] **Step 3: Delete legacy code and simplify generator**

`BiScriptGenerator` has one constructor and one validated options path. Remove `legacy`, `validateOptions`, `plannerOptions`
and blank fallbacks. Move Kafka/topic defaults into `BiScriptOptions`.

Mark naming, planner/plan, property filter, resolver, types, renderer and SQL syntax `internal`.

Declare `api(project(":wow-api"))` explicitly and retain `implementation(project(":wow-core"))`.

- [ ] **Step 4: Run GREEN plus static deletion gates**

```bash
./gradlew :wow-bi:clean :wow-bi:check --rerun-tasks
rg -n "ScriptEngine|ScriptTemplateEngine|StateExpansionScriptGenerator|SqlBuilder|ObjectMapStrategy" \
  wow-bi/src/main wow-bi/src/test
```

Expected: Gradle PASS; `rg` returns no matches.

Inspect Kotlin metadata/visibility and the generated JVM signatures. Kotlin `internal` classes remain bytecode-public,
so `jar tf` is an inventory and `javap` is an accidental-constructor/dependency check, not a Java access-control claim:

```bash
MAIN_JAR=$(find wow-bi/build/libs -name 'wow-bi-*.jar' ! -name '*sources*' ! -name '*javadoc*' | head -1)
jar tf "$MAIN_JAR" | sort
javap -classpath wow-bi/build/classes/kotlin/main me.ahoo.wow.bi.BiScriptGenerator
```

- [ ] **Step 5: Commit**

```bash
git add wow-bi
git commit -m "refactor(bi): remove legacy script generation APIs"
```

---

## Task 5: Pass The Domain Options Directly Through WebFlux

**Files:**

- Modify: `wow-webflux/build.gradle.kts`
- Delete: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/global/BiScriptRouteOptions.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/global/GenerateBIScriptHandlerFunction.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/bi/GenerateBIScriptHandlerFunctionTest.kt`

**Produces:** One WebFlux construction path with no route DTO or duplicated enum.

- [ ] **Step 1: Rewrite WebFlux tests to the target API**

Construct handler/factory only with `BiScriptOptions`. Assert `200`, `application/sql`, configured database/Kafka/topic,
and diagnostic logging behavior. Delete legacy blank/default/String constructor and private DTO reflection tests.

- [ ] **Step 2: Run RED**

```bash
./gradlew :wow-webflux:test --tests '*GenerateBIScriptHandlerFunctionTest'
```

Expected: compile failure because handler/factory still expose the old constructors and route DTO.

- [ ] **Step 3: Implement the direct path**

- Handler stores `BiScriptOptions` and calls `BiScriptGenerator(options).generate(MetadataSearcher.localAggregates)`.
- Factory stores one non-null `BiScriptOptions` and creates the handler.
- Delete all nullable dual-state fields, mappings and String constructors.
- Change `implementation(project(":wow-bi"))` to `api(project(":wow-bi"))` because the public constructor exposes it.

- [ ] **Step 4: Run GREEN and ABI/dependency gates**

```bash
./gradlew :wow-webflux:clean :wow-webflux:check --rerun-tasks
./gradlew :wow-webflux:dependencies --configuration api
rg -n "BiScriptRouteOptions|BiScriptRouteUnsupportedTypeStrategy|BiScriptRouteObjectMapStrategy" wow-webflux
```

Expected: tests PASS and `rg` returns no matches.

- [ ] **Step 5: Commit**

```bash
git add wow-webflux
git commit -m "refactor(webflux): use BI script options directly"
```

---

## Task 6: Collapse Spring Binding To One Domain Conversion

**Files:**

- Modify: `wow-spring-boot-starter/build.gradle.kts`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/bi/BiScriptProperties.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/GlobalRouteModule.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfiguration.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt`

**Produces:** One nullable Spring binding boundary and one conversion to the validated domain options.

- [ ] **Step 1: Replace private DTO reflection tests with behavior RED tests**

Using `ApplicationContextRunner` and WebTestClient, prove:

- explicit `wow.bi.script.*` values reach returned SQL
- BI Kafka/topic override > `wow.kafka.*` > domain defaults
- `UnsupportedTypeStrategy.RAW_JSON` binds directly
- blank values and depth zero fail context startup at `BiScriptOptions` construction
- default configuration constructs exactly one `GlobalRouteModule` target path

Delete legacy constructor and enum mapping loops.

- [ ] **Step 2: Run RED**

```bash
./gradlew :wow-spring-boot-starter:test --tests '*WebFluxAutoConfigurationTest'
```

Expected: compile/assertion failures until the route DTO and duplicate enums are removed.

- [ ] **Step 3: Implement one conversion**

- `BiScriptProperties.unsupportedTypeStrategy` uses the BI enum directly.
- Delete the object-map property and both Starter enums.
- Implement one `toBiScriptOptions(kafkaProperties)` that resolves precedence then calls the domain constructor.
- Keep only `GlobalRouteModule(options: BiScriptOptions)` and mark implementation module classes internal where possible.
- Add `webfluxSupportApi(project(":wow-bi"))`; keep WebFlux as `webfluxSupportImplementation`.

- [ ] **Step 4: Run GREEN and feature-variant checks**

```bash
./gradlew :wow-spring-boot-starter:clean :wow-spring-boot-starter:check --rerun-tasks
./gradlew :wow-spring-boot-starter:dependencies --configuration webfluxSupportApi
./gradlew :wow-spring-boot-starter:dependencies --configuration webfluxSupportImplementation
./gradlew :wow-spring-boot-starter:outgoingVariants
```

Also verify main compilation/runtime classpaths resolve the feature implementation; do not reference nonexistent
`webfluxSupportRuntimeClasspath`.

- [ ] **Step 5: Commit**

```bash
git add wow-spring-boot-starter
git commit -m "refactor(starter): centralize BI script configuration"
```

---

## Task 7: Add A Non-Skippable ClickHouse Semantic Integration Gate

**Files:**

- Modify: `build.gradle.kts`
- Modify: `.github/workflows/integration-test.yml`
- Modify: `wow-bi/build.gradle.kts`
- Create: `wow-bi/src/integrationTest/kotlin/me/ahoo/wow/bi/ClickHouseExpansionIntegrationTest.kt`
- Create: `wow-bi/src/integrationTest/resources/clickhouse-test-cluster.xml`
- Create supporting integration fixtures only under `wow-bi/src/integrationTest/` if needed.

**Produces:** Real ClickHouse proof for the schema behavior changed by this refactor.

- [ ] **Step 1: Register the integration layer and dependencies**

- Add `project(":wow-bi")` to root `integrationTestProjects`.
- Add `integrationTestImplementation("org.testcontainers:testcontainers-clickhouse")`.
- Pin `clickhouse/clickhouse-server:24.8.14.39-alpine` (minimum supported LTS), never `latest`.
- Add `wow-bi/**` to integration-test workflow paths.
- Add a test resource `clickhouse-test-cluster.xml` defining one shard/one replica `test_cluster` at localhost, copy it
  into `/etc/clickhouse-server/config.d/`, and render with `cluster = "test_cluster"`. This makes expansion
  `ON CLUSTER` DDL executable without weakening production rendering or adding a test-only topology branch.

- [ ] **Step 2: Write the semantic test before any production adjustment**

Create a minimal source `state_last` table matching expansion metadata, render the expansion view from a dedicated
nullable fixture aggregate, execute the DDL, and insert rows for:

- normal/null/missing scalar
- normal/null/empty/missing array
- normal/null/empty/missing map
- nullable object and nullable object element
- mixed object-map raw fallback

Query typed values, `toTypeName` and raw companions. Assert missing `""`, explicit null `"null"`, empty `[]/{}` and
normal raw JSON remain distinct. Add an internal `renderExpansionStatements(plan): List<String>` boundary and execute
those explicit statements; keep `renderExpansion(plan)` as their deterministic join. Never split a full script on `;`
heuristically.

Do not annotate the test with `disabledWithoutDocker`. Docker absence must fail explicit integration execution.

- [ ] **Step 3: Compile integration tests locally**

```bash
./gradlew :wow-bi:integrationTestClasses
```

Expected: PASS without starting Docker.

- [ ] **Step 4: Run the container test where Docker is available**

```bash
./gradlew :wow-bi:integrationTest --rerun-tasks
```

Expected: PASS with actual query results. If the current developer host has no Docker daemon, record that exact
environment failure; the task is not release-verified until the CI integration job passes.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts .github/workflows/integration-test.yml wow-bi
git commit -m "test(bi): verify expansion SQL with ClickHouse"
```

---

## Task 8: Replace Stale BI And OpenAPI Documentation

**Files:**

- Modify: `documentation/docs/zh/guide/bi.md`
- Modify: `documentation/docs/en/guide/bi.md`
- Modify: `documentation/docs/zh/guide/configuration.md`
- Modify: `documentation/docs/en/guide/configuration.md`
- Modify: `documentation/docs/zh/guide/open-api.md`
- Modify: `documentation/docs/en/guide/open-api.md`

**Produces:** Current API/config/schema documentation with a complete breaking migration path.

- [ ] **Step 1: Remove copied stale SQL and old API/config names**

- Replace `ScriptEngine` examples with `BiScriptGenerator`.
- Remove template/expansion/route constructor compatibility claims.
- Remove `object-map-strategy`; document `unsupported-type-strategy: FAIL | RAW_JSON`.
- Replace duplicated full SQL in OpenAPI guides with the route contract and a link to BI schema docs.
- Keep only short canonical SQL fragments in BI docs; ensure body/metadata/partition descriptions match renderer output.

- [ ] **Step 2: Document schema migration and rollback**

Include:

- scalar `Nullable(T)` and nullable array/map element types
- `__raw__*` namespace and exact missing/null/empty semantics
- whole-value raw fallback
- which expansion views must be drop/recreated
- downstream query changes
- backup, rollout order and rollback recreation procedure
- removed Kotlin/JVM APIs with direct replacements

- [ ] **Step 3: Run terminology and build gates**

```bash
rg -n "ScriptEngine|ScriptTemplateEngine|StateExpansionScriptGenerator|BiScriptRouteOptions|ObjectMapStrategy|STRING_WITH_DIAGNOSTIC|STRING_VALUE_WITH_DIAGNOSTIC" \
  documentation wow-bi wow-webflux wow-spring-boot-starter
cd documentation && pnpm docs:build
```

Expected: only an explicitly labelled migration table may mention removed names; documentation build PASS.

- [ ] **Step 4: Commit**

```bash
git add documentation
git commit -m "docs(bi): document lossless schema migration"
```

---

## Task 9: Final Verification And Independent Review

**Files:** No intended production edits; fixes found by review must use a new RED test and a focused commit.

- [ ] **Step 1: Run fresh focused verification**

```bash
./gradlew :wow-bi:clean :wow-bi:test --rerun-tasks
./gradlew :wow-bi:integrationTestClasses
./gradlew :wow-bi:check :wow-webflux:check :wow-spring-boot-starter:check :wow-openapi:check detekt --rerun-tasks
cd documentation && pnpm docs:build
```

Run `:wow-bi:integrationTest --rerun-tasks` when Docker is available; otherwise report the daemon error verbatim and
require green CI integration evidence before release.

- [ ] **Step 2: Verify deletion, tree hygiene and ABI**

```bash
rg -n "ScriptEngine|ScriptTemplateEngine|StateExpansionScriptGenerator|BiScriptRouteOptions|ObjectMapStrategy" \
  wow-bi/src wow-webflux/src wow-spring-boot-starter/src
git diff HEAD^ --check
git status --short
```

Inspect the final jar/public constructors with `jar tf` and `javap`; confirm dependency variants with Gradle reports.

- [ ] **Step 3: Request two independent reviews**

Review 1: correctness/nullability/ClickHouse SQL, including effective ancestor nullability, raw distinction and collisions.

Review 2: architecture/API/dependencies/docs/tests, including absence of compatibility layers and duplicate config enums.

Both reviewers return strengths, findings with file/line evidence, and assessment. Fix every P0/P1; justify or fix P2.

- [ ] **Step 4: Re-run affected RED/GREEN tests and full gates after fixes**

No success claim may rely on pre-fix output.

- [ ] **Step 5: Produce final handoff**

Report:

- exact goal/scope achieved
- commits and breaking API/schema migration
- actual commands/results/test counts
- ClickHouse connector/container version evidence
- Docker/CI status if any integration execution remains external
- exact worktree path, detached/branch state, clean/dirty state, and push status
