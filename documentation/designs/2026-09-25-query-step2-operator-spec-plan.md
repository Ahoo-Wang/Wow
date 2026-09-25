# 查询迁移第 2 步：OperatorSpec、能力表与违规模型

目标架构见 [2026-09-24-query-target-architecture-design.md](2026-09-24-query-target-architecture-design.md)（§5.2、§6.1、附录 A 第 2 步）。本步只改内部结构，**对外行为不变**：错误文案黄金测试（`QueryRestErrorContractTest`）与 OpenAPI 快照必须一条不变。

## 现状的问题

「每个运算符要什么能力、取值按什么规则校验、开销高不高」这件事写了三遍，彼此靠人工保持一致：

| 位置 | 内容 |
|---|---|
| `QuerySchemaValidation.kt` 的 `QueryValidator.filter` | 运算符 → 能力、取值规则 |
| `FilterComplexity.kt` 的 `isExpensive` | 运算符 → 是否高开销 |
| Mongo、ES 过滤编译器 | 运算符 → 按哪个能力取物理绑定 |

另外：
- `QueryCapability` 是开放的 data class，任何字符串都能构造，但实际只有 12 个值，也没有外部构造者；
- 校验失败直接抛带自由文本的 `QuerySchemaValidationException`，第 3 步的错误目录无从接手；
- 游标可用、字段受保护这类只依赖字段本身的事实，每个请求都重新计算一遍。

## 改动

### 1. `QueryCapability` 改为封闭枚举

- 保留名称 `QueryCapability`。目标架构文档中的 `StorageCapability` 即指它；改名要动约 670 处引用，而含义不变，不值得。
- JSON 形态不变（枚举名即原来的字符串值）；`QueryCapability.X` 的调用方源码不变。
- 声明文件中写了未知能力名时，解码失败；声明文件格式不在兼容范围内。

### 2. `FilterOperatorSpec`（wow-api，`me.ahoo.wow.api.query.spec`）

按 `FilterOperator` 穷尽定义，漏写就无法编译。需要看节点才能确定的（EQ 取 null、SEARCH 的模式、STARTS_WITH 的前缀）以节点为参数。

```kotlin
sealed interface FilterOperatorSpec {
    val operator: FilterOperator
    val target: OperatorTarget                               // LOGICAL / SYSTEM_FIELD / FIELD / MODEL_OR_FIELDS / NONE
    fun requiredCapability(node: FilterExpression): QueryCapability?
    val valueRule: ValueRule                                 // NONE / DOMAIN / COLLECTION_DOMAIN / COLLECTION / SINGLE_STRING / TEMPORAL
    fun cost(node: FilterExpression): OperatorCost           // NORMAL / EXPENSIVE
}
val FilterOperator.spec: FilterOperatorSpec
```

**对照表**：现有校验与 `isExpensive` 的每一个分支都映射到规格。

| 运算符 | 目标 | 能力 | 取值规则 | 开销 |
|---|---|---|---|---|
| MATCH_ALL、MATCH_NONE | NONE | — | NONE | 正常 |
| ID、IDS | SYSTEM_FIELD（模型身份字段） | EXACT_MATCH | NONE | 正常 |
| AGGREGATE_ID(S)、TENANT_ID、OWNER_ID、SPACE_ID、DELETION | SYSTEM_FIELD | EXACT_MATCH | NONE | 正常 |
| AND、OR | LOGICAL | — | — | 正常 |
| NOR | LOGICAL | — | — | 高 |
| EQ | FIELD | 取值为 null 时 PRESENCE，否则 EXACT_MATCH | 非 null 时 DOMAIN | 正常 |
| NE | FIELD | 同 EQ | 同 EQ | 高 |
| IN | FIELD | EXACT_MATCH | DOMAIN | 正常 |
| NOT_IN | FIELD | EXACT_MATCH | DOMAIN | 高 |
| CONTAINS_ALL | FIELD | EXACT_MATCH | COLLECTION_DOMAIN | 正常 |
| CONTAINS、ENDS_WITH | FIELD | LITERAL_MATCH | NONE | 高 |
| STARTS_WITH | FIELD | LITERAL_MATCH | NONE | 前缀为空或忽略大小写时高 |
| GT、GTE、LT、LTE、BETWEEN | FIELD | RANGE | DOMAIN | 正常 |
| IS_EMPTY | FIELD | PRESENCE | COLLECTION | 高 |
| IS_EMPTY_STRING | FIELD | EXACT_MATCH | SINGLE_STRING | 正常 |
| IS_NOT_EMPTY_STRING | FIELD | EXACT_MATCH | SINGLE_STRING | 高 |
| EXISTS | FIELD | PRESENCE | NONE | 正常 |
| IS_NULL、IS_NOT_NULL、NOT_EXISTS | FIELD | PRESENCE | NONE | 高 |
| TODAY … NEXT_YEAR（15 个相对时间） | FIELD | RANGE | TEMPORAL | 正常 |
| SEARCH | MODEL_OR_FIELDS | TERMS 模式 FULL_TEXT_TERMS，否则 FULL_TEXT_PHRASE | NONE | 正常 |
| ELEMENT_MATCH | FIELD（容器） | ELEMENT_SCOPE | ELEMENT_SCOPE：内层谓词在容器作用域内递归 | 正常 |

从规格派生：
- `QueryValidator.filter`：按 `target`、`requiredCapability`、`valueRule` 统一处理，不再逐个运算符写分支；
- `isExpensive`：`spec.cost(node) == EXPENSIVE`；
- Mongo、ES 编译器取物理绑定时用 `spec.requiredCapability(node)`，翻译本身仍逐个运算符写（那是各后端的原生差异）。

`GroupSpec`、`MetricSpec` 同样处理：分组类型 → 能力（TERMS → AGGREGATE_TERMS，HISTOGRAM → AGGREGATE_NUMERIC，DATE_HISTOGRAM → AGGREGATE_TEMPORAL）；指标类型 → 对字段或表达式的要求（ANY 要单值、NUMERIC / PERCENTILE 要数值表达式、DISTINCT_COUNT 允许 TERMS 或 NUMERIC）。

### 3. 能力表

只依赖字段本身的事实，挂在 `QueryFieldSchema` 上，每个实例首次读取时计算一次。静态字段的实例由 schema 构造时解析并缓存，所以每个 schema 只算一次；动态字段按具体键解析时新建实例。`QueryFieldSchema` 持有所属 schema，构造函数改为 internal，只由 schema 创建。

- `capabilities: Set<QueryCapability>`：有绑定的能力（已有）；
- `protected: Boolean`：是否受脱敏保护，读 schema 的保护索引；
- `cursorSortable: Boolean`：CURSOR_SORT、单值、无元素祖先、不受保护。

`isCursorFieldAllowed`、`isFieldProtected` 的逐请求计算改为读这两个属性。

依赖取值或请求上下文的判断（取值是否落在声明域内、元素作用域是否匹配、STARTS_WITH 的开销）仍在准入时做，见目标架构 §5.2 的划分。

### 4. 违规模型

`QueryViolation`（wow-query，sealed）承载结构化信息，由它渲染出与现在**逐字相同**的文案：

| 违规 | 现有文案 |
|---|---|
| `UnknownField(field)` | `Unknown logical field [f].` |
| `UnsupportedCapability(field, capabilities)` | `Field [f] does not support [A or B].` |
| `ElementScopeRequired(field)` | `Field [f] requires its declared element scope.` |
| `ValueMismatch(field)` | `Filter value does not match [f].` |
| `NotCollection(field)` | `Field [f] is not a known collection.` |
| `NotSingleString(field)` | `Field [f] is not a single string.` |
| `ModelSearchUnsupported` | `Model search is unsupported.` |
| `CursorNotAllowed(field)` | `Field [f] cannot be used for a cursor.` |
| `ProtectedAggregation(field)` | `Protected field [f] cannot be aggregated.` |
| `MissingKeyRequiresString(field)` | `Field [f] must be a single-valued string field to declare missingKey.` |
| `AnyRequiresSingleValue` | `ANY requires a single value.` |
| `IncompleteProjection` | `Native storage cannot deliver a complete source projection; select available fields explicitly.` |
| 指标过滤的各项违规 | `MetricFilterValidation.kt` 的现有文案 |

`QuerySchemaValidationException` 增加 `violation` 属性，`message` 由违规渲染。错误码与 HTTP 映射不变。本步只覆盖 `QueryValidator` 与指标过滤校验；其余来源（绑定、Profile、后端原生检查）在第 3 步随错误目录一起收口。

## 顺序与验证

每一项单独提交，同一个 PR：

1. `QueryCapability` 枚举化；
2. `FilterOperatorSpec` 及对照表测试：逐个运算符断言目标、能力、取值规则、开销，与改动前的 `isExpensive` 逐节点比对；
3. 校验器与 `isExpensive` 改读规格；
4. Mongo、ES 编译器取绑定改读规格；
5. 能力表；
6. 违规模型。

每一项都要求：
- `QueryRestErrorContractTest` 黄金文件不变；
- OpenAPI 快照不变；
- wow-query、wow-webflux、starter 的 check 通过，Mongo、ES 的集成测试在 CI 通过。
