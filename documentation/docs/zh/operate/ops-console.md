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

- **台账留在它的服务里。** 停放必须和它旁边的检查点一样可靠，所以每个服务把失败台账保存在自己的存储里，并自己重试停放的聚合：拥有条目所在分区的节点每 10 秒查找到期的重试，默认共 10 次，第一次在失败 3 分钟后，之后每次间隔加倍，每次允许运行 2 分钟（`wow.subscription.parked.*`，[停放的聚合](../guide/failures#parked-aggregates)）。ops 服务什么也不重试：它是一个视图和控制面板，不是失败存放的地方。
- **服务发布它们的台账。** 服务在停放时、重试用尽时、运维人员对条目执行操作之后，以及每 30 秒一次的扫描中（`wow.ops.publish.interval`），把每个条目作为命令发给 ops 上下文，所以 ops 服务在故障之后能追上来。ops 服务部署后，服务会自行开始发布：`wow.ops.publish.enabled` 取默认的 `auto` 时，只要它的主题传输（Kafka，或开发中的内存主题）上存在 ops 命令主题（由 ops 服务创建）就发布，并在启动时和每个 `wow.kafka.topics.partition-refresh`（30 秒）检查一次。开始时它记录 `ops takes the failure ledger now: <service> publishes it (wow.ops.publish).`，主题消失时记录 `ops does not take the failure ledger: <service> stops publishing it.`。没有传输时，`auto` 什么也不发布；`true` 不论检查结果都发布，没有传输时拒绝启动；`false` 从不发布。
- **ops 从不自己动手。** 运维人员的操作记录在条目上，由 ops 的 Saga 以台账的系统命令 `wow.ledger.*` 之一，发到所属服务的命令主题上。所属服务在自己的台账上执行它，并记录结果。发布台账的服务会自行从命令主题接收这些命令，即使它在那里不接收任何其他命令（`wow.kafka.command-intake.enabled` 关闭）：它的订阅 `wow.ledger.commands` 只执行台账的命令，从主题末尾开始读取。它只接受来自 `wow.ops.publish.context` 所指上下文（默认为 `ops`）的 Saga 的这些命令，对其他来源会发出警告。

ops 服务里的每个条目，是一个订阅里的一个停放的聚合，带有它的状态：

| 状态 | 含义 |
| --- | --- |
| `SCHEDULED` | 已停放；所属服务会自动以退避重试它。 |
| `EXHAUSTED` | 自动重试已用尽；等待运维人员处理。 |
| `UNRECOVERABLE` | 被标记为不值得自动重试，或者它的来源已不再保留它停放时的位置。 |
| `RESOLVED` | 不再停放：已成功恢复，或已跳过。只有 ops 服务显示它；所属服务里已没有这个条目。 |

## 部署 {#deploy-it}

ops 服务是一个运行在 MongoDB 和 Kafka（或 PostgreSQL）上、位于 CoSec 之后的 Wow 服务，它以静态文件提供控制台。[`deploy/ops`](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/ops) 里有两个节点的 Kubernetes 清单，做法与 eShop 的服务相同（[部署模型](./topology)）：

1. 构建包含控制台的镜像，或使用已发布的镜像。
2. 准备 MongoDB 和 Kafka；改用 PostgreSQL 时，加上清单的 `postgres` 组件。
3. 创建 ops 上下文拥有的主题：`wow.ops.commands`、`wow.ops.receipts` 和 `wow.ops.parked.state`。命令主题的分区数与 ops 上下文的日志完全相同。
4. 把 CoSec 网关放在前面，让控制台和 ops 路由都经过它：网关校验每个运维人员的访问令牌，服务解析网关转发的令牌，自己不持有密钥（[保护部署](./security#put-the-gateway-in-front)）。

然后，同一 Kafka 上的每个服务会在 ops 命令主题出现后的 `wow.kafka.topics.partition-refresh` 之内自行开始发布台账，并补发期间停放的条目；不应出现在控制台里的服务设置 `wow.ops.publish.enabled: false`。运维人员的操作从每个所属服务自己的命令主题到达，发布台账的服务会自行为它们读取这个主题。

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

各服务的台账发布到 Wow 的默认租户 `(0)`。CoSec 为服务的命令授权时（`wow.cosec.authorizer.enabled: true`），改由 CoSec 的策略而不是角色来决定谁是运维人员，每个操作按它的系统命令 `wow.ledger.*` 判定。

## 使用控制台 {#work-with-the-console}

- **概览。** 首页：一个时间窗口内和总共停放了多少条目，多少需要关注、正在重试、不可恢复或已解决；停放条目随时间的变化；排名靠前的失败聚类、上下文和失败的订阅。点击每个数字都会打开它所统计的队列。每 15 秒刷新一次。
- **队列。** 以记录视图展示的停放条目：_需要关注_（已用尽或不可恢复）、_重试中_、_不可恢复_、_已解决_、_已停放_ 和 _全部_，以及按错误类型、上下文和订阅分组的 _失败聚类_ 等分析。每个字段都可以过滤、排序和分组。
- **详情。** 一个条目：所属服务记录的错误类型和消息、聚合及其停放时的版本、按重试规格进行的尝试、最近一次运维请求及其解决方式，然后是它的历史时间线。
- **操作。** 带原因恢复、带原因跳过、标记为可恢复、应用重试规格。每个操作都要求确认，可以作用于一个条目或一组选中的条目。ops 服务记录下操作后，控制台就报告它；所属服务处理的结果随条目的下一条记录到达，即已解决的条目或新的状态，或者作为连同错误码一起显示的拒绝。已解决的条目不接受操作。

恢复会从聚合停下的版本开始重新读取它的事件，再次交给订阅，包括订阅还没处理到的事件。跳过则越过它们。只有当你确定订阅对这些事件的效果不再需要，或者你已用别的方式弥补时，才选择跳过。

控制台不显示堆栈信息：服务把失败记录为它的异常类型和消息，这也是运维人员分组和搜索所依据的东西。

## 不用 ops 服务 {#without-the-ops-service}

单个服务也可以公开它自己的停放条目：`wow.ops.enabled: true` 在它的公开端口上提供 `/wow/ops/parked`，带有同样的四个操作。它默认关闭。读取按系统命令 `wow.ledger.Read` 授权，每个操作按它的 `wow.ledger.*` 命令授权，由应用的 `CommandAuthorizer` 决定：没有它时，每个操作都以 403 拒绝，宿主会在启动时警告。恢复或跳过在本节点执行时回答 200；留给拥有条目分区的节点、由它在下一次扫描时执行时，回答 202，结果为 `REQUESTED`。

每个服务还可以在聚合停放或重试用尽时通知一个 webhook：设置 `wow.ops.webhook.url`，`wow.ops.webhook.format` 设为 `generic`（Wow 的 JSON）或 `wecom`（企业微信机器人），并用 `wow.ops.webhook.events` 在 `parked` 和 `exhausted` 之间选择。
