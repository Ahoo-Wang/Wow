---
title: 查询服务
description: 使用逻辑字段、FilterExpression、运行时查询 Schema 与受护栏约束的 WebFlux 路由查询快照和事件流。
---

# 查询服务

Wow 读链路包含四类独立职责：

```text
事件 -> 快照/投影 -> 逻辑查询模型 -> 查询服务 -> 受保护的 HTTP 路由/API 客户端
```

`wow-query` 定义查询模型与过滤器链；`wow-mongo`、`wow-elasticsearch` 把逻辑字段映射为后端原生查询；`wow-webflux` 增加请求作用域重写和 HTTP 成本护栏；OpenAPI 描述线协议，API 客户端只调用已发布路由。这些层都不会创建身份认证或应用专用投影。

## FilterExpression

`FilterExpression` 是当前过滤合同。JSON 只使用 `op` 作为类型判别字段：

```json
{
  "op": "AND",
  "operands": [
    {"op": "EQ", "field": "state.status", "value": "CREATED"},
    {"op": "DELETION", "state": "ACTIVE"}
  ]
}
```

`field` 是逻辑路径，不是 MongoDB 或 Elasticsearch 字段名。命名段以字母或下划线开头，可包含字母、数字、下划线和连字符；纯数字段表示数组下标，例如 `state.items.0.productId`。物理路径与能力由后端适配器负责。

快照查询默认追加 `DELETION = ACTIVE`。当 `DELETION` 本身是根表达式，或出现在根表达式递归 `AND` 合取树的任意深度时，会抑制该默认值。嵌套在 `OR` 或 `NOR` 下的删除条件不属于显式顶层合取作用域，因此仍保留 ACTIVE guard。事件流查询保留完整历史，不追加快照删除作用域。

### 操作符

| 类别 | `op` | 主要字段 | 合同 |
|---|---|---|---|
| 常量 | `MATCH_ALL`, `MATCH_NONE` | - | 匹配全部或完全不匹配 |
| 元数据 | `ID`, `IDS`, `AGGREGATE_ID`, `AGGREGATE_IDS`, `TENANT_ID`, `OWNER_ID`, `SPACE_ID` | `value` / `values` | 仅查询根可用的文档和消息元数据过滤器 |
| 逻辑 | `AND`, `OR`, `NOR` | `operands` | 至少一个操作数 |
| 比较 | `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE` | `field`, `value` | `EQ`/`NE` 会把 `null` 规范化为空值谓词 |
| 字符串 | `CONTAINS`, `STARTS_WITH`, `ENDS_WITH` | `field`, `value`, `stringComparison` | 默认 `CASE_SENSITIVE` |
| 集合 | `IN`, `NOT_IN`, `CONTAINS_ALL` | `field`, `values` | 非空且不能包含 `null` |
| 区间 | `BETWEEN` | `field`, `lowerBound`, `upperBound` | 两端均包含 |
| 形状 | `IS_EMPTY`, `IS_NULL`, `IS_NOT_NULL`, `EXISTS`, `NOT_EXISTS` | `field` | 使用后端原生空值/null/存在性语义 |
| 删除 | `DELETION` | `state` | `ACTIVE`、`DELETED` 或 `ALL` |
| 数组元素 | `ELEMENT_MATCH` | `field`, `predicate` | 子谓词相对元素，排除仅查询根可用的过滤器 |
| 全文 | `SEARCH` | `query`, `fields`, `mode` | 默认 `TERMS`，可选 `PHRASE`；后端支持不同 |
| 相对时间 | `TODAY`, `YESTERDAY`, `BEFORE_TODAY`, `TOMORROW`, `THIS_WEEK`, `NEXT_WEEK`, `LAST_WEEK`, `THIS_MONTH`, `NEXT_MONTH`, `LAST_MONTH`, `LAST_YEAR`, `THIS_YEAR`, `NEXT_YEAR`, `RECENT_DAYS`, `EARLIER_DAYS` | `field` 与时间选项 | 后端编译前转换为绝对区间 |

数值时间字段使用 `timeUnit`，默认 `MILLISECONDS`。设置 `datePattern` 后会生成格式化字符串并忽略 `timeUnit`。运行时查询模型 Schema 可以声明 `Temporal.Date`、`Temporal.Epoch` 或 `Temporal.Formatted`；最终发布的能力仍取决于后端能够证明的物理映射。

::: info 后端边界
MongoDB 全文搜索使用集合文本索引，无法把 `$text` 限定到请求中的 `fields`；Elasticsearch 会解析支持的搜索字段和 multi-fields。应通过运行时查询模型 Schema 发现能力，不要从公共 JSON 类型推断后端完全一致。
:::

## Kotlin DSL

使用 `filterExpression` 构建独立表达式：

```kotlin
val filter = filterExpression {
    aggregateId("order-1")
    pathState {
        "status" eq "CREATED"
        "totalAmount" gte 100
        "items".elementMatch {
            "productId" eq "product-1"
            "quantity" gt 0
        }
    }
}
```

同一块内的表达式用 `AND` 合并；使用 `or`、`nor`、`and` 显式分组。`String.path` 创建词法路径作用域，嵌套作用域继续追加相对名称；`pathState` 是 `"state".path` 的简写。

`ELEMENT_MATCH` 会创建独立的元素相对作用域。元数据过滤器、`DELETION` 和 `SEARCH` 是查询根表达式，不能放入其中。`expression(...)` 只在当前查询根插入已构建表达式；预构建的 `LogicalField` 不会自动重定基准。

### 查询 DSL

`singleQuery`、`listQuery` 与 `pagedQuery` 共用 filter、projection 和 sort 合同：

```kotlin
val query = pagedQuery {
    filter {
        pathState {
            "status" eq "CREATED"
            "createTime".recentDays(7, ZoneId.of("Asia/Shanghai"))
        }
    }
    projection {
        include("aggregateId")
        include("state.status")
    }
    sort { "state.createTime".desc() }
    pagination {
        index(1)
        size(20)
    }
}

query.query(snapshotQueryService)
```

分页从 1 开始。在 JVM 查询服务边界，`ListQuery.limit = 0` 表示无限制。WebFlux HTTP 护栏根据 `wow.webflux.query.*` 拒绝或限制请求，其默认值不会改变进程内查询模型。

后端执行前，`QuerySchemaResolver` 会解析逻辑字段与能力。`wow.query.schema.validation-mode=COMPATIBLE` 接受 `EXACT` 和 `COMPATIBLE`，`STRICT` 只接受 `EXACT`。兼容回退并不能证明一个字段在所有后端具有相同物理行为。

### 快照聚合

`AggregationQuery` 返回动态表格行，并且至少需要一个 metric：

```kotlin
val query = aggregation {
    filter { "state.status" eq "PAID" }
    expand("state.items") { "quantity" gt 0 }
    terms("productId", "product")
    sum(field("price") * field("quantity"), "revenue")
    count("lineCount")
    sort { "revenue".desc() }
    limit(20)
}

query.query(snapshotQueryService)
```

路径相对性属于公共合同：

- 根 `filter` 使用快照绝对路径；
- 第一个 Element 路径是绝对路径；
- 后续每个 Element 路径都相对当前已展开元素；
- 每个 Element filter 相对自身元素；
- group 和 metric 字段相对最内层 Element；没有 Element 时使用快照绝对路径；
- Elements 是一条有序父子链，不是多个同级展开。

group 支持 `TERMS`、`HISTOGRAM`、`DATE_HISTOGRAM`；metric 支持 `COUNT`、`ANY` 以及数值 `SUM`、`AVG`、`MIN`、`MAX`。数值表达式支持有限常量和 `ADD`、`SUBTRACT`、`MULTIPLY`、`DIVIDE`。`COUNT` 返回 `Long`；没有值参与时，数值 metric 返回 `null`，否则返回有限 `Double`。

`ANY` 从组内选择一个非 null 标量。该值在不同执行和后端间刻意不保证稳定，不能替代确定性的 group key。没有 group 的查询会返回一行汇总；输入为空时仍返回一行，其中 `COUNT = 0`、数值 metric 为 `null`。

alias 必须是唯一的单段逻辑字段，且不能使用 `__wow` 前缀。sort 字段引用 alias；Wow 会按声明顺序追加缺失的 group alias，以形成稳定排序。结构上限为 5 个 Elements、32 个 groups、64 个 metrics、32 个有效 sort 字段、表达式深度 8、表达式节点总数 256、结果行 10,000；默认行数上限为 100。

基础 HTTP 路由是 `POST /{aggregate}/snapshot/aggregation`。对于动态 tenant 或 owned 聚合，目录还会贡献 tenant/owner 作用域查询变体。它复用普通快照查询过滤器链，因此请求作用域和已配置 ABAC 过滤器可以扩展根 filter；结果脱敏会刻意跳过聚合。`allow-expensive-operators=false` 时，HTTP 聚合会拒绝 Elements、按 metric alias 排序、非 Field 数值表达式和 filter 中的高成本操作符。

`AggregationQuery` 只使用规范 `filter`；省略时模型默认 `MATCH_ALL`。反序列化完成后，请求作用域重写仍会追加 tenant/owner/space filters。

自定义 `SnapshotQueryService` 可能继承默认的不支持 `aggregate()` 实现。single/list/paged/count 成功且路由已发布，并不能证明自定义服务支持聚合；应对所选后端做测试。

#### 场景案例

以下请求体都发送到相应聚合的 `snapshot/aggregation` 路由。带作用域的准确路径以运行实例 OpenAPI 为准。

##### 按分类统计数量

```json
{
  "groupBy": [
    {"type": "TERMS", "field": "state.status", "alias": "status"}
  ],
  "metrics": [
    {"type": "COUNT", "alias": "count"}
  ],
  "sort": [{"field": "status", "direction": "ASC"}],
  "limit": 10
}
```

##### 过滤后汇总整体指标

没有 group 时，一行结果汇总过滤后的输入：

```json
{
  "filter": {"op": "EQ", "field": "state.status", "value": "FAILED"},
  "metrics": [
    {"type": "COUNT", "alias": "failedCount"},
    {
      "type": "NUMERIC",
      "function": "AVG",
      "expression": {"type": "FIELD", "field": "state.retryState.retries"},
      "alias": "averageRetries"
    }
  ]
}
```

##### 按数值区间观察分布

`HISTOGRAM` 返回每个桶的下界：

```json
{
  "groupBy": [
    {"type": "HISTOGRAM", "field": "state.totalAmount", "alias": "amountRange", "interval": 100}
  ],
  "metrics": [{"type": "COUNT", "alias": "orderCount"}],
  "sort": [{"field": "amountRange", "direction": "ASC"}]
}
```

##### 按业务时间查看趋势

`DATE_HISTOGRAM` 要求运行时 Schema 发布时间聚合能力。桶 key 是桶起点的 epoch 毫秒：

```json
{
  "groupBy": [
    {
      "type": "DATE_HISTOGRAM",
      "field": "state.createdAt",
      "alias": "day",
      "unit": "DAY",
      "timeZone": "Asia/Shanghai"
    }
  ],
  "metrics": [{"type": "COUNT", "alias": "createdCount"}]
}
```

MongoDB 必须通过集合元数据证明原生 BSON Date，或使用已声明的数值 epoch；Elasticsearch 使用原生 date/date_nanos 映射，或为已声明 epoch 创建请求级 runtime date。格式化时间字符串不会仅因存在 date pattern 就获得 date-histogram 能力。

##### 展开集合并取 Top-N

```json
{
  "filter": {"op": "EQ", "field": "state.status", "value": "PAID"},
  "elements": [
    {
      "path": "state.items",
      "filter": {"op": "GT", "field": "quantity", "value": 0}
    }
  ],
  "groupBy": [
    {"type": "TERMS", "field": "productId", "alias": "productId"}
  ],
  "metrics": [
    {
      "type": "NUMERIC",
      "function": "SUM",
      "expression": {"type": "FIELD", "field": "quantity"},
      "alias": "totalQuantity"
    }
  ],
  "sort": [{"field": "totalQuantity", "direction": "DESC"}],
  "limit": 10
}
```

### 重写查询

filter 是不可变值。策略过滤器追加新表达式，而不是更改字段含义：

```kotlin
val requiredScope = filterExpression {
    "state.warehouseId" eq warehouseId
}
context.appendFilter(requiredScope)
```

WebFlux `RewriteRequestFilter` 会追加由路由和请求头解析出的 tenant、owner、space 元数据过滤器。这会约束后端查询，但不会认证调用方，也不能证明调用方有权选择这些作用域值。

## REST API

内置 WebFlux Handler 会把 `ServerRequest` 写入 Reactor Context；只有这时 `HttpQueryGuardFilter` 才会约束列表/分页窗口、过滤节点与值数量、高成本操作符策略和空闲超时。注入式查询服务及其他非 WebFlux 上下文不受这些 HTTP 专用限制。

Schema resolver 校验逻辑字段与后端能力；HTTP guard 限制请求成本；应用安全过滤器授权 Principal。三者是不同边界。

### 分页查询

```http
POST /tenant/tenant-1/sales-order/snapshot/paged
Content-Type: application/json
Wow-Space-Id: space-1
```

```json
{
  "filter": {"op": "EQ", "field": "state.status", "value": "CREATED"},
  "projection": {"include": ["aggregateId", "state.status"]},
  "sort": [{"field": "state.createTime", "direction": "DESC"}],
  "pagination": {"index": 1, "size": 20}
}
```

以上前缀只是租户资源示例。默认本地路由不会添加限界上下文 alias，实际路径以生成 OpenAPI 为准。

### 列表与单条查询

list 与 single 请求体共用 `filter`、`projection`、`sort`。list 额外包含 `limit`，single 没有分页字段：

```json
{
  "filter": {"op": "AGGREGATE_ID", "value": "order-1"},
  "limit": 1,
  "sort": []
}
```

使用 `/snapshot/list/state`、`/snapshot/paged/state`、`/snapshot/single/state` 获取仅状态响应。逻辑查询字段仍基于完整快照模型，例如 `state.status`；响应解包不会重命名请求字段。

### 计数

规范 count 请求体直接是 `FilterExpression`，外层没有 `filter`：

```http
POST /sales-order/snapshot/count
Content-Type: application/json

{"op": "EQ", "field": "state.status", "value": "CREATED"}
```

`FilterExpression` 兼容反序列化器还接受 `{}`，并按旧 `Condition.ALL` 处理，再应用请求作用域 filters。出现判别字段时，只能使用新 `op` 或旧 `operator` 之一；两者同时出现会被拒绝。OpenAPI 只发布规范 `FilterExpression` 请求体。

JVM 侧使用 `filter.count(queryService)`。count 按所选后端合同保持精确；禁用高成本操作符时，HTTP 成本策略可能拒绝无过滤 count。

## 兼容与迁移

`Condition`、`Operator`、`ConditionDsl` 是已弃用兼容输入。旧构造函数和 count 扩展会将其一次性转换为 `FilterExpression`；执行管线只保留 `filter`。API 反序列化规则按端点区分：

| 端点请求体 | 两种表达都没有 | 新表达 | 旧表达 | 两者同时存在 |
|---|---|---|---|---|
| single/list/paged | 拒绝 | 接受 `filter` | 接受 `condition` | 拒绝 |
| aggregation | 接受；省略 `filter` 时默认 `MATCH_ALL` | 接受 `filter` | 拒绝 | 拒绝 |
| count | 按旧 `Condition.ALL` 接受 | 接受顶层 `op` | 接受顶层 `operator` | 拒绝 |

- OpenAPI 只发布新查询形状；运行时旧格式兼容不会写入规范 Schema；
- 旧 `MATCH` 转换为 `SEARCH`，且不能放在 element match 中；
- 旧 `RAW` 没有替代操作符。后端原生查询应由应用自有且显式保护的端点承担。

从 8.14.x 升级时，查询执行入口完成了破坏性重命名，旧类型与 Spring Bean 名不保留别名：

| 8.14.x | 当前版本 |
|---|---|
| `me.ahoo.wow.query.filter.QueryHandler` / `AbstractQueryHandler` | `me.ahoo.wow.query.QueryGateway` / `AbstractQueryGateway` |
| `me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler` / `DefaultSnapshotQueryHandler` | `me.ahoo.wow.query.snapshot.SnapshotQueryGateway` / `DefaultSnapshotQueryGateway` |
| `me.ahoo.wow.query.event.filter.EventStreamQueryHandler` / `DefaultEventStreamQueryHandler` | `me.ahoo.wow.query.event.EventStreamQueryGateway` / `DefaultEventStreamQueryGateway` |
| `snapshotQueryHandler` / `eventStreamQueryHandler` Bean | `snapshotQueryGateway` / `eventStreamQueryGateway` Bean |

自定义查询过滤器的 `@FilterType` 必须改为对应 `QueryGateway` 类型。自定义 Gateway 不再实现 `Handler` 或公开 `handle(QueryContext)`；它必须实现 `aggregate`，且 Gateway 的 `count` 只接受 `FilterExpression`。聚合级 `QueryService`、`QueryServiceProxy`、两个 `QueryServiceRegistrar`、后端 `QueryService` 与 Factory 保持不变：受管 `QueryService` 仍通过 Gateway，直接使用 Factory 仍会绕过策略链。此次重命名不改变 HTTP/OpenAPI 查询形状、线协议或存储数据，因此本身不需要数据迁移。

迁移期间不要偷偷改变已有字段含义。应增加新逻辑字段或显式 Schema 覆盖，并在目标兼容模式下验证新旧请求。

## JSON Schema

规范线协议 Schema 与运行时字段目录分别版本化：

- [`filter-expression.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/filter-expression.schema.json)
- [`single-query.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/single-query.schema.json)
- [`list-query.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/list-query.schema.json)
- [`paged-query.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/paged-query.schema.json)
- [`count-query.schema.json`](https://github.com/Ahoo-Wang/Wow/blob/main/schema/query/v2/count-query.schema.json)

OpenAPI 发布三个不同层次。通用 component schemas 定义 `FilterExpression`、single/list/paged query 与 aggregation 的 JSON 形状；每个聚合专用 request-body component 引用其中一个通用 Schema，并增加静态 `x-wow-query-fields`：其枚举由 system fields 与 `JsonQuerySchemaSource` 推断字段组成。该扩展是设计时字段目录，不是后端已证明能力列表。

`GET /{aggregate}/snapshot/schema` 返回第三层：当前 `QueryModelSchemaMetadata`，包含合并后的逻辑元数据与后端已证明能力；`POST /{aggregate}/snapshot/schema/refresh` 刷新它。这些运行时 Schema 路由刻意不生成 tenant、owner 或 aggregate-ID 路径变体，因为它们描述模型而非调用方数据；spaced 聚合的公共聚合合同仍可能声明 `Wow-Space-Id`。

运行时 Schema 会把系统字段与 JSON Schema 推断、classpath 约定、Bean 注册、工作目录约定合并，再由后端适配器解析物理绑定。KSP 生成的 `*Properties` 常量是编译期路径导航辅助，不是该运行时 Schema，也不会发布 HTTP 路由。

## 查询服务注册器

`SnapshotQueryServiceRegistrar` 与 `EventStreamQueryServiceRegistrar` 为本地聚合注册 `order.SnapshotQueryService` 等服务。这些类型安全的聚合级服务通过 `QueryServiceProxy` 委托 `QueryGateway`，在到达后端前执行查询重写、已配置的 ABAC 过滤和脱敏。

```kotlin
class OrderReader(
    private val queryService: SnapshotQueryService<OrderState>,
) {
    fun get(id: String): Mono<OrderState> = singleQuery {
        filter { aggregateId(id) }
    }.query(queryService).toState().throwNotFoundIfEmpty()
}
```

Factory 是更底层的后端入口。直接通过 `SnapshotQueryServiceFactory` 或 `EventStreamQueryServiceFactory` 创建的服务会绕过 `QueryGateway` 策略链。原始 Factory 应只放在受信基础设施代码中，不要作为普通请求路径暴露。
