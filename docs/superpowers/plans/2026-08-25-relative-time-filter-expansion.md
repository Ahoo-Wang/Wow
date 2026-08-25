# RelativeTimeFilter 自然周期扩展 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 新增五种由服务端时钟求值的自然周期过滤器，并将相对时间公共属性提升到 `RelativeTimeFilter`，同时保持现有 wire 行为不变。

**架构：** 将 `YESTERDAY`、`NEXT_MONTH`、`LAST_YEAR`、`THIS_YEAR`、`NEXT_YEAR` 建模为显式 `FilterExpression` 子类型。`FilterNormalizer` 将其展开为现有的下界包含、上界不包含范围谓词；MongoDB 编译保持不变，Elasticsearch 字段解析新增 RANGE 能力要求。

**技术栈：** Kotlin 2.4.10、Java 17 `java.time`、Jackson 多态序列化、JUnit Jupiter 6、FluentAssert、JSON Schema Draft 7、Gradle、VitePress。

**设计规范：** `docs/superpowers/specs/2026-08-25-relative-time-filter-expansion-design.md`

## 全局约束

- 仅增加 `YESTERDAY`、`NEXT_MONTH`、`LAST_YEAR`、`THIS_YEAR`、`NEXT_YEAR`；不增加滚动时长、截至当前、季度或财务周期过滤器。
- 保持 `RelativeTimeFilter` 为密封接口，并在接口声明 `field`、`zoneId`、`datePattern`、`dateFormatter`。
- `dateFormatter` 继续仅用于 JVM，并标注 `@get:JsonIgnore`；保留所有现有相对时间 JSON 字段与默认值。
- 使用一次捕获的 `Clock.instant()` 和过滤器有效时区，将每个新周期规范化为 `GTE start AND LT end`。
- 不向已废弃的 `Operator`、`Condition` 或 `LegacyConditionAdapter` 增加条目。
- 不增加依赖、配置、后端专属选项或通用自然周期抽象。
- 保持框架路径响应式；本变更不引入阻塞调用。
- Kotlin 测试使用 FluentAssert `.assert()`。
- 同步更新中英文查询文档。

## 文件映射

- `wow-api/src/main/kotlin/me/ahoo/wow/api/query/FilterExpression.kt`：公开操作符与 Jackson 子类型注册。
- `wow-api/src/main/kotlin/me/ahoo/wow/api/query/RelativeTimeFilters.kt`：公共接口合同与五种新表达式类型。
- `wow-api/src/test/kotlin/me/ahoo/wow/api/query/FilterExpressionTest.kt`：JSON、接口属性与校验覆盖。
- `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/FilterDsl.kt`：五个 Kotlin DSL 入口。
- `wow-query/src/main/kotlin/me/ahoo/wow/query/FilterNormalizer.kt`：自然周期展开与共享日历辅助函数。
- `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/FilterDslTest.kt`：DSL 构造覆盖。
- `wow-query/src/test/kotlin/me/ahoo/wow/query/FilterNormalizerTest.kt`：固定时钟、时区与闰年范围覆盖。
- `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolver.kt`：RANGE 字段能力解析。
- `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolverTest.kt`：父路径 RANGE 映射覆盖。
- `schema/query/v2/filter-expression.schema.json`：公开 JSON Schema 联合与对象形态。
- `wow-schema/src/test/kotlin/me/ahoo/wow/schema/typed/query/FilterExpressionDefinitionProviderTest.kt`：静态 Schema 发布与校验覆盖。
- `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`：生成的 OpenAPI 兼容快照。
- `documentation/docs/zh/guide/query.md`：中文操作符与 DSL 文档。
- `documentation/docs/en/guide/query.md`：英文操作符与 DSL 文档。

---

### Task 1：发布五种 API 表达式类型

**文件：**
- 修改：`wow-api/src/main/kotlin/me/ahoo/wow/api/query/FilterExpression.kt:42-142`
- 修改：`wow-api/src/main/kotlin/me/ahoo/wow/api/query/RelativeTimeFilters.kt:35-198`
- 测试：`wow-api/src/test/kotlin/me/ahoo/wow/api/query/FilterExpressionTest.kt`

**接口：**
- 输入：现有 `LogicalField`、`FilterExpression`、`FilterOperator`、`ZoneId`、`DateTimeFormatter` 行为。
- 输出：`YesterdayFilter`、`NextMonthFilter`、`LastYearFilter`、`ThisYearFilter`、`NextYearFilter`，以及 `RelativeTimeFilter.field: LogicalField`、`RelativeTimeFilter.zoneId: String?`。

- [ ] **步骤 1：添加失败的 API 合同测试**

向 `FilterExpressionTest` 添加以下测试：

```kotlin
@Test
fun `new relative calendar filters should round trip through the common contract`() {
    val field = LogicalField("state.createTime")
    val filters = listOf<RelativeTimeFilter>(
        YesterdayFilter(field, "UTC", "yyyy-MM-dd"),
        NextMonthFilter(field, "UTC", "yyyy-MM-dd"),
        LastYearFilter(field, "UTC", "yyyy-MM-dd"),
        ThisYearFilter(field, "UTC", "yyyy-MM-dd"),
        NextYearFilter(field, "UTC", "yyyy-MM-dd"),
    )

    filters.forEach { filter ->
        filter.field.assert().isEqualTo(field)
        filter.zoneId.assert().isEqualTo("UTC")
        filter.datePattern.assert().isEqualTo("yyyy-MM-dd")
        filter.resolvedDateFormatter().assert().isNotNull()
        val json = jsonMapper.writeValueAsString(filter)
        json.contains("dateFormatter").assert().isFalse()
        jsonMapper.readValue(json, FilterExpression::class.java).assert().isEqualTo(filter)
    }

    val runtimeFilter: RelativeTimeFilter = YesterdayFilter(
        field = field,
        dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE,
    )
    jsonMapper.writeValueAsString(runtimeFilter).contains("dateFormatter").assert().isFalse()
}

@Test
fun `relative calendar filters should reject invalid common configuration`() {
    val field = LogicalField("state.createTime")

    org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
        YesterdayFilter(field, zoneId = "")
    }
    org.junit.jupiter.api.assertThrows<java.time.DateTimeException> {
        NextMonthFilter(field, zoneId = "Not/AZone")
    }
    org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
        ThisYearFilter(field, datePattern = "invalid")
    }
}
```

- [ ] **步骤 2：运行 API 测试并确认失败**

运行：

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.FilterExpressionTest"
```

预期：`compileTestKotlin` 因五种新过滤器类以及公共 `field`、`zoneId` 属性未定义而失败。

- [ ] **步骤 3：添加操作符与 Jackson 子类型注册**

在 `FilterOperator` 的 `EARLIER_DAYS` 后追加以下名称，避免改变现有枚举序号：

```kotlin
YESTERDAY,
NEXT_MONTH,
LAST_YEAR,
THIS_YEAR,
NEXT_YEAR,
```

向 `@JsonSubTypes` 添加以下条目：

```kotlin
JsonSubTypes.Type(YesterdayFilter::class, name = "YESTERDAY"),
JsonSubTypes.Type(NextMonthFilter::class, name = "NEXT_MONTH"),
JsonSubTypes.Type(LastYearFilter::class, name = "LAST_YEAR"),
JsonSubTypes.Type(ThisYearFilter::class, name = "THIS_YEAR"),
JsonSubTypes.Type(NextYearFilter::class, name = "NEXT_YEAR"),
```

- [ ] **步骤 4：提升接口公共属性**

将 `RelativeTimeFilter` 声明替换为：

```kotlin
sealed interface RelativeTimeFilter : FilterExpression {
    val field: LogicalField
    val zoneId: String?
    val datePattern: String?

    @get:JsonIgnore
    val dateFormatter: DateTimeFormatter?

    fun resolvedDateFormatter(): DateTimeFormatter? = dateFormatter ?: datePattern.toDateFormatter()
}
```

在所有现有 `RelativeTimeFilter` data class 中，将公共构造属性改为以下声明；`time` 与 `days` 保持不变：

```kotlin
override val field: LogicalField,
override val zoneId: String? = null,
override val datePattern: String? = null,
@get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
```

- [ ] **步骤 5：添加五个最小过滤器类**

将以下类添加到 `RelativeTimeFilters.kt` 对应的日、月、年分组：

```kotlin
@JsonTypeName("YESTERDAY")
data class YesterdayFilter(
    override val field: LogicalField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.YESTERDAY

    init {
        zoneId.requireZoneId()
        datePattern.toDateFormatter()
    }
}

@JsonTypeName("NEXT_MONTH")
data class NextMonthFilter(
    override val field: LogicalField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.NEXT_MONTH

    init {
        zoneId.requireZoneId()
        datePattern.toDateFormatter()
    }
}

@JsonTypeName("LAST_YEAR")
data class LastYearFilter(
    override val field: LogicalField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.LAST_YEAR

    init {
        zoneId.requireZoneId()
        datePattern.toDateFormatter()
    }
}

@JsonTypeName("THIS_YEAR")
data class ThisYearFilter(
    override val field: LogicalField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.THIS_YEAR

    init {
        zoneId.requireZoneId()
        datePattern.toDateFormatter()
    }
}

@JsonTypeName("NEXT_YEAR")
data class NextYearFilter(
    override val field: LogicalField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.NEXT_YEAR

    init {
        zoneId.requireZoneId()
        datePattern.toDateFormatter()
    }
}
```

- [ ] **步骤 6：运行 API 检查**

运行：

```bash
./gradlew :wow-api:check
```

预期：全部 `wow-api` 测试与静态检查通过。

- [ ] **步骤 7：提交 API 合同**

```bash
git add wow-api/src/main/kotlin/me/ahoo/wow/api/query/FilterExpression.kt \
  wow-api/src/main/kotlin/me/ahoo/wow/api/query/RelativeTimeFilters.kt \
  wow-api/src/test/kotlin/me/ahoo/wow/api/query/FilterExpressionTest.kt
git commit -m "feat(query): add relative calendar filter contracts"
```

---

### Task 2：添加 DSL 构造与固定时钟规范化

**文件：**
- 修改：`wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/FilterDsl.kt:206-234`
- 修改：`wow-query/src/main/kotlin/me/ahoo/wow/query/FilterNormalizer.kt:16-200`
- 测试：`wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/FilterDslTest.kt`
- 测试：`wow-query/src/test/kotlin/me/ahoo/wow/query/FilterNormalizerTest.kt`

**接口：**
- 输入：任务 1 的五种过滤器类与 `RelativeTimeFilter` 公共属性。
- 输出：`String.yesterday`、`String.nextMonth`、`String.lastYear`、`String.thisYear`、`String.nextYear`，以及规范化后的 `[start, end)` 范围表达式。

- [ ] **步骤 1：添加失败的 DSL 测试**

向 `FilterDslTest` 添加以下测试：

```kotlin
@Test
fun `should build extended relative calendar filters`() {
    val field = LogicalField("createdAt")
    val expression = filter {
        "createdAt".yesterday(ZoneOffset.UTC, "yyyy-MM-dd")
        "createdAt".nextMonth(ZoneOffset.UTC, "yyyy-MM-dd")
        "createdAt".lastYear(ZoneOffset.UTC, "yyyy-MM-dd")
        "createdAt".thisYear(ZoneOffset.UTC, "yyyy-MM-dd")
        "createdAt".nextYear(ZoneOffset.UTC, "yyyy-MM-dd")
    } as AndFilter

    expression.operands.assert().containsExactly(
        YesterdayFilter(field, ZoneOffset.UTC.id, "yyyy-MM-dd"),
        NextMonthFilter(field, ZoneOffset.UTC.id, "yyyy-MM-dd"),
        LastYearFilter(field, ZoneOffset.UTC.id, "yyyy-MM-dd"),
        ThisYearFilter(field, ZoneOffset.UTC.id, "yyyy-MM-dd"),
        NextYearFilter(field, ZoneOffset.UTC.id, "yyyy-MM-dd"),
    )
}
```

- [ ] **步骤 2：运行 DSL 测试并确认失败**

运行：

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.dsl.FilterDslTest.should build extended relative calendar filters"
```

预期：`compileTestKotlin` 因五个 DSL 方法尚不存在而失败。

- [ ] **步骤 3：实现五个 DSL 方法**

导入五种过滤器类型，并在现有日/月方法附近添加以下方法：

```kotlin
fun String.yesterday(zoneId: ZoneId? = null, datePattern: String? = null) =
    add(YesterdayFilter(field(this), zoneId?.id, datePattern))

fun String.nextMonth(zoneId: ZoneId? = null, datePattern: String? = null) =
    add(NextMonthFilter(field(this), zoneId?.id, datePattern))

fun String.lastYear(zoneId: ZoneId? = null, datePattern: String? = null) =
    add(LastYearFilter(field(this), zoneId?.id, datePattern))

fun String.thisYear(zoneId: ZoneId? = null, datePattern: String? = null) =
    add(ThisYearFilter(field(this), zoneId?.id, datePattern))

fun String.nextYear(zoneId: ZoneId? = null, datePattern: String? = null) =
    add(NextYearFilter(field(this), zoneId?.id, datePattern))
```

- [ ] **步骤 4：运行 DSL 测试并确认通过**

再次运行步骤 2 的命令。

预期：指定测试通过。

- [ ] **步骤 5：添加失败的固定时钟范围测试**

向 `FilterNormalizerTest` 添加以下测试：

```kotlin
@Test
fun `should expand extended calendar filters in their local zone across leap year`() {
    val field = LogicalField("createdAt")
    val zoneId = "Asia/Shanghai"
    val localNormalizer = FilterNormalizer(
        clock = Clock.fixed(Instant.parse("2024-02-29T12:00:00Z"), ZoneOffset.UTC),
        defaultZoneId = ZoneOffset.UTC,
        defaultDeletionState = null,
    )
    val cases = listOf(
        YesterdayFilter(field, zoneId) to
            (Instant.parse("2024-02-27T16:00:00Z") to Instant.parse("2024-02-28T16:00:00Z")),
        NextMonthFilter(field, zoneId) to
            (Instant.parse("2024-02-29T16:00:00Z") to Instant.parse("2024-03-31T16:00:00Z")),
        LastYearFilter(field, zoneId) to
            (Instant.parse("2022-12-31T16:00:00Z") to Instant.parse("2023-12-31T16:00:00Z")),
        ThisYearFilter(field, zoneId) to
            (Instant.parse("2023-12-31T16:00:00Z") to Instant.parse("2024-12-31T16:00:00Z")),
        NextYearFilter(field, zoneId) to
            (Instant.parse("2024-12-31T16:00:00Z") to Instant.parse("2025-12-31T16:00:00Z")),
    )

    cases.forEach { (relative, expected) ->
        val normalized = localNormalizer.normalize(relative) as AndFilter
        val start = normalized.operands[0] as GreaterThanOrEqualFilter
        val end = normalized.operands[1] as LessThanFilter
        start.field.assert().isEqualTo(field)
        end.field.assert().isEqualTo(field)
        start.value.asLong().assert().isEqualTo(expected.first.toEpochMilli())
        end.value.asLong().assert().isEqualTo(expected.second.toEpochMilli())
    }
}
```

向 `should expand every relative time filter` 的列表添加以下条目：

```kotlin
YesterdayFilter(field, "UTC"),
NextMonthFilter(field, "UTC"),
LastYearFilter(field, "UTC"),
ThisYearFilter(field, "UTC"),
NextYearFilter(field, "UTC"),
```

- [ ] **步骤 6：运行规范化测试并确认失败**

运行：

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.FilterNormalizerTest.should expand extended calendar filters in their local zone across leap year"
```

预期：测试失败，因为新过滤器保持未展开状态，没有变为 `AndFilter` 范围。

- [ ] **步骤 7：使用接口公共属性实现规范化**

在 `FilterNormalizer.kt` 导入 `YesterdayFilter`、`NextMonthFilter`、`LastYearFilter`、`ThisYearFilter`、`NextYearFilter`、`RelativeTimeFilter`。

向 `expandRelativeTime` 添加以下准确分支，同时保留现有 `BeforeTodayFilter`、`RecentDaysFilter`、`EarlierDaysFilter` 分支：

```kotlin
is YesterdayFilter -> expression.dayRange(now, -1)
is TodayFilter -> expression.dayRange(now, 0)
is TomorrowFilter -> expression.dayRange(now, 1)
is LastWeekFilter -> expression.weekRange(now, -1)
is ThisWeekFilter -> expression.weekRange(now, 0)
is NextWeekFilter -> expression.weekRange(now, 1)
is LastMonthFilter -> expression.monthRange(now, -1)
is ThisMonthFilter -> expression.monthRange(now, 0)
is NextMonthFilter -> expression.monthRange(now, 1)
is LastYearFilter -> expression.yearRange(now, -1)
is ThisYearFilter -> expression.yearRange(now, 0)
is NextYearFilter -> expression.yearRange(now, 1)
```

用以下公共辅助函数替换日/周/月的类型专属扩展重载，并添加年份辅助函数：

```kotlin
private fun RelativeTimeFilter.dayRange(now: Instant, offset: Long): FilterExpression =
    range(
        field,
        today(now, zoneId).plusDays(offset).atStartOfDay(),
        today(now, zoneId).plusDays(offset + 1).atStartOfDay(),
        zone(zoneId),
        resolvedDateFormatter(),
    )

private fun RelativeTimeFilter.weekRange(now: Instant, offset: Long): FilterExpression =
    weekRange(field, today(now, zoneId), offset, zone(zoneId), resolvedDateFormatter())

private fun RelativeTimeFilter.monthRange(now: Instant, offset: Long): FilterExpression =
    monthRange(field, today(now, zoneId), offset, zone(zoneId), resolvedDateFormatter())

private fun RelativeTimeFilter.yearRange(now: Instant, offset: Long): FilterExpression {
    val start = today(now, zoneId).withDayOfYear(1).plusYears(offset)
    return range(
        field,
        start.atStartOfDay(),
        start.plusYears(1).atStartOfDay(),
        zone(zoneId),
        resolvedDateFormatter(),
    )
}
```

删除旧的 `TodayFilter.dayRange`、`TomorrowFilter.dayRange`、三个类型专属 `weekRange` 和两个类型专属 `monthRange` 重载。保留现有按字段工作的 `weekRange`、`monthRange`、`range`、`instantNode`、`today`、`zone`。

- [ ] **步骤 8：运行查询模块检查**

运行：

```bash
./gradlew :wow-query:check
```

预期：全部查询 DSL 与规范化测试通过。

- [ ] **步骤 9：提交 DSL 与规范化实现**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/FilterDsl.kt \
  wow-query/src/main/kotlin/me/ahoo/wow/query/FilterNormalizer.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/FilterDslTest.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query/FilterNormalizerTest.kt
git commit -m "feat(query): normalize relative calendar filters"
```

---

### Task 3：按 RANGE 解析 Elasticsearch 字段

**文件：**
- 修改：`wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolver.kt:167-226`
- 测试：`wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolverTest.kt:154-211`

**接口：**
- 输入：任务 1 的五种过滤器类型及其 `copy(field = ...)` 方法。
- 输出：五种新过滤器均支持父路径的 `ElasticsearchFieldUsage.RANGE` 解析。

- [ ] **步骤 1：添加失败的父路径映射测试**

导入五种过滤器类与 `RelativeTimeFilter`，然后向 `ElasticsearchIndexMappingResolverTest` 添加以下测试：

```kotlin
@Test
fun `should resolve extended relative calendar fields as ranges from parent`() {
    val mapping = ElasticsearchIndexMapping.from(INDEX, textWithKeyword())
    val filters = listOf<RelativeTimeFilter>(
        YesterdayFilter(LogicalField("age")),
        NextMonthFilter(LogicalField("age")),
        LastYearFilter(LogicalField("age")),
        ThisYearFilter(LogicalField("age")),
        NextYearFilter(LogicalField("age")),
    )

    filters.forEach { filter ->
        val resolved = mapping.resolve(filter, "state") as RelativeTimeFilter
        resolved.field.value.assert().isEqualTo("state.age")
    }
}
```

- [ ] **步骤 2：运行映射测试并确认失败**

运行：

```bash
./gradlew :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolverTest.should resolve extended relative calendar fields as ranges from parent"
```

预期：断言失败，因为未解析过滤器仍保留 `age`，没有解析为 `state.age`。

- [ ] **步骤 3：添加五个 RANGE 解析分支**

在 `ElasticsearchIndexMapping.resolveTyped` 现有相对时间分支附近添加：

```kotlin
is YesterdayFilter -> filter.copy(field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE))
is NextMonthFilter -> filter.copy(field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE))
is LastYearFilter -> filter.copy(field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE))
is ThisYearFilter -> filter.copy(field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE))
is NextYearFilter -> filter.copy(field = filter.field.resolve(parent, ElasticsearchFieldUsage.RANGE))
```

不要修改 `AbstractElasticsearchFilterConverter`：`FilterNormalizer` 会在转换前移除这些节点。

- [ ] **步骤 4：运行 Elasticsearch 检查**

运行：

```bash
./gradlew :wow-elasticsearch:check
```

预期：新映射测试与全部现有 Elasticsearch 单元测试通过。

- [ ] **步骤 5：提交 Elasticsearch 字段解析**

```bash
git add wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolver.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolverTest.kt
git commit -m "feat(elasticsearch): resolve relative calendar filter fields"
```

---

### Task 4：发布 Schema、OpenAPI 与文档

**文件：**
- 修改：`schema/query/v2/filter-expression.schema.json:22-257`
- 测试：`wow-schema/src/test/kotlin/me/ahoo/wow/schema/typed/query/FilterExpressionDefinitionProviderTest.kt`
- 修改：`wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`
- 修改：`documentation/docs/zh/guide/query.md`
- 修改：`documentation/docs/en/guide/query.md`

**接口：**
- 输入：任务 1 的五个公开 `op` 名称与 JSON 形态。
- 输出：有效 Draft 7 Schema、生成的 OpenAPI 联合成员与双语用户文档。

- [ ] **步骤 1：添加失败的静态 Schema 测试**

向 `FilterExpressionDefinitionProviderTest` 添加以下测试：

```kotlin
@Test
fun `filter schema should publish extended relative calendar operators`() {
    val schemaDocument = WowSchemaLoader.load(FilterExpression::class.java)
    val definitions = schemaDocument.path("definitions")
    val expected = linkedMapOf(
        "yesterday" to "YESTERDAY",
        "nextMonth" to "NEXT_MONTH",
        "lastYear" to "LAST_YEAR",
        "thisYear" to "THIS_YEAR",
        "nextYear" to "NEXT_YEAR",
    )

    listOf("filterExpression", "elementPredicate").forEach { unionName ->
        val references = definitions.path(unionName).path("oneOf").toList()
            .map { it.path("\$ref").stringValue() }
        expected.keys.forEach { name -> references.assert().contains("#/definitions/$name") }
    }

    val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7)
        .getSchema(schemaDocument)
    val mapper = JsonMapper.builder().build()
    expected.values.forEach { operator ->
        val filter = mapper.readTree(
            """{"op":"$operator","field":"state.createdAt","zoneId":"UTC","datePattern":"yyyy-MM-dd"}""",
        )
        schema.validate(filter).assert().isEmpty()
    }
}
```

- [ ] **步骤 2：运行 Schema 测试并确认失败**

运行：

```bash
./gradlew :wow-schema:test --tests "me.ahoo.wow.schema.typed.query.FilterExpressionDefinitionProviderTest.filter schema should publish extended relative calendar operators"
```

预期：测试因五个联合引用与定义缺失而失败。

- [ ] **步骤 3：扩展两个 Schema 联合**

向 `definitions.filterExpression.oneOf` 与 `definitions.elementPredicate.oneOf` 都添加以下引用：

```json
{ "$ref": "#/definitions/yesterday" },
{ "$ref": "#/definitions/nextMonth" },
{ "$ref": "#/definitions/lastYear" },
{ "$ref": "#/definitions/thisYear" },
{ "$ref": "#/definitions/nextYear" }
```

添加以下定义别名：

```json
"yesterday": { "$ref": "#/definitions/yesterdayShape" },
"nextMonth": { "$ref": "#/definitions/nextMonthShape" },
"lastYear": { "$ref": "#/definitions/lastYearShape" },
"thisYear": { "$ref": "#/definitions/thisYearShape" },
"nextYear": { "$ref": "#/definitions/nextYearShape" },
```

在现有相对时间形态附近添加以下显式对象形态：

```json
"yesterdayShape": { "type": "object", "properties": { "op": { "enum": ["YESTERDAY"] }, "field": { "$ref": "#/definitions/logicalField" }, "zoneId": { "type": "string", "minLength": 1 }, "datePattern": { "type": "string", "minLength": 1 } }, "required": ["op", "field"], "additionalProperties": false },
"nextMonthShape": { "type": "object", "properties": { "op": { "enum": ["NEXT_MONTH"] }, "field": { "$ref": "#/definitions/logicalField" }, "zoneId": { "type": "string", "minLength": 1 }, "datePattern": { "type": "string", "minLength": 1 } }, "required": ["op", "field"], "additionalProperties": false },
"lastYearShape": { "type": "object", "properties": { "op": { "enum": ["LAST_YEAR"] }, "field": { "$ref": "#/definitions/logicalField" }, "zoneId": { "type": "string", "minLength": 1 }, "datePattern": { "type": "string", "minLength": 1 } }, "required": ["op", "field"], "additionalProperties": false },
"thisYearShape": { "type": "object", "properties": { "op": { "enum": ["THIS_YEAR"] }, "field": { "$ref": "#/definitions/logicalField" }, "zoneId": { "type": "string", "minLength": 1 }, "datePattern": { "type": "string", "minLength": 1 } }, "required": ["op", "field"], "additionalProperties": false },
"nextYearShape": { "type": "object", "properties": { "op": { "enum": ["NEXT_YEAR"] }, "field": { "$ref": "#/definitions/logicalField" }, "zoneId": { "type": "string", "minLength": 1 }, "datePattern": { "type": "string", "minLength": 1 } }, "required": ["op", "field"], "additionalProperties": false },
```

- [ ] **步骤 4：运行 Schema 检查**

运行：

```bash
./gradlew :wow-schema:check
```

预期：静态 Schema 测试与全部现有 Schema 测试通过。

- [ ] **步骤 5：验证并更新 OpenAPI 快照**

先证明已提交快照已过期：

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest.generated openapi should match example domain compatibility snapshot"
```

预期：失败信息显示生成的 OpenAPI 新增了五种过滤器变体。

使用仓库的显式更新模式更新兼容快照：

```bash
./gradlew -Dwow.snapshot.update=true :wow-openapi:test \
  --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest"
```

检查差异，要求只有 `example-domain-openapi.snapshot.json` 发生语义变化，路由合同快照必须不变。随后关闭更新模式重新运行：

```bash
./gradlew :wow-openapi:test --tests "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest"
```

预期：全部兼容快照测试通过。

- [ ] **步骤 6：更新中英文查询文档**

在 `documentation/docs/zh/guide/query.md` 的相对时间操作符行发布：

```text
TODAY、YESTERDAY、BEFORE_TODAY、TOMORROW、THIS_WEEK、NEXT_WEEK、LAST_WEEK、THIS_MONTH、NEXT_MONTH、LAST_MONTH、LAST_YEAR、THIS_YEAR、NEXT_YEAR、RECENT_DAYS、EARLIER_DAYS
```

在现有 `recentDays` 示例附近添加：

```kotlin
"state.createTime".yesterday(ZoneId.of("Asia/Shanghai"))
"state.createTime".nextMonth(ZoneId.of("Asia/Shanghai"))
"state.createTime".thisYear(ZoneId.of("Asia/Shanghai"))
```

在 `documentation/docs/en/guide/query.md` 发布以下准确的相对时间操作符列表：

```text
TODAY, YESTERDAY, BEFORE_TODAY, TOMORROW, THIS_WEEK, NEXT_WEEK, LAST_WEEK, THIS_MONTH, NEXT_MONTH, LAST_MONTH, LAST_YEAR, THIS_YEAR, NEXT_YEAR, RECENT_DAYS, EARLIER_DAYS
```

向英文 DSL 示例添加以下代码，不翻译标识符：

```kotlin
"state.createTime".yesterday(ZoneId.of("Asia/Shanghai"))
"state.createTime".nextMonth(ZoneId.of("Asia/Shanghai"))
"state.createTime".thisYear(ZoneId.of("Asia/Shanghai"))
```

- [ ] **步骤 7：构建文档**

运行：

```bash
cd documentation
pnpm install --frozen-lockfile
pnpm docs:build
```

预期：VitePress 构建完成，无断链或构建错误；继续前返回仓库根目录。

- [ ] **步骤 8：运行完整相关验证门槛**

运行：

```bash
./gradlew :wow-api:check :wow-query:check :wow-mongo:check \
  :wow-elasticsearch:check :wow-webflux:check \
  :wow-schema:check :wow-openapi:check
./gradlew detekt
git diff --check
```

预期：所有 Gradle 任务成功，Detekt 无违规，`git diff --check` 无空白错误。

- [ ] **步骤 9：复核范围并提交公开合同**

运行：

```bash
git status --short
git diff --stat
```

预期：变更文件仅限本计划列出的文件；已废弃 `Condition` API、MongoDB converter、依赖、配置和生成构建输出均无变化。

提交：

```bash
git add schema/query/v2/filter-expression.schema.json \
  wow-schema/src/test/kotlin/me/ahoo/wow/schema/typed/query/FilterExpressionDefinitionProviderTest.kt \
  wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json \
  documentation/docs/zh/guide/query.md \
  documentation/docs/en/guide/query.md
git commit -m "feat(query): publish relative calendar filter contracts"
```

- [ ] **步骤 10：确认实施分支干净**

运行：

```bash
git status --short
git log -4 --oneline
```

预期：没有未提交文件，最近四个实施提交与本计划的四条提交信息一致。
