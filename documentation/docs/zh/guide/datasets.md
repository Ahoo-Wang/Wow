---
title: 从服务发布数据集
description: 在一个文件中基于服务的数仓表声明数据集，把它的行限定给调用方，为字段脱敏，并像快照模型一样查询它。
outline: deep
---

# 从服务发布数据集

数据集是一张用于分析的只读表，基于服务的数仓表声明，像快照模型一样查询：过滤、分组和指标，限定在调用方的作用域内。服务在文件中声明它自己的数据集；分析目录则定义跨服务的数据集。本页声明一个数据集。数据集查询在 10.0 中是实验性的：它的 API 仍可能改变。

## 声明数据集 {#declare-a-dataset}

数据集是一个名为 `<context>.<dataset>.json` 的 JSON 文件，位于类路径的 `META-INF/wow/dataset/` 下。eShop 的 ordering 服务基于它订单的 ClickHouse 视图声明它的销售数据：

<!-- sample: examples/eshop/ordering/ordering-server/src/main/resources/META-INF/wow/dataset/eshop-ordering.sales.json -->

```json
{
  "title": "Sales",
  "identity": "id",
  "eventTime": "commit_time",
  "scope": { "tenant": "tenant_id", "owner": "owner_id", "space": "space_id" },
  "storage": { "table": "eshop_ordering_order_state_flat" },
  "fields": {
    "id": { "types": ["string"], "nullable": false, "description": "The commit of the order's current state: ref@version." },
    "tenant_id": { "types": ["string"], "nullable": false },
    "aggregate_id": { "types": ["string"], "nullable": false, "description": "The order's id." },
    "owner_id": { "types": ["string"], "nullable": false, "description": "The buyer whose checkout the order is: its owner." },
    "space_id": { "types": ["string"], "nullable": false },
    "version": { "types": ["integer"], "nullable": false },
    "commit_time": { "types": ["string"], "semantic": { "type": "temporal-date" }, "nullable": false },
    "deleted": { "types": ["boolean"], "nullable": false },
    "status": { "types": ["string"], "nullable": false, "description": "SUBMITTED, AWAITING_VALIDATION, STOCK_CONFIRMED, PAID, SHIPPED or CANCELLED." },
    "totalAmount": { "types": ["decimal"], "nullable": false, "description": "The order's total: each line's unit price times its quantity." }
  }
}
```

| 属性 | 声明 |
| --- | --- |
| `title` | 数据集叫什么 |
| `identity` | 标识一行的列 |
| `eventTime` | 相对时间过滤默认读取的列 |
| `scope` | 每一行的租户、所有者和空间所在的列：只有作用域包含某一行的调用方才能看到它。改用 `"platform": true` 则表示每个租户都能读取的数据集。`access` 指明每一行属于哪个聚合，此时由该聚合的访问策略决定能读哪些行（[见下文](#rows-of-an-aggregate-with-an-access-policy)） |
| `storage` | 后端的绑定：在 ClickHouse 上，是它读取的 `table` |
| `fields` | 每一列：它的 `types`、`nullable`、可选的 `semantic` 类型、`enum` 值和 `description` |

字段也可以用 `"sensitive": {"level": "confidential", "keepSuffix": 4}` 脱敏，与带 `@Sensitive` 的状态字段一样。

工作目录中 `<wow.query.dataset.config-directory>/wow/dataset/`（默认 `config/wow/dataset/`）下的文件，优先于类路径上的同名文件，所以运维人员不用重新构建就能调整一个数据集。

## 有访问策略的聚合的行 {#rows-of-an-aggregate-with-an-access-policy}

如果数据集的每一行都属于某一个聚合，就在作用域里指明这个聚合，以及存放每行聚合 id 的列：

<!-- sample: fragment -->

```json
"scope": { "tenant": "tenant_id", "access": { "aggregate": "shop.order", "id": "order_id" } }
```

该聚合有访问策略时（[编写访问策略](./ownership#write-an-access-policy)），由策略的 `read` 决定能读哪些行，取代所有者和空间列：

- **按聚合现在的样子。** 调用方能读某行所属聚合现在的样子时，这一行才显示，与这一行写入时记录了什么无关。在 ClickHouse 上，用的是 Sink 写入的数据库里 `<context>_<aggregate>_state` 中该聚合最新一次提交的属性，所以转移或属性变更会在 Sink 写入那次提交之后反映到数据集里。
- **一个聚合的行要么全显示，要么全不显示。** 策略的整个谓词按聚合判定，从不按某一行自己的列判定；数据集的每种操作（列表、分页、计数、聚合）读到的都是同样的行。
- **没有属性，就没有行。** 数仓里没有某个聚合的属性时，对受策略限制的每个调用方，这个聚合的行都隐藏：Sink 还没写入的聚合，或者最后一次由预发布版写入的聚合（[升级部署](../operate/upgrades#from-a-10-0-0-pre-release)）。对某个调用方授予全部聚合的策略，让它读到所有行。
- **服务与受信任代码。** 以服务自身身份读取的服务，以及受信任的进程内代码，不受访问策略约束，只受租户限制，与任何查询一样。

id 列是 `String` 列，聚合必须是本服务的聚合：绑定到本服务没有的聚合的数据集会被拒绝。没有访问策略的聚合，其行仍由所有者和空间列决定，与任何数据集一样。

## 行从哪里来 {#where-the-rows-come-from}

装配了数据集后端时才提供数据集；在 10.0 中是 ClickHouse 的后端：类路径上有 `wow-clickhouse`，并设置了 `wow.clickhouse.endpoint` 或声明了 ClickHouse `Client` bean。ClickHouse Sink 由 `wow.clickhouse.sink.enabled: true` 开启，它把每个发布的聚合的提交写入数据集所读取的表，例如 `<context>_<aggregate>_state_flat`，即以状态的标量字段为列的当前状态（[ClickHouse](../operate/clickhouse)）。

## 查询它 {#query-it}

| 请求 | 返回 |
| --- | --- |
| `POST /wow/dataset/<context>/<dataset>/query` | 对数据集的一次查询 |
| `GET /wow/dataset/<context>/<dataset>/query` | 它的能力描述 |
| `GET /wow/dataset` | 调用方能看到的数据集 |

请求体是一个[查询语言](../reference/query-language)写成的查询：例如某个买家按状态分组的销售额，就是一个按 `status` 分组、对 `totalAmount` 求和的 `aggregate`。每个调用方只读取它自己的租户、所有者和空间的行。在 Kotlin 中，`QueryApi.of(caller).datasets(DatasetName.of(context, dataset), rowType)` 执行同样的查询；它与它所服务的聚合一样是实验性的（`@OptIn(ExperimentalWowApi::class)`）。

`wow.query.dataset.max-concurrent`（8）限制一个节点同时运行的数据集查询数量，它们在自己的舱壁下运行，所以分析永远不会占满 Sink 正在写入的数仓；超出的查询会短暂等待，然后返回 `429 wow.query.busy`。已提供但当前无法查询的数据集返回 `409 wow.query.dataset-unavailable`，未知的数据集返回 `404 wow.query.dataset-not-found`。

## 跨服务的数据集 {#datasets-across-services}

关联多个服务的表的数据集，或者由分析师定义和评审、不需要发版的数据集，属于分析目录，它把每个版本发布为一个 ClickHouse 视图，每个节点都以同样的方式提供它（[平台服务](../migrate/platform)）。
