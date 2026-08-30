# V9 Query Mask Quality Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 PR #3099 的聚合解析回归、Schema 刷新后旧 Masker 泄漏风险、继承注解遗漏与冲突，并清除 V8 构造兼容债务。

**Architecture:** 保持现有 Query Schema → Gateway → raw `ObjectNode` Mask 流程。聚合路径在共享 resolver 恢复 Element 相对语义；Gateway 以 Schema 对象身份复用已编译 Masker；注解继承在 `MergedAnnotation` 根因处修复。V9 公开类型直接采用新构造合同，不保留 V8 构造桥。

**Tech Stack:** Kotlin 2.4、Java 17、Reactor、Jackson 3、JUnit Jupiter、Gradle、VitePress。

**Spec:** `document/design/2026-08-30-query-mask-quality-fixes.md`

## Global Constraints

- 不引入 KSP、新依赖、Mask 注册表、兼容 adapter 或新的缓存接口。
- V9 不保留旧 Kotlin/Java 构造器 ABI；Java 测试必须直接使用包含 Mask 字段的新构造合同。
- 每个行为变更先写失败测试并确认失败原因，再写最小实现。
- 每项任务使用独立 worktree 和独立提交；评审通过后才能集成。
- 不修改 Backend wire tree、存储数据、HTTP 请求/响应形状或公开 Mask 策略参数。
- 不自动合并 PR。

---

### Task 1: 恢复 Element 聚合字段的相对解析

**Files:**
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolverTest.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt`

**Interfaces:**
- Consumes: `QueryFieldSchemaResolver.resolve(field, capability, logicalParent, physicalParent, fieldIsAbsolute)`。
- Produces: `resolveAggregationField` 在存在 `logicalParent` 时始终生成 `${logicalParent.value}.${field.value}`，随后继续执行 masked-field 聚合拒绝。

- [ ] **Step 1: 写入失败的 EventStream Element 路径测试**

在 `QuerySchemaResolverTest` 添加：

```kotlin
@Test
fun `aggregation fields should remain relative to the innermost element`() {
    val query = AggregationQuery(
        elements = listOf(AggregationElement(LogicalField("body"))),
        groupBy = listOf(AggregationGroup.Terms(LogicalField("body.data"), "data")),
        metrics = listOf(AggregationMetric.Count("count")),
    )
    val resolver = QuerySchemaResolver(
        schema(
            mapOf(
                LogicalField("body") to fieldSchema(
                    QueryCapability.ELEMENT_SCOPE to "event.body",
                ),
                LogicalField("body.body.data") to fieldSchema(
                    QueryCapability.AGGREGATE_TERMS to "event.body.body.data.keyword",
                ),
            ),
        ),
    )

    resolver.resolve(query).compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
}
```

- [ ] **Step 2: 确认测试因 `body.data` 被误判为绝对路径而失败**

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QuerySchemaResolverTest.aggregation fields should remain relative to the innermost element"
```

Expected: FAIL，实际 compatibility 为 `COMPATIBLE` 而不是 `EXACT`。

- [ ] **Step 3: 恢复最小相对路径实现并保留 Mask 拒绝逻辑**

将 `resolveAggregationField` 的 `field` 参数改为：

```kotlin
field = if (logicalParent == null) field else LogicalField("${logicalParent.value}.${field.value}"),
```

不要修改通用 `LogicalField.absoluteTo`，因为普通 filter 和嵌套 Element 路径仍依赖它的现有语义。

- [ ] **Step 4: 运行 resolver 测试与原失败的 Mongo 集成测试**

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QuerySchemaResolverTest"
./gradlew :wow-mongo:integrationTest --tests "me.ahoo.wow.mongo.query.event.MongoEventStreamQueryBackendTest"
```

Expected: 两条命令均 PASS；Mongo 测试不再报告 `COMPATIBLE` 被 `STRICT` 拒绝。

- [ ] **Step 5: 提交聚合路径修复**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolverTest.kt
git commit -m "fix(query): restore relative aggregation fields"
```

---

### Task 2: 让 Gateway Masker 跟随 Schema refresh

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/QueryGateway.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/DefaultSnapshotQueryGatewayTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/event/DefaultEventStreamQueryGatewayTest.kt`

**Interfaces:**
- Consumes: `QueryModelSchemaProvider.schema(): Mono<QueryModelSchema>` 返回当前已发布 Schema。
- Produces: 每次结果查询读取当前 Schema；相同 Schema 对象复用 `Optional<SchemaMasker>`，新 Schema 对象重新编译；无 masked 字段时不为结果 publisher 添加 `map`。

- [ ] **Step 1: 写入 Snapshot refresh 与 Mask 执行错误测试**

在 `DefaultSnapshotQueryGatewayTest` 添加三个测试。第一个用 `AtomicReference<QueryModelSchema>` 让同一 Gateway 先读取 `unmaskedSchema()`，再读取 `maskedSchema()`，断言第一次返回 `state-value`、第二次返回星号，并断言 `schemaCalls == 2`。第二个把同一字段从 `@Mask` 规则刷新为 `@KeepMask(prefix = 2, suffix = 2)` 规则，断言结果从 `***********` 变为 `st*******ue`。第三个构造 `CompiledMask { throw failure }` 的 `MaskRule`，调用 `dynamicSingle`，断言 publisher 返回同一个 `failure` 且 `ErrorHandler` 收到同一实例。

核心断言：

```kotlin
val current = AtomicReference(unmaskedSchema())
val backend = SchemaSnapshotBackend { Mono.just(current.get()) }
val gateway = gateway(backend)

gateway.dynamicSingle(singleQuery { }).block()!!.stateValue().assert().isEqualTo("state-value")
current.set(maskedSchema())
gateway.dynamicSingle(singleQuery { }).block()!!.stateValue().assert().isEqualTo("***********")
backend.schemaCalls.get().assert().isEqualTo(2)
```

规则参数刷新测试使用：

```kotlin
val current = AtomicReference(maskedSchema())
val backend = SchemaSnapshotBackend { Mono.just(current.get()) }
val gateway = gateway(backend)

gateway.dynamicSingle(singleQuery { }).block()!!.stateValue().assert().isEqualTo("***********")
val annotation = Kept::value.javaField!!.getAnnotation(KeepMask::class.java)
val rule = MaskRule(KeepMaskStrategy::class, annotation, KeepMaskStrategy.compile(annotation))
current.set(
    QueryModelSchema(
        QueryModel.SNAPSHOT,
        emptySet(),
        mapOf(LogicalField("state.value") to fieldSchema(rule)),
    ),
)
gateway.dynamicSingle(singleQuery { }).block()!!.stateValue().assert().isEqualTo("st*******ue")
```

并添加 `private data class Kept(@field:KeepMask(prefix = 2, suffix = 2) val value: String)`。

```kotlin
val failure = IllegalStateException("mask failed")
val observed = CopyOnWriteArrayList<Throwable>()
val annotation = Masked::value.javaField!!.getAnnotation(Mask::class.java)
val schema = QueryModelSchema(
    QueryModel.SNAPSHOT,
    emptySet(),
    mapOf(
        LogicalField("state.value") to fieldSchema(
            MaskRule(FullMaskStrategy::class, annotation, CompiledMask { throw failure }),
        ),
    ),
)

StepVerifier.create(
    gateway(
        SchemaSnapshotBackend(Mono.just(schema)),
        errorHandler = ErrorHandler { _, error -> observed += error; Mono.empty() },
    ).dynamicSingle(singleQuery { }),
).expectErrorMatches { it === failure }.verify()
observed.assert().containsExactly(failure)
```

- [ ] **Step 2: 写入 EventStream Schema body-type refresh 测试**

把 `SchemaEventBackend.modelSchema` 改为 `() -> QueryModelSchema`，并保留接收固定 Schema 的 secondary constructor。添加测试：

```kotlin
@Test
fun `event gateway should refresh masker when body type schema changes`() {
    val eventStream = generateEventStream(
        MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId()),
        eventCount = 1,
        createdEventSupplier = { MockAggregateCreated("secret") },
    )
    val currentNode = AtomicReference(eventStream.toJsonNode<ObjectNode>())
    val oldType = currentNode.get().path("body").path(0).path("bodyType").stringValue()
    val currentSchema = AtomicReference(eventSchema(oldType))
    val backend = SchemaEventBackend(
        nodeSupplier = { currentNode.get().deepCopy<ObjectNode>() },
        modelSchema = { currentSchema.get() },
    )
    val gateway = DefaultEventStreamQueryGateway(
        MOCK_AGGREGATE_METADATA,
        backend,
        errorHandler = ErrorHandler { _, error -> Mono.error(error) },
    )

    gateway.dynamicSingle(singleQuery { }).block()!!
    val newType = "$oldType-refreshed"
    currentNode.set(currentNode.get().deepCopy<ObjectNode>().also { node ->
        (node.path("body").path(0) as ObjectNode).put("bodyType", newType)
    })
    currentSchema.set(eventSchema(newType))

    gateway.dynamicSingle(singleQuery { }).block()!!
        .path("body").path(0).path("body").path("data").stringValue()
        .assert().isEqualTo("******")
}
```

旧实现会继续使用首次 Masker，并以未知 `bodyType` 失败。

- [ ] **Step 3: 确认 refresh 新测试按预期失败**

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGatewayTest" --tests "me.ahoo.wow.query.event.DefaultEventStreamQueryGatewayTest"
```

Expected: refresh 用例失败；Mask 执行错误用例若已满足现有行为则允许先 PASS，但必须保留为回归合同。

- [ ] **Step 4: 用单个原子引用替换永久 Mono 缓存**

在 `QueryGateway.kt` 引入 `AtomicReference`，保留 `Optional` 以表达无 Mask 快速路径：

```kotlin
private val masker = (backend as? QueryModelSchemaProvider)?.let { provider ->
    val cached = AtomicReference<Pair<QueryModelSchema, Optional<SchemaMasker>>?>()
    Mono.defer { provider.schema() }.map { schema ->
        cached.get()?.takeIf { it.first === schema }?.second ?: Optional
            .ofNullable(SchemaMasker.create(schema))
            .also { cached.set(schema to it) }
    }
}
```

不要增加失效接口或订阅 refresh 事件；并发旧请求覆盖缓存只会导致下一次按对象身份重新编译，不会复用到新 Schema。

- [ ] **Step 5: 更新已有 schema call 断言并验证快速路径**

将永久缓存假设改为“每次结果查询读取 provider 当前 Schema”。保留无 Mask 结果内容不变、Schema 错误先于 Backend 订阅、`count` 不读取 Schema 的断言。不得给空 Masker 路径增加结果 `map`。

- [ ] **Step 6: 运行 Gateway 与 Masker 测试**

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGatewayTest" --tests "me.ahoo.wow.query.event.DefaultEventStreamQueryGatewayTest" --tests "me.ahoo.wow.query.mask.SchemaMaskerTest"
```

Expected: PASS。

- [ ] **Step 7: 提交 refresh 修复**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/QueryGateway.kt wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/DefaultSnapshotQueryGatewayTest.kt wow-query/src/test/kotlin/me/ahoo/wow/query/event/DefaultEventStreamQueryGatewayTest.kt
git commit -m "fix(query): refresh schema maskers"
```

---

### Task 3: 修复继承注解合并和 Java getter

**Files:**
- Modify: `wow-core/src/main/kotlin/me/ahoo/wow/infra/reflection/MergedAnnotation.kt`
- Modify: `wow-core/src/test/kotlin/me/ahoo/wow/infra/reflection/MergedAnnotationTest.kt`
- Modify: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/query/JsonQuerySchemaFixtures.kt`
- Create: `wow-schema/src/test/java/me/ahoo/wow/schema/query/JavaGetterMaskedState.java`
- Modify: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/query/JsonQuerySchemaSourceTest.kt`

**Interfaces:**
- Consumes: `MergedAnnotation.mergedAnnotations` 的当前本地覆盖合同。
- Produces: 本地同类注解覆盖全部父级；父接口相同注解按 equality 去重；同类不同参数全部保留；Java 方法按 JVM getter 签名继承 Kotlin property getter 注解。

- [ ] **Step 1: 写入通用注解合并失败测试**

在 `MergedAnnotationTest` 增加三组 fixture 与断言：

```kotlin
private interface LeftMergedState {
    @get:MergedMarker("left")
    val token: String
}

private interface RightMergedState {
    @get:MergedMarker("right")
    val token: String
}

private data class SiblingMergedState(override val token: String) : LeftMergedState, RightMergedState

private data class LocallyMergedState(
    @get:MergedMarker("local") override val token: String,
) : LeftMergedState, RightMergedState
```

断言 sibling 得到 `left`、`right` 两个值，反转接口声明顺序仍得到同一个集合；local 只得到 `local`。再增加两个父接口都使用 `MergedMarker("same")` 的 fixture，断言结果只有一个。

- [ ] **Step 2: 写入 Java getter 到 Query Schema 的失败测试**

创建 Java fixture：

```java
package me.ahoo.wow.schema.query;

public final class JavaGetterMaskedState implements GetterMaskedState {
    @Override
    public String getInheritedToken() {
        return "token";
    }
}
```

在 `JsonQuerySchemaFixtures.kt` 添加两个父接口对同一属性声明不同 `@KeepMask` 参数的状态类型；在 `JsonQuerySchemaSourceTest` 断言：

```kotlin
internal interface PrefixGetterMaskedState {
    @get:KeepMask(prefix = 1)
    val inheritedToken: String
}

internal interface SuffixGetterMaskedState {
    @get:KeepMask(suffix = 1)
    val inheritedToken: String
}

internal data class ConflictingInheritedGetterMaskedState(
    override val inheritedToken: String,
) : PrefixGetterMaskedState, SuffixGetterMaskedState

internal data class ReversedConflictingInheritedGetterMaskedState(
    override val inheritedToken: String,
) : SuffixGetterMaskedState, PrefixGetterMaskedState
```

在 `JsonQuerySchemaSourceTest` 断言：

```kotlin
load(JavaGetterMaskedState::class.java)
    .field("state.inheritedToken")
    .requiredMaskRule()
    .strategyType.assert().isEqualTo(FullMaskStrategy::class)
```

以及 sibling 参数冲突抛出 `QuerySchemaConflictException`，反转接口顺序仍抛出同类异常。

- [ ] **Step 3: 确认 core 与 schema 新测试失败**

Run:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.infra.reflection.MergedAnnotationTest"
./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.query.JsonQuerySchemaSourceTest"
```

Expected: sibling 不同参数被丢弃，Java getter 未取得 `@Mask`。

- [ ] **Step 4: 在共享根因处实现本地优先、父级按 equality 合并**

在 `MergedAnnotation` 增加私有 helper：

```kotlin
private fun mergeInheritedAnnotations(
    local: Set<Annotation>,
    inherited: Sequence<Annotation>,
): Set<Annotation> {
    val localTypes = local.mapTo(hashSetOf()) { it.annotationClass }
    return linkedSetOf<Annotation>().apply {
        addAll(local)
        inherited.filterNot { it.annotationClass in localTypes }.forEach(::add)
    }
}
```

class、property、function 三条路径都调用它。`LinkedHashSet` 自然去重完全相同的父注解，同时保留同类不同参数；`localTypes` 保持本地覆盖父级。

- [ ] **Step 5: 用 JVM 签名纳入父 property getter**

导入 `declaredMemberProperties` 与 `javaMethod`。函数父成员候选包括 `declaredFunctions` 和 `declaredMemberProperties.map { it.getter }`；`sameSignature` 的名称比较使用：

```kotlin
private val KFunction<*>.jvmName: String
    get() = javaMethod?.name ?: name
```

参数数量和参数类型比较继续沿用现有实现，避免按名称错误合并重载。

- [ ] **Step 6: 运行 core、schema 和关联查询测试**

Run:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.infra.reflection.MergedAnnotationTest"
./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.query.JsonQuerySchemaSourceTest"
./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QuerySchemaMergerTest"
```

Expected: PASS。

- [ ] **Step 7: 提交继承注解修复**

```bash
git add wow-core/src/main/kotlin/me/ahoo/wow/infra/reflection/MergedAnnotation.kt wow-core/src/test/kotlin/me/ahoo/wow/infra/reflection/MergedAnnotationTest.kt wow-schema/src/test/kotlin/me/ahoo/wow/schema/query/JsonQuerySchemaFixtures.kt wow-schema/src/test/java/me/ahoo/wow/schema/query/JavaGetterMaskedState.java wow-schema/src/test/kotlin/me/ahoo/wow/schema/query/JsonQuerySchemaSourceTest.kt
git commit -m "fix(schema): merge inherited mask annotations"
```

---

### Task 4: 清理 V8 构造兼容并明确 Mask 扩展合同

**Files:**
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/mask/Masking.kt`
- Modify: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/mask/MaskingTest.kt`
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/schema/QuerySchemaMetadata.kt`
- Delete: `wow-api/src/test/java/me/ahoo/wow/api/query/schema/QuerySchemaMetadataJvmCompatibilityTest.java`
- Create: `wow-api/src/test/java/me/ahoo/wow/api/query/schema/QuerySchemaMetadataJavaTest.java`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaDeclaration.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchema.kt`
- Delete: `wow-query/src/test/java/me/ahoo/wow/query/schema/QuerySchemaJvmCompatibilityTest.java`
- Create: `wow-query/src/test/java/me/ahoo/wow/query/schema/QuerySchemaJavaTest.java`
- Modify: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/query/JsonQuerySchemaFixtures.kt`
- Modify: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/query/JsonQuerySchemaSourceTest.kt`

**Interfaces:**
- Produces: `QueryFieldSchemaMetadata`、`QueryFieldDeclaration`、`QueryFieldSchema`、`LogicalQueryFieldSchema` 只有 V9 构造合同；Java 显式传入 `masked` 或 `maskRule`。
- Produces: `MaskStrategy.compile` 只在 Schema 构建期执行；`CompiledMask.mask` 可被并发复用，必须线程安全、非阻塞且返回非 null。

- [ ] **Step 1: 把 Java 测试改为 V9 构造合同**

删除 `JvmCompatibility` 命名。新的 Java 测试显式传入最后参数，并把 nonsynthetic constructor arity 约束为 V9 合同：`QueryFieldSchemaMetadata={12}`、`QueryFieldDeclaration={10,0}`、`QueryFieldSchema={12}`、`LogicalQueryFieldSchema={10}`。测试构造内容：

```java
QueryFieldSchemaMetadata metadata = new QueryFieldSchemaMetadata(
    new LogicalField("state.name"),
    "Name",
    "Description",
    null,
    Set.of(new QueryValueType("STRING")),
    true,
    true,
    QueryCardinality.SINGLE,
    null,
    false,
    Set.of(new QueryCapability("PRESENCE")),
    true
);
assertTrue(metadata.getMasked());
assertEquals(Set.of(12), constructorArities(QueryFieldSchemaMetadata.class));
```

```java
QueryFieldDeclaration declaration = new QueryFieldDeclaration(
    DeclarationValue.Unset.INSTANCE,
    DeclarationValue.Unset.INSTANCE,
    DeclarationValue.Unset.INSTANCE,
    DeclarationValue.Unset.INSTANCE,
    DeclarationValue.Unset.INSTANCE,
    DeclarationValue.Unset.INSTANCE,
    DeclarationValue.Unset.INSTANCE,
    DeclarationValue.Unset.INSTANCE,
    DeclarationValue.Unset.INSTANCE,
    DeclarationValue.Unset.INSTANCE
);
QueryFieldSchema schema = new QueryFieldSchema(
    "Name", "Description", null, Set.of(new QueryValueType("STRING")), true, true,
    QueryCardinality.SINGLE, null, false, Map.of(), null, null
);
LogicalQueryFieldSchema logical = new LogicalQueryFieldSchema(
    "Name", "Description", null, Set.of(new QueryValueType("STRING")), true, true,
    QueryCardinality.SINGLE, null, false, null
);
assertEquals(Set.of(10, 0), constructorArities(QueryFieldDeclaration.class));
assertEquals(Set.of(12), constructorArities(QueryFieldSchema.class));
assertEquals(Set.of(10), constructorArities(LogicalQueryFieldSchema.class));
```

逐项断言字段值，不再断言旧构造器存在。

- [ ] **Step 2: 先运行 Java 测试确认当前兼容 overload 仍存在**

Run:

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.schema.QuerySchemaMetadataJavaTest"
./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QuerySchemaJavaTest"
```

Expected: FAIL，因为旧 9/11 参数 overload 仍存在。

- [ ] **Step 3: 删除只服务 V8 的构造桥**

删除：

- `QueryFieldSchemaMetadata` 上的 `@JvmOverloads`；
- `QueryFieldDeclaration` 的 9 参数 secondary constructor；
- `QueryFieldSchema` 的 11 参数 secondary constructor；
- `LogicalQueryFieldSchema` 上的 `@JvmOverloads`。

不要新增 factory、builder 或 deprecated overload。

- [ ] **Step 4: 增加 Unicode KeepMask 与 Strategy 类型错误测试**

在 `MaskingTest` 增加：

```kotlin
mask.mask("A中😀BCD").assert().isEqualTo("A中**CD")
```

使用 `@KeepMask(prefix = 2, suffix = 2)`，断言按 Unicode code point 而不是 UTF-16 code unit 保留边缘。

在 schema fixture 定义错误绑定：

```kotlin
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Masking(FullMaskStrategy::class)
internal annotation class WrongStrategyMask

internal data class WrongStrategyMaskState(@field:WrongStrategyMask val secret: String)
```

在 `JsonQuerySchemaSourceTest` 断言抛出的 `QuerySchemaConflictException.message` 同时包含 `WrongStrategyMask` 与 `FullMaskStrategy`。

- [ ] **Step 5: 为公开 Mask SPI 添加最小 KDoc**

在 `Masking.kt` 为 `MaskStrategy` 与 `CompiledMask` 写明编译期、并发、非阻塞和非 null 合同。保留现有 `runMaskStrategyOperation` 包装逻辑；其消息已经包含 annotation 和 strategy 类型，不增加泛型反射验证器。

- [ ] **Step 6: 运行 API、query 与 schema 测试**

Run:

```bash
./gradlew :wow-api:check :wow-query:check :wow-schema:check
```

Expected: PASS；Java 新构造调用、Unicode 与错误诊断均通过。

- [ ] **Step 7: 提交 V9 API 清理**

```bash
git add wow-api/src/main/kotlin/me/ahoo/wow/api/query/mask/Masking.kt wow-api/src/test/kotlin/me/ahoo/wow/api/query/mask/MaskingTest.kt wow-api/src/main/kotlin/me/ahoo/wow/api/query/schema/QuerySchemaMetadata.kt wow-api/src/test/java/me/ahoo/wow/api/query/schema/QuerySchemaMetadataJvmCompatibilityTest.java wow-api/src/test/java/me/ahoo/wow/api/query/schema/QuerySchemaMetadataJavaTest.java wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaDeclaration.kt wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchema.kt wow-query/src/test/java/me/ahoo/wow/query/schema/QuerySchemaJvmCompatibilityTest.java wow-query/src/test/java/me/ahoo/wow/query/schema/QuerySchemaJavaTest.java wow-schema/src/test/kotlin/me/ahoo/wow/schema/query/JsonQuerySchemaFixtures.kt wow-schema/src/test/kotlin/me/ahoo/wow/schema/query/JsonQuerySchemaSourceTest.kt
git commit -m "refactor(query): remove v8 mask compatibility"
```

---

### Task 5: 对齐 Mask、fallback 与 V9 文档合同

**Files:**
- Modify: `documentation/docs/zh/guide/query/masking.md`
- Modify: `documentation/docs/en/guide/query/masking.md`
- Modify: `documentation/docs/zh/guide/query/query-model-schema.md`
- Modify: `documentation/docs/en/guide/query/query-model-schema.md`
- Modify: `documentation/docs/zh/guide/query/query-gateway.md`
- Modify: `documentation/docs/en/guide/query/query-gateway.md`
- Modify: `documentation/docs/zh/guide/query/v9-query-migration.md`
- Modify: `documentation/docs/en/guide/query/v9-query-migration.md`
- Modify: `skills/wow-develop/references/query-read-model.md`
- Modify: `skills/wow-migrate/references/migration-risk-map.md`
- Modify: `skills/wow-migrate/evals/behavior.jsonl`

**Interfaces:**
- Produces: 文档明确 Schema refresh 会让 Gateway 重新选择 Masker；根无 masked 字段仍不遍历结果；unavailable fallback 仅属于直接 resolver 路径，受管 Gateway 结果查询失败关闭。

- [ ] **Step 1: 更新中英文 Mask 缓存段落**

把“Gateway caches a successful masking decision”改为：Gateway 每次结果查询读取 Provider 当前 Schema，相同 Schema 实例复用 Masker，refresh 发布新实例后重新编译。根无 masked 字段时复用空决定，不遍历 JSON、不添加逐结果 `map`。

- [ ] **Step 2: 收窄中英文 unavailable fallback 描述**

在 `query-model-schema.md` 的 COMPATIBLE fallback 段落后明确：该回退只适用于直接 `QueryModelSchemaProvider.resolve(...)` 的请求解析。受管 Gateway 在返回数据前需要 Schema 执行 Mask，Schema 不可用时结果查询失败关闭且不会订阅 Backend；`count` 不读取 Mask Schema。

- [ ] **Step 3: 对齐 Gateway、V9 migration 与项目技能**

删除“永久缓存首次决定”的表述，写明 V9 构造器包含新增 Mask 字段且不保留 V8 JVM overload。`wow-develop` 与 `wow-migrate` 引用必须同时区分 managed Gateway 和 direct resolver/backend；更新 B16 eval 的预期行为，使后续迁移建议不能把 COMPATIBLE fallback 扩大到未脱敏响应。

- [ ] **Step 4: 检查中英文关键合同一一对应**

Run:

```bash
rg -n "refresh|刷新|fallback|回退|fast path|快速路径|V8|constructor|构造" documentation/docs/zh/guide/query documentation/docs/en/guide/query skills/wow-develop/references/query-read-model.md skills/wow-migrate/references/migration-risk-map.md skills/wow-migrate/evals/behavior.jsonl
```

Expected: 中英文都包含 refresh、managed Gateway fail-closed、direct resolver fallback 和 V9 无兼容 overload 四项合同。

- [ ] **Step 5: 构建文档站点**

Run:

```bash
cd documentation
pnpm install --frozen-lockfile
pnpm docs:build
```

Expected: PASS，无失效链接或 VitePress 构建错误。

- [ ] **Step 6: 提交文档合同修复**

```bash
git add documentation/docs/zh/guide/query/masking.md documentation/docs/en/guide/query/masking.md documentation/docs/zh/guide/query/query-model-schema.md documentation/docs/en/guide/query/query-model-schema.md documentation/docs/zh/guide/query/query-gateway.md documentation/docs/en/guide/query/query-gateway.md documentation/docs/zh/guide/query/v9-query-migration.md documentation/docs/en/guide/query/v9-query-migration.md skills/wow-develop/references/query-read-model.md skills/wow-migrate/references/migration-risk-map.md skills/wow-migrate/evals/behavior.jsonl
git commit -m "docs(query): clarify mask refresh boundaries"
```

---

### Task 6: 集成、复审与全量验证

**Files:**
- Integrate: Tasks 1-5 reviewed commits
- Verify: entire repository

**Interfaces:**
- Consumes: 五个独立、已通过规格和代码质量评审的提交。
- Produces: 可快进推送到 `agent/static-annotation-mask-v9-main` 的质量修复分支。

- [ ] **Step 1: 按 Task 1-5 顺序 cherry-pick 已评审提交**

每次 cherry-pick 后运行 `git diff --check`；发生冲突时只解决当前任务触及文件，不覆盖其他工作树内容。

- [ ] **Step 2: 获取并合并最新 main**

```bash
git fetch origin main agent/static-annotation-mask-v9-main
git merge --no-edit origin/main
```

Expected: 不重写远端 PR 历史；如有冲突，解决后重新运行下面全部验证。

- [ ] **Step 3: 运行窄模块和静态分析**

```bash
./gradlew :wow-api:check :wow-core:check :wow-query:check :wow-schema:check :wow-mongo:check :wow-elasticsearch:check detekt
```

Expected: PASS。

- [ ] **Step 4: 运行原失败测试和全部集成测试**

```bash
./gradlew :wow-mongo:integrationTest --tests "me.ahoo.wow.mongo.query.event.MongoEventStreamQueryBackendTest"
./gradlew allIntegrationTest --stacktrace
```

Expected: PASS，0 failures。

- [ ] **Step 5: 运行全仓构建与文档构建**

```bash
./gradlew build
cd documentation
pnpm install --frozen-lockfile
pnpm docs:build
```

Expected: 两条构建均 PASS。

- [ ] **Step 6: 执行最终代码质量复审**

评审范围为 `origin/agent/static-annotation-mask-v9-main..HEAD`，逐项核对：聚合路径、refresh 后不泄漏、继承冲突稳定失败、Java getter、无 V8 构造桥、文档一致性。Critical/Important finding 必须修复并重跑受影响验证。

- [ ] **Step 7: 快进更新现有 PR 分支**

```bash
git push origin HEAD:agent/static-annotation-mask-v9-main
```

Expected: fast-forward push；记录远端 SHA，保持 PR #3099 打开且不合并。
