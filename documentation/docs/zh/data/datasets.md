---
title: 数据集与分析目录
description: 在分析目录中基于数仓的表定义数据集，审核它的源及其保护级别，把它发布为一个版本，并在源改变时让它继续提供服务。
---

# 数据集与分析目录

数据集是一张分析用的表，像快照模型一样查询。服务在一个文件中声明基于它自己的表的数据集（[从服务发布数据集](../guide/datasets)）。关联多张表的数据集，无论是多个服务的表还是分析人员自己的表，则在分析目录中定义：分析目录是一个独立的服务，分析人员在其中起草定义，审批人审核它们，每个发布的版本都会成为每个节点都提供的视图。分析目录在 10.0 中处于孵化阶段，基于 ClickHouse。

分析目录只是控制面。数据集查询从不调用它：每个节点读取已发布的版本，自己回答查询。

## 源 {#sources}

数仓所配置的数据库中的每张表和每个视图都是一个源，它的 ID 为 `<connection>:<database>:<table>`。源有两种：

- **由 ClickHouse Sink 维护的表**在它的 `wow_catalog` 行中描述自己：它保存的聚合、它的租户、所有者和空间列，以及每个值的保护级别。分析目录只采纳来自该上下文受信任写入者（`wow.analytics.catalog.trusted-writers.<account>`）的这一行，并且只在表的标识和列仍与之相符时采纳。
- **分析人员的表**从它的列起草（`DraftSource`）并接受审核：声明它的租户、所有者和空间列，以及它对已知聚合的引用（`ReviseSource`），或者把它声明为每个租户共享的平台维度，例如地区；记录每个字符串列的保护级别（`ReviewSourceSensitivity`）；最后由审批人把它标记为已审核（`MarkSourceReviewed`）。

Wow 表的保护级别在分析目录中只能收紧。当 Sink 的那一行降低了它时，要由审批人接受这个改变（`ApproveSensitivityChange`），数据集才会看到它。

## 定义数据集 {#define-a-dataset}

定义以别名指明各个源、引入它们的关联、列、过滤和主体，主体是每一行从中取得租户、所有者和空间的那个源。带客户姓名的订单：

<!-- sample: fragment -->

```json
{
  "context": "shop",
  "name": "order_customers",
  "definition": {
    "sources": [
      { "alias": "o", "sourceId": "default:wow:shop_order_state" },
      { "alias": "c", "sourceId": "default:crm:customers" }
    ],
    "subject": "o",
    "joins": [
      {
        "alias": "c",
        "kind": "LEFT",
        "on": [
          { "left": "o.tenant_id", "right": "c.tenant" },
          { "left": "o.state.customerId", "right": "c.id" }
        ]
      }
    ],
    "columns": [
      { "name": "order_id", "from": "o.id" },
      { "name": "customer", "from": "c.name" },
      { "name": "month", "expression": "toStartOfMonth(o.created_at)" }
    ],
    "filters": ["o.state.status != 'CANCELLED'"],
    "identity": "order_id",
    "eventTime": "month"
  }
}
```

以 `DraftDataset` 发往 `POST /analytics-catalog/dataset/draft-dataset`；数据集的 ID 为 `<context>.<name>`。

- **关联**以租户为键，所以没有哪一行会关联到另一个租户的行，除非被关联的源是审批人审核过的平台维度。`kind` 默认为 `INNER`；`LEFT` 关联缺失的一侧为 `null`。
- **列**取一个源的值 `<alias>.<column>`，或 JSON 列中的值 `<alias>.<column>.<path>`，或一个 `expression`。表达式是一门小语言：引用、字面量、算术和比较、`and`、`or`、`not`、`in`、`between`、`is null`，以及对一组固定的 ClickHouse 函数的调用，即对同一行的值做的转换、日期、文本、条件和算术。任何读取其他行、其他表或服务器的东西都不能调用。`filters` 也是表达式，按查询过滤的方式解读。
- **保护级别**跟随血缘：由敏感值计算出的列，与那个值受到同样的保护；列可以声明自己的保护级别（`sensitivity`），低于其血缘要求的声明需要审批人才能发布。
- `identity` 指明标识一行的列，`eventTime` 指明相对时间过滤默认读取的列。

分析目录在定义起草或修订（`ReviseDataset`）时对照目录检查它，并拒绝指明未知源或列、跨租户关联，或调用了函数集之外的函数的定义。

## 发布与版本 {#publish-and-version}

`PublishDataset` 把定义编译成下一个版本，即数据集数据库（`wow_datasets`）中名为 `ds_<context>_<name>_v<N>` 的 ClickHouse 视图，这是分析目录运行的唯一 DDL。每个提供查询的节点都读取发布记录，每 5 秒读取变化的部分，每 5 分钟读取全部，之后就从那个版本回答 `POST /wow/dataset/<context>/<name>/query`，每个值按它的源所说的方式遮蔽。节点只有在读取了发布记录之后才就绪。

| 命令 | 作用 |
| --- | --- |
| `PublishDataset` | 把定义发布为下一个版本 |
| `ApproveDatasetPublication`、`RefuseDatasetPublication` | 决定需要审批人的发布 |
| `RollbackDataset` | 重新提供一个较早的版本，如果它的视图已被删除，就重新创建 |
| `RetireDataset` | 停止查询；视图在保留期（默认 7 天）之后删除，在此之前再次发布会让数据集恢复 |

当发布由敏感值计算或降低了保护级别时，需要审批人；设置 `wow.analytics.catalog.require-publication-approval` 后，每次发布都需要审批人；`require-source-approval` 让每个源都要等到 `ApproveSource` 之后，数据集才能读取它。两个开关默认都关闭。

## 源改变时 {#when-a-source-changes}

对源的改变会重新编译每个读取它的已发布数据集。编译出相同列（相同的名字和类型）的数据集，会自动发布为它的下一个版本：例如更严格的遮蔽、另一种呈现。其他任何情况都会把它标记为 `NEEDS_REVISION`：某个列会改变、定义不再能编译，或者发布现在需要审批人。

需要修订的数据集，只要它已发布版本的视图仍给出发布时的那些列，就继续提供那个版本。一旦不再如此，它的查询就以 409 `wow.query.dataset-unavailable` 回答，直到修订版本发布或源恢复；问题记录在数据集的状态中。

## 查询数据集 {#query-a-dataset}

数据集像快照模型一样查询，使用同样的[查询语言](../reference/query-language)和同样的能力描述符，可以通过 HTTP、Kotlin 中的 `QueryApi.datasets` 或 TypeScript 的 `DatasetClient`。每个调用方只读到自己租户、所有者和空间的行，以主体的列为准。引用聚合的列，即 Wow 表的 `aggregate_id` 或源声明的引用，把每一行链接到拥有该聚合的服务，所以视图可以对它执行操作。

数据集查询在自己的舱壁下运行，每个节点 `wow.query.dataset.max-concurrent`（8），所以分析永远不会从正在写入数仓的 Sink 那里抢走数仓。超出的查询会短暂等待，然后以 429 `wow.query.busy` 回答。未知的数据集以 404 `wow.query.dataset-not-found` 回答。

## 角色 {#roles}

| 角色 | 可以 |
| --- | --- |
| `analytics.catalog.admin` | 做任何事，包括管理连接和受信任的写入者 |
| `analytics.catalog.approver` | 审核源、接受保护级别的改变、决定发布 |
| `analytics.catalog.editor` | 起草和修订源与数据集，发布、回滚和下线 |
| `analytics.catalog.viewer` | 读取分析目录 |

查询数据集不需要其中任何角色，只需要节点的查询策略。分析目录的配置在 `wow.analytics.catalog.*` 之下；它的 [README](https://github.com/Ahoo-Wang/Wow/tree/main/platform/analytics/catalog) 列出了每个键。把 9.x 的数据集注册中心迁移到分析目录，是[平台服务](../migrate/platform)的一部分。
