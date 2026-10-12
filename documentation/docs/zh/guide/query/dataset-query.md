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
  "scope": {
    "tenant": "tenant_id",
    "owner": "owner_id",
    "tags": { "region": "region", "dept": "depts" }
  },
  "storage": { "table": "bi_db.order_wide", "deduplicate": "FINAL" },
  "fields": {
    "order_id": { "types": ["STRING"], "nullable": false },
    "tenant_id": { "types": ["STRING"], "nullable": false },
    "owner_id": { "types": ["STRING"], "nullable": false },
    "city": { "types": ["STRING"], "nullable": false },
    "region": { "types": ["STRING"], "nullable": false },
    "depts": {
      "kind": "ARRAY",
      "items": { "types": ["STRING"] },
      "nullable": false
    },
    "amount": { "types": ["DECIMAL"], "nullable": false },
    "updated_at": {
      "types": ["STRING"],
      "semantic": { "type": "TEMPORAL_DATE" }
    },
    "customer_phone": {
      "types": ["STRING"],
      "sensitive": { "level": "CONFIDENTIAL", "keepSuffix": 4 }
    }
  }
}
```

| 键                           | 含义                                                                                                                                                               |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `fields`                     | 可查询的字段，每个是表的一列，使用[查询模型 Schema](./query-model-schema.md) 的声明词汇。没有声明的列不会暴露                                                      |
| `scope.tenant`               | 租户列：调用方范围中的 `tenantId` 落到这一列，`wow.query.require-authenticated-scope` 也要求它。与 `scope.platform` 二选一                                         |
| `scope.platform`             | `true` 表示表里没有租户列，数据集属于平台租户 `(0)`：调用方的租户是 `(0)` 时读到全部记录，是其他租户时什么都读不到，从不对所有租户开放。适合企业仓库中不分租户的表 |
| `scope.owner`、`scope.space` | 可选。所有者列与空间列                                                                                                                                             |
| `scope.tags`                 | 可选。ABAC 标签键到列的绑定，见 [ABAC](#abac)                                                                                                                      |
| `identity`                   | 可选。每行的唯一标识：ID 过滤、游标分页与 View Engine 明细视图的 `rowKey` 都需要它                                                                                 |
| `eventTime`                  | 可选。`FIRST`/`LAST` 指标的默认 `orderBy`                                                                                                                          |
| `fields.*.sensitive`         | 字段的敏感等级与遮挡方式（`level`、`keepPrefix`、`keepSuffix`），语义同 `@Sensitive`。宽表的列没有类型可以携带注解，所以数据集声明是例外                           |
| `storage`                    | 查询后端自己的绑定，核心原样交给后端，见[ClickHouse 后端](#clickhouse)                                                                                             |

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
      endpoints: [http://clickhouse:8123]
      username: wow_reader
      password: ${CLICKHOUSE_PASSWORD}
      database: bi_db
```

数据集的 `storage` 写 `table`（`database.table`，或数据源默认库中的表名）、可选的 `deduplicate`：`NONE`（默认）或 `FINAL`，以及可选的 `source`。`FINAL` 在读取时合并 Replacing、Collapsing 等 MergeTree 的行，读到的是最终一致的数据。

Catalog 编译数据集时，用 `system.columns` 检查表是否符合声明，任何不一致都让编译失败，Catalog 保留上一个版本：

- 每个声明的字段都是表的一列，类型族与声明一致（字符串类、整数、`Float`/`Decimal`、`Bool`、日期）；
- 日期列声明为 `TEMPORAL_DATE`；声明为非空的字段不能是 `Nullable` 列；
- 范围列、标签列与身份列不能是 `Nullable`；范围列与身份列也不能是数组；
- `FINAL` 需要会合并行的引擎；
- 数组只支持 `Array(String)`（元素可以是 `LowCardinality(String)` 或 `FixedString`），声明为字符串数组；`Map`、`Tuple` 与其他数组暂不支持。

`wow.query.clickhouse` 下的连接设置是默认数据源；`wow.query.clickhouse.sources.{name}` 配置其他命名数据源（`endpoints`、`username`、`password`、`database`、`connection-timeout`、`socket-timeout`、`max-connections`），每个使用自己的连接。数据集的 `storage.source` 写数据源的名字，省略时使用默认数据源：

```yaml
wow:
  query:
    clickhouse:
      sources:
        warehouse:
          endpoints: [http://dw:8123]
          username: wow_reader
          password: ${DW_PASSWORD}
          database: dw
```

每个入口的查询有上限，以服务端设置下发，超限的查询失败而不是返回截断的结果：HTTP 查询默认 30 秒、4 GiB 内存（`wow.query.clickhouse.http.*`），进程内查询默认不限（`wow.query.clickhouse.in-process.*`）。订阅取消时，后端在 ClickHouse 上停止对应的查询。

建议为 Wow 使用只读账号（`readonly=2`）。安全边界是 Wow 的准入，不是 ClickHouse 的行策略。

## HTTP 路由

| 方法   | 路径                                       | 请求体与响应                                                                                 |
| ------ | ------------------------------------------ | -------------------------------------------------------------------------------------------- |
| `GET`  | `/dataset`                                 | 目录：本服务提供的每个数据集的上下文、名字、标题与能力描述版本（schema 不能编译时为 `null`） |
| `GET`  | `/dataset/{context}/{dataset}/schema`      | 能力描述（ETag，未变化时 304）                                                               |
| `POST` | `/dataset/{context}/{dataset}/count`       | `FilterExpression` → 记录数                                                                  |
| `POST` | `/dataset/{context}/{dataset}/list`        | `ListQuery` → 行（JSON 数组或 SSE）                                                          |
| `POST` | `/dataset/{context}/{dataset}/paged`       | `PagedQuery` → `{ total, list }`                                                             |
| `POST` | `/dataset/{context}/{dataset}/cursor`      | `CursorQuery` → `{ list, nextCursor }`                                                       |
| `POST` | `/dataset/{context}/{dataset}/single`      | `SingleQuery` → 一行，没有时 404                                                             |
| `POST` | `/dataset/{context}/{dataset}/aggregation` | `AggregationQuery` → 行（JSON 数组或 SSE）                                                   |

配置了数据集查询后端（例如 ClickHouse）时注册这些路由。除目录外，每个路由也在 `/tenant/{tenantId}` 下提供。这些路由与聚合的查询路由走同一条管道：HTTP 入口、HTTP 预算、空闲超时、错误码与能力描述。服务没有提供的数据集返回 404。

调用方范围取自路径中的 `{tenantId}`（没有时取 `Command-Tenant-Id`）、`Command-Owner-Id` 与 `Wow-Space-Id`，后两者只在数据集声明了对应列时生效。它们都来自请求，所以是**声明**的范围；由可信网关（例如 CoSec）认证时，注册自己的 `DatasetQueryRequestScope` 并将其标记为已认证。`ScopeContributor` 默认只作用于聚合路由：数据集的列与聚合不同，贡献者覆盖 `contribute(dataset, request)` 才会限制数据集。

## 运行时增删数据集

`QueryDatasets` Bean 是本服务提供的数据集，启动时装入 `wow.query.datasets` 的声明，运行时可以 `add`、`replace`、`remove`。变更立即生效：Catalog 按新声明重新编译，网关、路由与目录随之更新。与本上下文的聚合同名、或本服务没有数据集查询后端时，`add` 被拒绝，什么都不改变。

## 在视图引擎中分析数据集

数据集在运行时发布，所以视图引擎不为它写定义，而是从它的能力描述派生（`defineDataset`），再在运行时登记到引擎（`engine.register`）。描述里记录层的每个字段都会出现，口径取字段的 `description`，没有时取字段名：

```typescript
import { DatasetQueryClient } from '@ahoo-wang/wow-client';
import { defineDataset, type ViewEngine } from '@ahoo-wang/wow-view-engine';

async function registerDataset(
  engine: ViewEngine,
  context: string,
  dataset: string,
  title: string,
): Promise<void> {
  const client = new DatasetQueryClient({ context, dataset });
  const result = await client.describe();
  if (result.notModified) return;
  const id = `${context}.${dataset}`;
  engine.register({
    definition: defineDataset(result.descriptor, { id, source: id, title }),
    source: client,
  });
}
```

同一个数据集再登记一次会整份替换，已经打开的视图用旧定义直到重开；`engine.unregister(id)` 移除一个。已存的视图引用一个还没登记的数据集时，打开会报「定义不存在」。

`DatasetResources` 替宿主做完这些：它读目录（`DatasetDirectoryClient`），按版本登记、再登记、移除，并答出当前提供着的数据集。宿主为每个生成一个绑定，导航里就出现它们，打开一个就是开始分析它：

```typescript
import {
  DatasetDirectoryClient,
  DatasetQueryClient,
} from '@ahoo-wang/wow-client';
import { DatasetResources, type ViewEngine } from '@ahoo-wang/wow-view-engine';
import { bind, type ViewBinding } from '@ahoo-wang/wow-view-engine/ui';

async function datasetBindings(engine: ViewEngine): Promise<ViewBinding[]> {
  const directory = new DatasetDirectoryClient();
  const datasets = new DatasetResources(engine, {
    list: () => directory.list(),
    source: entry =>
      new DatasetQueryClient({
        context: entry.contextName,
        dataset: entry.name,
      }),
  });
  const served = await datasets.refresh();
  return served.map(({ id }) =>
    bind(id, { route: view => `/datasets/${id}${view ? `/${view}` : ''}` }),
  );
}
```

数据集的标题、名词与字段口径（只能改词与收窄，D86）可以由租户的管理员存进视图存储，不必写进宿主代码。`@ahoo-wang/wow-view-store` 的 `WowDatasetWordings` 读写它，交给 `DatasetResources` 作措辞存储：每次 `refresh` 前读一遍，按存着的措辞登记；读不到时沿用上次读到的。

```typescript
import { WowDatasetWordings } from '@ahoo-wang/wow-view-store';

const datasets = new DatasetResources(engine, {
  list: () => directory.list(),
  source: entry => new DatasetQueryClient({ context: entry.contextName, dataset: entry.name }),
  wordings: new WowDatasetWordings({ fetcher }), // 与 WowViewStore 同一个 fetcher
});
```

宿主给了 `editDatasetWording` 许可时，视图管理器多一个「措辞」页签：管理员在那里改标题、名词、字段的名称、次序与显示，保存后数据集按新词重新登记。谁能写由 CoSec 网关决定（[视图存储](../extensions/view-store.md#数据集措辞)）。

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

## ABAC {#abac}

`AbacQueryPolicy` 也作用于在 `scope.tags` 中绑定了标签列的数据集，规则与 Snapshot 的 `tags` 系统字段相同。每个标签键绑定一列：单值标签用字符串列，多值标签用 `Array(String)` 列。空字符串或空数组表示记录没有这个标签。

| 主体标签     | 字符串列                  | 数组列                    |
| ------------ | ------------------------- | ------------------------- |
| `["a", "b"]` | 值为 `a` 或 `b`，或为空串 | 含 `a` 或 `b`，或为空数组 |
| 通配 `["*"]` | 不为空串                  | 不为空数组                |

- 关闭 `wow.query.abac.match-missing-tag-key` 时，“为空”的记录不再作为公开资源匹配。
- 数据集没有绑定的标签键，视为所有记录都不带这个标签：默认不限制；通配或关闭上一项时，什么都不匹配。
- 没有 `scope.tags` 的数据集不受 `AbacQueryPolicy` 限制，也不解析主体标签。
- 标签列是普通字段，调用方可以按元素过滤（`IN`、`CONTAINS_ALL`、`IS_EMPTY`），但不能排序或聚合数组列。

## TypeScript 客户端

`@ahoo-wang/wow-client` 的 `DatasetQueryClient` 对应上面的路由。它满足 View Engine 的 `ViewSource`（包括 `describe`），可以直接作为 Record、Analysis 与 Dashboard 的数据源：

```typescript
import { DatasetQueryClient, aggregation } from '@ahoo-wang/wow-client';

const orders = new DatasetQueryClient({
  context: 'analytics',
  dataset: 'order_wide',
});
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
- **数组列**：标量操作数匹配任一元素。空数组就是缺失，所以 `IS_EMPTY`、`IS_NULL`、`NOT_EXISTS` 都匹配空数组，与 Elasticsearch 一致；不支持整个数组相等。
- 单个数值字段按原生类型聚合，`Decimal` 求和不丢精度；表达式按 `Float64` 计算。
