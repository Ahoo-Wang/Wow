---
title: 选择存储
description: 每种存储保存什么：默认用 MongoDB，PostgreSQL 同样可选，Elasticsearch 在主存储旁边做检索，ClickHouse 做分析；以及怎样安排一个聚合类型的数据。
---

# 选择存储

服务把它的日志、快照、定时器、检查点、租约、失败台账和预留保存在存储里。一种类型的一个存储就够了，大多数服务不需要更多。本页说明每种类型用来做什么，怎样在旁边为某个聚合类型加一个检索索引，以及为什么日志从不挪走。

## 角色 {#roles}

| 存储 | 保存 | 用于 |
| --- | --- | --- |
| MongoDB | 全部：日志、快照、定时器、检查点、租约、失败台账和预留；提供快照查询和 `events` 查询 | 默认选择。一个副本集，不够时分片（[MongoDB](./mongodb#recommended-topology)） |
| PostgreSQL | 全部，与 MongoDB 相同 | 你运行的是 PostgreSQL 时，同样可选（[见下文](#when-postgresql)） |
| Elasticsearch | 某个聚合类型快照的一份副本，以及通过 Sink 写入的事件 | 检索状态和事件：全文、模糊匹配、多维度聚合（[Elasticsearch](./elasticsearch)） |
| ClickHouse | 不是存储：发布流的 Sink，以及数据集的数据仓库 | 跨服务的分析（[ClickHouse](./clickhouse)） |

## 安排聚合类型的数据 {#per-aggregate-type}

1. **默认情况下，聚合类型只写入 MongoDB。** 它的日志和快照都在那里，命令和状态读取读取它们，快照查询和 `events` 查询也在那里运行。
2. **状态需要被检索的类型**，快照仍保存在 MongoDB 上，另在 Elasticsearch 上加一份副本，由它的快照查询读取：

   <!-- sample: fragment -->

   ```yaml
   wow:
     stores:
       main: { type: mongo }
       search: { type: elasticsearch }
     log:
       store: main
     aggregates:
       "[shop.product]":
         snapshot:
           stores: [main, search]
         query:
           snapshot:
             store: search
   ```

   它的快照查询（包括列表）读取 Elasticsearch。它的命令从 MongoDB 加载状态，状态读取（`GET …/state`，包括 `minVersion`）也读取 MongoDB，所以强一致读从不等待 Elasticsearch。

3. **只检索事件历史的类型**，快照留在原处，让它的 `events` 查询指向 Elasticsearch 存储：`query.events.store: search`。宿主会自行启动事件检索 Sink，不需要第二个快照存储。

## 不是双写 {#not-a-dual-write}

Elasticsearch 上的副本不是在命令路径中与主存储并列写入的。命令提交到日志，每个快照存储由它自己的一个订阅从日志写入：第一个存储是 `snapshot`，其余每个是 `snapshot:<store>`，各有自己的检查点。快照存储只保留它收到的最高版本，所以收到重复或迟到提交的存储，最终停在日志所说的位置。Elasticsearch 可能落后于主存储，但不会永久偏离它，需要时随时可以从日志重建（[重建与重放](./replay)）。

## Elasticsearch 从不是日志，也从不是主存储 {#elasticsearch-is-never-the-log}

- **近实时。** 写入在刷新之后才可检索：默认情况下写入会等待刷新（`refresh: wait_for`）。日志的尾读和命令的加载必须立即看到提交。
- **没有事务。** 文档整体替换，由乐观并发保护；日志需要版本和请求上的唯一键，检索索引不维护这些。
- **映射会增长。** 状态和事件的每个字段都会建立映射，所以映射随模型增长；映射必须改变的索引要重建，而不是迁移。

因此宿主拒绝把 Elasticsearch 存储作为日志存储，也拒绝把它作为聚合类型的主快照存储或唯一的快照存储：命令从主存储加载状态，状态读取是对它的强一致读。副本用于它擅长的检索，主存储用于一切必须是最新的东西。

## 代价 {#costs}

- **更多写入。** 每个快照存储都要为它的类型的每次提交写一次：两个存储，快照写入就是两倍。
- **版本不一致。** 检索可能显示比稍后的状态读取更旧的版本。答案必须是最新的时，读取状态。
- **又一个集群。** Elasticsearch 是在 MongoDB 之外要运行、规划容量、保护和监控的第二个系统。

## 什么时候选 PostgreSQL {#when-postgresql}

PostgreSQL 保存 MongoDB 保存的一切，保证也相同，所以选择取决于你运行的是什么：

- 你的组织运行 PostgreSQL，有它的备份、监控和人员。
- 你希望数据在 SQL 里：表有文档说明，每个聚合类型一张（[PostgreSQL](./postgresql#the-tables)）。
- 状态未被缓存的命令，在快照位于日志所在数据库时，一次往返读出快照和其后的日志；MongoDB 则分开读取。

PostgreSQL 不做文本检索：需要检索的类型加一份 Elasticsearch 副本，如上面的规则 2。为每个节点规划它的连接（[规划连接池](./postgresql#size-the-pools)）。
