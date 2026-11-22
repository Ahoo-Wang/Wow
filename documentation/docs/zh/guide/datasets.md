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
| `lineage` | 它的保护从哪里来：`source`（默认）或 `none`（[见下文](#protection)） |
| `fields` | 每一列：它的 `types`、`nullable`、可选的 `semantic` 类型、`enum` 值和 `description` |

字段也可以用 `"sensitive": {"level": "confidential", "keepSuffix": 4}` 声明自己的脱敏方式，只能比它的来源更严（[见下文](#protection)）。

工作目录中 `<wow.query.dataset.config-directory>/wow/dataset/`（默认 `config/wow/dataset/`）下的文件，优先于类路径上的同名文件，所以运维人员不用重新构建就能调整一个数据集。

## 保护跟随来源 {#protection}

数据集的列按它的来源受保护，无论数据集是否把它声明为 `sensitive`。当表是 ClickHouse sink 为本服务 Manifest 中某个聚合写的表时，持有状态标为 [`@Sensitive`](./queries#describe-what-types-cannot) 的值的列，在每个结果里都被脱敏，比较时也像状态读取那样受限：`CONFIDENTIAL` 的列不能按值过滤或排序。Wow 用 sink 写 `wow_catalog` 行时的同一个函数、从同一份 Manifest 读出这份保护，所以数据集里从不另存一份。

| 表 | 它受保护的列 |
| --- | --- |
| `<ctx>_<agg>_state_flat` | 状态属性带 `@Sensitive` 的每个扁平列，按状态的方式脱敏 |
| `<ctx>_<agg>_state` | 同样的扁平列；以及 `state`、`state_numbers` 列，即含有受保护值的文档 |
| `<ctx>_<agg>_state_events`、`<ctx>_<agg>_events` | `state`、`state_numbers`、`events` 和 `payload` 列，即含有受保护值的文档 |

- **声明只能收紧。** 字段可以声明 `sensitive`：级别更高，或者脱敏后保留的部分更少，就按声明脱敏。比来源更松的声明（级别更低，或保留得更多）会被拒绝，节点不会启动；错误信息指明状态里的路径。服务自己的数据集没有审批人，所以放宽保护就是修改状态上的 `@Sensitive`，随代码评审。需要降低保护的数据集应放在[分析目录](../data/datasets#sources)里。
- **不读文档列。** 字段只能整列脱敏，不能只遮其中一个值，所以读取含有受保护值的文档列的数据集会被拒绝。请从这个值自己的列读它，即 `_state_flat` 里的那一列。
- **不是状态的表要声明 `"lineage": "none"`。** 运营方自己的视图、关联的结果、别的服务的表或非 Wow 服务的表里有什么，Wow 无从得知。基于这类表的数据集要声明 `"lineage": "none"`，这是作者的断言：这张表里没有状态标为 `@Sensitive` 的值；这样的数据集只按它声明的 `sensitive` 脱敏。没有这一声明时，数据集以不可用状态提供：它的查询答 `409 wow.query.dataset-unavailable`，节点启动时在日志里写明原因。

## 有访问策略的聚合的行 {#rows-of-an-aggregate-with-an-access-policy}

如果数据集的每一行都属于某一个聚合，就在作用域里指明这个聚合，以及存放每行聚合 id 的列：

<!-- sample: fragment -->

```json
"scope": { "tenant": "tenant_id", "access": { "aggregate": "shop.order", "id": "order_id" } }
```

该聚合有访问策略时（[编写访问策略](./ownership#write-an-access-policy)），由策略的 `read` 决定能读哪些行，取代所有者和空间列：

- **按聚合现在的样子。** 调用方能读某行所属聚合现在的样子时，这一行才显示，与这一行写入时记录了什么无关。在 ClickHouse 上，用的是 Sink 写入的数据库里 `<context>_<aggregate>_state` 中该聚合最新一次提交的属性，所以转移或属性变更会在 Sink 写入那次提交之后反映到数据集里。服务的后续版本新声明的属性，在该版本的 Sink 启动时就在 `_state` 上有了它的列（`access_<name>`），但每一行保留写入时的属性，所以在聚合下一次提交之前，该聚合最新一行里没有它的值；要立即读到，可以重置该聚合的 Sink 订阅 `wow.clickhouse-sink.<aggregate>`，它会按日志现在保存的属性重新写入每次提交（[重建数仓](../operate/clickhouse#rebuild-the-warehouse)）。
- **一个聚合的行要么全显示，要么全不显示。** 策略的整个谓词按聚合判定，从不按某一行自己的列判定；数据集的每种操作（列表、分页、计数、聚合）读到的都是同样的行。
- **没有属性，就没有行。** 数仓里没有某个聚合的属性时，对受策略限制的每个调用方，这个聚合的行都隐藏：Sink 还没写入的聚合。对某个调用方授予全部聚合的策略，让它读到所有行。
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
