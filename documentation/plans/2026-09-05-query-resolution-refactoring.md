# Query Resolution Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保持查询合同的前提下，消除动态字段反向解析的全字段扫描，以及投影、聚合解析中的临时兼容级别集合。

**Architecture:** 保留现有 Gateway → Schema → ResolvedQuery → Backend 边界。动态索引由每个 QueryFieldSchemaResolver 构造并只读使用；QuerySchemaResolver 在原有校验顺序中直接累积兼容级别。生产代码仅修改这两个现有文件。

**Tech Stack:** Kotlin、JVM 17、现有 JUnit/FluentAssert/Reactor 测试支持、现有 JMH 与 Gradle 构建流程。

**Spec:** [已确认设计](../designs/2026-09-05-query-resolution-refactoring-design.md)

## Global Constraints

- 基线生产提交：`4a64789b6`；设计提交：`91af473b8`；Wow 版本保持 `9.0.8`。
- 保持现有公开方法、参数、返回类型、构造合同与查询语义；不添加兼容桥或迁移层。
- Query JSON、Schema HTTP、OpenAPI/schema 生成合同、Cursor wire、存储布局和 Spring 配置均不变。
- 不引入 Planner、统一 Engine、第二棵物理 Query AST、全局查询缓存或新依赖。
- metrics 保持先 Any、再 Numeric 的两轮校验顺序；Binary 左右两侧均执行解析。
- 保留 Schema 快照、Context 和结果节点的订阅隔离，不增加阻塞运行时调用。
- 使用现有 Apache 2.0 文件头和 FluentAssert `.assert()`；不提交 JMH 原始结果或其他生成输出。
- 后续编译器、分页器及接入层重构不属于本计划。

## 文件职责

| 文件 | 责任 |
|---|---|
| `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryFieldSchemaResolver.kt` | 构造动态反向索引与最长祖先查找 |
| `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt` | 投影与聚合兼容级别直接汇总 |
| `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolverTest.kt` | 扩展现有解析合同、作用域及汇总回归 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/query/QuerySchemaScaleBenchmark.kt` | 新增不同 Schema 规模、构造成本和查询组件宽度的基准 |
| `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/query/QuerySchemaResolverBenchmark.kt` | 保留原有六项基准作为对照，不更改其测量逻辑 |
| 本计划与设计文档 | 记录实施状态、实测结果和后续阶段依据 |

## Task 1: 建立相同基准代码的优化前数据

**Files:** Create `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/query/QuerySchemaScaleBenchmark.kt`；Read `QuerySchemaResolverBenchmark.kt`。

**Interfaces:** 只使用现有 `QueryModelSchema.resolvePhysicalField(QueryField, QueryCapability)`、`resolve(ISingleQuery)`、`resolve(AggregationQuery)` 以及公开 Schema 构造器，不改生产接口。

- [x] **Step 1: 添加文件内共用的基准字段构造函数。** 使用明确的逻辑、已解析、物理路径，避免只测到逻辑字段的快速路径。

```kotlin
private fun benchmarkField(
    path: String,
    resolved: String = path,
    physical: String = resolved,
    dynamic: Boolean = false,
): QueryFieldSchema = QueryFieldSchema(
    title = null,
    description = null,
    enumValues = null,
    valueTypes = emptySet(),
    nullable = true,
    required = false,
    cardinality = QueryCardinality.SINGLE,
    semanticType = null,
    dynamicChildren = dynamic,
    bindings = setOf(
        QueryCapability.EXACT_MATCH,
        QueryCapability.PRESENCE,
        QueryCapability.AGGREGATE_TERMS,
        QueryCapability.AGGREGATE_NUMERIC,
    ).associateWith { QueryFieldBinding(QueryField(resolved), QueryField(physical), null) },
    rewriteMode = if (path == resolved) QueryRewriteMode.NONE else QueryRewriteMode.REQUIRED,
)
```

- [x] **Step 2: 添加 `QueryFieldResolutionScaleBenchmark`。** 使用现有基准的 `@State(Scope.Benchmark)`、AverageTime、NANOSECONDS、3 forks、5 次 200ms 预热、10 次 200ms 测量、1 thread 注解。声明以下参数和字段；在 `@Setup` 中准备数据。

```kotlin
@Param("static32", "static256", "static2048", "dynamic1", "dynamic16", "dynamic128", "none32", "none2048")
lateinit var shape: String
private lateinit var fields: Map<QueryField, QueryFieldSchema>
private lateinit var schema: QueryModelSchema
private lateinit var hit: QueryField
private val missing = QueryField("document.missing.code")

@Setup
fun setup() {
    val count = shape.filter(Char::isDigit).toInt()
    val staticCount = if (shape.startsWith("dynamic")) 32 else count
    val dynamicCount = when {
        shape.startsWith("none") -> 0
        shape.startsWith("dynamic") -> count
        else -> 1
    }
    fields = buildMap {
        repeat(staticCount) { index ->
            val path = "state.field$index"
            put(QueryField(path), benchmarkField(path))
        }
        repeat(dynamicCount) { index ->
            val suffix = if (index == 0) "dynamic" else "dynamic.branch$index"
            val path = "state.$suffix"
            put(QueryField(path), benchmarkField(path, "document.$suffix", "storage.$suffix", true))
        }
    }
    schema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), fields)
    val suffix = if (dynamicCount <= 1) "dynamic.code" else "dynamic.branch${dynamicCount - 1}.code"
    hit = if (dynamicCount == 0) QueryField("state.field0") else QueryField("document.$suffix")
    val expected = if (dynamicCount == 0) hit else QueryField("storage.$suffix")
    check(schema.resolvePhysicalField(hit, QueryCapability.EXACT_MATCH) == expected)
    check(schema.resolvePhysicalField(missing, QueryCapability.EXACT_MATCH) == missing)
}

@Benchmark
fun physicalHit(): QueryField = schema.resolvePhysicalField(hit, QueryCapability.EXACT_MATCH)

@Benchmark
fun physicalMiss(): QueryField = schema.resolvePhysicalField(missing, QueryCapability.EXACT_MATCH)

@Benchmark
fun constructSchema(): QueryModelSchema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), fields)
```

`none` 用例同时提供精确命中对照和无动态根的构造成本。所有输入在 Setup 构造，只有 constructSchema 把 Schema 构造放在计时区内。

- [x] **Step 3: 添加 `QueryComponentResolutionBenchmark`，复用同一组 JMH 注解。** 参数 `@Param("1", "16", "64") var width = 1`，准备以下 Schema、Projection 与混合指标查询；指标别名唯一且不超过现有 64 项上限。

```kotlin
private lateinit var schema: QueryModelSchema
private lateinit var projectionQuery: SingleQuery
private lateinit var aggregationQuery: AggregationQuery

@Setup
fun setup() {
    val fields = (0 until width).associate { index ->
        val path = "state.field$index"
        QueryField(path) to benchmarkField(path)
    }
    schema = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), fields)
    projectionQuery = SingleQuery(MatchAllFilter, Projection(include = fields.keys.toList()))
    aggregationQuery = AggregationQuery(metrics = fields.keys.mapIndexed { index, field ->
        if (index % 2 == 0) {
            AggregationMetric.Any(field, "metric$index")
        } else {
            AggregationMetric.Numeric(
                AggregationFunction.SUM,
                AggregationExpression.Binary(
                    AggregationExpressionOperator.ADD,
                    AggregationExpression.Field(field),
                    AggregationExpression.Constant(1.0),
                ),
                "metric$index",
            )
        }
    })
    check(schema.resolve(projectionQuery).compatibility == QueryCompatibilityLevel.EXACT)
    check(schema.resolve(aggregationQuery).compatibility == QueryCompatibilityLevel.EXACT)
}

@Benchmark
fun projection(): QuerySchemaResolution<ISingleQuery> = schema.resolve(projectionQuery)

@Benchmark
fun aggregation(): QuerySchemaResolution<AggregationQuery> = schema.resolve(aggregationQuery)
```

- [x] **Step 4: 构建基准并列举实际生成的 JMH jar。**

```bash
./gradlew :wow-benchmarks:jmhJar --console=plain
rg --files wow-benchmarks/build/libs | rg 'jmh.*\.jar$'
```

用唯一返回的 jar 运行 `java -jar <实际路径> -l`，确认三个查询 Resolver/Scale/Component 类均被发现。实际路径在执行记录中固定，不猜测版本化文件名。

- [x] **Step 5: 保存可复现的 baseline。** 使用任务输出确定的 jar 路径执行以下实际 Python runner；它仅运行已存在的 JMH CLI，不增加构建工具或项目依赖。

```python
from pathlib import Path
import subprocess

output = Path('build/query-resolution')
output.mkdir(parents=True, exist_ok=True)
jars = list(Path('wow-benchmarks/build/libs').glob('*jmh*.jar'))
assert len(jars) == 1, jars
pattern = r'.*Query(SchemaResolver|FieldResolutionScale|ComponentResolution)Benchmark.*'
args = ['java', '-jar', str(jars[0]), pattern,
        '-f', '3', '-wi', '5', '-i', '10', '-w', '200ms', '-r', '200ms',
        '-t', '1', '-jvmArgs', '-Xms256m -Xmx256m', '-prof', 'gc', '-foe', 'true',
        '-rf', 'json', '-rff', str(output / 'baseline.json')]
with (output / 'baseline.log').open('w') as log:
    subprocess.run(args, stdout=log, stderr=subprocess.STDOUT, check=True)
with (output / 'baseline-environment.txt').open('w') as log:
    subprocess.run(['java', '-version'], stdout=log, stderr=subprocess.STDOUT, check=True)
    subprocess.run(['git', 'rev-parse', 'HEAD'], stdout=log, check=True)
```

仅在完整运行成功、结果 JSON 包含全部预期场景时继续。生产基线必须仍为原始解析器。基准执行期间不要同时运行其他构建或性能测试。

- [x] **Step 6: 提交基准代码。**

```bash
git add wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/query/QuerySchemaScaleBenchmark.kt
git commit -m "test(query): benchmark resolver scaling and allocation"
```

## Task 2: 动态反向索引及合同回归

**Files:** Modify `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryFieldSchemaResolver.kt`；Test `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolverTest.kt`。

**Interfaces:** 保持 `resolve(...) : QueryFieldResolution`；新增索引及 helper 均 private，后端继续调用现有 QueryModelSchema 方法。

- [x] **Step 1: 用现有 `schema`、`fieldSchema` helper 添加最长祖先与同前缀优先级测试。** 核心测试如下：

```kotlin
@Test
fun `resolved dynamic lookup should choose the longest ancestor`() {
    val resolver = QueryFieldSchemaResolver(schema(linkedMapOf(
        QueryField("state.outer") to fieldSchema(
            QueryCapability.EXACT_MATCH to "document.labels",
            dynamicChildren = true, physicalPath = "storage.outer",
        ),
        QueryField("state.inner") to fieldSchema(
            QueryCapability.EXACT_MATCH to "document.labels.inner",
            dynamicChildren = true, physicalPath = "storage.inner",
        ),
    )))
    val result = resolver.resolve(QueryField("document.labels.inner.code"), QueryCapability.EXACT_MATCH)
    result.logical.assert().isEqualTo(QueryField("state.inner.code"))
    result.physicalField.assert().isEqualTo(QueryField("storage.inner.code"))
    result.compatibility.assert().isEqualTo(QueryCompatibilityLevel.EXACT)
}
```

同前缀用例按顺序加入静态 `state.static`、动态 `state.first`、动态 `state.second`，三者均为同 capability、`document.shared` → `storage.shared`；子字段必须来自 `state.first.code`，精确 `document.shared` 仍来自静态声明。另验证 `document.sharedOther.code` 不匹配、不同 capability 不借用 binding、动态子字段无 ELEMENT_SCOPE。复用已有冲突、物理 parent、精确字段优先、EventStream 和 mask 用例。

- [x] **Step 2: 增加与性能目标直接对应的全字段扫描回归。** 用 Map 委托计数 entries 访问，Schema 构造完成后清零；反向解析一个动态子字段不应枚举全部字段。

```kotlin
val declared = mapOf(QueryField("state.labels") to fieldSchema(
    QueryCapability.EXACT_MATCH to "document.labels",
    dynamicChildren = true,
    physicalPath = "storage.labels",
))
var enumerations = 0
val fields = object : Map<QueryField, QueryFieldSchema> by declared {
    override val entries: Set<Map.Entry<QueryField, QueryFieldSchema>>
        get() { enumerations++; return declared.entries }
}
val model = schema(fields)
enumerations = 0
model.resolvePhysicalField(QueryField("document.labels.code"), QueryCapability.EXACT_MATCH)
    .assert().isEqualTo(QueryField("storage.labels.code"))
enumerations.assert().isEqualTo(0)
```

运行 `./gradlew :wow-query:test --tests 'me.ahoo.wow.query.schema.QuerySchemaResolverTest' --console=plain`。语义合同应在旧实现通过；扫描回归应因旧实现访问 entries 失败，确认失败原因后再实现。

- [x] **Step 3: 构造动态反向索引。** 在当前精确索引构建循环中复用同一个 ResolvedField 实例，动态索引在精确索引前初始化；只存符合条件的绑定。

```kotlin
private val dynamicResolvedFieldIndex = HashMap<Pair<QueryCapability, String>, ResolvedField>()
```

在既有冲突检查之后加入：

```kotlin
if (fieldSchema.dynamicChildren) {
    dynamicResolvedFieldIndex.putIfAbsent(capability to binding.resolvedField.path, resolvedField)
}
```

构造结束后不再修改该私有 Map；保持现有精确索引冲突异常。ELEMENT_SCOPE 项用于保留既有数值相对路径校验，不能产生动态子字段解析结果。

- [x] **Step 4: 替换 `resolvedField` 的扫描实现。**

```kotlin
private fun resolvedField(field: QueryField, capability: QueryCapability): ResolvedField? {
    resolvedFieldIndex[capability to field]?.let { return it }
    if (dynamicResolvedFieldIndex.isEmpty()) return null
    var source: ResolvedField? = null
    var relative: QueryField? = null
    var separator = field.path.lastIndexOf('.')
    while (separator > 0) {
        val ancestorPath = field.path.substring(0, separator)
        dynamicResolvedFieldIndex[capability to ancestorPath]?.let {
            val candidateRelative = checkNotNull(field.relativeTo(it.binding.resolvedField))
            if (source == null) {
                source = it
                relative = candidateRelative
            }
        }
        separator = field.path.lastIndexOf('.', separator - 1)
    }
    val finalSource = source ?: return null
    if (capability == QueryCapability.ELEMENT_SCOPE) return null
    val finalRelative = checkNotNull(relative)
    val dynamicSchema = finalSource.fieldSchema.resolveDynamic(
        source = finalSource.logical,
        relative = finalRelative,
        elementAncestor = finalSource.logical in schema.elementDescendantDynamicFields,
    )
    return ResolvedField(
        finalSource.logical.append(finalRelative),
        dynamicSchema,
        checkNotNull(dynamicSchema.binding(capability)),
    )
}
```

- [x] **Step 5: 重跑 Resolver 与 QueryModelSchema 测试并提交。**

```bash
./gradlew :wow-query:test --tests 'me.ahoo.wow.query.schema.QuerySchemaResolverTest' --tests 'me.ahoo.wow.query.schema.QueryModelSchemaTest' --console=plain
git add wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryFieldSchemaResolver.kt wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolverTest.kt
git commit -m "perf(query): index resolved dynamic field ancestors"
```

## Task 3: 删除投影与聚合兼容级别集合

**Files:** Modify `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt` 和 `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryFieldSchemaResolver.kt` 的失去调用方的兼容级别扩展；Test `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolverTest.kt`。

**Interfaces:** 保持所有 resolve 返回类型；将私有 `collectExpressionLevels(..., levels)` 改为 `resolveExpression(...): QueryCompatibilityLevel`。

- [x] **Step 1: 添加混合兼容级别与引用复用测试，先在现有实现运行。** 复用 helper，避免为保持行为的重构编造应失败的语义断言。

```kotlin
@Test
fun `projection should combine include and exclude levels without replacing it`() {
    val resolver = QuerySchemaResolver(schema(mapOf(
        QueryField("state.known") to fieldSchema(QueryCapability.PRESENCE to "state.known"),
        QueryField("state.unavailable") to fieldSchema(),
    )))
    val projection = Projection(
        include = listOf(QueryField("state.known"), QueryField("state.unknown")),
        exclude = listOf(QueryField("state.unavailable")),
    )
    val result = resolver.resolve(projection)
    result.value.assert().isSameAs(projection)
    result.compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
}
```

聚合构造 Count、Any 与 Numeric(Binary(ADD, Field, Constant)) 的混合列表；对已知数值字段、未知字段和缺少数值能力的字段分别期望 EXACT、COMPATIBLE、INCOMPATIBLE，并断言无重写时返回同一 AggregationQuery。复用现有脱敏、嵌套 elements 与局部重写测试。

运行 `./gradlew :wow-query:test --tests 'me.ahoo.wow.query.schema.QuerySchemaResolverTest' --console=plain`，本任务语义用例在优化前应通过；优化收益由 Task 1/4 的同基准对照验证。

- [x] **Step 2: 替换 projection 中的三组临时列表。**

```kotlin
var compatibility = QueryCompatibilityLevel.EXACT
projection.include.forEach { field ->
    compatibility = maxOf(compatibility, fieldResolver.resolveProjection(field).compatibility)
}
projection.exclude.forEach { field ->
    compatibility = maxOf(compatibility, fieldResolver.resolveProjection(field).compatibility)
}
```

返回分支把 `compatibility.combined()` 改为 `compatibility`，保留空投影与 EventStream 检查的原结构。

- [x] **Step 3: 聚合直接累積兼容级别。** `levels` 替换为初始化到 `rootFilter.compatibility` 的局部变量；container、element filter、group 的每个 `levels += value` 改为 `compatibility = maxOf(compatibility, value)`。保留元素按需复制逻辑。两轮 metrics 分别使用 `if (metric is AggregationMetric.Any)`、`if (metric is AggregationMetric.Numeric)`，不构造类型过滤列表。Any 的 MANY 分支直接把兼容级别设为 INCOMPATIBLE。

表达式 helper 完整替换为：

```kotlin
private fun resolveExpression(
    expression: AggregationExpression,
    logicalParent: QueryField?,
    resolvedParent: QueryField?,
    physicalParent: QueryField?,
): QueryCompatibilityLevel = when (expression) {
    is AggregationExpression.Field -> resolveAggregationField(
        expression.field, QueryCapability.AGGREGATE_NUMERIC,
        logicalParent, resolvedParent, physicalParent,
    ).compatibility
    is AggregationExpression.Constant -> QueryCompatibilityLevel.EXACT
    is AggregationExpression.Binary -> maxOf(
        resolveExpression(expression.left, logicalParent, resolvedParent, physicalParent),
        resolveExpression(expression.right, logicalParent, resolvedParent, physicalParent),
    )
    else -> QueryCompatibilityLevel.INCOMPATIBLE
}
```

Numeric 分支以 `maxOf(compatibility, resolveExpression(...))` 累积；最终 QuerySchemaResolution 使用局部 compatibility，不改查询构造分支。

确认 `rg -n '\.combined\(|fun.*combined\(' wow-query/src` 不再显示 Iterable 扩展的调用方后，删除 `QueryFieldSchemaResolver.kt` 文件末尾不再使用的 `internal fun Iterable<QueryCompatibilityLevel>.combined()`，保留三参数的 private combined 函数。

- [x] **Step 4: 完整查询模块检查并提交。**

```bash
./gradlew :wow-query:check --console=plain
git add wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryFieldSchemaResolver.kt wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolverTest.kt
git commit -m "perf(query): reduce projection and aggregation allocations"
```

## Task 4: 对照验证、接入回归与交付

**Files:** 验证所有实现和现有相关测试；更新本计划与设计文档的实际完成状态，不修改后端生产代码。

**Interfaces:** 验证公开 QueryModelSchema、Gateway、Backend 合同与 Task 1 相同的 JMH 场景。

- [x] **Step 1: 重建候选基准。** 运行 `./gradlew :wow-benchmarks:jmhJar --console=plain`。复用 Task 1 的同一 runner，仅把输出名改为 `candidate.json`、`candidate.log`、`candidate-environment.txt`。保持其他所有参数、基准源码及 JDK 不变；运行期间不并发构建。

  修正前候选保留为 `candidate.*`；最终提交 `d281e1cce` 的候选使用 `final-candidate.json`、`final-candidate.log`、`final-candidate-environment.txt`，避免覆盖诊断数据。最终基准源码相对 `a4185d2b6` 无差异，JDK 仍为 Zulu 17.0.7。

- [x] **Step 2: 比较完整结果矩阵。** 按 benchmark 名称与 params 键值匹配 baseline/candidate。核对不存在失败、缺行或参数变化；逐项比较 primaryMetric 的 score/scoreError 与 secondaryMetrics 的 `gc.alloc.rate.norm`，报告查询热路径、静态对照、Schema 构造成本。对误差重叠不宣称耗时提升，对疑似退化仅复测相关场景，不无条件重复全量。

```python
import json
from pathlib import Path
root = Path('build/query-resolution')
def rows(name):
    values = json.loads((root / name).read_text())
    return {(row['benchmark'], tuple(sorted(row.get('params', {}).items()))): row for row in values}
before, after = rows('baseline.json'), rows('candidate.json')
assert before.keys() == after.keys()
for key in sorted(before):
    old, new = before[key], after[key]
    a, b = old['primaryMetric'], new['primaryMetric']
    old_bytes = old['secondaryMetrics']['gc.alloc.rate.norm']['score']
    new_bytes = new['secondaryMetrics']['gc.alloc.rate.norm']['score']
    print(key, 'ns/op', a['score'], a['scoreError'], '->', b['score'], b['scoreError'],
          'B/op', old_bytes, '->', new_bytes)
```

- [x] **Step 3: 运行后端与接入回归。**

```bash
./gradlew :wow-mongo:test --tests 'me.ahoo.wow.mongo.query.*' :wow-elasticsearch:test --tests 'me.ahoo.wow.elasticsearch.query.*' --console=plain
./gradlew :wow-spring:test --tests 'me.ahoo.wow.spring.query.*' :wow-webflux:test --tests 'me.ahoo.wow.webflux.route.query.*' --tests 'me.ahoo.wow.webflux.route.snapshot.*' --tests 'me.ahoo.wow.webflux.route.event.*' --console=plain
./gradlew :wow-mongo:integrationTest --tests 'me.ahoo.wow.mongo.query.*' :wow-elasticsearch:integrationTest --tests 'me.ahoo.wow.elasticsearch.query.*' --console=plain
```

集成测试沿用现有 MongoTestFixture/ElasticsearchTestFixture 和容器环境；先检查其本地服务策略。环境失败与查询行为失败分开记录。只修复本阶段引入的失败，不为测试调整生产语义。

- [x] **Step 4: 审查最小差异与验收合同。**

```bash
git diff 91af473b8 --check
git diff 91af473b8 --stat
git diff 91af473b8 -- wow-query/src/main
git status --short
```

核对新增动态索引大小受声明数量约束、查找无全字段扫描、两种父路径转换未变、metrics 和 expression 的顺序未变。核对没有公开 API、生成合同、依赖或模块改动。按实际验证结果更新设计与本计划，并提交文档。

- [x] **Step 5: 交付第一阶段结果。** 提供变更文件、功能测试实际数量、性能对照与构造成本、原始结果路径，以及未运行的验证项（若有）。依据结果指出第二阶段的具体候选，不提前实施未设计的后端重构。

### Task 4 实际结果

最终完整矩阵成功，基线与候选各 36 行，benchmark/params 键集合完全一致，无失败、缺行或参数变化。代表性最终数据如下；完整逐行结果见 `.superpowers/sdd/2026-09-05-query-resolution-refactoring/task-4-report.md`。

| 场景 | 基线 ns/op | 最终候选 ns/op | 基线 B/op | 最终候选 B/op | 判定 |
| --- | ---: | ---: | ---: | ---: | --- |
| `physicalMiss(dynamic1)` | 422.672 ± 1.939 | 306.290 ± 7.786 | 2160.001 | 1248.001 | 改善 |
| `physicalMiss(static32)` | 421.151 ± 1.862 | 308.630 ± 4.834 | 2160.001 | 1248.001 | 改善 |
| `physicalMiss(static2048)` | 8582.174 ± 58.680 | 278.208 ± 9.647 | 50512.023 | 1248.001 | 改善 |
| `physicalHit(dynamic128)` | 12864.006 ± 124.245 | 3900.580 ± 23.172 | 26712.035 | 9896.010 | 改善 |
| `projection(width=64)` | 1759.904 ± 43.060 | 1160.950 ± 23.998 | 9032.005 | 6293.336 | 改善 |
| `aggregation(width=64)` | 1432.937 ± 31.777 | 922.421 ± 8.779 | 4864.004 | 2608.002 | 改善 |
| `physicalHit(none32)` | 7.517 ± 0.068 | 7.597 ± 0.158 | 40.000 | 40.000 | 误差重叠，无可复现退化 |
| `fieldInferMappedSort` | 24.953 ± 0.142 | 24.914 ± 0.243 | 312.000 | 312.000 | 误差重叠，无可复现退化 |
| `constructSchema(dynamic128)` | 25720.877 ± 179.044 | 31022.219 ± 325.109 | 72176.070 | 109144.083 | 构造耗时 1.206x，约 +36,968 B/次 |
| `constructSchema(static2048)` | 343087.049 ± 6031.577 | 414368.329 ± 6095.390 | 1076293.067 | 1076665.102 | 构造耗时 1.208x；复测 1.090x |

构造 B/op 只表示一次 Schema 构造期间的分配量，不表示索引 retained heap；本阶段未测 retained heap。针对性构造复测确认 `dynamic128` 为 25730.255 ± 188.109 → 32455.041 ± 222.367 ns/op、72176.070 → 109144.087 B/op，包含 256/2048 个命名静态字段和 1 个动态根的 `static256/2048` 为 1.082x/1.090x；只有 `none32/none2048` 是无动态根对照。查询热路径及静态/identity 对照均无可复现退化；`identityDynamicFilter` 的分配疑似信号经成对复测为 3032.002 → 3032.002 B/op，耗时区间重叠。

最终验证：`:wow-query:check` 394 项并通过 Detekt；Mongo query 单测 155 项、Elasticsearch query 单测 142 项、Spring query 单测 2 项、WebFlux query/snapshot/event route 单测 87 项、Mongo query 集成测试 85 项、Elasticsearch query 集成测试 93 项。合计 958 项测试，全部 0 failure、0 error、0 skip；Testcontainers 环境无失败。

原始产物位于 `build/query-resolution/baseline.{json,log}`、`baseline-environment.txt`、`final-candidate.{json,log}`、`final-candidate-environment.txt`、`rerun-{baseline,final}-construct-selected.{json,log}`；验证日志为 `final-wow-query-check.log`、`final-backend-unit.log`、`final-spring-webflux.log`、`final-backend-integration.log`。生成输出均保持忽略且不提交。

第一阶段的测量结论限定于共享解析内核。第二阶段按既定方向单独设计[聚合编译结果复用](../designs/2026-09-05-aggregation-compiler-refactoring-design.md)；动态索引延迟构造、容量估算与 retained heap 测量仍需生产剖析提供证据，不因本阶段构造成本变化而提前实施。
