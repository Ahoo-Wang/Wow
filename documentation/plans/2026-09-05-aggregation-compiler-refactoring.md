# 聚合编译结果复用 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 减少 MongoDB 和 Elasticsearch 聚合编译中的重复字段解析和输入构造，保持现有查询行为。

**Architecture:** 只重排两个内部编译器的工作。ES 从一次 Schema 解析同时取得物理 Binding 和日期语义；MongoDB 每个分组一次生成过滤条件与分组表达式。继续使用现有 Schema、BSON 和 ES 计划类型。

**Tech Stack:** Kotlin 2.4.10 / JVM 17、MongoDB BSON、Elasticsearch Java client、JUnit、MockK、FluentAssert、JMH。

**Spec:** [已确认设计](../designs/2026-09-05-aggregation-compiler-refactoring-design.md)

## Global Constraints

- 生产改动限定为 `MongoAggregationCompiler.kt` 和 `ElasticsearchAggregationCompiler.kt` 两个现有内部编译器。
- 公开 Gateway、Backend、Schema 接口及其职责不变。Query JSON、Schema HTTP、生成 OpenAPI/schema、存储布局、Cursor 协议、Spring 配置和版本号不变。
- 不增加依赖、Gradle 模块或构建插件，不修改生产类的可见性。
- 所有复用值只属于当前一次 `compile` 调用；不缓存跨查询结果，不新增共享可变状态。
- resolved alias 命中优先于同名声明缺 capability；相对字段继续使用 `parent.append(field)`；编译器使用完整物理路径。
- 保留 MongoDB 动态 temporal capability 缺失时的原生 `$toDate` 回退，以及 ES 对可识别动态字段缺少该能力时的拒绝。
- 保留首次 Resolver 的路径校验、现有异常类型和首个语义失败；不改写 epoch 换算、Painless、element resolvedParent 维护、贡献计数、排序与结果解码。
- 基准只测 `compile(query, schema)`；同一份基准源码对照优化前后实现；28 个场景；JDK 17、单线程、固定 256 MiB 堆、3 forks、5 次 200ms 预热、10 次 200ms 测量及 GC profiler。
- B/op 仅表示编译期分配，不表示 retained heap；不能将编译微基准比例外推为数据库查询端到端加速比例。
- 原始产物放在忽略的 `build/aggregation-compiler/`；不提交生成输出；文档在 `documentation/designs/` 与 `documentation/plans/`。

## 文件与执行安排

| 文件 | 职责 |
|---|---|
| `wow-benchmarks/src/jmh/java/me/ahoo/wow/benchmark/query/AggregationCompilerBenchmark.java` | 两种编译器的同源基准与 Setup 正确性检查 |
| `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationCompiler.kt` | ES 私有解析复用 |
| `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt` | ES 原生计划与解析次数合同 |
| `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation/MongoAggregationCompiler.kt` | MongoDB 分组与字段解析复用 |
| `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt` | BSON 输出与解析次数合同 |
| 本计划及关联设计 | 最终验证记录与测量结论 |

当前基线为 `f3be596f0`，生产代码基线为 `27fe81b40`，Wow 版本 `9.0.8`。现有隔离 worktree 为 `/Users/ahoo/.codex/worktrees/b9ae/Wow`，由应用管理 detached HEAD。研究阶段的两个聚合编译器测试已通过：MongoDB 27、ES 12，共 39 项；之后只有文档变更，无需再建立相同基线。

按 Task 1 → 2 → 3 → 4 执行。用户已选择子代理逐项实施与独立审查，不再次询问执行方式。每项结束提交、记录证据并审查；控制器维护独立 SDD ledger。性能测量期间不并发 Gradle 构建或其他性能测试。

### Task 1: 建立并冻结聚合编译基准

**Files:**
- Create: `wow-benchmarks/src/jmh/java/me/ahoo/wow/benchmark/query/AggregationCompilerBenchmark.java`
- Output (ignored): `build/aggregation-compiler/baseline-jmh.jar`、`baseline.json`、`baseline.log`、`baseline-environment.txt`

**Interfaces:**
- Consumes: `MongoAggregationCompiler(SnapshotFilterCompiler.INSTANCE).compile(AggregationQuery, QueryModelSchema): List<Bson>` 与 ES 对应构造器及 `ElasticsearchAggregationPlan`。
- Produces: JMH `AggregationCompilerBenchmark.compile()`；参数 `backend=mongo|elasticsearch`、`shape=known_terms|unknown_terms|known_histogram|known_epoch|unknown_date|known_metric|count_only`、`width=1|16`。

- [ ] **Step 1: 用现有 Java JMH 源集创建基准。** 复用仓库版权头。使用显式 imports；Kotlin companion 常量通过 `Companion.get...()` 访问，不修改生产可见性或加反射。主体如下：

```java
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@Threads(1)
public class AggregationCompilerBenchmark {
    @Param({"mongo", "elasticsearch"}) public String backend;
    @Param({"known_terms", "unknown_terms", "known_histogram", "known_epoch",
            "unknown_date", "known_metric", "count_only"}) public String shape;
    @Param({"1", "16"}) public int width;
    private QueryModelSchema schema;
    private AggregationQuery query;
    private final MongoAggregationCompiler mongo = new MongoAggregationCompiler(
            me.ahoo.wow.mongo.query.snapshot.SnapshotFilterCompiler.INSTANCE);
    private final ElasticsearchAggregationCompiler elasticsearch = new ElasticsearchAggregationCompiler(
            me.ahoo.wow.elasticsearch.query.snapshot.SnapshotFilterCompiler.INSTANCE);

    @Setup
    public void setup() {
        Map<QueryField, QueryFieldSchema> fields = new LinkedHashMap<>();
        List<AggregationGroup> groups = new ArrayList<>();
        List<AggregationMetric> metrics = new ArrayList<>();
        for (int index = 0; index < width; index++) {
            QueryField logical = new QueryField("state.field" + index);
            QueryField resolved = new QueryField("document.field" + index);
            QueryField physical = new QueryField("storage.field" + index);
            QueryCapability capability = switch (shape) {
                case "known_histogram", "known_metric" -> QueryCapability.Companion.getAGGREGATE_NUMERIC();
                case "known_epoch", "unknown_date" -> QueryCapability.Companion.getAGGREGATE_TEMPORAL();
                default -> QueryCapability.Companion.getAGGREGATE_TERMS();
            };
            if (shape.startsWith("known_")) {
                QuerySemanticType semantic = shape.equals("known_epoch")
                        ? new Temporal.Epoch(TimeUnit.MICROSECONDS) : null;
                QueryValueType valueType = shape.equals("known_terms")
                        ? QueryValueType.Companion.getSTRING() : QueryValueType.Companion.getINTEGER();
                fields.put(logical, new QueryFieldSchema(
                        null, null, null, Set.of(valueType), false, true, QueryCardinality.SINGLE,
                        semantic, false, Map.of(capability, new QueryFieldBinding(resolved, physical, null)),
                        null, QueryRewriteMode.REQUIRED, null, null));
            }
            QueryField input = shape.equals("known_epoch") ? resolved : logical;
            String alias = "group" + index;
            switch (shape) {
                case "known_terms", "unknown_terms" -> groups.add(new AggregationGroup.Terms(input, alias));
                case "known_histogram" -> groups.add(new AggregationGroup.Histogram(input, alias, 10.0));
                case "known_epoch", "unknown_date" -> groups.add(new AggregationGroup.DateHistogram(
                        input, alias, AggregationDateUnit.DAY, "UTC"));
                case "known_metric" -> metrics.add(new AggregationMetric.Numeric(
                        AggregationFunction.SUM, new AggregationExpression.Field(input), "metric" + index));
                case "count_only" -> metrics.add(new AggregationMetric.Count("count" + index));
                default -> throw new IllegalArgumentException(shape);
            }
        }
        if (!groups.isEmpty()) metrics.add(new AggregationMetric.Count("count"));
        schema = new QueryModelSchema(QueryModel.Companion.getSNAPSHOT(), Set.of(), fields);
        query = new AggregationQuery(MatchAllFilter.INSTANCE, List.of(), groups, metrics, List.of(), 100);
        verifyPlan(compile());
    }

    @Benchmark
    public Object compile() {
        return backend.equals("mongo") ? mongo.compile(query, schema) : elasticsearch.compile(query, schema);
    }
}
```

- [ ] **Step 2: 添加 Setup 原生输出检查。** 本检查替代基准专用测试框架。Mongo 检查完整物理路径与 null summary 分组键；ES 检查各原生 source/runtime/metric 路径及数量。方法如下（补齐该类所需 imports）：

```java
private void verifyPlan(Object result) {
    String expected = (shape.startsWith("known_") ? "storage" : "state") + ".field0";
    if (backend.equals("mongo")) {
        List<?> pipeline = (List<?>) result;
        List<BsonDocument> documents = pipeline.stream().map(stage -> ((Bson) stage).toBsonDocument()).toList();
        String json = documents.toString();
        BsonDocument group = documents.stream().filter(stage -> stage.containsKey("$group"))
                .findFirst().orElseThrow().getDocument("$group");
        if (query.getGroupBy().isEmpty() && !group.get("_id").isNull()) {
            throw new IllegalStateException("summary must use a null group key");
        }
        if (!shape.equals("count_only") && !json.contains(expected)) {
            throw new IllegalStateException("missing path: " + expected);
        }
        return;
    }
    ElasticsearchAggregationPlan plan = (ElasticsearchAggregationPlan) result;
    if (plan.getGroupSources().size() != query.getGroupBy().size()
            || plan.getMetrics().size() != query.getMetrics().size()) {
        throw new IllegalStateException("wrong compiled dimensions");
    }
    String actual = switch (shape) {
        case "known_terms", "unknown_terms" -> plan.getGroupSources().get(0).value().terms().field();
        case "known_histogram" -> plan.getGroupSources().get(0).value().histogram().field();
        case "unknown_date" -> plan.getGroupSources().get(0).value().dateHistogram().field();
        case "known_epoch" -> plan.getRuntimeMappings().get("__wow_date_histogram_0")
                .script().params().get("field").to(String.class);
        case "known_metric" -> ((ElasticsearchAggregationMetric.Numeric) plan.getMetrics().get(0)).getField();
        case "count_only" -> expected;
        default -> throw new IllegalArgumentException(shape);
    };
    if (!expected.equals(actual)) throw new IllegalStateException("wrong path: " + actual);
    int expectedRuntimeFields = shape.equals("known_epoch") ? width : 0;
    if (plan.getRuntimeMappings().size() != expectedRuntimeFields) {
        throw new IllegalStateException("wrong runtime field count");
    }
}
```

- [ ] **Step 3: 构建与发现检查，然后提交基准源码。**

```bash
./gradlew :wow-benchmarks:jmhJar --console=plain
rg --files wow-benchmarks/build/libs | rg 'jmh.*\.jar$'
```

用返回的唯一 jar 执行 `java -jar <实际 jar 路径> -l '.*AggregationCompilerBenchmark.*'`，预期发现 `compile`。若 JVM 签名细节需要调整，仅修改基准侧调用。执行 `git diff --check`，只提交新增基准，提交信息 `test(query): add aggregation compiler benchmarks`。

- [ ] **Step 4: 冻结 baseline 并运行完整矩阵。** 以下 runner 存为忽略目录下的 `run-benchmark.py`；这是构建产物辅助脚本，不提交。首次使用 `baseline`，后续使用 `candidate`。两次共用该脚本及同一基准源码。基准运行失败应先修复 fixture，再重新冻结 baseline；冻结后生产改动才可开始。

```python
from pathlib import Path
import json
import shutil
import subprocess
import sys

label = sys.argv[1]
assert label in {"baseline", "candidate"}
out = Path("build/aggregation-compiler")
out.mkdir(parents=True, exist_ok=True)
jars = list(Path("wow-benchmarks/build/libs").glob("*jmh*.jar"))
assert len(jars) == 1, jars
frozen = out / f"{label}-jmh.jar"
shutil.copyfile(jars[0], frozen)
with (out / f"{label}-environment.txt").open("w") as log:
    subprocess.run(["java", "-version"], stdout=log, stderr=subprocess.STDOUT, check=True)
    subprocess.run(["git", "rev-parse", "HEAD"], stdout=log, check=True)
    subprocess.run(["shasum", "-a", "256", str(frozen)], stdout=log, check=True)
args = ["java", "-jar", str(frozen), ".*AggregationCompilerBenchmark.*",
        "-f", "3", "-wi", "5", "-i", "10", "-w", "200ms", "-r", "200ms",
        "-t", "1", "-jvmArgs", "-Xms256m -Xmx256m", "-prof", "gc", "-foe", "true",
        "-rf", "json", "-rff", str(out / f"{label}.json")]
with (out / f"{label}.log").open("w") as log:
    subprocess.run(args, stdout=log, stderr=subprocess.STDOUT, check=True)
rows = json.loads((out / f"{label}.json").read_text())
keys = {(r["params"]["backend"], r["params"]["shape"], r["params"]["width"]) for r in rows}
assert len(rows) == len(keys) == 28
assert all("gc.alloc.rate.norm" in r["secondaryMetrics"] for r in rows)
print(f"{label}: 28 complete cases")
```

Run: `python3 build/aggregation-compiler/run-benchmark.py baseline`。报告源码提交、jar checksum、28 行完整性及日志路径；不声称性能改善。

### Task 2: ES 字段路径与日期语义共用解析结果

**Files:**
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationCompiler.kt` (`dateField`、私有 `QueryField.resolve`)
- Test: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt`

**Interfaces:**
- Consumes: 现有 `resolveFieldSchema(QueryField, QueryCapability): QueryFieldSchema?` 与 `QueryFieldSchema.binding(capability): QueryFieldBinding?`。
- Produces: 同签名、同原生计划的 `compile`；一次字段出现位置只执行一次 Schema 解析。新增 helper 仅为本文件 private。

- [ ] **Step 1: 添加能在原实现失败的实际编译合同。** 引入 `spyk`，监控真实 Schema；不 stub Schema 返回值。以 Terms 为最小 RED：

```kotlin
@Test
fun `terms should reuse its resolved binding`() {
    val observed = spyk(schema(field("state.status", QueryCapability.AGGREGATE_TERMS, "storage.status", "keyword")))
    val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
        aggregation { terms("state.status", "status"); count("count") }, observed,
    )
    plan.groupSources.single().value().terms().field().assert().isEqualTo("storage.status")
    verify(exactly = 1) { observed.resolveFieldSchema(QueryField("state.status"), QueryCapability.AGGREGATE_TERMS) }
    verify(exactly = 0) {
        observed.resolvePhysicalField(QueryField("state.status"), QueryCapability.AGGREGATE_TERMS, any(), any(), any())
    }
}
```

同一测试方式覆盖 DateHistogram：复用已有 Epoch runtime 测试，将其 Schema 包为 `spyk`，对 `document.createdAt` 的 temporal 解析断言一次且不再 `resolvePhysicalField`；现有 runtime params、脚本与下一次 count compile 的映射隔离断言保留。字段定向 verify 避免把 filterCompiler 对其他字段的解析计入次数。

Run: `./gradlew :wow-elasticsearch:test --tests 'me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchAggregationCompilerTest' --console=plain`。预期原实现仅新增工作量断言失败；记录失败行与命令。

- [ ] **Step 2: 替换私有解析与 dateField 前段。** 使用一个纯回退 helper 保持明确声明缺能力的检查顺序，具体代码：

```kotlin
private fun QueryField.resolve(
    parent: QueryField?, physicalParent: QueryField?, schema: QueryModelSchema, capability: QueryCapability,
): String {
    val logicalField = parent?.append(this) ?: this
    return schema.resolveFieldSchema(logicalField, capability)?.binding(capability)?.physicalField?.path
        ?: compatiblePath(logicalField, physicalParent, schema, capability)
}

private fun QueryField.compatiblePath(
    logicalField: QueryField, physicalParent: QueryField?, schema: QueryModelSchema, capability: QueryCapability,
): String {
    if (logicalField in schema.fields) {
        throw QuerySchemaValidationException("Query field [$logicalField] does not support [$capability].")
    }
    return physicalParent?.append(this)?.path ?: logicalField.path
}
```

`dateField` 前段改为下列代码；后续 `when (semanticType)` 与 runtime field 构造完整保留：

```kotlin
val logicalField = parent?.append(field) ?: field
val capability = QueryCapability.AGGREGATE_TEMPORAL
val fieldSchema = schema.resolveFieldSchema(logicalField, capability)
val physicalPath = fieldSchema?.binding(capability)?.physicalField?.path
    ?: field.compatiblePath(logicalField, physicalParent, schema, capability)
if (fieldSchema == null) {
    if (schema.field(logicalField) != null) {
        throw QuerySchemaValidationException("Query field [$logicalField] does not support [$capability].")
    }
    return physicalPath
}
```

- [ ] **Step 3: 锁住本次改动的日期回退边界。** 用现有 fixture 构造 Schema，必要时对生成的 `QueryFieldSchema.copy(dynamicChildren = true)` 构造新 Schema，避免扩大 TestField 设计。补充以下真实输入输出；可组合在一个表驱动测试中，但每项都有独立断言：

```kotlin
// 明确 metadata 和动态后代缺少 temporal 能力，都应拒绝。
val root = QueryField("state.extra")
val declared = schema(field(root.path, QueryCapability.PRESENCE, "storage.extra", "object"))
val dynamic = declared.copy(fields = declared.fields.mapValues { (_, value) -> value.copy(dynamicChildren = true) })
listOf(declared to root.path, dynamic to "state.extra.createdAt").forEach { (inputSchema, path) ->
    assertThrows<QuerySchemaValidationException> {
        ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation { dateHistogram(path, AggregationDateUnit.DAY, "day"); count("count") }, inputSchema,
        )
    }.message.assert().contains("AGGREGATE_TEMPORAL")
}
// 完全没有 metadata 的同一字段则保留原生路径。
val fallback = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
    aggregation { dateHistogram("state.extra.createdAt", AggregationDateUnit.DAY, "day"); count("count") }, schema(),
)
fallback.groupSources.single().value().dateHistogram().field().assert().isEqualTo("state.extra.createdAt")
```

再用以下输入覆盖 temporal alias 优先级及缺少语义，封装成 JUnit 测试，沿用相对前缀、element/filter scopes、Any、Numeric 和算术字段的现有合同，不复制已有测试。

```kotlin
val temporal = field("state.createdAt", QueryCapability.AGGREGATE_TEMPORAL, "storage.createdAt", "date",
    Temporal.Date, resolvedPath = "document.createdAt")
val aliasSchema = schema(temporal, field("document.createdAt", QueryCapability.PRESENCE, "document.createdAt", "date"))
val query = aggregation { dateHistogram("document.createdAt", AggregationDateUnit.DAY, "day"); count("count") }
val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(query, aliasSchema)
plan.groupSources.single().value().dateHistogram().field().assert().isEqualTo("storage.createdAt")
val unsupported = schema(field("state.createdAt", QueryCapability.AGGREGATE_TEMPORAL, "storage.createdAt", "date",
    resolvedPath = "document.createdAt"))
assertThrows<QuerySchemaValidationException> {
    ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(query, unsupported)
}.message.assert().contains("does not have a supported temporal semantic type")
```

- [ ] **Step 4: 聚焦测试 GREEN、自审并提交。** 执行 Step 1 的同一命令及 `git diff --check`。检查没有改动日期脚本、runtime 名称、element 遍历或公共接口。提交两个文件，信息 `refactor(elasticsearch): reuse aggregation field bindings`；报告 RED/GREEN 与测试数。

### Task 3: MongoDB 分组一次编译并复用原生输入

**Files:**
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation/MongoAggregationCompiler.kt` (`compile` 分组部分、`group`、`AggregationGroup.expression`、`dateInput`、私有 `QueryField.resolve`)
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt`

**Interfaces:**
- Consumes: 现有 Schema Binding 与 `scalarOrSingleton`、`epochDate`、原生 MongoDB builders。
- Produces: 私有 `AggregationGroup.compile(parent, physicalParent, schema): Pair<Bson, Any>`；公开 JVM `compile` 签名和最终管道不变；私有 `group` 增加 `id: Document?` 参数并移除自己的 groupBy 解析。

- [ ] **Step 1: 添加每组一次解析的 RED 合同。** 在真实 Schema 上使用 `spyk`，以下结构覆盖 Terms、Histogram、DateHistogram 与未知 root 回退；groupBy 构造用 DSL，每种输入只含一个分组和一个 Count。Terms 最小可运行测试如下：

```kotlin
@Test
fun `group should resolve its input once for match and group stages`() {
    val observed = spyk(schema(field("state.status", QueryCapability.AGGREGATE_TERMS, "storage.status")))
    val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
        aggregation { terms("state.status", "status"); count("count") }, observed,
    ).map { it.toBsonDocument() }
    pipeline[1].toJson().assert().contains("storage.status")
    pipeline[2].getDocument("\$group").getDocument("_id").getString("status").value.assert()
        .isEqualTo("\$storage.status")
    verify(exactly = 1) { observed.resolveFieldSchema(QueryField("state.status"), QueryCapability.AGGREGATE_TERMS) }
}
```

对 Histogram 使用 numeric capability 和 DECIMAL，断言 `$isNumber` 与 `$divide` 消费相同 scalar/singleton BSON。对 Epoch DateHistogram 使用 MICROSECONDS，断言 `$match.$expr.$ne[0]` 等于 `$group._id.day.$toLong.$dateTrunc.date`。对未知 root 的 DateHistogram 断言一次 `resolveFieldSchema`、零次该字段 `resolvePhysicalField`，且两个位置均为原生 `{"$toDate":"$state.createdAt"}`。

Run: `./gradlew :wow-mongo:test --tests 'me.ahoo.wow.mongo.query.snapshot.MongoAggregationCompilerTest' --console=plain`。预期新增次数断言失败，现有原生结构断言通过；记录 RED。

- [ ] **Step 2: 在分组循环生成过滤列表与 id。** 将原两轮 groupBy 编译替换为：

```kotlin
val groupId = query.groupBy.takeIf { it.isNotEmpty() }?.let { groups ->
    val id = Document()
    val filters = groups.map { group ->
        val (filter, expression) = group.compile(logicalParent, physicalParent, schema)
        id[group.alias] = expression
        filter
    }
    add(Aggregates.match(Filters.and(filters)))
    id
}
add(group(query, groupId, logicalParent, physicalParent, schema))
```

`group` 加 `id: Document?` 参数并删去旧的 `query.groupBy...associateTo`；保留 accumulators 全部原逻辑。原 `AggregationGroup.expression` 改为返回 `Pair<Bson, Any>` 的 `compile`：

```kotlin
private fun AggregationGroup.compile(
    parent: QueryField?, physicalParent: String?, schema: QueryModelSchema,
): Pair<Bson, Any> = when (this) {
    is AggregationGroup.Terms -> {
        val path = field.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_TERMS)
        Filters.and(Filters.exists(path), Filters.ne(path, null)) to "\$$path"
    }
    is AggregationGroup.Histogram -> {
        val path = field.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_NUMERIC)
        val input = scalarOrSingleton("\$$path")
        Filters.expr(Document("\$isNumber", input)) to Document(
            "\$multiply", listOf(Document("\$floor", Document("\$divide", listOf(input, interval))), interval),
        )
    }
    is AggregationGroup.DateHistogram -> {
        val input = dateInput(parent, physicalParent, schema)
        val truncation = Document("date", input)
            .append("unit", unit.name.lowercase())
            .append("timezone", if (timeZone == "Z") "UTC" else timeZone)
            .apply { if (unit == AggregationDateUnit.WEEK) append("startOfWeek", "Monday") }
        Filters.expr(Document("\$ne", listOf(input, null))) to Document("\$toLong", Document("\$dateTrunc", truncation))
    }
}
```

删除不再使用的 `AggregationGroup.capability` 属性。不得重排 Metrics、排序、limit 或修改 epochDate/scalarOrSingleton 算法。

- [ ] **Step 3: 删除未知字段重复解析。** 普通 `QueryField.resolve` 的最后一行改为 `return logicalField.path`。`dateInput` 的前段如下，后面的 physicalPath 异常与 semantic `when` 保持原样：

```kotlin
val logicalField = parent?.append(field) ?: field
val fieldSchema = schema.resolveFieldSchema(logicalField, QueryCapability.AGGREGATE_TEMPORAL)
    ?: schema.fields[logicalField]
val temporalBinding = fieldSchema?.binding(QueryCapability.AGGREGATE_TEMPORAL)
if (fieldSchema == null) {
    val physicalPath = physicalParent?.let { "$it.${field.path}" } ?: logicalField.path
    return Document("\$toDate", "\$$physicalPath")
}
```

- [ ] **Step 4: 补足会受本次合并影响的语义合同。** 复用已有动态 temporal fallback、周起点、UTC、epoch 精度、alias、相对前缀和能力缺失测试。补充 summary id 与连续编译的相同结构；日期 alias 优先级使用下列输入，保留先解析 Binding 的顺序：

```kotlin
val temporal = field("state.createdAt", QueryCapability.AGGREGATE_TEMPORAL, "storage.createdAt",
    semanticType = Temporal.Date, resolvedPath = "document.createdAt")
val aliasSchema = schema(temporal, field("document.createdAt", QueryCapability.PRESENCE, "document.createdAt"))
val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
    aggregation { dateHistogram("document.createdAt", AggregationDateUnit.DAY, "day"); count("count") }, aliasSchema,
).map { it.toBsonDocument() }
pipeline[1].toJson().assert().contains("storage.createdAt").doesNotContain("document.createdAt")
pipeline[2].toJson().assert().contains("storage.createdAt").doesNotContain("document.createdAt")
```

首个失败与缺 semantic 的验证如下：

```kotlin
@Test
fun `group compilation should preserve the first semantic failure`() {
    val input = schema(
        field("state.first", QueryCapability.AGGREGATE_TEMPORAL, "storage.first"),
        field("state.second", QueryCapability.PRESENCE, "storage.second"),
    )
    assertThrows<QuerySchemaValidationException> {
        MongoAggregationCompiler(SnapshotFilterCompiler).compile(
            aggregation {
                dateHistogram("state.first", AggregationDateUnit.DAY, "first")
                terms("state.second", "second")
                count("count")
            }, input,
        )
    }.message.assert().isEqualTo("Query field [state.first] does not have a supported temporal semantic type.")
}
```

针对缺少 metadata 的日期分组，在已有已映射 element parent 下断言 `$toDate` 保留完整物理 parent 和原相对字段；参数带重名前缀时不得消除该前缀。对动态 resolved alias 下数值后缀（如 `document.extra.123`）保留 `IllegalArgumentException`。原生节点在方法局部创建，检查没有把 `Document` 放入编译器成员；跨 compile 隔离由连续编译测试与该所有权检查共同证明。

- [ ] **Step 5: 聚焦测试 GREEN、自审并提交。** 执行 Step 1 同一测试命令及 `git diff --check`；无分组场景不得新增空 `Document`。提交两个文件，信息 `refactor(mongo): compile aggregation group inputs once`；报告 RED/GREEN、测试数及最终 BSON 保持的证据。

### Task 4: 对照性能、完整查询回归及交付记录

**Files:**
- Modify: `documentation/designs/2026-09-05-aggregation-compiler-refactoring-design.md`
- Modify: `documentation/plans/2026-09-05-aggregation-compiler-refactoring.md`
- Output (ignored): `build/aggregation-compiler/candidate-jmh.jar`、`candidate.json`、`candidate.log`、`candidate-environment.txt`、验证日志与完整对照表。

**Interfaces:**
- Consumes: Task 1 冻结 baseline、同一份 Java 基准、Task 2/3 已审查生产实现与测试。
- Produces: 28 行 `backend,shape,width,baseline ns/op,candidate ns/op,error,B/op` 对照、明确范围的验证结果及最终文档。

- [ ] **Step 1: 重建候选，运行同参数 JMH。**

```bash
./gradlew :wow-benchmarks:jmhJar --console=plain
python3 build/aggregation-compiler/run-benchmark.py candidate
```

保存完整日志，按三项参数 join 两个 JSON。检查无遗漏、失败或非有限的主要分数；同时比较 mean/error 与 `gc.alloc.rate.norm`。宽度 1 与 16 分别报告，不能只选择最大改善行。初次完整矩阵结束前不并发其他验证。

- [ ] **Step 2: 处理真实测量疑点。** 如果 Count 对照、Mongo known_metric 对照或其他场景存在误差不重叠的变慢，使用冻结的 baseline/candidate jar 对该 backend/shape/width 成对复测，保留相同迭代与 GC 参数并分别存日志。无疑点不额外复测。若改善不足或确认回退，向控制器报告测量与最小修正方向；由实现任务子代理修复并审查后再覆盖相关验证，不自行扩大生产改动范围。

- [ ] **Step 3: 运行与两编译器边界匹配的 Gradle 验证。** 不重复仅仅已通过且没有新改动的聚焦测试；这里扩大到相关查询合同、Detekt 与真实查询集成。每条命令记录输出和 XML 的 tests/failures/errors/skipped：

```bash
./gradlew :wow-query:check :wow-mongo:detekt :wow-elasticsearch:detekt --console=plain
./gradlew :wow-mongo:test --tests 'me.ahoo.wow.mongo.query.*' :wow-elasticsearch:test --tests 'me.ahoo.wow.elasticsearch.query.*' --console=plain
./gradlew :wow-mongo:integrationTest --tests 'me.ahoo.wow.mongo.query.*' :wow-elasticsearch:integrationTest --tests 'me.ahoo.wow.elasticsearch.query.*' --console=plain
```

现有 Testcontainers 环境可用，实际服务状态仍由命令确认。区分实现失败与环境失败；不静默跳过。Spring/WebFlux 未改动，第一阶段已有对应验证，本阶段不重复执行无新增风险的 HTTP 检查。

- [ ] **Step 4: 写入实测结果并提交。** 在本计划追加执行结果，在关联设计更新状态与结果。写出代表性宽度的收益、全部 28 行产物位置、对照场景结果、误差与限制、测试实际数量、复测原因。记录生产源码与冻结 jar 的提交/校验值，声明微基准只覆盖编译。标记完成的步骤；未实现项目不勾选。执行 `git diff --check` 和本地 Markdown 链接/代码围栏检查后，提交两份文档，信息 `docs(query): record aggregation compiler validation`。

全部任务完成后由控制器进行一次覆盖本阶段 `f3be596f0..HEAD` 的最终审查。把结果留在当前工作区，不自行合并、推送或发布。
