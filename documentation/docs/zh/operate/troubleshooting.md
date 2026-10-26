---
title: 故障排查
description: 症状、原因和处置；节点无法启动、命令超时或被拒绝、订阅停放聚合或落后、分区没有属主。
outline: deep
---

# 故障排查

每一节从你看到的现象出发，指出常见的原因，并说明该怎么做。节点给出的每个回答都带着一个错误码；全部错误码见[错误码](../reference/error-codes)。

## 节点无法启动 {#a-node-will-not-start}

宿主宁可拒绝启动，也不带着一个会悄悄做错事的设置运行。日志会指出原因：

| 日志说 | 原因 | 处置 |
| --- | --- | --- |
| 没有持久后端 | 没有 `DataSource` 或 `MongoClient` Bean，又不在开发 profile 中，且内部端口不只绑定在回环网卡上 | 设置 `spring.datasource.url`（类路径上要有 HikariCP）或 `spring.mongodb.uri`，给应用提供它的存储 Bean，或者有意设置 `wow.backend: memory` |
| 服务名不是节点能在内部端口上声明的名字 | `wow.service`（没有时为 `spring.application.name`）含有字母、数字、`.`、`_` 和 `-` 之外的字符，或者超过 253 个字符 | 重命名服务，每个节点以及对端的 `wow.http.services` 里都一致 |
| 未知的安全配置键 | `wow.cosec.`、`wow.http.services.`、`wow.calls.` 或 `wow.internal.` 下有拼错的键 | 修正这个键（[配置服务](./configuration#keys-are-checked)） |
| 节点不提供的订阅、聚合或上下文 | `wow.subscriptions`、`wow.aggregates` 或 `wow.contexts` 下有一个什么都不匹配的名字 | 修正名字，或删除这一项 |
| 没有命令传输 | 某个 Saga 向另一个上下文发命令，而节点既没有 Kafka 也没有内存主题 | 设置 `wow.kafka.bootstrap-servers` |
| 缺少主题，或命令主题的分区数不对 | `wow.kafka.ddl: validate`，而运维人员没有按日志的需要创建主题 | 创建主题：命令主题的分区数恰好是 `wow.log.partitions`（[Kafka](./kafka#creating-topics)） |
| 另一种格式的结构 | 一张由不兼容的 Wow 构建创建的表 | 运行那个主版本的迁移；绝不要编辑 `wow_format` |

## 命令超时 {#commands-time-out}

`wow.wait.timeout`（504）表示等待用完了时间，而不是命令失败：**命令可能已经提交**。problem 会指出到达的最后一个阶段。

- **用同一个请求 ID** 再发一次：确实已经提交的命令会被当作重复请求回答，带着它原来的结果。
- 等待较后的阶段（`HANDLED`、Saga 的那一跳、`SNAPSHOT`）也就是在等待订阅。链上有被停放或落后的订阅时，等待就会超时（[等待结果](../concepts/waits)）。
- `wow.command.unknown`（503）表示转发给属主的命令没有及时得到回答：不知道它是否已提交，用同一个请求 ID 重试是安全的。

## 命令被拒绝 {#commands-are-refused}

| 错误码 | 原因 | 处置 |
| --- | --- | --- |
| `wow.command.busy`（429） | 节点或聚合的在途命令太多 | 在 `Retry-After` 之后重试；持续出现就增加节点（[伸缩与容量](./scaling#limits-a-node-enforces)） |
| `wow.aggregate.version-mismatch`（409，带 `If-Match` 时为 412） | 聚合不处于客户端期望的版本 | 重新读取，再决定是否重试 |
| `wow.command.contended`（409） | 并发的写者一再抢先拿到版本 | 用同一个请求 ID 重试 |
| `wow.command.request-id-reused`（409） | 这个请求 ID 已被另一条命令使用 | 给每条命令自己的请求 ID |
| `wow.aggregate.forbidden`（403） | 聚合属于与调用方不同的所有者或空间 | 检查调用方的作用域（[保护部署](./security)） |
| `wow.auth.forbidden`（403） | 授权器拒绝调用方执行这条命令 | 检查调用方的角色或策略 |
| `wow.call.command-forbidden`（403） | 某个服务调用了它在 `wow.http.services` 下的那一项没有列出的命令 | 在目标服务上为这个服务列出这条命令 |
| `wow.storage.unavailable`（503） | 访问不到某个存储 | 检查数据库；用同一个请求 ID 重试 |
| `wow.node.standby`（503） | 节点正为迁移切换处于待机 | 在切换时恢复它（[部署模型](./topology#standby-for-a-migrations-cut-over)） |

## 订阅停放聚合 {#a-subscription-parks-aggregates}

`wow.subscription.parked` 大于零，ops 控制台会列出这些聚合。每个条目都带着错误，以及订阅停下时的版本。

- 先修复原因：部署修正后的处理函数，或者让它调用的外部系统恢复。
- 然后恢复这些聚合：订阅从每个聚合停下的地方重新读取它的流（[ops 服务与控制台](./ops-console)）。
- Saga 发出的命令被拒绝，只有在 Saga 没有对应的 `@OnRejected` 处理函数时才会停放聚合（[失败与重试](../guide/failures)）。

## 订阅对每个聚合都停下了 {#a-subscription-stops-for-every-aggregate}

某个分区的 `wow.subscription.paused` 为 1：订阅把一次失败判定为每个聚合都依赖的东西（某个存储或下游服务）发生了故障，于是暂停这个分区，直到它恢复。期间不会停放任何东西。从订阅的日志中找出是什么不可用；分区会自行恢复。

## 订阅落后 {#a-subscription-falls-behind}

`wow.subscription.backlog` 持续增长，subscriptions 健康组件处于降级。处理函数比日志增长得慢：缓慢的外部调用、写入一个需要索引的存储，或者下游在施加背压（`wow.subscription.throttled`）。先加快处理函数，再增加节点来分散它的分区（[伸缩与容量](./scaling#subscriptions)）。

## 分区没有属主 {#a-partition-has-no-owner}

ownership 健康组件为 `DOWN`，或者在某个节点离开期间，某些聚合的命令变慢了。宕掉的节点的租约在 `wow.cluster.lease-ttl`（10 秒）后过期，然后分区会移动。如果没有移动：

- 节点之间访问不到彼此的内部端口：检查网络策略和 `wow.node.address`，即每个节点公布的地址。
- 租约存储不可用：stores 健康组件会说明这一点。

## 另一个上下文的主题 {#a-topic-of-another-context}

| 症状 | 原因 | 处置 |
| --- | --- | --- |
| 节点启动后一直未就绪 | 它读取的某个主题仍在加入它的租约组 | 等待；或者设置 `wow.health.topics.required: false` |
| `wow.kafka.offset.gaps` 大于零 | 保留策略删除了某个订阅尚未读取的偏移量 | 提高主题的保留期；决定 `wow.kafka.offset-gap` 怎么设（[Kafka](./kafka#reading-other-contexts-streams)） |
| `wow.subscription.undecodable` 大于零 | 这个版本无法解码的消息 | 升级读取方服务的 api，或者是写入方发送了未知的格式 |

## 查询被拒绝或很慢 {#queries-are-refused-or-slow}

`wow.query.busy`（429）表示达到了存储的查询上限；存储的追加处于压力之下时，这个上限会自适应地降低。`wow.query.timeout`（504）表示查询运行的时间超出了它的预算：收窄它，而不是再运行一次。给查询一个单独的连接池或只读副本（[PostgreSQL](./postgresql#size-the-pools)）。
