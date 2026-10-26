---
title: ops 服务与控制台
description: 部署 ops 服务，把每个服务的失败台账接到它上面，并用它的控制台查找、重试和跳过停放的聚合。
---

# ops 服务与控制台

订阅在某个聚合上一再失败时，会把这个聚合停放在它所在服务的失败台账里（[订阅、失败与重放](../concepts/subscriptions#failures-park-one-aggregate)）。ops 服务把所有服务的台账汇集到一处，运维人员在它的控制台里查看停放了什么、决定怎么处理。本页说明它们怎样协作、怎样部署，以及怎样使用。

## 它们怎样协作 {#how-it-fits-together}

```mermaid
sequenceDiagram
    autonumber
    participant S as 所属服务
    participant K as Kafka
    participant O as ops 服务
    actor Op as 运维人员
    S->>S: 在自己的台账里停放一个聚合
    S->>K: 记录它（wow.ops.commands）
    K->>O: 这个条目对应的停放聚合
    Op->>O: 恢复、跳过、标记为可恢复、应用重试规格
    O->>K: 系统命令 wow.ledger.*（wow.<context>.commands）
    K->>S: 在它的台账上执行
    S->>K: 记录结果
    K->>O: 条目已解决，或记录下拒绝
```

- **台账留在它的服务里。** 停放必须和它旁边的检查点一样可靠，所以每个服务把失败台账保存在自己的存储里，并自己持续重试停放的聚合。ops 服务是一个视图和控制面板，不是失败存放的地方。
- **服务发布它们的台账。** 设置了 `wow.ops.publish.enabled` 的服务，在停放时、以及每 30 秒一次的扫描中，把每个条目作为命令发给 ops 上下文，所以 ops 服务在故障之后能追上来。这需要 Kafka 传输。
- **ops 从不自己动手。** 运维人员的操作记录在条目上，由 ops 的 Saga 以台账的系统命令 `wow.ledger.*` 之一，发到所属服务的命令主题上。所属服务在自己的台账上执行它，并记录结果。它只接受来自 ops 上下文 Saga 的这些命令。

ops 服务里的每个条目，是一个订阅里的一个停放的聚合，带有它的状态：

| 状态 | 含义 |
| --- | --- |
| `SCHEDULED` | 已停放；所属服务会自动以退避重试它。 |
| `EXHAUSTED` | 自动重试已用尽；等待运维人员处理。 |
| `UNRECOVERABLE` | 被标记为不值得自动重试。 |
| `RESOLVED` | 不再停放：已成功恢复，或已跳过。 |

## 部署 {#deploy-it}

ops 服务是一个运行在 PostgreSQL 和 Kafka 上、位于 CoSec 之后的 Wow 服务，它以静态文件提供控制台。[`deploy/ops`](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/ops) 里有两个节点的 Kubernetes 清单，做法与 eShop 的服务相同（[部署模型](./topology)）：

1. 构建包含控制台的镜像，或使用已发布的镜像。
2. 准备 PostgreSQL 和 Kafka。
3. 创建 ops 上下文拥有的主题：`wow.ops.commands`、`wow.ops.receipts` 和 `wow.ops.parked.state`。命令主题的分区数与 ops 上下文的日志完全相同。
4. 设置 CoSec 签发令牌所用的密钥。

然后，在要展示其台账的**每个服务**上，用 `wow.ops.publish.enabled: true` 打开发布。所属服务拥有自己的命令主题，运维人员的操作就从这个主题到达。

## 谁能做什么 {#who-may-do-what}

运维人员用 CoSec 访问令牌登录控制台。服务从令牌中读取运维人员的租户、主体标识和角色：

<!-- sample: platform/ops/ops-server/src/main/resources/application.yaml#roles -->

```yaml
wow:
  cosec:
    # Tenant-level callers (decision D189): their scope has no owner, so they read every entry of their tenant. An
    # operator acts on the entries, a viewer reads them; the services' ledgers publish to Wow's default tenant, (0),
    # which a principal of CoSec's default tenant is in. A principal with neither role reads nothing.
    tenant-level:
      roles: ops.operator, ops.viewer
```

- `ops.operator` 读取条目并对它们执行操作。
- `ops.viewer` 只读取条目。
- 两个角色都没有的主体什么都读不到。没有有效令牌的请求返回 401，控制台随即退出登录。

各服务的台账发布到 Wow 的默认租户 `(0)`。打开 CoSec 自己的授权后，也可以改由 CoSec 的策略按系统命令 `wow.ledger.*` 来决定。

## 使用控制台 {#work-with-the-console}

- **概览。** 首页：一个时间窗口内和总共停放了多少条目，多少需要关注、正在重试、不可恢复或已解决；停放条目随时间的变化；排名靠前的失败聚类、上下文和失败的订阅。点击每个数字都会打开它所统计的队列。每 15 秒刷新一次。
- **队列。** 以记录视图展示的停放条目：_需要关注_（已用尽或不可恢复）、_重试中_、_不可恢复_、_已解决_、_已停放_ 和 _全部_，以及按错误类型、上下文和订阅分组的 _失败聚类_ 等分析。每个字段都可以过滤、排序和分组。
- **详情。** 一个条目：所属服务记录的错误类型和消息、聚合及其停放时的版本、按重试规格进行的尝试、最近一次运维请求及其解决方式，然后是它的历史时间线。
- **操作。** 带原因恢复、带原因跳过、标记为可恢复、应用重试规格。每个操作都要求确认，可以作用于一个条目或一组选中的条目，并报告结果。已解决的条目不接受操作，拒绝会连同错误码一起显示。

恢复会从聚合停下的版本开始重新读取它的事件，再次交给订阅，包括订阅还没处理到的事件。跳过则越过它们。只有当你确定订阅对这些事件的效果不再需要，或者你已用别的方式弥补时，才选择跳过。

控制台不显示堆栈信息：服务把失败记录为它的异常类型和消息，这也是运维人员分组和搜索所依据的东西。

## 不用 ops 服务 {#without-the-ops-service}

单个服务也可以公开它自己的停放条目：`wow.ops.enabled: true` 在它的公开端口上提供 `/wow/ops/parked`，带有同样的四个操作，按系统命令 `wow.ledger.*` 授权。它默认关闭。

每个服务还可以在聚合停放或重试用尽时通知一个 webhook：设置 `wow.ops.webhook.url`，`wow.ops.webhook.format` 设为 `generic`（Wow 的 JSON）或 `wecom`（企业微信机器人），并用 `wow.ops.webhook.events` 在 `parked` 和 `exhausted` 之间选择。
