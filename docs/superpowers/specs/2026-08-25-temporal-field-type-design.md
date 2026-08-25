# 显式时间字段类型设计

## 背景

`AggregationGroup.DateHistogram` 当前在 MongoDB 中通过 `$toDate` 转换分组字段，在 Elasticsearch 中直接使用 composite date histogram。默认 Elasticsearch 快照模板却将 `firstEventTime`、`eventTime`、`snapshotTime` 映射为 `long`，导致这些数值 epoch 字段无法直接用于 Elasticsearch 日期分桶，而 MongoDB 可以转换，形成跨后端差异。

`RelativeTimeFilter` 同时以分散的 `datePattern`、运行时 `dateFormatter` 和 `timeUnit` 描述目标字段的时间表示。十五种相对时间表达式重复携带这些属性，模型允许多个参数组合出含义不清的状态，OpenAPI 也无法把数值 epoch 与格式化字符串表达为清晰的联合类型。

本设计引入统一的时间字段类型，由查询声明目标存储字段的物理时间表示。MongoDB 与 Elasticsearch 只执行该声明，不按字段名或运行时值猜测语义。

## 目标

- 以通用 `FieldType` 骨架及其唯一子接口 `Temporal` 表达原生日期、数值 epoch 和格式化字符串。
- `LogicalField` 统一持有 name 与可选 type，并在 JSON 中接受字符串或对象。
- `DateHistogram` 支持原生日期与数值 epoch；默认按 `NUMBER/MILLISECONDS` 解释。
- `DateHistogram.timeZone` 缺省时使用当前 JVM 系统时区。
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

| 方案 | 字段身份 | 类型归属 | JSON | 结论 |
|---|---|---|---|---|
| 操作持有 `field + fieldType` | 路径独立 | RelativeTime/DateHistogram 重复持有 | 两个相邻属性 | 拒绝 |
| `LogicalField(name, type?)` | 路径与可选类型形成一个值 | 所有操作共享字段骨架 | 无类型为字符串，有类型为对象 | 采用 |
| enum + 平铺参数 | 路径独立 | 参数散落 | 非法组合多 | 拒绝 |

`AUTO/INFER` 被拒绝：Elasticsearch 可以读取 mapping，MongoDB 没有固定 schema；混合值和字符串格式无法得到确定的跨后端推断结果。时间操作遇到未声明类型的 LogicalField 时使用明确默认 `NUMBER/MILLISECONDS`，不进行推断。

## 公共模型

### FieldType 骨架

`FieldType` 是通用字段类型根接口，当前只有一个子接口 `Temporal`。DATE、NUMBER、STRING 是 Temporal 的叶子实现；JSON 不增加额外 `TEMPORAL` 包装层，仍以叶子 `type` 作为 discriminator。未来如出现真实的非时间字段类型，可新增 FieldType 子接口而不改变 LogicalField。

```kotlin
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(FieldType.Temporal.Date::class, name = "DATE"),
    JsonSubTypes.Type(FieldType.Temporal.NumericEpoch::class, name = "NUMBER"),
    JsonSubTypes.Type(FieldType.Temporal.FormattedString::class, name = "STRING"),
)
@Schema(
    oneOf = [FieldType.Temporal::class],
)
sealed interface FieldType {
    @Schema(
        oneOf = [
            FieldType.Temporal.Date::class,
            FieldType.Temporal.NumericEpoch::class,
            FieldType.Temporal.FormattedString::class,
        ],
        discriminatorProperty = "type",
    )
    sealed interface Temporal : FieldType {
        data object Date : Temporal

        data class NumericEpoch(
            val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ) : Temporal

        data class FormattedString(
            val datePattern: String? = null,
            @get:JsonIgnore
            val dateFormatter: DateTimeFormatter? = null,
        ) : Temporal {
            init {
                require((datePattern == null) != (dateFormatter == null))
            }
        }
    }
}
```

JSON subtype ID 固定为 `DATE`、`NUMBER`、`STRING`。`FormattedString` 将 JSON `datePattern` 与 Condition runtime `dateFormatter` 内聚到同一个 subtype，并要求两者恰好提供一个。非空 pattern 通过 `DateTimeFormatter.ofPattern` 校验；`dateFormatter` 使用 `@JsonIgnore`，不进入 JSON/OpenAPI。`NumericEpoch` 使用 `java.util.concurrent.TimeUnit`，默认 `MILLISECONDS`。

### LogicalField

`LogicalField` 从纯字符串路径扩展为 `name + optional type`。`value` 重命名为 `name`；校验、`toString()` 与后端路径解析都只使用 name。

```kotlin
@JsonSerialize(using = LogicalFieldJsonSerializer::class)
@JsonDeserialize(using = LogicalFieldJsonDeserializer::class)
@Schema(types = ["string", "object"])
data class LogicalField(
    val name: String,
    val type: FieldType? = null,
) {
    init {
        require(LOGICAL_FIELD_PATTERN.matches(name)) { "Logical field is invalid: [$name]." }
    }

    override fun toString(): String = name
}
```

Jackson 在 LogicalField 公共序列化边界集中实现双形态：

- `type == null` 序列化为字符串，字符串反序列化为 `LogicalField(name, null)`。
- `type != null` 序列化为 `{ "name": ..., "type": ... }`，对象反序列化时要求 name，type 可选。
- 其他 JSON token 在公共边界失败；后端不识别 string/object wire shape。

OpenAPI 3.1 使用 `types = ["string", "object"]` 并发布对象属性 name/type；静态 JSON Schema 使用 `oneOf` 精确表达 string 与 object。LogicalField 的 equality/hashCode 同时包含 name 与 type，typed 与 untyped 字段是不同的查询声明。

时间操作通过一个公共函数解析类型：null 返回 `FieldType.Temporal.NumericEpoch(MILLISECONDS)`，Temporal 原样返回，未来非 Temporal 类型在时间操作构造阶段失败。

能力矩阵：

| 使用位置 | 未声明 | DATE | NUMBER | STRING |
|---|---:|---:|---:|---:|
| `RelativeTimeFilter` | NUMBER/MILLISECONDS | 是 | 是 | 是 |
| `AggregationGroup.DateHistogram` | NUMBER/MILLISECONDS | 是 | 是 | 否 |

### 分桶单位与存储单位

本设计不新增 `AggregationDateUnit`。它是现有 DateHistogram 公共类型，继续表达分桶的日历单位：YEAR、QUARTER、MONTH、WEEK、DAY、HOUR、MINUTE、SECOND。

`java.util.concurrent.TimeUnit` 只用于 `NumericEpoch` 的存储单位换算。它表达固定时长，包含纳秒、微秒和毫秒，却不包含 WEEK、MONTH、QUARTER、YEAR；月份、季度、年份和受 DST 影响的日期分桶也不能用固定毫秒数表达。因此两者职责不同：

- `AggregationDateUnit`：如何按日历截断和分桶。
- `TimeUnit`：数值 epoch 字段以什么单位存储。

## API 与 JSON

### DateHistogram

`DateHistogram` 不再单独持有 fieldType，直接读取 `field.type`；不保留旧构造器：

```kotlin
data class DateHistogram(
    @get:Schema(description = "Untyped fields default to NUMBER/MILLISECONDS; DATE and NUMBER are supported.")
    override val field: LogicalField,
    override val alias: String,
    val unit: AggregationDateUnit,
    val timeZone: String = ZoneId.systemDefault().id,
) : AggregationGroup {
    init {
        require(field.temporalTypeOrDefault() !is FieldType.Temporal.FormattedString)
        ZoneId.of(timeZone)
    }
}
```

DSL 提供 String 简写与 LogicalField 主入口。String 简写创建 untyped LogicalField；显式类型使用 LogicalField：

```kotlin
fun dateHistogram(
    field: LogicalField,
    unit: AggregationDateUnit,
    alias: String,
    timeZone: ZoneId = ZoneId.systemDefault(),
)

fun dateHistogram(
    field: String,
    unit: AggregationDateUnit,
    alias: String,
    timeZone: ZoneId = ZoneId.systemDefault(),
)
```

`timeZone` 缺省时在构造查询的 JVM 上读取当前系统时区。JSON Schema/OpenAPI 无法把动态系统值写成固定 default literal，因此通过字段说明记录该语义；需要跨环境确定结果的请求应显式传入时区。

默认快照时间示例：

```json
{
  "type": "DATE_HISTOGRAM",
  "field": {
    "name": "snapshotTime",
    "type": {
      "type": "NUMBER",
      "timeUnit": "MILLISECONDS"
    }
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
  "field": {
    "name": "state.createdAt",
    "type": { "type": "DATE" }
  },
  "alias": "day",
  "unit": "DAY"
}
```

field 为字符串或对象但 type 缺失时，DateHistogram 使用 `NUMBER/MILLISECONDS`。LogicalField 自定义 serializer/deserializer 是双形态字段协议的唯一公共读取边界，不承担旧 DateHistogram 后端语义兼容。

### RelativeTimeFilter

`RelativeTimeFilter` 统一声明：

```kotlin
sealed interface RelativeTimeFilter : FilterExpression {
    val field: LogicalField
    val zoneId: String?
}
```

各具体 Filter 删除分散的 `datePattern`、`dateFormatter`、`timeUnit`，类型统一位于 field。通用构造顺序是 field、表达式专属参数、zoneId。共享构造校验调用 `field.temporalTypeOrDefault()`，因此未来非 Temporal 类型会在 RelativeTimeFilter 公共模型边界失败。Filter DSL 接受可选 `FieldType.Temporal` 并创建对应 LogicalField，不保留旧参数重载。`LegacyConditionAdapter` 将 String pattern 或任意 `DateTimeFormatter` 放入 LogicalField.type，不新增 adapter 层。

```kotlin
fun String.today(
    type: FieldType.Temporal? = null,
    zoneId: ZoneId? = null,
)
```

格式化字符串示例：

```json
{
  "op": "TODAY",
  "field": {
    "name": "state.createdAtText",
    "type": {
      "type": "STRING",
      "datePattern": "yyyy-MM-dd HH:mm:ss"
    }
  },
  "zoneId": "Asia/Shanghai"
}
```

原生日期示例：

```json
{
  "op": "THIS_WEEK",
  "field": {
    "name": "state.createdAt",
    "type": { "type": "DATE" }
  },
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

边界输出按 `field.temporalTypeOrDefault()` 决定：

- `Date`：生成毫秒精度的内部 `Instant` POJO 节点。
- `NumericEpoch`：按 `timeUnit` 生成整数边界；使用现有精确换算并保留溢出失败。
- `FormattedString`：使用 pattern 解析出的 formatter 或 Condition 原样提供的 runtime formatter 生成字符串边界。

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
internal fun resolveTemporal(field: LogicalField, docValuesRequired: Boolean): LogicalField
```

它按声明验证实际 mapping 与 doc values：

- DATE：`date` 或 `date_nanos`。
- NUMBER：具有 doc values 的数值 mapping。
- STRING：可进行范围比较的字符串 mapping。

不匹配时抛出 `ElasticsearchFieldResolutionException`，错误包含索引、字段、声明类型、实际 mapping 类型和期望类型。返回值通过 `field.copy(name = resolvedName)` 保留原 type；逻辑字段与已有 multi-field 解析规则继续复用，不向 aggregation compiler 暴露 `ElasticsearchMappedField` 或 `Property.Kind`。

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

STRING 继续供 RelativeTimeFilter 使用：归一化器计算时间边界并使用 `datePattern` 解析出的 formatter 或 Condition runtime formatter 输出字符串，然后执行范围比较。调用方负责选择能保持时间顺序的存储格式。

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

- `FieldType`/`FieldType.Temporal` schema 使用 DATE、NUMBER、STRING 的 `oneOf` 和 `type` discriminator。
- `LogicalField` 的 OpenAPI 3.1 type 同时包含 string/object；对象属性为必填 name 与可选 type。
- DateHistogram.field 的 OpenAPI 说明明确限制为 untyped/DATE/NUMBER；公共构造验证负责拒绝 STRING 和未来非 Temporal 类型。
- 静态 JSON Schema 的 logicalField 使用 `oneOf` 表达字符串路径或 `{name,type}` 对象。
- `timeUnit` 可选且默认 `MILLISECONDS`；STRING JSON 中 `FormattedString.datePattern` 必填，`dateFormatter` 完全隐藏。
- 更新 `schema/query/v2/filter-expression.schema.json`，让所有 logical field 位置复用双形态定义；RelativeTime 不再平铺时间参数。
- 更新 wow-schema 与 wow-openapi 快照，验证 discriminator、必填属性和 `additionalProperties`。
- 只为 LogicalField 双形态增加集中 serializer/deserializer，不增加操作级 alias、creator 或后端读取分支。

## TDD 与测试策略

每个行为先增加一个最小失败测试并运行，确认失败来自缺失行为，再实现最小修复并转绿；不先批量编写全部测试。

### 公共 API 与 JSON

- FieldType Temporal 的 DATE、NUMBER、STRING JSON round-trip。
- LogicalField 字符串/对象双形态 round-trip；对象必须使用 name/type，非法 token 和缺失 name 失败。
- NUMBER 默认 `MILLISECONDS`。
- RelativeTimeFilter 通过 field.type 使用三种类型，未声明时默认 NUMBER/MILLISECONDS。
- DateHistogram 通过 field.type 使用 DATE、NUMBER，未声明时默认 NUMBER/MILLISECONDS。
- DateHistogram 缺省 `timeZone` 等于 `ZoneId.systemDefault().id`；跨后端 TCK 显式传入时区以保持环境无关。
- DateHistogram + STRING、空/非法 `datePattern` 被公共模型拒绝。
- `FormattedString` 拒绝 pattern/formatter 同时为空或同时存在；Condition 的 String pattern 与任意 runtime `DateTimeFormatter` 保持原有格式化语义。
- 不增加旧 FilterExpression/AggregationQuery JSON 兼容测试。
- Condition 协议不修改；更新现有 adapter 断言以验证统一模型，不另建兼容层或重复测试套件。

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

### Schema、OpenAPI 与文档

- 验证 FieldType 骨架、Temporal 三分支、LogicalField string/object 双形态及 DateHistogram STRING 拒绝。
- 更新中英文 `documentation/docs/*/guide/query.md`：解释字段类型声明、默认值、RelativeTime 三种表示、DateHistogram STRING 限制、`snapshotTime` 示例和 epoch-millisecond 桶键。
- 文档说明 DateHistogram 默认使用当前 JVM 系统时区；跨环境稳定的请求应显式声明 `timeZone`。
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

- `wow-api`：`FieldType`、LogicalField 双形态序列化、DateHistogram、RelativeTimeFilter 与 Jackson/OpenAPI 注解。
- `wow-query`：Aggregation DSL、Filter DSL、内部 `RelativeTimeFilterNormalizer`、`FilterNormalizer` 委托。
- `wow-mongo`：DATE/NUMBER DateHistogram 编译、Instant 范围值适配及集成测试。
- `wow-elasticsearch`：时间 mapping 验证、NUMBER runtime date、Instant 范围值适配、分页请求验证及集成测试。
- `test/wow-tck`：原生日期、epoch milliseconds 与 epoch seconds 共享场景。
- `wow-schema`、`wow-openapi`、静态 query schema：联合类型与快照。
- `documentation`：中英文查询合同和示例。

不改变 Gradle 模块结构、feature capability、默认 Elasticsearch 模板、CI/CD 或发布配置。

## 完成条件

- 公共 API、JSON Schema 与 OpenAPI 准确表达 `FieldType -> Temporal` 骨架、LogicalField string/object 双形态及时间操作约束。
- RelativeTimeFilter 的 DATE、NUMBER、STRING 都能归一化并由两个后端执行。
- DateHistogram 的 DATE 与 NUMBER 在 MongoDB、Elasticsearch 中产生一致的时间桶；STRING 在公共模型边界失败。
- 默认模板三个 long 时间字段无需重建索引即可执行 Elasticsearch DateHistogram。
- 数值无效值合同由两个真实后端测试证明，pagination 每页携带 runtime mappings。
- 桶键始终为桶起点 epoch milliseconds。
- 现有 Condition 转换语义、时区、SECOND、Monday week 和查询结构行为未回归。
- 所有目标模块检查、真实 integrationTest、Detekt、文档构建与 diff 检查通过。

## 分支与交付

独立分支 `feat/temporal-field-type` 基于刷新后的 `origin/main` 提交 `0e7161cb5` 创建。设计文档、实现、测试和文档进入同一个独立 PR。PR 创建前再次刷新 main 并处理漂移，报告本地测试、真实集成、文档、兼容性范围和未执行的发布/部署证据。未经后续明确授权不合并 PR。
