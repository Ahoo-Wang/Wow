# 聚合算术表达式设计

## 背景

Wow 8.12 的 `AggregationQuery` 已支持 Elements 展开、分组和 Count、Sum、Avg、Min、Max 指标。数值指标已经通过 `AggregationExpression` 接收输入，但当前唯一实现是 `Field`，因此只能聚合单个物理字段，不能表达 `price * quantity` 或 `price * quantity - discount` 这类常见业务计算。

现有公共模型已经为表达式预留非 `sealed` 接口和缺省 `FIELD` JSON subtype。本设计沿用该边界，只增加可移植的基础算术，不引入字符串公式、字段目录或后端原始脚本入口。

## 目标

- 数值指标支持字段、有限数值常量以及 `+`、`-`、`*`、`/` 递归组合。
- 表达式只作为 Sum、Avg、Min、Max 的输入，不改变分组模型。
- MongoDB 与 Elasticsearch 对计算表达式返回相同的有限 `Double` 结果合同。
- 缺失、`null`、非数值、空数组、多元素数组、除零或非有限表达式结果不参与指标计算。
- 保持现有字段表达式 JSON、Kotlin DSL 和纯字段执行路径兼容。
- 复用现有结构限制、HTTP expensive guard、OpenAPI 和双后端 TCK。

## 非目标

- 不支持一元运算、Abs、Round、Coalesce 或其他数学函数。
- 不支持分组表达式、metric alias 引用、Having、Batch 或 Facet。
- 不解析字符串公式，不暴露 MongoDB pipeline、`$function`、JavaScript、Painless 或 Elasticsearch script。
- 不增加字段 Catalog、Scanner、类型枚举或跨后端 mapping 证明器。
- 不增加依赖、Gradle 模块、配置项、表达式缓存、持久化 runtime field 或 CI 工作流。
- 不改变现有纯 `Field` 指标对后端 mapping 错误和非有限结果的处理。

## 公共模型

`AggregationExpression` 保持非 `sealed`，保留 `Field` 为缺省 JSON subtype，并增加两个实现：

```kotlin
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    property = "type",
    defaultImpl = AggregationExpression.Field::class,
)
@JsonSubTypes(
    JsonSubTypes.Type(AggregationExpression.Field::class, name = "FIELD"),
    JsonSubTypes.Type(AggregationExpression.Constant::class, name = "CONSTANT"),
    JsonSubTypes.Type(AggregationExpression.Binary::class, name = "BINARY"),
)
interface AggregationExpression {
    data class Field(val field: LogicalField) : AggregationExpression

    data class Constant(val value: Double) : AggregationExpression

    data class Binary(
        val operator: AggregationExpressionOperator,
        val left: AggregationExpression,
        val right: AggregationExpression,
    ) : AggregationExpression
}

enum class AggregationExpressionOperator {
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
}
```

`Constant` 构造时要求 `value.isFinite()`。`Binary` 始终有且只有左右两个操作数；不使用可变参数列表，因此减法和除法的结合方向不需要额外约定。

非 `sealed` 接口避免新增框架 subtype 破坏调用方源码中的穷举假设。当前版本只接受三个已注册实现；程序内传入其他实现时，在 `AggregationQuery` 结构校验阶段返回 unsupported expression 错误。

### JSON 合同

已有字段表达式仍可省略 `type`：

```json
{
  "expression": {
    "field": "state.amount"
  }
}
```

新增表达式使用显式 subtype：

```json
{
  "type": "NUMERIC",
  "function": "SUM",
  "expression": {
    "type": "BINARY",
    "operator": "SUBTRACT",
    "left": {
      "type": "BINARY",
      "operator": "MULTIPLY",
      "left": {"field": "price"},
      "right": {"field": "quantity"}
    },
    "right": {"type": "CONSTANT", "value": 10.0}
  },
  "alias": "total"
}
```

JSON 不接受非有限数值。未知 `type`、未知属性或缺少 `Binary` 必填属性时，继续由现有严格请求解码拒绝。

## 结构限制

`AggregationQuery` 构造时递归检查所有 `AggregationMetric.Numeric.expression`：

- 叶子节点深度为 1，`Binary` 深度为 `1 + max(left, right)`。
- 单个表达式最大深度为 `MAX_EXPRESSION_DEPTH = 8`。
- `Field`、`Constant`、`Binary` 各计一个节点。
- 同一查询所有数值指标最多包含 `MAX_EXPRESSION_NODES = 256` 个节点。
- 每次在多个指标中声明相同表达式都分别计数。

这两个固定上限与现有 Elements、group、metric 和 sort 上限一样，不增加配置项。现有最多 64 个纯字段数值指标只包含 64 个表达式节点，仍然有效。

公共层只验证表达式结构和有限常量，不验证字段存在性、字段类型或物理 mapping。

## 求值合同

表达式在根快照或最内层 Element 作用域中按每个文档求值：

- 没有 Elements 时，`Field` 使用快照绝对逻辑路径。
- 有 Elements 时，`Field` 相对最内层 Element，后端先解析为绝对物理路径。
- 每个字段操作数必须在当前文档中恰好产生一个数值；数值标量和单元素数值数组等价。
- 缺失、`null`、字符串、布尔值、对象、空数组或多元素数组不产生表达式值。
- 所有字段值和常量在运算前统一为 `Double`。
- 任一操作数没有值时，当前 `Binary` 没有值。
- `DIVIDE` 的右值为 `0.0` 或 `-0.0` 时，当前 `Binary` 没有值。
- 任一中间结果或最终结果为 NaN 或正负无穷时，当前 `Binary` 没有值。

Sum、Avg、Min、Max 忽略没有表达式值的文档。没有任何有效值时返回 `null`；Count 不受表达式影响。有效指标结果继续规范化为有限 `Double`。

纯 `Field` 表达式继续走现有原生字段聚合路径，不因本次增强改变其 mapping、数组或错误语义。上述逐文档无效值合同适用于 `Constant` 或 `Binary` 参与的计算表达式。

精确金额仍由调用方使用缩放整数建模；本设计不承诺十进制定点精度。

## Kotlin DSL

保留现有字段快捷方法：

```kotlin
sum("state.amount", "total")
```

`AggregationQueryDsl` 直接增加最小表达式入口，不创建第二个 builder：

```kotlin
fun field(name: String): AggregationExpression
fun constant(value: Double): AggregationExpression

operator fun AggregationExpression.plus(other: AggregationExpression): AggregationExpression
operator fun AggregationExpression.minus(other: AggregationExpression): AggregationExpression
operator fun AggregationExpression.times(other: AggregationExpression): AggregationExpression
operator fun AggregationExpression.div(other: AggregationExpression): AggregationExpression

fun sum(expression: AggregationExpression, alias: String)
fun avg(expression: AggregationExpression, alias: String)
fun min(expression: AggregationExpression, alias: String)
fun max(expression: AggregationExpression, alias: String)
```

这些函数和成员扩展只在 `aggregation {}` receiver 中可见：

```kotlin
val query = aggregation {
    expand("state.items")
    terms("productId", "product")
    sum(
        field("price") * field("quantity") - constant(10.0),
        "total",
    )
}
```

不增加 `ExpressionDsl`、字符串 parser、`Double` 左右操作数重载或 alias 推断。需要在 DSL 外构造表达式的调用方直接使用公开模型。

## MongoDB 编译

`MongoAggregationCompiler` 递归把非字段表达式编译为原生聚合表达式：

| 逻辑运算 | MongoDB 运算 |
| --- | --- |
| `ADD` | `$add` |
| `SUBTRACT` | `$subtract` |
| `MULTIPLY` | `$multiply` |
| `DIVIDE` | `$divide` |

每个编译节点返回“有限 `Double` 或 `null`”：

- `Field` 先按 Element 作用域解析，解包单元素数组，再通过数值检查和安全转换拒绝非数值、空数组、多元素数组、转换错误与非有限值。
- `Constant` 直接写入有限 Double 字面量。
- `Binary` 只在左右值均有效时执行运算。
- `DIVIDE` 在进入 `$divide` 前判断除数是否为零。
- 运算结果再次执行有限 Double 检查。

数值指标继续使用现有 accumulator 和 companion count。accumulator 接收编译后的可空表达式；companion count 只统计表达式有值的文档，从而保持“无贡献值返回 `null`”合同。

首期直接在每个指标中内联表达式，不增加 `$set` 阶段、不跨指标去重，也不缓存表达式结果。实现只使用 MongoDB 原生表达式，不使用 `$function` 或 JavaScript。

## Elasticsearch 编译

纯 `Field` 指标继续解析并聚合现有物理字段。包含 `Constant` 或 `Binary` 的指标由 `ElasticsearchAggregationCompiler` 生成一个请求级 `double` runtime field：

- runtime field 名称使用框架保留前缀和 metric 序号，例如 `__wow_expression_0`，不拼接用户 alias。
- Painless source 只由已知 AST 和固定运算符生成。
- 物理字段名与常量通过 script parameters 传入，不插入用户字符串到 source。
- 字段读取同时检查 mapping 中存在、当前文档值数量等于 1、值实现 `Number`。
- 每层计算使用 `doubleValue()`，并检查缺失、除零和 `Double.isFinite`。
- 只有最终值有效时才调用 `emit`；runtime field 不 emit 即表示该文档没有贡献值。

metric aggregation 与现有 value-count aggregation 同时引用该 runtime field。每个非字段 metric 独立生成一个 runtime field；首期不跨指标去重。

`ElasticsearchAggregationPlan` 携带 runtime mappings，`ElasticsearchAggregationPager` 在同一 PIT 生命周期的每次分页请求中都附加它们。group、排序、Top-N、PIT 更新和关闭语义不变。

runtime fields 属于 Elasticsearch expensive query。框架不尝试绕过集群级 `search.allow_expensive_queries`；底层拒绝时保留后端错误。

## HTTP Guard

`HttpQueryGuardFilter` 把任一非 `Field` 数值表达式视为 expensive operator：

- `query.allow-expensive-operators=true` 时允许，保持现有默认行为。
- 显式设置为 `false` 时，在进入后端前拒绝该查询。
- 纯 `Field` 指标不新增限制。
- 程序内直接调用 `SnapshotQueryService.aggregate()` 不经过 HTTP guard。

表达式深度和节点上限属于公共模型结构校验，对 HTTP 和程序内调用都生效。

## OpenAPI 与 Schema

通用 `AggregationExpression` Schema 改为递归 `oneOf`：

- `AggregationExpression.Field`
- `AggregationExpression.Constant`
- `AggregationExpression.Binary`

Schema 使用 `type` discriminator。`Binary.left` 与 `Binary.right` 引用通用 `AggregationExpression`，`Constant.value` 为 double，`operator` 为固定四值枚举。

现有 `AggregationMetric.Numeric.expression` 从直接引用 `Field` 改为引用通用表达式。聚合专属 RequestBody、HTTP 路径、动态响应和 `x-wow-query-fields` 保持不变；不生成表达式字段枚举，不改写 `FilterExpression` Schema。

生成客户端会看到新增的递归联合类型。已有不带 `type` 的字段 JSON 继续可用，不提供迁移适配层。

## 错误策略

- 非有限常量、表达式过深、节点过多或程序内未知表达式实现使用 `IllegalArgumentException`，由现有 HTTP ErrorHandler 转换。
- 未知 JSON subtype 和格式错误由现有严格反序列化拒绝。
- 计算表达式中的缺失、非数值、空数组、多元素数组、除零和非有限结果按“无值”处理，不记录逐文档错误。
- Painless 编译失败、生成脚本缺陷、后端请求失败等设计外错误直接传播，不降级为空结果。
- 纯 `Field` 指标继续保留现有后端错误和结果语义。
- 不统一 MongoDB 与 Elasticsearch 的错误文本。

## 测试策略

### 公共模型与 DSL

- `wow-api` 验证旧 `FIELD` JSON 兼容以及 `CONSTANT`、`BINARY` 往返。
- 验证四种 operator、有限常量、深度 8 边界和全查询 256 节点边界。
- 验证未知程序内表达式实现被拒绝。
- `wow-query` 验证 DSL 运算符优先级生成准确 AST，并保留已有字段快捷方法。

### MongoDB

- 编译器测试覆盖四种运算、递归组合、Element 相对字段和有限 Double 保护。
- 覆盖缺失、`null`、非数值、单元素数值数组、空数组、多元素数组、除零和非有限中间结果。
- 验证 companion count 与 accumulator 使用同一表达式有值合同。
- 集成测试执行真实 pipeline，不只比较 BSON 文本。

### Elasticsearch

- 编译器测试验证 runtime field 名称、参数化字段/常量和固定 Painless source。
- 验证单值检查、除零检查、有限值检查以及只在有效时 emit。
- Pager 测试验证每个 PIT 请求都携带 runtime mappings。
- 验证纯 `Field` 指标不生成 runtime field。

### 跨后端与边界

共享 `SnapshotQueryServiceSpec` 增加以下标准数据场景，使 MongoDB 与 Elasticsearch 的实际集成测试执行同一合同：

- `SUM(price * quantity - discount)`。
- 同一递归表达式用于 Avg、Min、Max。
- Elements 最内层相对字段解析。
- 单元素数值数组参与计算；缺失、`null`、非数值、空数组、多元素数组和除零文档被忽略。
- 全部文档无有效表达式值时返回 `null`。

`wow-webflux` 验证 expensive guard；`wow-openapi` 和 schema 快照验证递归联合类型；中英文查询文档增加一个算术表达式示例及空值语义说明。

## 影响范围

预计只修改现有职责内文件：

- `wow-api/.../AggregationQuery.kt` 及其测试。
- `wow-query/.../AggregationQueryDsl.kt` 及其测试。
- MongoDB aggregation compiler 及测试。
- Elasticsearch aggregation compiler、plan/pager 及测试。
- `HttpQueryGuardFilter` 及测试。
- OpenAPI/Schema 测试与快照。
- 共享 `SnapshotQueryServiceSpec` 和双后端集成测试。
- 中英文 query、MongoDB、Elasticsearch 和 WebFlux 文档。

不移动模块职责，不新增公共 service、handler 或 endpoint。

## 验证

实现阶段从最窄测试开始，完成前至少运行：

```bash
./gradlew detekt \
  :wow-api:check \
  :wow-query:check \
  :wow-mongo:check \
  :wow-elasticsearch:check \
  :wow-webflux:check \
  :wow-openapi:check \
  :wow-schema:check

./gradlew \
  :wow-mongo:integrationTest \
  :wow-elasticsearch:integrationTest

cd documentation && pnpm docs:build
```

## 完成条件

- 现有字段表达式 JSON、DSL 和纯字段后端计划保持兼容。
- 四则 AST、结构限制和空值合同在公共测试中固定。
- MongoDB 使用原生表达式，Elasticsearch 使用框架生成的请求级 runtime field。
- 双后端 TCK 对标准数据得到一致的有限 `Double` 或 `null` 结果。
- 非字段表达式由现有 HTTP expensive guard 控制。
- OpenAPI/Schema、生成客户端可见合同和中英文文档同步。
- 没有新增字段 Catalog、字符串 parser、原始脚本入口、依赖或配置项。

## 参考资料

- [MongoDB `$isNumber`](https://www.mongodb.com/docs/manual/reference/operator/aggregation/isnumber/)
- [MongoDB aggregation expressions](https://www.mongodb.com/docs/manual/reference/aggregation/)
- [Elasticsearch runtime fields](https://www.elastic.co/guide/en/elasticsearch/reference/current/runtime.html)
- [Painless runtime field context](https://www.elastic.co/docs/reference/scripting-languages/painless/painless-runtime-fields-context)
- [Painless missing values](https://www.elastic.co/docs/reference/scripting-languages/painless/painless-walkthrough-missing-keys-or-values)
- [Painless numeric operators](https://www.elastic.co/docs/reference/scripting-languages/painless/painless-operators-numeric)
