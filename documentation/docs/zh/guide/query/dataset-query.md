---
title: 数据集查询
description: 把 ClickHouse 宽表等声明式数据集接入 Wow 查询：声明、ClickHouse 后端、HTTP 路由、治理与语义差异。
---

# 数据集查询

聚合的 Snapshot 与 EventStream 只能回答“一个聚合”的问题。跨聚合的问题（订单 × 客户 × 商品）由分析师在 ClickHouse 中加工成**宽表**，Wow 把宽表声明为**数据集**（Dataset），经同一套准入、能力描述、范围、策略与脱敏提供查询。View Engine 的 Record、Analysis 与 Dashboard 因此可以直接观察跨聚合数据。

关联发生在宽表里，不在查询协议里：一次查询始终针对一个数据集，查询语言就是[过滤条件](./filter-expression.md)、[数据查询](./data-query.md)与[聚合查询](./aggregation-query.md)的同一套 AST。设计依据见 `documentation/designs/2026-10-10-clickhouse-dataset-query-design.md`。

## 声明数据集

数据集在一个限界上下文内命名，例如 `analytics.order_wide`。它的声明随应用发布，放在 `META-INF/wow/dataset/{context}.{dataset}.json`；工作目录的 `config/wow/dataset/{context}.{dataset}.json` 存在时整体替换它，运维可以不重新构建就调整一个数据集。

```json
{
  "title": "订单宽表",
  "identity": "order_id",
  "eventTime": "updated_at",
  "scope": { "tenant": "tenant_id", "owner": "owner_id" },
  "storage": { "table": "bi_db.order_wide", "deduplicate": "FINAL" },
  "fields": {
    "order_id": { "types": ["STRING"], "nullable": false },
    "tenant_id": { "types": ["STRING"], "nullable": false },
    "owner_id": { "types": ["STRING"], "nullable": false },
    "city": { "types": ["STRING"], "nullable": false },
    "amount": { "types": ["DECIMAL"], "nullable": false },
    "updated_at": { "types": ["STRING"], "semantic": { "type": "TEMPORAL_DATE" } },
    "customer_phone": { "types": ["STRING"], "sensitive": { "level": "CONFIDENTIAL", "keepSuffix": 4 } }
  }
}
```

| 键 | 含义 |
| --- | --- |
| `fields` | 可查询的字段，每个是表的一列，使用[查询模型 Schema](./query-model-schema.md) 的声明词汇。没有声明的列不会暴露 |
| `scope.tenant` | 必填。租户列：调用方范围中的 `tenantId` 落到这一列，`wow.query.require-authenticated-scope` 也要求它 |
| `scope.owner`、`scope.space` | 可选。所有者列与空间列 |
| `identity` | 可选。每行的唯一标识：ID 过滤、游标分页与 View Engine 明细视图的 `rowKey` 都需要它 |
| `eventTime` | 可选。`FIRST`/`LAST` 指标的默认 `orderBy` |
| `fields.*.sensitive` | 字段的敏感等级与遮挡方式（`level`、`keepPrefix`、`keepSuffix`），语义同 `@Sensitive`。宽表的列没有类型可以携带注解，所以数据集声明是例外 |
| `storage` | 查询后端自己的绑定，核心原样交给后端，见[ClickHouse 后端](#clickhouse) |

在 `wow.query.datasets` 中列出服务提供的数据集：

```yaml
wow:
  query:
    datasets:
      - analytics.order_wide
```

数据集占用它所在上下文的命名槽位：同一上下文中，数据集不能与聚合同名，否则启动失败。所以按聚合名生效的 `QueryPolicy` 不会作用到数据集上。数据集的模型是 `DATASET`，策略可以读取 `context.schema.model` 区分它。

## ClickHouse 后端 {#clickhouse}

加入 `clickhouse-support` 能力并开启 ClickHouse：

```kotlin
dependencies {
    implementation("me.ahoo.wow:wow-spring-boot-starter") {
        capabilities { requireCapability("me.ahoo.wow:clickhouse-support") }
    }
}
```

```yaml
wow:
  query:
    clickhouse:
      enabled: true
      endpoints: [ http://clickhouse:8123 ]
      username: wow_reader
      password: ${CLICKHOUSE_PASSWORD}
      database: bi_db
```

数据集的 `storage` 写 `table`（`database.table`，或 `wow.query.clickhouse.database` 中的表名）和可选的 `deduplicate`：`NONE`（默认）或 `FINAL`。`FINAL` 在读取时合并 Replacing、Collapsing 等 MergeTree 的行，读到的是最终一致的数据。

Catalog 编译数据集时，用 `system.columns` 检查表是否符合声明，任何不一致都让编译失败，Catalog 保留上一个版本：

- 每个声明的字段都是表的一列，类型族与声明一致（字符串类、整数、`Float`/`Decimal`、`Bool`、日期）；
- 日期列声明为 `TEMPORAL_DATE`；声明为非空的字段不能是 `Nullable` 列；
- 范围列与身份列不能是 `Nullable`；
- `FINAL` 需要会合并行的引擎；
- `Array`、`Map`、`Tuple` 等列暂不支持。

每个入口的查询有上限，以服务端设置下发，超限的查询失败而不是返回截断的结果：HTTP 查询默认 30 秒、4 GiB 内存（`wow.query.clickhouse.http.*`），进程内查询默认不限（`wow.query.clickhouse.in-process.*`）。订阅取消时，后端在 ClickHouse 上停止对应的查询。

建议为 Wow 使用只读账号（`readonly=2`）。安全边界是 Wow 的准入，不是 ClickHouse 的行策略。

## HTTP 路由

| 方法 | 路径 | 请求体与响应 |
| --- | --- | --- |
| `GET` | `/dataset/{context}/{dataset}/schema` | 能力描述（ETag，未变化时 304） |
| `POST` | `/dataset/{context}/{dataset}/count` | `FilterExpression` → 记录数 |
| `POST` | `/dataset/{context}/{dataset}/list` | `ListQuery` → 行（JSON 数组或 SSE） |
| `POST` | `/dataset/{context}/{dataset}/paged` | `PagedQuery` → `{ total, list }` |
| `POST` | `/dataset/{context}/{dataset}/cursor` | `CursorQuery` → `{ list, nextCursor }` |
| `POST` | `/dataset/{context}/{dataset}/single` | `SingleQuery` → 一行，没有时 404 |
| `POST` | `/dataset/{context}/{dataset}/aggregation` | `AggregationQuery` → 行（JSON 数组或 SSE） |

每个路由也在 `/tenant/{tenantId}` 下提供。这些路由与聚合的查询路由走同一条管道：HTTP 入口、HTTP 预算、空闲超时、错误码与能力描述。服务没有提供的数据集返回 404。

调用方范围取自路径中的 `{tenantId}`（没有时取 `Command-Tenant-Id`）、`Command-Owner-Id` 与 `Wow-Space-Id`，后两者只在数据集声明了对应列时生效。它们都来自请求，所以是**声明**的范围；由可信网关（例如 CoSec）认证时，注册自己的 `DatasetQueryRequestScope` 并将其标记为已认证。聚合路由上的 `ScopeContributor` 不作用于数据集路由。

## 进程内查询

`DatasetQueryGateways` Bean 按名称提供每个数据集的 `DatasetQueryGateway`。它与聚合的查询网关有同样的十个方法，记录都是 `ObjectNode`：

```kotlin
val orders = datasetQueryGateways[MaterializedNamedAggregate("analytics", "order_wide")]!!
orders.aggregate(
    aggregation {
        filter { "tenant_id" eq tenantId }
        terms("city", "city")
        sum("amount", "gmv")
        sort { "gmv".desc() }
        limit(10)
    },
)
```

`QueryFilter` 与 `QueryPolicy` 同样作用于数据集查询；只想作用于数据集的过滤器实现 `DatasetQueryFilter`。

## TypeScript 客户端

`@ahoo-wang/wow-client` 的 `DatasetQueryClient` 对应上面的路由。它满足 View Engine 的 `ViewSource`（包括 `describe`），可以直接作为 Record、Analysis 与 Dashboard 的数据源：

```typescript
import { DatasetQueryClient, aggregation } from '@ahoo-wang/wow-client';

const orders = new DatasetQueryClient({ context: 'analytics', dataset: 'order_wide' });
const byCity = await orders.aggregate(
  aggregation.query({
    groupBy: [aggregation.terms('city', 'city')],
    metrics: [aggregation.sum(aggregation.field('amount'), 'gmv')],
  }),
);
```

`resourceAttribution: ResourceAttributionPathSpec.TENANT` 让请求走 `/tenant/{tenantId}` 前缀，`{tenantId}` 由 `urlParams` 填入。

## 语义差异

- **缺失值**：ClickHouse 表里唯一的缺失值是 `Nullable` 列中的 `NULL`。非 `Nullable` 的列不支持 `IS_NULL`、`EXISTS` 等存在性操作符，准入时拒绝；`Nullable` 列上 `EXISTS` 与 `IS_NOT_NULL` 相同，与 Elasticsearch 一样无法区分显式 `null` 与缺失。
- **NE 与 NOT_IN** 也匹配 `NULL`，与 MongoDB 一致。
- **DISTINCT_COUNT** 精确计算（`uniqExact`）；**PERCENTILE** 使用 t-digest 近似，能力描述标明 `approximate`。
- **ABAC**：`AbacQueryPolicy` 只作用于 Snapshot，数据集与 EventStream 一样不受它限制。
- 单个数值字段按原生类型聚合，`Decimal` 求和不丢精度；表达式按 `Float64` 计算。
