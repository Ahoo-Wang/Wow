# 分桶体验（Phase 4：空桶补齐 + 缺失桶）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为聚合分组交付 `DateHistogram.dense`（窗口内补零时间序列）、`Terms.missingKey`（缺失值哨兵桶）与 TERMS 截断语义文档澄清。

**Architecture:** AST 尾参扩展（wire 纯增量）→ wow-query 共享网格算术（`DenseDateGrid` + `EmptyAggregationValues`，双后端复用）→ Mongo 索引空间 `$densify` 服务端管道 + `$ifNull` 哨兵键 → ES keyword 运行时字段 + pager 跨页流式补零 → TCK 跨后端契约 + 中英文档。

**Tech Stack:** Kotlin（JVM 17）、MongoDB Java Driver 5.8（`Aggregates.densify`/`DensifyRange`）、Elasticsearch Java Client（runtime fields、composite）、JUnit 5 + fluent-assert、Reactor。

**Spec:** `documentation/designs/2026-09-12-aggregation-reporting-epic-design.md` 的「Phase 4：分桶体验」章节（252-342 行）。执行者须同时阅读该章节与本计划。

## Global Constraints

- 兼容边界**仅 REST/wire**：新字段一律**尾参 + 默认值**，默认值序列化省略（`NON_NULL`/`NON_FALSE`），无新能力的查询 JSON 字节不变；不做二进制兼容承诺。
- 禁用 `@JvmOverloads`。
- 存储版本要求**仅文档注明，不做运行时门控/降级**：MongoDB ≥ 5.1（`$densify`/`$dateDiff`）；ES runtime fields 为既有依赖。
- 核心查询链路保持 Reactor 响应式，禁止阻塞。
- 测试断言用 fluent-assert `.assert()`（非 AssertJ `assertThat`）。
- 每任务 TDD：先写失败测试 → 亲眼确认 RED 失败在预期断言 → 最小实现 → GREEN → 提交。
- detekt 必须通过（注意 ImportOrdering）。
- 分组桶补齐语义窗口 = **[首个实际桶键, 末个实际桶键]**，仅补内部间隙；补齐行指标空语义：Count/DistinctCount→0、Numeric/Percentile/Any→null、Derived 按空值求值；补齐行参与 sort/having/limit。

---

## File Structure

| 动作 | 文件 | 职责 |
|---|---|---|
| Modify | `wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt` | Terms.missingKey / DateHistogram.dense + 构造期校验 |
| Modify | `wow-api/src/test/kotlin/me/ahoo/wow/api/query/AggregationQueryTest.kt` | AST 校验 + wire 形状 |
| Create | `wow-query/src/main/kotlin/me/ahoo/wow/query/aggregation/DenseDateGrid.kt` | 时区安全网格算术（anchor/indexOf/keyOf/keysBetween） |
| Create | `wow-query/src/main/kotlin/me/ahoo/wow/query/aggregation/EmptyAggregationValues.kt` | 指标空值映射 + 派生空值求值器（自 wow-mongo 提升） |
| Modify | `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidation.kt` | missingKey 字段限制 |
| Modify | `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt` | terms/dateHistogram 尾参 |
| Modify | `wow-mongo/.../mongo/query/aggregation/MongoAggregationCompiler.kt` | $ifNull 哨兵键 + 索引空间 densify 管道 |
| Modify | `wow-mongo/.../mongo/query/AbstractMongoQueryBackend.kt` | emptySummary 委托共享求值器 |
| Modify | `wow-elasticsearch/.../query/aggregation/ElasticsearchAggregationCompiler.kt` | missingKey 运行时字段 + DenseBucketPlan |
| Modify | `wow-elasticsearch/.../query/aggregation/ElasticsearchAggregationPlan.kt`（若计划类型定义于此；否则在编译器同包） | 计划类型追加 dense |
| Modify | `wow-elasticsearch/.../query/aggregation/ElasticsearchAggregationPager.kt` | 跨页流式补零 |
| Modify | `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryBackendSpec.kt` | 7 个契约场景 |
| Modify | `documentation/docs/{zh,en}/guide/query/aggregation-query.md`、`snapshot-aggregation.md` | 用户文档 |

---

### Task 1: AST — `Terms.missingKey` + `DateHistogram.dense`

**Files:**
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt`
- Test: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/AggregationQueryTest.kt`

**Interfaces:**
- Consumes: 既有 `AggregationGroup`/`AggregationQuery`。
- Produces: `AggregationGroup.Terms(field, alias, missingKey: String? = null)`；`AggregationGroup.DateHistogram(field, alias, unit, timeZone = "UTC", dense: Boolean = false)`。错误信息 verbatim：`"terms missingKey must not be blank."`、`"dense requires DATE_HISTOGRAM to be the only groupBy."`（Task 3/5/6/7/8/9 依赖这两个属性名）。

- [ ] **Step 1: 写失败测试**（追加到 `AggregationQueryTest.kt`，沿用该文件的 `configuredMapper`/`assertThrows`/fluent-assert 习语）

```kotlin
@Test
fun `terms should reject blank missing key`() {
    val error = assertThrows<IllegalArgumentException> {
        AggregationGroup.Terms(QueryField("productId"), "product", missingKey = " ")
    }
    error.message.assert().isEqualTo("terms missingKey must not be blank.")
}

@Test
fun `dense should require date histogram to be the only group by`() {
    val error = assertThrows<IllegalArgumentException> {
        AggregationQuery(
            groupBy = listOf(
                AggregationGroup.DateHistogram(QueryField("createdAt"), "day", AggregationDateUnit.DAY, dense = true),
                AggregationGroup.Terms(QueryField("productId"), "product"),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
        )
    }
    error.message.assert().isEqualTo("dense requires DATE_HISTOGRAM to be the only groupBy.")
}

@Test
fun `bucket options should keep wire shapes additive`() {
    // 默认值省略：既有查询 JSON 字节不变（本断言是本任务的 wire 兼容红线）
    configuredMapper.writeValueAsString(AggregationGroup.Terms(QueryField("productId"), "product"))
        .assert().isEqualTo("""{"type":"TERMS","field":"productId","alias":"product"}""")
    configuredMapper.writeValueAsString(
        AggregationGroup.Terms(QueryField("productId"), "product", missingKey = "__missing__"),
    ).assert().isEqualTo("""{"type":"TERMS","field":"productId","alias":"product","missingKey":"__missing__"}""")
    configuredMapper.writeValueAsString(
        AggregationGroup.DateHistogram(QueryField("createdAt"), "day", AggregationDateUnit.DAY),
    ).assert().isEqualTo("""{"type":"DATE_HISTOGRAM","field":"createdAt","alias":"day","unit":"DAY","timeZone":"UTC"}""")
    configuredMapper.writeValueAsString(
        AggregationGroup.DateHistogram(QueryField("createdAt"), "day", AggregationDateUnit.DAY, dense = true),
    ).assert().isEqualTo(
        """{"type":"DATE_HISTOGRAM","field":"createdAt","alias":"day","unit":"DAY","timeZone":"UTC","dense":true}""",
    )
}

@Test
fun `bucket options should round trip through json`() {
    val json = """
        {
          "groupBy": [
            {"type": "TERMS", "field": "productName", "alias": "name", "missingKey": "UNKNOWN"},
            {"type": "DATE_HISTOGRAM", "field": "createdAt", "alias": "day", "unit": "MONTH",
             "timeZone": "Asia/Shanghai", "dense": true}
          ],
          "metrics": [{"type": "COUNT", "alias": "count"}]
        }
    """.trimIndent()
    val query = configuredMapper.readValue(json, AggregationQuery::class.java)
    (query.groupBy[0] as AggregationGroup.Terms).missingKey.assert().isEqualTo("UNKNOWN")
    (query.groupBy[1] as AggregationGroup.DateHistogram).let { dense ->
        dense.timeZone.assert().isEqualTo("Asia/Shanghai")
        dense.dense.assert().isTrue()
    }
}
```

- [ ] **Step 2: 跑测试确认 RED**

Run: `./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.AggregationQueryTest"`
Expected: 4 个新测试编译失败（`missingKey`/`dense` 参数不存在）——编译错误即 RED。

- [ ] **Step 3: 最小实现**

`AggregationQuery.kt`：

```kotlin
data class Terms(
    override val field: QueryField,
    override val alias: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val missingKey: String? = null,
) : AggregationGroup {
    init {
        requireAggregationAlias(alias)
        require(!missingKey.isNullOrBlank()) { "terms missingKey must not be blank." }
    }
}
```

```kotlin
data class DateHistogram(
    override val field: QueryField,
    override val alias: String,
    val unit: AggregationDateUnit,
    val timeZone: String = "UTC",
    @get:JsonInclude(JsonInclude.Include.NON_FALSE)
    val dense: Boolean = false,
) : AggregationGroup {
    init {
        requireAggregationAlias(alias)
        ZoneId.of(timeZone)
    }
}
```

`AggregationQuery.init` 追加（置于 `requireValidHaving` 调用之后）：

```kotlin
groupBy.forEach { group ->
    if (group is AggregationGroup.DateHistogram && group.dense) {
        require(groupBy.size == 1) { "dense requires DATE_HISTOGRAM to be the only groupBy." }
    }
}
```

- [ ] **Step 4: 跑测试确认 GREEN**

Run: `./gradlew :wow-api:test`
Expected: PASS（全类，含既有回归）。

- [ ] **Step 5: 提交**

```bash
git add wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt wow-api/src/test/kotlin/me/ahoo/wow/api/query/AggregationQueryTest.kt
git commit -m "feat(api): add terms missingKey and date histogram dense options"
```

---

### Task 2: 共享网格算术 — `DenseDateGrid` + `EmptyAggregationValues`（wow-query）

**Files:**
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/aggregation/DenseDateGrid.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/aggregation/EmptyAggregationValues.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackend.kt`（emptySummary 委托 + 删除私有 evaluateOver）
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/aggregation/DenseDateGridTest.kt`、`EmptyAggregationValuesTest.kt`（新建）

**Interfaces:**
- Consumes: `AggregationDateUnit`、`AggregationMetric`、`DerivedExpression`、`AggregationExpressionOperator`（wow-api）。
- Produces（Task 6/8 依赖，签名精确）：
  - `class DenseDateGrid(unit: AggregationDateUnit, timeZone: ZoneId)`：`val anchor: ZonedDateTime`、`fun indexOf(epochMillis: Long): Long`、`fun keyOf(index: Long): Long`、`fun keysBetween(fromMillis: Long, toMillis: Long): List<Long>`（严格介于两者之间的网格键，按流方向排序）。
  - `object EmptyAggregationValues`：`fun values(metrics: List<AggregationMetric>): LinkedHashMap<String, Any?>`、`fun DerivedExpression.evaluateOver(values: Map<String, Any?>): Double?`。

- [ ] **Step 1: 写失败测试**

`DenseDateGridTest.kt`：

```kotlin
package me.ahoo.wow.query.aggregation

import me.ahoo.wow.api.query.AggregationDateUnit
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test

class DenseDateGridTest {
    private val utc = ZoneId.of("UTC")

    private fun millis(dateTime: String): Long = Instant.parse(dateTime).toEpochMilli()
    private fun millis(zone: ZoneId, dateTime: String): Long =
        ZonedDateTime.parse(dateTime).withZoneSameLocal(zone)... // 见下：直接用 ZonedDateTime 构造

    @Test
    fun `utc day grid should step whole days`() {
        val grid = DenseDateGrid(AggregationDateUnit.DAY, utc)
        grid.indexOf(millis("2026-01-02T00:00:00Z")).assert().isEqualTo(20455L) // 自 1970-01-01 的天数
        grid.keyOf(20455L).assert().isEqualTo(millis("2026-01-02T00:00:00Z"))
        grid.keysBetween(millis("2026-01-01T00:00:00Z"), millis("2026-01-04T00:00:00Z"))
            .assert().containsExactly(millis("2026-01-02T00:00:00Z"), millis("2026-01-03T00:00:00Z"))
    }

    @Test
    fun `descending gap should emit keys in stream direction`() {
        val grid = DenseDateGrid(AggregationDateUnit.DAY, utc)
        grid.keysBetween(millis("2026-01-04T00:00:00Z"), millis("2026-01-01T00:00:00Z"))
            .assert().containsExactly(millis("2026-01-03T00:00:00Z"), millis("2026-01-02T00:00:00Z"))
    }

    @Test
    fun `adjacent buckets should have no gap keys`() {
        val grid = DenseDateGrid(AggregationDateUnit.SECOND, utc)
        grid.keysBetween(millis("2026-01-01T00:00:00Z"), millis("2026-01-01T00:00:01Z"))
            .assert().isEmpty()
    }

    @Test
    fun `shanghai month grid should align local month starts`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val grid = DenseDateGrid(AggregationDateUnit.MONTH, zone)
        val january = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val march = ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val february = ZonedDateTime.of(2026, 2, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        grid.keysBetween(january, march).assert().containsExactly(february)
        // 固定 +8 偏移下 UTC 日历步进会漂移（31 日 + 1 月 → 28 日），本地日历算术必须保持网格
        grid.keyOf(grid.indexOf(february)).assert().isEqualTo(february)
    }

    @Test
    fun `dst zone month grid should keep local midnight boundaries`() {
        val zone = ZoneId.of("America/New_York")
        val grid = DenseDateGrid(AggregationDateUnit.MONTH, zone)
        val january = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val april = ZonedDateTime.of(2026, 4, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val expected = listOf(2026, 2, 1).map {
            ZonedDateTime.of(2026, it, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        }
        grid.keysBetween(january, april).assert().containsExactly(expected.single())
    }

    @Test
    fun `week grid should anchor monday and step whole weeks`() {
        val grid = DenseDateGrid(AggregationDateUnit.WEEK, utc)
        grid.anchor.dayOfWeek.assert().isEqualTo(java.time.DayOfWeek.MONDAY)
        val week1 = Instant.parse("2026-01-05T00:00:00Z").toEpochMilli() // 周一
        val week3 = Instant.parse("2026-01-19T00:00:00Z").toEpochMilli()
        grid.keysBetween(week1, week3).assert()
            .containsExactly(Instant.parse("2026-01-12T00:00:00Z").toEpochMilli())
    }

    @Test
    fun `quarter grid should step whole quarters`() {
        val grid = DenseDateGrid(AggregationDateUnit.QUARTER, utc)
        grid.keysBetween(
            Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
            Instant.parse("2026-07-01T00:00:00Z").toEpochMilli(),
        ).assert().containsExactly(Instant.parse("2026-04-01T00:00:00Z").toEpochMilli())
    }
}
```

（注：删除示例中 `millis(zone, ...)` 私有重载——未用；保留 `millis(String)`。fluent-assert 静态导入与项目其他 wow-query 测试一致：`import me.ahoo.wow.test.assert` 系列——以 `wow-query/src/test` 既有测试的导入为准。`20455L` 为 1970-01-01 至 2026-01-02 的天数，实现时用 `java.time.temporal.ChronoUnit.DAYS.between` 校验该常量，若手算有误以程序计算值修正测试常量并注明。）

`EmptyAggregationValuesTest.kt`：

```kotlin
package me.ahoo.wow.query.aggregation

import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.QueryField
import kotlin.test.Test

class EmptyAggregationValuesTest {
    @Test
    fun `counts are zero and value metrics are null`() {
        val values = EmptyAggregationValues.values(
            listOf(
                AggregationMetric.Count("count"),
                AggregationMetric.Any(QueryField("name"), "any"),
                AggregationMetric.Numeric(
                    me.ahoo.wow.api.query.AggregationFunction.SUM,
                    me.ahoo.wow.api.query.AggregationExpression.Field(QueryField("amount")),
                    "total",
                ),
                AggregationMetric.DistinctCount(
                    me.ahoo.wow.api.query.AggregationExpression.Field(QueryField("productId")),
                    "products",
                ),
            ),
        )
        values["count"].assert().isEqualTo(0L)
        values["any"].assert().isNull()
        values["total"].assert().isNull()
        values["products"].assert().isEqualTo(0L)
    }

    @Test
    fun `derived metrics evaluate over empty values in declaration order`() {
        val values = EmptyAggregationValues.values(
            listOf(
                AggregationMetric.Count("count"),
                AggregationMetric.Derived("aov", div("count")),
                AggregationMetric.Derived("scaled", constantTimesTwo()),
            ),
        )
        values["aov"].assert().isNull() // 0 除以 0 → null
        values["scaled"].assert().isEqualTo(2.0)
    }

    private fun div(metric: String) = DerivedExpression.Binary(
        me.ahoo.wow.api.query.AggregationExpressionOperator.DIVIDE,
        DerivedExpression.MetricRef(metric),
        DerivedExpression.MetricRef(metric),
    )

    private fun constantTimesTwo() = DerivedExpression.Binary(
        me.ahoo.wow.api.query.AggregationExpressionOperator.MULTIPLY,
        DerivedExpression.Constant(1.0),
        DerivedExpression.Constant(2.0),
    }
}
```

- [ ] **Step 2: 跑测试确认 RED**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.aggregation.*"`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 最小实现**

`DenseDateGrid.kt`：

```kotlin
package me.ahoo.wow.query.aggregation

import me.ahoo.wow.api.query.AggregationDateUnit
import java.time.Instant
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.time.temporal.TemporalUnit

/**
 * Timezone-safe bucket-index arithmetic for dense date histograms.
 *
 * MongoDB `$densify` has no timezone option: month/quarter/year stepping over raw dates drifts off
 * the `$dateTrunc(timezone)` grid even for fixed offsets. Both backends therefore work in a
 * integer bucket-index space anchored at a grid-aligned local midnight (Monday for WEEK); Mongo
 * inverts indices with `$dateAdd(timezone)` and this class mirrors it with `java.time`, so the
 * grids are identical by construction.
 */
class DenseDateGrid(unit: AggregationDateUnit, private val timeZone: ZoneId) {
    private val stepUnit: TemporalUnit = when (unit) {
        AggregationDateUnit.YEAR -> ChronoUnit.YEARS
        AggregationDateUnit.QUARTER -> IsoFields.QUARTER_YEARS
        AggregationDateUnit.MONTH -> ChronoUnit.MONTHS
        AggregationDateUnit.WEEK -> ChronoUnit.WEEKS
        AggregationDateUnit.DAY -> ChronoUnit.DAYS
        AggregationDateUnit.HOUR -> ChronoUnit.HOURS
        AggregationDateUnit.MINUTE -> ChronoUnit.MINUTES
        AggregationDateUnit.SECOND -> ChronoUnit.SECONDS
    }

    val anchor: ZonedDateTime = ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, timeZone).let {
        if (unit == AggregationDateUnit.WEEK) it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else it
    }

    fun indexOf(epochMillis: Long): Long =
        anchor.until(Instant.ofEpochMilli(epochMillis).atZone(timeZone), stepUnit)

    fun keyOf(index: Long): Long = anchor.plus(index, stepUnit).toInstant().toEpochMilli()

    /** Grid keys strictly between the two bucket keys, emitted in stream direction. */
    fun keysBetween(fromMillis: Long, toMillis: Long): List<Long> {
        if (fromMillis == toMillis) return emptyList()
        return if (fromMillis < toMillis) {
            val toIndex = indexOf(toMillis)
            ((indexOf(fromMillis) + 1) until toIndex).map(::keyOf)
        } else {
            val toIndex = indexOf(toMillis)
            ((indexOf(fromMillis) - 1) downTo toIndex + 1).map(::keyOf)
        }
    }
}
```

`EmptyAggregationValues.kt`：

```kotlin
package me.ahoo.wow.query.aggregation

import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.DerivedExpression

/**
 * Empty-bucket semantics shared by Mongo dense fills, Elasticsearch client-side fills and the
 * ungrouped empty summary: counts are zero, value metrics are null, deriveds evaluate in
 * declaration order over the synthetic values (null propagation, divide-by-zero to null).
 */
object EmptyAggregationValues {
    fun values(metrics: List<AggregationMetric>): LinkedHashMap<String, Any?> {
        val values = LinkedHashMap<String, Any?>()
        metrics.forEach { metric ->
            values[metric.alias] = when (metric) {
                is AggregationMetric.Count, is AggregationMetric.DistinctCount -> 0L
                is AggregationMetric.Any, is AggregationMetric.Numeric, is AggregationMetric.Percentile -> null
                is AggregationMetric.Derived -> metric.expression.evaluateOver(values)
            }
        }
        return values
    }

    fun DerivedExpression.evaluateOver(values: Map<String, Any?>): Double? = when (this) {
        is DerivedExpression.MetricRef -> (values[metric] as? Number)?.toDouble()
        is DerivedExpression.Constant -> value
        is DerivedExpression.Binary -> {
            val leftValue = left.evaluateOver(values) ?: return null
            val rightValue = right.evaluateOver(values) ?: return null
            when (operator) {
                AggregationExpressionOperator.ADD -> leftValue + rightValue
                AggregationExpressionOperator.SUBTRACT -> leftValue - rightValue
                AggregationExpressionOperator.MULTIPLY -> leftValue * rightValue
                AggregationExpressionOperator.DIVIDE ->
                    if (rightValue == 0.0) {
                        return null
                    } else {
                        leftValue / rightValue
                    }
            }.takeIf { it.isFinite() }
        }
    }
}
```

`AbstractMongoQueryBackend.kt`：`emptySummary` 改为委托并删除私有 `evaluateOver`（及其专用导入）：

```kotlin
private fun AggregationQuery.emptySummary(): Document = Document(EmptyAggregationValues.values(metrics))
```

新增导入 `me.ahoo.wow.query.aggregation.EmptyAggregationValues`。

- [ ] **Step 4: 跑测试确认 GREEN（含 wow-mongo 回归）**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.aggregation.*" :wow-mongo:test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/aggregation/ wow-query/src/test/kotlin/me/ahoo/wow/query/aggregation/ wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackend.kt
git commit -m "feat(query): share dense date grid and empty metric value semantics"
```

---

### Task 3: schema 校验 — missingKey 字段限制

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidation.kt`（`aggregate` 的 groupBy 循环，约 288-295 行）
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidationTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `Terms.missingKey`；既有 `aggregationField`/`requireSchema`/`boundSchemaFixture`/`validateQuery`。
- Produces: 校验规则——声明 `missingKey` 的 Terms 字段必须 `QueryValueKind.SCALAR` + `QueryValueType.STRING` + `QueryCardinality.SINGLE`。错误信息 verbatim：`"Field [<logicalField>] must be a single-valued string field to declare missingKey."`

- [ ] **Step 1: 写失败测试**（追加到 `QuerySchemaValidationTest.kt`，沿用 387-417 行的 fixture 习语）

```kotlin
@Test
fun `terms missing key requires a single valued string field`() {
    val schema = boundSchemaFixture(
        objectFixture(
            "name" to scalarFixture(QueryValueType.STRING),
            "amount" to scalarFixture(QueryValueType.DECIMAL),
            "names" to arrayFixture(scalarFixture(QueryValueType.STRING)),
        )
    )
    validateQuery(
        aggregation {
            terms("name", "name", missingKey = "UNKNOWN")
            count("count")
        },
        schema,
    ).assert().isNotNull()
    assertThrows<QuerySchemaValidationException> {
        validateQuery(
            aggregation {
                terms("amount", "amount", missingKey = "UNKNOWN") // 数值字段
                count("count")
            },
            schema,
        )
    }.message.assert().isEqualTo("Field [amount] must be a single-valued string field to declare missingKey.")
    assertThrows<QuerySchemaValidationException> {
        validateQuery(
            aggregation {
                terms("names", "names", missingKey = "UNKNOWN") // 多值字段
                count("count")
            },
            schema,
        )
    }
}
```

（fixture 辅助若 `scalarFixture(QueryValueType.STRING)` 签名不符，以 `QuerySchemaFixtures.kt` 实际 API 为准调整；断言的错误信息字符串保持 verbatim。）

- [ ] **Step 2: 跑测试确认 RED**

Run: `./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QuerySchemaValidationTest"`
Expected: 第一个 `validateQuery`（合法路径）抛 `QuerySchemaValidationException` 之外的失败——实为**不抛**导致 `assert().isNotNull()` 前无异常但后两个 assertThrows 失败（未抛异常）。RED 点：两个 assertThrows 均"Expected exception未抛出"。

- [ ] **Step 3: 最小实现**

`aggregate` 的 groupBy 循环改为（捕获 `aggregationField` 返回值）：

```kotlin
query.groupBy.forEach { group ->
    val capability = when (group) {
        is AggregationGroup.Terms -> QueryCapability.AGGREGATE_TERMS
        is AggregationGroup.Histogram -> QueryCapability.AGGREGATE_NUMERIC
        is AggregationGroup.DateHistogram -> QueryCapability.AGGREGATE_TEMPORAL
    }
    val field = aggregationField(group.field, setOf(capability), parent)
    if (group is AggregationGroup.Terms && group.missingKey != null) {
        requireSchema(
            field.value.kind == QueryValueKind.SCALAR &&
                QueryValueType.STRING in field.value.valueTypes &&
                field.value.cardinality == QueryCardinality.SINGLE,
        ) { "Field [${field.logicalField}] must be a single-valued string field to declare missingKey." }
    }
}
```

（`field.logicalField` 属性名以 `aggregationField` 返回类型为准——`sort()` 中已有 `field.logicalField` 用法可循。）

- [ ] **Step 4: 跑测试确认 GREEN**

Run: `./gradlew :wow-query:test`
Expected: PASS（全模块）。

- [ ] **Step 5: 提交**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidation.kt wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidationTest.kt
git commit -m "feat(query): restrict terms missingKey to single-valued string fields"
```

---

### Task 4: DSL — terms/dateHistogram 尾参

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt`（58-73 行）
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDslTest.kt`（若文件名不同，以 `dsl/` 目录既有聚合 DSL 测试为准）

**Interfaces:**
- Consumes: Task 1 的 AST 尾参。
- Produces: `fun terms(field: String, alias: String, missingKey: String? = null)`；`fun dateHistogram(field: String, unit: AggregationDateUnit, alias: String, timeZone: ZoneId = ZoneOffset.UTC, dense: Boolean = false)`（Task 9 的 TCK 场景用这两个签名）。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test
fun `group builders should expose bucket options`() {
    val query = aggregation {
        terms("productId", "product", missingKey = "UNKNOWN")
        dateHistogram("createdAt", AggregationDateUnit.DAY, "day", timeZone = ZoneId.of("Asia/Shanghai"), dense = true)
        count("count")
    }
    (query.groupBy[0] as AggregationGroup.Terms).missingKey.assert().isEqualTo("UNKNOWN")
    (query.groupBy[1] as AggregationGroup.DateHistogram).let { day ->
        day.timeZone.assert().isEqualTo("Asia/Shanghai")
        day.dense.assert().isTrue()
    }
}
```

- [ ] **Step 2: 跑测试确认 RED**

Run: `./gradlew :wow-query:test --tests "*AggregationQueryDsl*"`
Expected: 编译失败（命名参数不存在）。

- [ ] **Step 3: 最小实现**

```kotlin
fun terms(field: String, alias: String, missingKey: String? = null) {
    groups += AggregationGroup.Terms(QueryField(field), alias, missingKey)
}
```

```kotlin
fun dateHistogram(
    field: String,
    unit: AggregationDateUnit,
    alias: String,
    timeZone: ZoneId = ZoneOffset.UTC,
    dense: Boolean = false,
) {
    groups += AggregationGroup.DateHistogram(QueryField(field), alias, unit, timeZone.id, dense)
}
```

- [ ] **Step 4: 跑测试确认 GREEN**

Run: `./gradlew :wow-query:test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/
git commit -m "feat(query): expose missingKey and dense in the aggregation dsl"
```

---

### Task 5: Mongo — missingKey（守卫去除 + `$ifNull` 哨兵键）

**Files:**
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation/MongoAggregationCompiler.kt`（`AggregationGroup.compile` 485-521 行与调用方 88-97 行）
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt`

**Interfaces:**
- Consumes: Task 1 `Terms.missingKey`；既有 `compile(parent, physicalParent, schema): Pair<Bson, Any>`（本任务改为 `Pair<Bson?, Any>`）。
- Produces: missingKey 声明时——Terms 分组**无** `$match` 守卫，`_id.<alias>` = `{$ifNull: ["$<path>", <missingKey>]}`；未声明时行为与现状逐字节一致（Task 9 TCK 场景 ⑧ 依赖）。

- [ ] **Step 1: 写失败测试**（追加到 `MongoAggregationCompilerTest.kt`，沿用该文件既有的"编译→断言管道 Document"习语；fixture/schema 构造照抄相邻用例）

```kotlin
@Test
fun `terms missingKey replaces the guard with an ifNull sentinel key`() {
    val pipeline = compile(
        aggregation {
            terms("state.name", "name", missingKey = "UNKNOWN")
            count("count")
        },
    )
    val stages = pipeline.map { it.toDocument() }
    // 无分组守卫 $match
    stages.none { it.containsKey("\$match") }.assert().isTrue()
    val group = stages.first { it.containsKey("\$group") }.getDocument("\$group")
    group.getDocument("_id").assert().isEqualTo(
        Document("name", Document("\$ifNull", listOf("\$state.name", "UNKNOWN"))),
    )
}

@Test
fun `terms without missingKey keeps the exists guard`() {
    val pipeline = compile(
        aggregation {
            terms("state.name", "name")
            count("count")
        },
    )
    val stages = pipeline.map { it.toDocument() }
    stages.any { it.containsKey("\$match") }.assert().isTrue()
}
```

（`compile`/`aggregation` 的测试内包装函数以该文件既有用例为准——若直接构造 `MongoAggregationCompiler(filterCompiler).compile(query, schema)`，照抄相邻测试的 schema fixture。）

- [ ] **Step 2: 跑测试确认 RED**

Run: `./gradlew :wow-mongo:test --tests "*MongoAggregationCompilerTest*"`
Expected: 第一个测试失败——守卫 `$match` 仍存在 / `_id.name` 为 `"$state.name"` 而非 `$ifNull` 文档。

- [ ] **Step 3: 最小实现**

`AggregationGroup.compile` 返回类型改 `Pair<Bson?, Any>`，Terms 分支：

```kotlin
is AggregationGroup.Terms -> {
    val path = field.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_TERMS)
    if (missingKey == null) {
        Filters.and(Filters.exists(path), Filters.ne(path, null)) to "\$$path"
    } else {
        null to Document("\$ifNull", listOf("\$$path", missingKey))
    }
}
```

调用方（`compile()` 88-97 行）：

```kotlin
val groupId = query.groupBy.takeIf { it.isNotEmpty() }?.let { groups ->
    val id = Document()
    val filters = groups.mapNotNull { group ->
        val (filter, expression) = group.compile(logicalParent, physicalParent, schema)
        id[group.alias] = expression
        filter
    }
    if (filters.isNotEmpty()) {
        add(Aggregates.match(Filters.and(filters)))
    }
    id
}
```

- [ ] **Step 4: 跑测试确认 GREEN**

Run: `./gradlew :wow-mongo:test`
Expected: PASS（全模块，含既有守卫断言回归）。

- [ ] **Step 5: 提交**

```bash
git add wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation/MongoAggregationCompiler.kt wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt
git commit -m "feat(mongo): bucket missing terms values into the declared sentinel key"
```

---

### Task 6: Mongo — dense（索引空间 `$densify` 管道）

**Files:**
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation/MongoAggregationCompiler.kt`（compile 装配 60-109 行、`compile` DateHistogram 分支 512-520 行、`project` 256-353 行）
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt`

**Interfaces:**
- Consumes: Task 2 `DenseDateGrid(unit, ZoneId)`；Task 1 `DateHistogram.dense`；驱动 API `Aggregates.densify(field, DensifyRange.fullRangeWithStep(1L), DensifyOptions.densifyOptions())`（`com.mongodb.client.model.densify.*`，driver-core 5.8 已核实）、`Aggregates.set(Field(name, expr))`（`com.mongodb.client.model.Field`）。
- Produces: dense 查询管道 = `… $group(_id.<alias> = {$dateDiff: [anchor, $dateTrunc(…), unit, timezone]}) → $set(<alias> = $_id.<alias>) → $densify(<alias>, step 1, bounds full) → $project（<alias> = {$toLong: {$dateAdd: [anchor, "$<alias>", unit, timezone]}}，指标投影带 $ifNull 空语义守卫） → 既有 derived/having/$sort/$limit`。dense=false 时管道与现状等价（仅指标投影的 ifNull 守卫为真实文档上的无操作改写）。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test
fun `dense date histogram groups by bucket index and densifies numerically`() {
    val pipeline = compile(
        aggregation {
            dateHistogram("state.createdAt", AggregationDateUnit.DAY, "day", dense = true)
            count("count")
            sum("state.amount", "total")
        },
    )
    val stages = pipeline.map { it.toDocument() }
    val group = stages.first { it.containsKey("\$group") }.getDocument("\$group")
    group.getDocument("_id").getDocument("day").let { index ->
        index.getString("\$dateDiff").let { } // 顶层为 $dateDiff 文档
        index.getDocument("\$dateDiff").assert().isNotNull()
    }
    val set = stages.first { it.containsKey("\$set") }
    set.getDocument("\$set").assert().isEqualTo(Document("day", "\$_id.day"))
    val densify = stages.first { it.containsKey("\$densify") }.getDocument("\$densify")
    densify.getString("field").assert().isEqualTo("day")
    densify.getDocument("range").assert().isEqualTo(
        Document("step", 1).append("bounds", "full"),
    )
    val project = stages.first { it.containsKey("\$project") }.getDocument("\$project")
    project.getDocument("day").getDocument("\$toLong").getDocument("\$dateAdd").let { add ->
        add.getString("unit").assert().isEqualTo("day")
        add["quantity"].assert().isEqualTo("\$day")
    }
    project.getDocument("count").assert().isEqualTo(
        Document("\$ifNull", listOf("\$count", 0L)),
    )
}

@Test
fun `non dense date histogram keeps the toLong truncation key`() {
    val pipeline = compile(
        aggregation {
            dateHistogram("state.createdAt", AggregationDateUnit.DAY, "day")
            count("count")
        },
    )
    val stages = pipeline.map { it.toDocument() }
    stages.none { it.containsKey("\$densify") || it.containsKey("\$set") }.assert().isTrue()
    val group = stages.first { it.containsKey("\$group") }.getDocument("\$group")
    group.getDocument("_id").getDocument("day").assert().isNotNull() // {$toLong: {$dateTrunc: …}}
    group.getDocument("_id").getDocument("day").containsKey("\$toLong").assert().isTrue()
}
```

（`getDocument`/`getString` 为 `Document` 的 Kotlin 扩展或 `doc.get(key, Document::class.java)`——以该测试文件既有习语为准；重点断言：`$dateDiff` 键、`$densify` range、`$dateAdd` 反演、count 的 `$ifNull`。）

- [ ] **Step 2: 跑测试确认 RED**

Run: `./gradlew :wow-mongo:test --tests "*MongoAggregationCompilerTest*"`
Expected: 第一个测试失败——`$set`/`$densify` 阶段不存在（`first { }` 抛 NoSuchElement）。

- [ ] **Step 3: 最小实现**

(a) `compile()` 装配（60-109 行区）：在 elements 展开之后、groupId 构建之前计算：

```kotlin
val denseGroup = query.groupBy.singleOrNull()?.let { it as? AggregationGroup.DateHistogram }?.takeIf { it.dense }
val denseGrid = denseGroup?.let { DenseDateGrid(it.unit, ZoneId.of(it.timeZone)) }
```

groupId 与 `add(group(...))` 之间把 grid 传入 `group.compile(..., denseGrid)`；`add(group(...))` 之后、`add(project(query))` 之前：

```kotlin
if (denseGroup != null && denseGrid != null) {
    add(Aggregates.set(Field(denseGroup.alias, "\$_id.${denseGroup.alias}")))
    add(
        Aggregates.densify(
            denseGroup.alias,
            DensifyRange.fullRangeWithStep(1L),
            DensifyOptions.densifyOptions(),
        ),
    )
}
add(project(query, denseGroup, denseGrid))
```

(b) `AggregationGroup.compile` 追加 `denseGrid: DenseDateGrid?` 参数，DateHistogram 分支：

```kotlin
is AggregationGroup.DateHistogram -> {
    val input = dateInput(parent, physicalParent, schema)
    val truncation = Document("date", input)
        .append("unit", unit.name.lowercase())
        .append("timezone", mongoTimeZone(timeZone))
        .apply { if (unit == AggregationDateUnit.WEEK) append("startOfWeek", "Monday") }
    if (denseGrid != null) {
        val anchor = Date.from(denseGrid.anchor.toInstant())
        Filters.expr(Document("\$ne", listOf(input, null))) to Document(
            "\$dateDiff",
            Document("startDate", anchor)
                .append("endDate", Document("\$dateTrunc", truncation))
                .append("unit", unit.name.lowercase())
                .append("timezone", mongoTimeZone(timeZone))
                .apply { if (unit == AggregationDateUnit.WEEK) append("startOfWeek", "Monday") },
        )
    } else {
        Filters.expr(Document("\$ne", listOf(input, null))) to
            Document("\$toLong", Document("\$dateTrunc", truncation))
    }
}
```

(c) `project(query, denseGroup, denseGrid)`：

- 分组投影：

```kotlin
query.groupBy.forEach { group ->
    add(
        if (group === denseGroup && denseGrid != null) {
            Projections.computed(
                group.alias,
                Document(
                    "\$toLong",
                    Document(
                        "\$dateAdd",
                        Document("startDate", Date.from(denseGrid.anchor.toInstant()))
                            .append("unit", denseGroup!!.unit.name.lowercase())
                            .append("quantity", "\$${group.alias}")
                            .append("timezone", mongoTimeZone(denseGroup.timeZone)),
                    ),
                ),
            )
        } else {
            Projections.computed(itAlias(group.alias)),
        },
    )
}
```

（`itAlias` 即原有 `"\$_id.${it.alias}"` 内联表达式，保持原样。）

- 指标投影（对真实文档无操作的 `$ifNull` 守卫，使 densify 合成文档获得空语义）：

```kotlin
is AggregationMetric.Count -> add(
    Projections.computed(metric.alias, Document("\$ifNull", listOf("\$${metric.alias}", 0L))),
)
```

Numeric 分支的 count 守卫由 `Document("\$eq", listOf("\$${metric.countAlias}", 0))` 改为：

```kotlin
Document("\$eq", listOf(Document("\$ifNull", listOf("\$${metric.countAlias}", 0L)), 0))
```

Percentile 分支同上改 countAlias，且 `arrayElemAt` 第一参数由 `"\$${metric.alias}"` 改为：

```kotlin
Document("\$ifNull", listOf("\$${metric.alias}", emptyList<Any>()))
```

DistinctCount 分支 `$reduce` 的 `input` 由 `"\$${metric.alias}"` 改为：

```kotlin
Document("\$ifNull", listOf("\$${metric.alias}", emptyList<Any>()))
```

新增导入：`com.mongodb.client.model.Field`、`com.mongodb.client.model.densify.DensifyOptions`、`com.mongodb.client.model.densify.DensifyRange`、`me.ahoo.wow.query.aggregation.DenseDateGrid`、`java.util.Date`。

- [ ] **Step 4: 跑测试确认 GREEN**

Run: `./gradlew :wow-mongo:test`
Expected: PASS。若既有用例对 count 守卫 `$eq` 形态做精确断言而失败，仅更新期望文档（行为等价改写），并在提交信息注明。

- [ ] **Step 5: 提交**

```bash
git add wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation/MongoAggregationCompiler.kt wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt
git commit -m "feat(mongo): densify date histograms in bucket-index space"
```

---

### Task 7: ES — missingKey（keyword 运行时字段）

**Files:**
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationCompiler.kt`（`toSource` 216-244 行）
- Test: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt`

**Interfaces:**
- Consumes: Task 1 `Terms.missingKey`；既有 runtimeMappings 机制（epoch runtime field 同款，268-316 行）。
- Produces: missingKey 声明时——`runtimeMappings["__wow_missing_terms_<index>"]` 为 keyword 运行时字段（单值透传，缺失/null → 哨兵字符串），terms 源 field 指向该运行时字段名（Task 9 TCK 场景 ⑧ 依赖其字典序语义）。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test
fun `terms missingKey routes the source through a keyword runtime field`() {
    val plan = compile(
        aggregation {
            terms("state.name", "name", missingKey = "UNKNOWN")
            count("count")
        },
    )
    val runtime = plan.runtimeMappings.getValue("__wow_missing_terms_0")
    // 编译产物断言：运行时字段存在，且唯一分组源指向它
    plan.groupSources.single().let { source ->
        source.name().assert().isEqualTo("name")
        // CompositeAggregationSource 的 field 断言方式以该测试文件对既有 terms 源的断言习语为准
    }
}

@Test
fun `terms without missingKey keeps the physical field`() {
    val plan = compile(
        aggregation {
            terms("state.name", "name")
            count("count")
        },
    )
    plan.runtimeMappings.containsKey("__wow_missing_terms_0").assert().isFalse()
}
```

（plan/compile 包装以该文件既有用例为准；至少断言 `runtimeMappings` 键存在/不存在 + 源字段名，运行时字段 type 可经 `RuntimeField` 的 builder 输出 JSON 断言 `keyword`。）

- [ ] **Step 2: 跑测试确认 RED**

Run: `./gradlew :wow-elasticsearch:test --tests "*ElasticsearchAggregationCompilerTest*"`
Expected: 第一个测试 `getValue` 抛 NoSuchElement——运行时字段不存在。

- [ ] **Step 3: 最小实现**

`toSource` Terms 分支：

```kotlin
is AggregationGroup.Terms -> {
    if (missingKey == null) {
        CompositeAggregationSource.of {
            it.terms { terms ->
                terms.field(field.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_TERMS))
                    .order(sort.direction.toSortOrder())
            }
        }
    } else {
        val runtimeFieldName = "__wow_missing_terms_$index"
        runtimeMappings[runtimeFieldName] = missingKeyRuntimeField(
            field.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_TERMS),
            missingKey,
        )
        CompositeAggregationSource.of {
            it.terms { terms ->
                terms.field(runtimeFieldName).order(sort.direction.toSortOrder())
            }
        }
    }
}
```

新增私有函数（镜像 `epochDateRuntimeField` 的结构）：

```kotlin
/**
 * Single-valued passthrough with a declared sentinel: the sentinel stays a plain string key so
 * composite ordering matches MongoDB's `$ifNull` lexicographic position (composite
 * `missing_bucket` orders its null key first, which would diverge).
 */
private fun missingKeyRuntimeField(physicalPath: String, missingKey: String): RuntimeField {
    val params = mapOf(
        "field" to JsonData.of(physicalPath),
        "missing" to JsonData.of(missingKey),
    )
    val source = """
        String field = params.field;
        if (doc.containsKey(field) && doc[field].size() == 1) {
            def raw = doc[field].value;
            if (raw != null) {
                return raw.toString();
            }
        }
        return params.missing;
    """.trimIndent()
    return RuntimeField.of { runtime ->
        runtime.type(RuntimeFieldType.Keyword)
            .script(
                Script.of { script ->
                    script.lang(ScriptLanguage.Painless)
                        .source { it.scriptString(source) }
                        .params(params)
                },
            )
    }
}
```

- [ ] **Step 4: 跑测试确认 GREEN**

Run: `./gradlew :wow-elasticsearch:test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationCompiler.kt wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt
git commit -m "feat(elasticsearch): bucket missing terms values via keyword runtime field"
```

---

### Task 8: ES — dense（pager 跨页流式补零）

**Files:**
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationCompiler.kt`（compile 152-172 行区，构造 dense 计划）
- Modify: 计划类型定义文件（`ElasticsearchAggregationPlan` 所在文件，编译器同包）
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationPager.kt`
- Test: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationPagerTest.kt`

**Interfaces:**
- Consumes: Task 2 `DenseDateGrid`/`EmptyAggregationValues`；Task 1 `DateHistogram.dense`；既有 `matchesHaving`/`toObjectNode`/`nativeValue`。
- Produces:
  - `data class DenseBucketPlan(val alias: String, val grid: DenseDateGrid, val metrics: List<AggregationMetric>)`；`ElasticsearchAggregationPlan` 尾参 `val dense: DenseBucketPlan? = null`。
  - `internal fun fillGapRows(fromKey: Long, toKey: Long, plan: ElasticsearchAggregationPlan): List<ObjectNode>`（Task 9/评审依赖的补零行构造：空语义指标值 + having 过滤）。
  - `AggregationPage` 追加 `val firstKey: Long? = null, val lastKey: Long? = null`（pre-having 的页首/页末桶键）。

- [ ] **Step 1: 写失败测试**（`ElasticsearchAggregationPagerTest.kt`，直接构造 plan 调 `fillGapRows`；plan 构造照抄该文件/编译器测试既有方式）

```kotlin
@Test
fun `dense gap rows carry empty metric semantics and respect having`() {
    val dayGrid = DenseDateGrid(AggregationDateUnit.DAY, ZoneId.of("UTC"))
    val plan = basePlan(
        dense = DenseBucketPlan(
            alias = "day",
            grid = dayGrid,
            metrics = listOf(
                AggregationMetric.Count("count"),
                AggregationMetric.Numeric(
                    AggregationFunction.SUM,
                    AggregationExpression.Field(QueryField("amount")),
                    "total",
                ),
            ),
        ),
    )
    val day1 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
    val day4 = Instant.parse("2026-01-04T00:00:00Z").toEpochMilli()
    val rows = fillGapRows(day1, day4, plan)
    rows.assert().hasSize(2)
    rows.forEach { row ->
        row.path("day").longValue().assert().isBetween(day1 + 1, day4 - 1)
        row.path("count").longValue().assert().isZero()
        row.path("total").assert().isNull()
    }
    val filtered = fillGapRows(day1, day4, plan.copy(having = HavingExpression.Condition("count", ComparisonOperator.GT, 0.0)))
    filtered.assert().isEmpty()
}
```

（`basePlan` 为本任务在测试内新增的辅助函数，按 `ElasticsearchAggregationPlan` 实际构造参数补齐 rootQuery=`Query.of { it.matchAll { m -> m } }`、空 elements/groupSources/其余默认值；`isBetween` 若 fluent-assert 无此 API 改用两次比较断言。）

- [ ] **Step 2: 跑测试确认 RED**

Run: `./gradlew :wow-elasticsearch:test --tests "*ElasticsearchAggregationPagerTest*"`
Expected: 编译失败（`DenseBucketPlan`/`fillGapRows`/plan.dense 不存在）。

- [ ] **Step 3: 最小实现**

(a) 计划类型（定义于 plan 数据类同文件）：

```kotlin
data class DenseBucketPlan(
    val alias: String,
    val grid: DenseDateGrid,
    val metrics: List<AggregationMetric>,
)
```

`ElasticsearchAggregationPlan` 追加尾参 `val dense: DenseBucketPlan? = null`。

(b) 编译器 `compile(...)` 返回前：

```kotlin
val dense = query.groupBy.singleOrNull()?.let { it as? AggregationGroup.DateHistogram }?.takeIf { it.dense }
    ?.let { DenseBucketPlan(it.alias, DenseDateGrid(it.unit, ZoneId.of(it.timeZone)), query.metrics) }
return ElasticsearchAggregationPlan(
    ...,
    having = query.having,
    dense = dense,
)
```

(c) Pager `grouped()`（78-102 行）：

```kotlin
private fun grouped(
    plan: ElasticsearchAggregationPlan,
    pit: ElasticsearchPointInTime.Session,
    firstAggregation: Aggregation,
): Flux<ObjectNode> {
    val pages = searchPage(plan, pit, aggregation = firstAggregation)
        .expand { page ->
            if (page.shouldStop(plan)) {
                Mono.empty()
            } else {
                searchPage(plan, pit, page.afterKey, page.fetched)
            }
        }
    val rows = if (plan.dense == null) {
        pages.concatMapIterable({ it.rows }, 1)
    } else {
        var previousKey: Long? = null
        pages.concatMap(
            { page ->
                val gapRows = previousKey?.let { prev ->
                    page.firstKey?.let { next -> fillGapRows(prev, next, plan) }
                }.orEmpty()
                page.lastKey?.let { previousKey = it }
                Flux.concat(Flux.fromIterable(gapRows), Flux.fromIterable(page.rows))
            },
            1,
        )
    }
    if (!plan.metricSorted) {
        // having filters client-side, so a page can yield more survivors than the remaining
        // limit; dense fills likewise emit more rows than the server page size — both paths
        // cap at the limit client-side, the no-fill no-having path stays composite-capped
        return if (plan.having != null || plan.dense != null) rows.take(plan.limit.toLong()) else rows
    }
    return rows.collect(
        { BoundedTopRows(plan.effectiveSort, plan.limit, plan.groupSources.map { it.name() }) },
        BoundedTopRows::add,
    ).flatMapMany { Flux.fromIterable(it.result()) }
}
```

(d) `searchPage`（104-120 行）记录页首/页末原始桶键并计入 AggregationPage：

```kotlin
private fun searchPage(
    plan: ElasticsearchAggregationPlan,
    pit: ElasticsearchPointInTime.Session,
    afterKey: Map<String, FieldValue> = emptyMap(),
    fetched: Int = 0,
    aggregation: Aggregation = plan.aggregation(afterKey, plan.pageSize(fetched)),
): Mono<AggregationPage> {
    return search(plan, pit, aggregation).map { response ->
        val composite = response.innermost(plan).getValue(GROUP_AGGREGATION).composite()
        val buckets = composite.buckets().array()
        val denseAlias = plan.dense?.alias
        val firstKey = denseAlias?.let { buckets.firstOrNull()?.key()?.getValue(it) }
            ?.let { it.nativeValue() as Long }
        val lastKey = denseAlias?.let { buckets.lastOrNull()?.key()?.getValue(it) }
            ?.let { it.nativeValue() as Long }
        val rows = buckets.asSequence()
            .map { it.toRow(plan) }
            .filter { row -> plan.having == null || row.matchesHaving(plan.having) }
            .toList()
        AggregationPage(rows, composite.afterKey(), fetched + rows.size, firstKey, lastKey)
    }
}
```

(e) 文件级 internal 函数（置于 `matchesHaving` 旁）：

```kotlin
/**
 * Client-side dense fill between two consecutive actual bucket keys: the gap rows follow each
 * metric's empty semantics and participate in having like any other row.
 */
internal fun fillGapRows(
    fromKey: Long,
    toKey: Long,
    plan: ElasticsearchAggregationPlan,
): List<ObjectNode> {
    val dense = requireNotNull(plan.dense)
    val rows = dense.grid.keysBetween(fromKey, toKey).map { key ->
        val values = LinkedHashMap<String, Any?>()
        values[dense.alias] = key
        values.putAll(EmptyAggregationValues.values(dense.metrics))
        values.toObjectNode()
    }
    return if (plan.having == null) rows else rows.filter { it.matchesHaving(plan.having) }
}
```

`AggregationPage` 追加 `val firstKey: Long? = null, val lastKey: Long? = null`。新增导入 `me.ahoo.wow.query.aggregation.DenseDateGrid`、`me.ahoo.wow.query.aggregation.EmptyAggregationValues`（编译器侧）、`java.time.ZoneId`。

- [ ] **Step 4: 跑测试确认 GREEN**

Run: `./gradlew :wow-elasticsearch:test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationPagerTest.kt wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt
git commit -m "feat(elasticsearch): stream dense gap fills across composite pages"
```

---

### Task 9: TCK 契约场景（SnapshotQueryBackendSpec）

**Files:**
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryBackendSpec.kt`（having 场景之后、`saveAggregationStates` 之前追加；数据事实：states A+B 的 lines createdAt 分布为 2026-01-01/01-02×2/01-03/02-01/02-02，productName 仅 2 行有值）
- Test: 即本文件（契约由 wow-mongo 与 wow-elasticsearch 的 integrationTest 分别执行）。

**Interfaces:**
- Consumes: Task 4 DSL（`dateHistogram(field, unit, alias, timeZone, dense)`/`terms(field, alias, missingKey)`）；Task 5/6/8 双后端实现；Task 2 空语义。
- Produces: 设计场景 ①-⑧ 的跨后端契约。
- **范围说明（设计偏离记录）**：与 Phase 2/3 不同，本阶段**不**向 `EventStreamQueryBackendSpec` 加场景——`generateEventStream` 的事件同刻创建，撑不起 dense 间隙/缺失字段数据；跨后端契约已由该 spec 在 Mongo/ES 两套存储下的集成执行覆盖（事件/快照只是查询面差异）。

- [ ] **Step 1: 写失败测试**（追加 7 个测试；逐个跑 RED 需 Mongo/ES 容器——本任务直接以集成测试 Red 为准）

```kotlin
@Test
fun `aggregation dense day histogram should fill interior gaps with empty metric semantics`() {
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        dateHistogram("createdAt", AggregationDateUnit.DAY, "day", dense = true)
        count("count")
        sum("amount", "total")
        derived("aov") { ref("total") / ref("count") }
    }.query(queryBackendBinding)
        .collectList()
        .test()
        .assertNext { rows ->
            // 5 个实际桶（01-01/01-02/01-03/02-01/02-02）+ 28 个补齐日（01-04..01-31）
            rows.assert().hasSize(33)
            rows.first().path("day").longValue().assert()
                .isEqualTo(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli())
            rows.last().path("day").longValue().assert()
                .isEqualTo(Instant.parse("2026-02-02T00:00:00Z").toEpochMilli())
            val gapRow = rows.first { it.path("day").longValue() == Instant.parse("2026-01-04T00:00:00Z").toEpochMilli() }
            gapRow.path("count").longValue().assert().isZero()
            gapRow.path("total").assert().isNull()
            gapRow.path("aov").assert().isNull()
        }
        .verifyComplete()
}

@Test
fun `aggregation dense should not fill a single bucket`() {
    saveAggregationStates(aggregationAnyNullState()) // 单行 line：2026-01-04
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        dateHistogram("createdAt", AggregationDateUnit.DAY, "day", dense = true)
        count("count")
    }.query(queryBackendBinding)
        .collectList()
        .test()
        .assertNext { rows -> rows.assert().hasSize(1) }
        .verifyComplete()
}

@Test
fun `aggregation dense should let having drop filled rows`() {
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        dateHistogram("createdAt", AggregationDateUnit.DAY, "day", dense = true)
        count("count")
        having { "count" gte 1.0 } // 补齐行 count=0 判假
    }.query(queryBackendBinding)
        .collectList()
        .test()
        .assertNext { rows -> rows.assert().hasSize(5) } // 仅实际桶
        .verifyComplete()
}

@Test
fun `aggregation dense should let having match exactly the filled rows`() {
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        dateHistogram("createdAt", AggregationDateUnit.DAY, "day", dense = true)
        count("count")
        having { "count" eq 0.0 }
    }.query(queryBackendBinding)
        .collectList()
        .test()
        .assertNext { rows ->
            rows.assert().hasSize(28)
            rows.first().path("day").longValue().assert()
                .isEqualTo(Instant.parse("2026-01-04T00:00:00Z").toEpochMilli())
        }
        .verifyComplete()
}

@Test
fun `aggregation dense should place filled rows in descending order`() {
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        dateHistogram("createdAt", AggregationDateUnit.DAY, "day", dense = true)
        count("count")
        sort { "day".desc() }
    }.query(queryBackendBinding)
        .collectList()
        .test()
        .assertNext { rows ->
            rows.take(3).map { it.path("day").longValue() }.assert().containsExactly(
                Instant.parse("2026-02-02T00:00:00Z").toEpochMilli(),
                Instant.parse("2026-02-01T00:00:00Z").toEpochMilli(),
                Instant.parse("2026-01-31T00:00:00Z").toEpochMilli(), // 首个补齐日
            )
        }
        .verifyComplete()
}

@Test
fun `aggregation dense should count filled rows toward the limit`() {
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        dateHistogram("createdAt", AggregationDateUnit.DAY, "day", dense = true)
        count("count")
        limit(5) // 01-01/01-02/01-03 为实际桶，01-04/01-05 为补齐行
    }.query(queryBackendBinding)
        .collectList()
        .test()
        .assertNext { rows ->
            rows.assert().hasSize(5)
            rows[3].path("count").longValue().assert().isZero()
            rows[4].path("count").longValue().assert().isZero()
        }
        .verifyComplete()
}

@Test
fun `aggregation terms missingKey should bucket missing values into the sentinel key`() {
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        terms("productName", "name", missingKey = "__missing__")
        count("count")
    }.query(queryBackendBinding)
        .collectList()
        .test()
        .assertNext { rows ->
            // productName 有值：alpha 行 "Alpha"、B 的 alpha 行 "Alpha 2026"；缺省 4 行
            // 字典序："Alpha" < "Alpha 2026" < "__missing__"（'A'=0x41 < '_'=0x5F）
            rows.map { it.path("name").textValue() }.assert()
                .containsExactly("Alpha", "Alpha 2026", "__missing__")
            rows[2].path("count").longValue().assert().isEqualTo(4L)
        }
        .verifyComplete()
}
```

另有 WEEK×Asia/Shanghai 网格场景（设计 ⑦）：

```kotlin
@Test
fun `aggregation dense week histogram should align local week starts in a non utc zone`() {
    saveAggregationStates(*aggregationStates().toTypedArray())
    val zone = ZoneId.of("Asia/Shanghai")
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        dateHistogram("createdAt", AggregationDateUnit.WEEK, "week", timeZone = zone, dense = true)
        count("count")
    }.query(queryBackendBinding)
        .collectList()
        .test()
        .assertNext { rows ->
            // 实际桶：2025-12-29 周（01-01/01-02/01-03 三行）、2026-01-26 周（02-01）、2026-02-02 周（02-02）
            // 补齐：2026-01-05/01-12/01-19 三周
            rows.assert().hasSize(6)
            rows.map { it.path("week").longValue() }.assert().containsExactly(
                ZonedDateTime.of(2025, 12, 29, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
                ZonedDateTime.of(2026, 1, 5, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
                ZonedDateTime.of(2026, 1, 12, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
                ZonedDateTime.of(2026, 1, 19, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
                ZonedDateTime.of(2026, 1, 26, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
                ZonedDateTime.of(2026, 2, 2, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
            )
            rows[1].path("count").longValue().assert().isZero()
        }
        .verifyComplete()
}
```

（聚合 DSL 中 `derived("aov") { ref("total") / ref("count") }` 的 `ref` 帮助函数沿用 Phase 2 场景写法。**周界事实核对**：2026-01-01 是周四 → 其周一为 2025-12-29；2026-02-01 是周日 → 属 2026-01-26 周；2026-02-02 是周一 → 开新周。）

- [ ] **Step 2: 跑测试确认 RED（双后端集成）**

Run: `./gradlew :wow-mongo:integrationTest :wow-it:integrationTest --stacktrace`（ES 场景经由 `:wow-it`；以本仓库 test-runtime 文档中两组后端集成任务为准）
Expected: 新场景失败——补齐行缺失（行数 5 而非 33 等）、missingKey 参数不被编译（早期任务未合入则先编译失败）。**必须亲眼确认失败原因即语义缺失而非测试自身错误。**

- [ ] **Step 3: 无实现**（Task 5/6/7/8 已提供实现；本任务仅契约。若场景失败暴露实现缺陷，回对应任务修复——遵守 5 轮修复上限与 TDD 先证原则）

- [ ] **Step 4: 跑测试确认 GREEN（双后端集成 + 全量契约）**

Run: `./gradlew :wow-mongo:integrationTest :wow-it:integrationTest allContractTest --stacktrace`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryBackendSpec.kt
git commit -m "test(tck): pin dense gap fill and missing key bucket contracts"
```

---

### Task 10: OpenAPI 快照 + 全量回归

**Files:**
- Modify: wow-openapi 快照资源（由 `-Dwow.snapshot.update=true` 再生成，路径在 `wow-openapi/src/test/resources/`）

**Interfaces:**
- Consumes: Task 1-8 全部。
- Produces: 快照纯增量（DateHistogram 多 `dense` 可选属性、Terms 多 `missingKey` 可选属性）。

- [ ] **Step 1: 再生成快照并核对纯增量**

```bash
./gradlew :wow-openapi:test -Dwow.snapshot.update=true
git status --short wow-openapi/src/test/resources/
git diff wow-openapi/src/test/resources/ | head -80
```

Expected: diff 仅含 `dense`/`missingKey` 可选属性新增，无既有行删改。

- [ ] **Step 2: 无 flag 验证 + detekt + 全量回归**

```bash
./gradlew :wow-openapi:test
./gradlew detekt
./gradlew :wow-api:check :wow-query:check :wow-mongo:check :wow-elasticsearch:check :wow-webflux:check
./gradlew :wow-benchmarks:test :wow-benchmarks:benchmarkSmoke --stacktrace
./gradlew allLocalTest allContractTest --stacktrace
```

Expected: 全绿（benchmark 校验 Java 位置参数兼容——既有 `new AggregationGroup.Terms(input, alias)` / `DateHistogram` 4 参调用不因尾参新增而破坏）。

- [ ] **Step 3: 提交**

```bash
git add wow-openapi/src/test/resources/
git commit -m "chore(openapi): regenerate snapshots for bucket options"
```

---

### Task 11: 文档（中英）+ Epic 文档入库

**Files:**
- Modify: `documentation/docs/zh/guide/query/aggregation-query.md`
- Modify: `documentation/docs/en/guide/query/aggregation-query.md`
- Modify: `documentation/docs/zh/guide/query/snapshot-aggregation.md`（场景 13/14）
- Modify: `documentation/docs/en/guide/query/snapshot-aggregation.md`（场景 13/14）
- Modify: `documentation/designs/2026-09-12-aggregation-reporting-epic-design.md`（状态行：Phase 4 已交付）

**Interfaces:**
- Consumes: 本计划全部语义。
- Produces: 用户可依文档使用 dense/missingKey 并理解截断语义与版本要求。

- [ ] **Step 1: 中文文档新增三节**（置于分组/排序章节之后、HAVING 章节邻近；英文镜像同结构）

`aggregation-query.md`（zh）追加：

```markdown
### 空桶补齐（dense）

`DATE_HISTOGRAM` 分组可声明 `dense: true`，将时间序列补齐为连续桶：

- 窗口为**首个实际桶到末个实际桶**，仅补内部间隙——首尾之外不补；空结果或单桶不产生补齐行。
- 补齐行的指标遵循各指标的空语义：`COUNT`/`DISTINCT_COUNT` 为 `0`，`SUM`/`AVG` 等值指标为 `null`，派生指标按空值求值（null 传播、除零为 null）。
- 补齐行是普通行：参与排序（含倒序）、参与 `having` 过滤、计入 `limit`。
- `dense` 要求 `DATE_HISTOGRAM` 是唯一分组维度；存储要求 MongoDB ≥ 5.1。
- 成本口径：补齐桶数 = 窗口 × 粒度。超大窗口配合秒级粒度在两端都是反模式，请按业务需要选择粒度。

### 缺失桶（missingKey）

`TERMS` 分组可声明 `missingKey`（字符串），字段缺失或为 null 的记录归入该哨兵键桶：

- 仅允许声明在**单值字符串字段**上（多值/数值字段在构造或 schema 校验时拒绝）。
- 哨兵键与真实键共享同一键空间——若数据中存在与哨兵相同的真实值，两者合并为同一桶。
- 哨兵以普通字符串参与字典序排序，MongoDB 与 Elasticsearch 行为一致。

### 截断语义（澄清）

聚合结果截断统一由 `sort` + `limit` 表达，没有独立的 `size` 参数：

- 分组排序：按分组序分页，收满 `limit` 行即停止。
- 指标排序：全局 Top-N——为保证正确性必然扫描全部桶，`limit` 即截断。
- `having` 场景见 HAVING 章节：`limit` 语义为过滤后的行数。
```

`snapshot-aggregation.md`（zh）追加场景 13/14（示例用 `state.createdAt` DAY dense + `productName` missingKey，结果 JSON 演示补齐行 `count: 0` 与 `__missing__` 桶——数值以 TCK 场景 ①⑧ 为准编写，HTTP JSON 块结构与场景 12 一致）。

- [ ] **Step 2: 文档构建验证**

```bash
cd documentation && pnpm docs:build
```

Expected: 构建成功，无死链。

- [ ] **Step 3: 提交**

```bash
git add documentation/docs/zh/guide/query/aggregation-query.md documentation/docs/en/guide/query/aggregation-query.md documentation/docs/zh/guide/query/snapshot-aggregation.md documentation/docs/en/guide/query/snapshot-aggregation.md documentation/designs/2026-09-12-aggregation-reporting-epic-design.md
git commit -m "docs(query): document dense date histograms, missing key buckets and truncation semantics"
```

---

## Self-Review 记录

- **Spec 覆盖**：设计章节「范围决策 1（单维 dense）」→ Task 1 校验 + Task 9 场景；「决策 2（无 size）」→ Task 11 文档；「决策 3（missingKey 字符串单值）」→ Task 1/3 校验 + Task 5/7 实现 + Task 9 场景 ⑧；「空桶补齐语义」→ Task 2 空语义 + Task 6/8 双后端 + Task 9 场景 ①-⑦；「双后端·共享网格」→ Task 2；「Mongo 索引空间 densify」→ Task 6；「ES pager 跨页补齐」→ Task 8；「DSL」→ Task 4；「HttpQueryGuard 无变更」→ 全局约束（无任务，符合设计）；「TCK 场景 ①-⑨」→ Task 9（⑨ 为 Task 1/3 单测）；「文档与版本要求」→ Task 11。
- **占位符扫描**：Task 9 Step 1 场景代码完整；Task 11 文档正文完整（snapshot-aggregation 场景 13/14 的 JSON 数值锚定 TCK 场景 ①⑧）。
- **类型一致性**：`DenseDateGrid(unit, timeZone)`/`keysBetween(from, to)`（Task 2 定义，Task 8 消费）；`EmptyAggregationValues.values(metrics)`（Task 2 定义，Task 8 消费）；`DenseBucketPlan(alias, grid, metrics)`（Task 8 内自洽）；错误信息三条 verbatim 全链一致。
