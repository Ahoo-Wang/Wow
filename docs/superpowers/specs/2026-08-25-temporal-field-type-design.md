# 显式时间字段类型设计

## 背景

`AggregationGroup.DateHistogram` 当前在 MongoDB 中通过 `$toDate` 转换分组字段，在 Elasticsearch 中直接使用 composite date histogram。默认 Elasticsearch 快照模板却将 `firstEventTime`、`eventTime`、`snapshotTime` 映射为 `long`，导致这些数值 epoch 字段无法直接用于 Elasticsearch 日期分桶，而 MongoDB 可以转换，形成跨后端差异。

`RelativeTimeFilter` 同时以分散的 `datePattern`、运行时 `dateFormatter` 和 `timeUnit` 描述目标字段的时间表示。十五种相对时间表达式重复携带这些属性，模型允许多个参数组合出含义不清的状态，OpenAPI 也无法把数值 epoch 与格式化字符串表达为清晰的联合类型。

本设计引入统一的时间字段类型，由查询声明目标存储字段的物理时间表示。MongoDB 与 Elasticsearch 只执行该声明，不按字段名或运行时值猜测语义。

## 目标

- 以公共可辨识联合类型表达原生日期、数值 epoch 和格式化字符串三种时间字段表示。
- `DateHistogram` 支持原生日期与数值 epoch；默认按 `NUMBER/MILLISECONDS` 解释。
- `RelativeTimeFilter` 支持原生日期、数值 epoch 与格式化字符串，并移除分散的时间表示参数。
- 将相对时间展开从 `FilterNormalizer` 拆到单一职责的内部归一化器。
- MongoDB 与 Elasticsearch 对数值 epoch 使用一致的单位转换、无效值排除、时区和桶键合同。
- Elasticsearch 对数值字段生成请求级 `date` runtime field，并在所有 composite/PIT 请求中携带它。
- 通过公共模型测试、后端真实集成测试和共享 TCK 证明合同。
- 更新中英文查询文档、JSON Schema 与 OpenAPI。

## 非目标

- 不增加 `AUTO` 或 `INFER`。查询声明是语义来源。
- 不为 DateHistogram 支持字符串时间，也不设计 Wow 自有日期格式 DSL。
- 不修改默认快照模板，不迁移或重建已有 Elasticsearch 索引。
- 不按 `firstEventTime`、`eventTime`、`snapshotTime` 等字段名硬编码行为。
- 不新增 Catalog、Scanner、normalizer 接口、工厂、注册表或后端插件点。
- 不新增依赖、模块、配置项或持久化 runtime field。
- 不暴露 MongoDB pipeline、Painless 或任意脚本入口。
- 不为近期新增的 `FilterExpression`、`AggregationQuery` 承诺 Kotlin/Java 源码或 JSON 兼容。
- 不修改废弃的 `Condition` 协议；`Condition -> FilterExpression` 继续保持现有转换语义。

## 方案比较

| 方案 | 职责与 JSON | 非法状态 | 兼容读取 | OpenAPI | 结论 |
|---|---|---|---|---|---|
| 可辨识联合类型 | `fieldType` 内聚表示方式和参数 | 每个 subtype 只拥有有效参数 | 不需要 | 可表达 `oneOf` 与 discriminator | 采用 |
| enum + 平铺参数 | `fieldType` 与 `timeUnit/datePattern` 分散 | 可构造 DATE + timeUnit 等无意义组合 | 不需要 | 难以表达条件属性 | 拒绝 |
| 保留 nullable 参数 | 继续重复当前属性 | null 组合含义模糊 | 不需要 | 联合语义不清 | 拒绝 |

`AUTO/INFER` 也被拒绝：Elasticsearch 可以读取 mapping，MongoDB 没有固定 schema；混合值和字符串格式无法得到确定的跨后端推断结果。默认 `NUMBER/MILLISECONDS` 已覆盖默认快照字段，无需再引入推断状态。

## 公共模型

公共类型命名为 `TemporalFieldType`。名称表达目标字段类型，避免 `TemporalFieldStorageType` 的冗长，也不会暗示分桶结果类型。

```kotlin
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(TemporalFieldType.Date::class, name = "DATE"),
    JsonSubTypes.Type(TemporalFieldType.NumericEpoch::class, name = "NUMBER"),
    JsonSubTypes.Type(TemporalFieldType.FormattedString::class, name = "STRING"),
)
@Schema(
    oneOf = [
        TemporalFieldType.Date::class,
        TemporalFieldType.NumericEpoch::class,
        TemporalFieldType.FormattedString::class,
    ],
    discriminatorProperty = "type",
)
sealed interface TemporalFieldType {
    data object Date : TemporalFieldType

    data class NumericEpoch(
        val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    ) : TemporalFieldType

    data class FormattedString(
        val datePattern: String,
    ) : TemporalFieldType
}
```

JSON subtype ID 固定为 `DATE`、`NUMBER`、`STRING`。`FormattedString` 在构造时要求非空 pattern 并通过 `DateTimeFormatter.ofPattern` 校验；解析出的 formatter 是 JSON 忽略的内部属性。`NumericEpoch` 使用 `java.util.concurrent.TimeUnit`，默认 `MILLISECONDS`。

能力矩阵：

| 使用位置 | DATE | NUMBER | STRING | 默认值 |
|---|---:|---:|---:|---|
| `RelativeTimeFilter` | 是 | 是 | 是 | `NUMBER/MILLISECONDS` |
| `AggregationGroup.DateHistogram` | 是 | 是 | 否 | `NUMBER/MILLISECONDS` |

`DateHistogram` 在公共模型构造阶段拒绝 `FormattedString`；其 OpenAPI 属性只声明 DATE 与 NUMBER 两个分支。无需增加第二组 capability 接口。

## API 与 JSON

### DateHistogram

`DateHistogram` 将 `fieldType` 放在 `timeZone` 前；不保留旧构造器：

```kotlin
data class DateHistogram(
    override val field: LogicalField,
    override val alias: String,
    val unit: AggregationDateUnit,
    val fieldType: TemporalFieldType = TemporalFieldType.NumericEpoch(),
    val timeZone: String = "UTC",
) : AggregationGroup
```

DSL 使用相同顺序：

```kotlin
fun dateHistogram(
    field: String,
    unit: AggregationDateUnit,
    alias: String,
    fieldType: TemporalFieldType = TemporalFieldType.NumericEpoch(),
    timeZone: ZoneId = ZoneOffset.UTC,
)
```

默认快照时间示例：

```json
{
  "type": "DATE_HISTOGRAM",
  "field": "snapshotTime",
  "fieldType": {
    "type": "NUMBER",
    "timeUnit": "MILLISECONDS"
  },
  "alias": "day",
  "unit": "DAY",
  "timeZone": "Asia/Shanghai"
}
```

原生日期字段示例：

```json
{
  "type": "DATE_HISTOGRAM",
  "field": "state.createdAt",
  "fieldType": { "type": "DATE" },
  "alias": "day",
  "unit": "DAY"
}
```

缺少 `fieldType` 时使用普通构造默认值 `NUMBER/MILLISECONDS`。这是新协议的默认行为，不是 legacy 兼容层；不增加自定义反序列化器，也不测试旧 DateHistogram JSON 的历史后端语义。

### RelativeTimeFilter

`RelativeTimeFilter` 统一声明：

```kotlin
sealed interface RelativeTimeFilter : FilterExpression {
    val field: LogicalField
    val fieldType: TemporalFieldType
    val zoneId: String?
}
```

各具体 Filter 删除 `datePattern`、`dateFormatter`、`timeUnit`，改为单个默认 `fieldType`。通用构造顺序是 `field`、表达式专属参数、`fieldType`、`zoneId`。Filter DSL 同样以 `fieldType` 替换旧参数，不保留源码兼容重载。

格式化字符串示例：

```json
{
  "op": "TODAY",
  "field": "state.createdAtText",
  "fieldType": {
    "type": "STRING",
    "datePattern": "yyyy-MM-dd HH:mm:ss"
  },
  "zoneId": "Asia/Shanghai"
}
```

原生日期示例：

```json
{
  "op": "THIS_WEEK",
  "field": "state.createdAt",
  "fieldType": { "type": "DATE" },
  "zoneId": "Asia/Shanghai"
}
```

不保留旧的平铺 RelativeTime JSON 读取。现有 `op` subtype ID 与 operator 名称保持不变。

## 相对时间归一化

`FilterNormalizer` 继续负责结构归一化、默认 deletion scope、捕获一次查询时刻和最终逻辑简化。相对时间递归展开移入内部具体类：

```kotlin
internal class RelativeTimeFilterNormalizer(
    private val defaultZoneId: ZoneId,
) {
    fun normalize(expression: FilterExpression, now: Instant): FilterExpression
}
```

它不拥有 `Clock`，不建立接口或工厂。`FilterNormalizer` 在一次 `normalize` 调用中只执行一次 `clock.instant()`，然后把同一个 `now` 交给整棵表达式树，保持跨午夜的一致性。

归一化器递归处理 `AND`、`OR`、`NOR` 与 `ELEMENT_MATCH`，保留现有日、周、月、年、时区、DST、闰年和半开区间 `[start, end)` 语义。WEEK 继续从 Monday 开始。

边界输出按 `fieldType` 决定：

- `Date`：生成毫秒精度的内部 `Instant` POJO 节点。
- `NumericEpoch`：按 `timeUnit` 生成整数边界；使用现有精确换算并保留溢出失败。
- `FormattedString`：使用已校验的 formatter 生成字符串边界。

DATE 使用毫秒精度，因为 MongoDB BSON Date 只有毫秒精度。MongoDB 过滤 converter 将内部 `Instant` 转为 `java.util.Date`；Elasticsearch converter 将其转为 epoch milliseconds。该 POJO 只存在于进程内归一化结果，不形成新的公开 JSON 协议。

## MongoDB DateHistogram

MongoDB 不持有字段 schema，完全按查询声明编译。

### DATE

DATE 将字段直接交给 `$dateTrunc`，不再生成 `$toDate`。缺失与 null 沿用现有分组键排除；其他与 DATE 声明冲突的值交给 MongoDB 原生错误，不增加防御分支。

### NUMBER

每个 NUMBER DateHistogram 使用按 group index 生成的内部临时字段，避免重复计算和用户可控名称。pipeline 依次：

1. 判断原值为单个数值；数组、缺失、null 和字符串不满足。
2. 通过 `$convert(onError: null, onNull: null)` 得到 `Long`，并比较原值与整数值，排除小数和 Long 范围外的值。
3. 使用 Decimal128 中间值按 `TimeUnit` 乘或除到 epoch milliseconds，再 `$convert` 为 Long；换算溢出得到 null。
4. 将 epoch milliseconds 转为 BSON Date，失败得到 null。
5. 在 `$group` 前排除临时字段为 null 的文档。
6. 对临时 Date 执行 `$dateTrunc`，最后 `$toLong` 返回桶起点。

比毫秒更细的单位按 `TimeUnit.convert` 语义向零截断；秒、分钟、小时和天使用精确倍率。时区、SECOND 固定间隔、其他 calendar interval，以及 WEEK 的 `startOfWeek: Monday` 保持现有行为。

## Elasticsearch 字段解析

复用 `ElasticsearchIndexMapping` 已有私有 mapping 结构，只增加直接返回物理字段路径的内部方法，不增加解析结果 DTO：

```kotlin
internal fun resolveTemporal(field: String, fieldType: TemporalFieldType): String
```

它按声明验证实际 mapping 与 doc values：

- DATE：`date` 或 `date_nanos`。
- NUMBER：具有 doc values 的数值 mapping。
- STRING：可进行范围比较的字符串 mapping。

不匹配时抛出 `ElasticsearchFieldResolutionException`，错误包含索引、字段、声明类型、实际 mapping 类型和期望类型。逻辑字段与已有 multi-field 解析规则继续复用，不向 aggregation compiler 暴露 `ElasticsearchMappedField` 或 `Property.Kind`。

RelativeTimeFilter 在字段解析后再由 converter 归一化，因此同一方法也验证 DATE、NUMBER、STRING 声明。没有 mapping 的自定义 converter 路径继续按调用方提供的物理字段工作，不虚构 mapping 校验。

## Elasticsearch DateHistogram

### DATE

DATE 使用解析后的 `date/date_nanos` 字段直接建立 composite date histogram，不生成 runtime field。

### NUMBER

NUMBER 为每个分组生成请求级 `date` runtime field，名称按 group index 生成。Painless 脚本：

- 通过 params 接收解析后的字段名和单位换算参数，不拼接用户输入。
- 只读取恰好一个 doc value；缺失、空值和多值不 emit。
- 只接受有限、处于 Long 范围且等于自身整数值的 Number。
- 比毫秒更细的单位使用整数除法；更粗的单位使用 `Math.multiplyExact`，只捕获该换算产生的 `ArithmeticException` 并不 emit，不使用宽泛异常吞掉其他脚本错误。
- 以 epoch milliseconds 调用 date runtime field 的 `emit(long)`。

runtime field 只加入 `ElasticsearchAggregationPlan.runtimeMappings`。现有 `ElasticsearchAggregationPager` 已在每一次 PIT/composite SearchRequest 上附带该 Map，包括 after-key 后续页，因此不增加新的分页状态或传递层。

date histogram 对 runtime date 执行现有 interval、time zone 和 sort 配置。Elasticsearch composite 返回的日期桶键与原生 date histogram 一样，是桶起点的 epoch milliseconds。

## STRING 边界

STRING 继续供 RelativeTimeFilter 使用：归一化器计算时间边界并按同一 `datePattern` 输出字符串，然后执行范围比较。调用方负责选择能保持时间顺序的存储格式。

DateHistogram 必须把每个存储字符串解析为时间点。MongoDB `$dateFromString` 使用 `%Y-%m-%d` 一类格式符，Elasticsearch/Java 使用 `yyyy-MM-dd` 一类 `DateTimeFormatter` 语法，时区和 locale 规则也不同。本次不引入格式翻译 DSL，不提供单后端降级，因此 DateHistogram 在公共模型层拒绝 STRING。

## 数值无效值合同

NUMBER 只接受单值、有限、可无损表示为 Long 的整数 epoch。处理如下：

| 输入 | MongoDB | Elasticsearch | 结果 |
|---|---|---|---|
| missing/null/empty | 转换为 null | 无 doc value | 不进入桶 |
| 多值 | 数组不满足单值数值 | `doc[field].size() != 1` | 不进入桶 |
| 非数值 | `$isNumber` 失败 | 数值 mapping 下无有效 doc value；非数值 mapping 在解析阶段冲突 | 不产生桶 |
| 小数 | 整数等值检查失败 | 整数等值检查失败 | 不进入桶 |
| 单位换算溢出 | `$convert` 得到 null | `Math.multiplyExact` 失败 | 不进入桶 |

Elasticsearch 数值 mapping 默认会在写入阶段拒绝非数值；真实集成测试使用允许 malformed 输入的数值字段证明无 doc value 时查询会排除该文档，并另测非数值 mapping 与 NUMBER 声明的清晰冲突。

任一 DateHistogram 分组键无效时，该文档不会形成 composite/group key，因此也不参与该聚合行的指标。实现只处理合同列出的情况，不增加脚本 catch-all、后端结果再校验或推测性容错。

## Schema 与 OpenAPI

- `TemporalFieldType` 总体 schema 使用 DATE、NUMBER、STRING 的 `oneOf` 和 `type` discriminator。
- RelativeTimeFilter 的 `fieldType` 引用完整联合。
- DateHistogram 的 `fieldType` 属性显式收窄为 DATE、NUMBER。
- 有默认值的 `fieldType` 与 `timeUnit` 在 JSON Schema/OpenAPI 中标为可选，并分别说明默认 `NUMBER/MILLISECONDS` 与 `MILLISECONDS`；`FormattedString.datePattern` 保持必填。
- 更新 `schema/query/v2/filter-expression.schema.json`，使用嵌套 `fieldType` 替换旧的平铺属性。
- 更新 wow-schema 与 wow-openapi 快照，验证 discriminator、必填属性和 `additionalProperties`。
- 不增加旧 JSON alias、creator 或 deserializer。

## TDD 与测试策略

每个行为先增加一个最小失败测试并运行，确认失败来自缺失行为，再实现最小修复并转绿；不先批量编写全部测试。

### 公共 API 与 JSON

- DATE、NUMBER、STRING JSON round-trip。
- NUMBER 默认 `MILLISECONDS`。
- RelativeTimeFilter 三种类型及默认值。
- DateHistogram DATE、NUMBER及默认值。
- DateHistogram + STRING、空/非法 `datePattern` 被公共模型拒绝。
- 不增加旧 FilterExpression/AggregationQuery JSON 兼容测试。
- Condition 未修改，不增加重复兼容测试；运行现有套件证明回归状态。

### 相对时间归一化

- 新建 `RelativeTimeFilterNormalizerTest`，使用固定 `now` 验证 DATE、milliseconds、seconds、nanoseconds 与 STRING 精确边界。
- 将现有相对日/周/月/年、时区、DST、闰年、Monday week 和 `[start, end)` 测试迁入新测试类。
- `FilterNormalizerTest` 只保留结构、deletion、逻辑简化及一次委托覆盖。
- MongoDB 与 Elasticsearch 各增加一个 DATE 范围转换测试；共享查询 TCK 使用真实原生日期证明结果一致。

### MongoDB

- 编译测试证明 DATE 直接 `$dateTrunc`，NUMBER 生成单位换算和无效值排除 pipeline。
- 真实 pipeline 集成覆盖 BSON Date、milliseconds、seconds、时区和 Monday week。
- 向原始 collection 写入 missing、null、empty、multi、string、fraction、overflow，验证只返回有效桶。

### Elasticsearch

- mapping resolver 覆盖 date、date_nanos、数值、字符串、doc-values 缺失及声明冲突。
- compiler 覆盖 DATE 无 runtime field、NUMBER runtime script 参数和各类单位换算。
- pager 捕获多页 SearchRequest，验证每页携带同一 runtime mappings。
- 真实集成覆盖 date、date_nanos、long、允许 malformed 的数值字段、空值、多值、小数和溢出。
- 使用默认模板和真实快照分别验证 `firstEventTime`、`eventTime`、`snapshotTime` 可按 NUMBER/MILLISECONDS 分桶。

### 跨后端 TCK

- 现有 `Instant createdAt` DateHistogram 场景改为显式 DATE，继续验证时区、SECOND 与 Monday week。
- 使用固定 `snapshotTime` 和显式 NUMBER/MILLISECONDS，在 MongoDB 与 Elasticsearch 中断言完全相同的桶键和计数。
- 增加 epoch seconds 字段与显式 NUMBER/SECONDS 场景。
- 增加 RelativeTime DATE 查询场景，证明内部 Instant 在两个 converter 中产生相同范围。

### Schema、OpenAPI 与文档

- 验证 JSON Schema 与 OpenAPI 的三分支总联合及 DateHistogram 两分支限制。
- 更新中英文 `documentation/docs/*/guide/query.md`：解释字段类型声明、默认值、RelativeTime 三种表示、DateHistogram STRING 限制、`snapshotTime` 示例和 epoch-millisecond 桶键。
- 将“Elasticsearch 必须映射为 date/date_nanos”改为按 DATE/NUMBER 声明分别说明 mapping 要求。

## 验证命令

```bash
./gradlew :wow-api:check :wow-query:check \
  :wow-mongo:check :wow-elasticsearch:check \
  :wow-schema:check :wow-openapi:check

./gradlew :wow-mongo:integrationTest \
  :wow-elasticsearch:integrationTest

./gradlew detekt

cd documentation
pnpm docs:build
```

每个 TDD 阶段先运行最窄测试；以上是完成前的整体验证。另运行 `git diff --check`。不以本地单元测试替代真实 integrationTest，也不把发布、部署或生产验证算入本 PR。

## 组件变更

- `wow-api`：`TemporalFieldType`、DateHistogram、RelativeTimeFilter 与具体表达式、Jackson/OpenAPI 注解。
- `wow-query`：Aggregation DSL、Filter DSL、内部 `RelativeTimeFilterNormalizer`、`FilterNormalizer` 委托。
- `wow-mongo`：DATE/NUMBER DateHistogram 编译、Instant 范围值适配及集成测试。
- `wow-elasticsearch`：时间 mapping 验证、NUMBER runtime date、Instant 范围值适配、分页请求验证及集成测试。
- `test/wow-tck`：原生日期、epoch milliseconds、epoch seconds 与 RelativeTime DATE 共享场景。
- `wow-schema`、`wow-openapi`、静态 query schema：联合类型与快照。
- `documentation`：中英文查询合同和示例。

不改变 Gradle 模块结构、feature capability、默认 Elasticsearch 模板、CI/CD 或发布配置。

## 完成条件

- 公共 API、JSON Schema 与 OpenAPI 准确表达 `TemporalFieldType` 及每个使用位置允许的 subtype。
- RelativeTimeFilter 的 DATE、NUMBER、STRING 都能归一化并由两个后端执行。
- DateHistogram 的 DATE 与 NUMBER 在 MongoDB、Elasticsearch 中产生一致的时间桶；STRING 在公共模型边界失败。
- 默认模板三个 long 时间字段无需重建索引即可执行 Elasticsearch DateHistogram。
- 数值无效值合同由两个真实后端测试证明，pagination 每页携带 runtime mappings。
- 桶键始终为桶起点 epoch milliseconds。
- 现有 Condition 转换语义、时区、SECOND、Monday week 和查询结构行为未回归。
- 所有目标模块检查、真实 integrationTest、Detekt、文档构建与 diff 检查通过。

## 分支与交付

独立分支 `feat/temporal-field-type` 基于刷新后的 `origin/main` 提交 `0e7161cb5` 创建。设计文档、实现、测试和文档进入同一个独立 PR。PR 创建前再次刷新 main 并处理漂移，报告本地测试、真实集成、文档、兼容性范围和未执行的发布/部署证据。未经后续明确授权不合并 PR。
