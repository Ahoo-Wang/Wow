# RelativeTimeFilter 自然周期扩展设计

## 背景

Wow 8.12 的 `FilterExpression` 已提供 `TODAY`、`TOMORROW`、完整的上周/本周/下周，以及上月/本月等相对时间表达式。这些表达式由 `FilterNormalizer` 在执行端根据统一 `Clock`、时区和可选日期格式展开为绝对范围，再交给 MongoDB 或 Elasticsearch 编译。

当前自然周期仍有三个缺口：缺少昨天、下月和年份周期。同时，每个相对时间实现都重复声明 `field`、`zoneId`、`datePattern` 与运行时 `dateFormatter`，而 `RelativeTimeFilter` 接口只声明了后两个日期格式属性。

本次设计补齐最常用的自然周期，并把通用属性提升到 `RelativeTimeFilter` 接口。继续沿用现有显式表达式风格，不引入通用时间表达式语言。

## 目标

- 新增 `YESTERDAY`、`NEXT_MONTH`、`LAST_YEAR`、`THIS_YEAR`、`NEXT_YEAR` 五种公开过滤表达式。
- 通过 Kotlin 类型、Filter DSL、JSON、JSON Schema 和 OpenAPI 公开这些表达式。
- 在执行端按查询时钟与时区将表达式展开为跨后端一致的绝对半开区间。
- 将 `field`、`zoneId`、`datePattern`、`dateFormatter` 统一声明在 `RelativeTimeFilter` 接口。
- 保持既有相对时间表达式、JSON 载荷和废弃 `Condition` API 的行为不变。

## 非目标

- 不增加滚动时长、截至当前、季度或财务周期表达式。
- 不增加通用 `CalendarPeriodFilter`、时间单位枚举或表达式解释器。
- 不向废弃的 `Operator`、`Condition` DSL 或 `LegacyConditionAdapter` 回填新能力。
- 不修改 MongoDB 与 Elasticsearch 的相对时间查询语法。
- 不增加配置项、依赖或后端专属选项。

## 方案选择

采用五个显式表达式类型：

| `FilterOperator` | Kotlin 类型 | Kotlin DSL |
|---|---|---|
| `YESTERDAY` | `YesterdayFilter` | `"field".yesterday(...)` |
| `NEXT_MONTH` | `NextMonthFilter` | `"field".nextMonth(...)` |
| `LAST_YEAR` | `LastYearFilter` | `"field".lastYear(...)` |
| `THIS_YEAR` | `ThisYearFilter` | `"field".thisYear(...)` |
| `NEXT_YEAR` | `NextYearFilter` | `"field".nextYear(...)` |

该方案与现有 `TodayFilter`、`NextWeekFilter`、`LastMonthFilter` 等 API 保持一致，生成客户端可以继续依赖 `op` discriminator 得到明确联合类型。相比通用周期表达式，它没有重复引入另一套抽象参数；相比只增加 DSL 方法，它能由 HTTP 客户端表达，并由服务端时钟统一计算范围。

## API 设计

### RelativeTimeFilter

`RelativeTimeFilter` 继续保持密封接口，并统一声明所有相对时间表达式共有的属性：

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

现有与新增 data class 通过构造器中的 `override val` 实现这些属性。`dateFormatter` 继续只服务 JVM 运行时兼容，并保持 `@JsonIgnore`；本次属性提升不改变任何现有 JSON 字段名或默认值。

### JSON 合同

五个表达式使用相同载荷结构，只有 `op` 不同：

```json
{
  "op": "YESTERDAY",
  "field": "state.createTime",
  "zoneId": "Asia/Shanghai",
  "datePattern": "yyyy-MM-dd HH:mm:ss"
}
```

`field` 为必填逻辑字段；`zoneId` 与 `datePattern` 可选。JSON 不包含 `dateFormatter`。新类型加入 `FilterExpression` 的 Jackson subtype、静态 JSON Schema 的 `oneOf`，并通过既有 Schema/OpenAPI 管线发布。

### DSL

五个 DSL 方法采用与现有方法相同的参数顺序：

```kotlin
fun String.yesterday(zoneId: ZoneId? = null, datePattern: String? = null)
fun String.nextMonth(zoneId: ZoneId? = null, datePattern: String? = null)
fun String.lastYear(zoneId: ZoneId? = null, datePattern: String? = null)
fun String.thisYear(zoneId: ZoneId? = null, datePattern: String? = null)
fun String.nextYear(zoneId: ZoneId? = null, datePattern: String? = null)
```

这些方法只构造对应表达式，不读取客户端时钟。

## 规范化与数据流

请求继续经过同一条路径：

```text
JSON / Kotlin DSL
  -> FilterExpression
  -> Elasticsearch 字段映射解析（如适用）
  -> FilterNormalizer
  -> GTE 周期起点 AND LT 下一周期起点
  -> MongoDB / Elasticsearch converter
```

所有周期都使用包含起点、不包含终点的 `[start, end)` 语义：

| 表达式 | 起点 | 终点 |
|---|---|---|
| `YESTERDAY` | 当前本地日期减一天的零点 | 当前本地日期零点 |
| `NEXT_MONTH` | 下月第一天零点 | 下下月第一天零点 |
| `LAST_YEAR` | 上年 1 月 1 日零点 | 本年 1 月 1 日零点 |
| `THIS_YEAR` | 本年 1 月 1 日零点 | 下一年 1 月 1 日零点 |
| `NEXT_YEAR` | 下一年 1 月 1 日零点 | 下下一年 1 月 1 日零点 |

`YESTERDAY` 复用现有日范围逻辑并使用偏移 `-1`；`NEXT_MONTH` 复用月范围逻辑并使用偏移 `+1`。年份由 `FilterNormalizer` 内部新增的 `yearRange` 处理偏移 `-1`、`0`、`+1`。不建立新的公开周期抽象。

`FilterNormalizer` 使用同一次 `normalize` 调用捕获的 `Clock.instant()`，确保一棵过滤树中的所有相对时间表达式共享同一查询时刻。周期边界先在表达式时区中按本地日历计算，再转换为 epoch millisecond 或按 `datePattern` 格式化；因此 DST、闰年与不同月份长度由 `java.time` 处理。

相对时间表达式继续允许出现在 `AND`、`OR`、`NOR` 与 `ELEMENT_MATCH` 中。规范化递归处理这些节点。

## 组件变更

### wow-api

- `FilterOperator` 新增五个枚举值。
- `FilterExpression` 注册五个 Jackson subtype。
- `RelativeTimeFilter` 提升四个通用属性。
- `RelativeTimeFilters.kt` 新增五个 data class，并复用现有时区与日期格式校验。

### wow-query

- `FilterDsl` 新增五个构造方法。
- `FilterNormalizer.expandRelativeTime` 识别五个新类型。
- 新增私有 `yearRange`，复用现有 `range` 与 `instantNode`。

### wow-elasticsearch

- `ElasticsearchIndexMappingResolver` 将五个新类型按 `ElasticsearchFieldUsage.RANGE` 解析。
- converter 不增加分支；规范化后仍只编译普通比较表达式。

### wow-mongo

- 不修改 converter；规范化后仍只编译普通比较表达式。

### Schema、OpenAPI 与文档

- 更新 `schema/query/v2/filter-expression.schema.json` 的联合引用及五种对象定义。
- 更新受影响的 OpenAPI 快照。
- 更新中英文查询文档中的操作符表和 DSL 示例。

## 校验与错误处理

- 非空 `zoneId` 在表达式构造时通过 `ZoneId.of` 校验；非法值在构造或反序列化阶段失败，HTTP 调用沿用现有请求解析错误处理。
- 非空 `datePattern` 在构造时通过 `DateTimeFormatter.ofPattern` 校验。
- 未指定 `zoneId` 时使用 `FilterNormalizer.defaultZoneId`。
- 未指定 `datePattern` 时输出 epoch millisecond，与现有相对时间表达式一致。
- 不为非法值增加回退、容错格式或后端差异分支。

## 测试策略

### wow-api

- 表驱动验证五种表达式的 Jackson round-trip 与 `op` discriminator。
- 通过 `RelativeTimeFilter` 访问四个公共属性，并验证 `dateFormatter` 不进入 JSON。
- 新增非法时区与非法日期格式回归测试，并保留既有相对时间覆盖。

### wow-query

- 使用固定 `Clock` 表驱动验证五个表达式展开后的精确起止时刻。
- 覆盖非 UTC 时区与闰年边界，证明 `[start, end)` 和本地日历语义。
- 验证五个 DSL 方法生成对应类型。

### wow-elasticsearch

- 表驱动验证五种表达式都以 `RANGE` usage 解析逻辑字段及多字段映射。

### wow-schema 与 wow-openapi

- 验证静态 Schema 的 `filterExpression.oneOf` 包含五个新定义。
- 验证每种新 JSON 载荷通过 Schema 校验。
- 更新并核对 OpenAPI 快照中的 discriminator 和请求体联合类型。

### 验证命令

```bash
./gradlew :wow-api:check :wow-query:check :wow-mongo:check \
  :wow-elasticsearch:check :wow-webflux:check \
  :wow-schema:check :wow-openapi:check
./gradlew detekt
```

## 完成条件

- 五种表达式可通过 Kotlin、JSON、Filter DSL、JSON Schema 与 OpenAPI 使用。
- MongoDB 与 Elasticsearch 对同一时钟、时区和日期格式产生等价的绝对范围。
- `RelativeTimeFilter` 统一暴露四个公共属性，且现有 JSON wire shape 不变。
- 既有相对时间、废弃 `Condition`、删除范围和嵌套过滤语义不变。
- 相关模块检查与 Detekt 通过。
