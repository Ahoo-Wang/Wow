---
title: 重建与重放
description: 从日志（唯一的事实）重建读模型、快照和标签：从头开始的新订阅、按修订号或按请求重建快照，以及标签回填。
outline: deep
---

# 重建与重放

日志是唯一的事实。快照、读模型、数仓和检索索引都从它派生，所以它们中的任何一个都能从日志重建，不需要迁移脚本，也不用停止服务。本页按最常用的顺序列出这些做法。

## 读模型：一个新订阅 {#a-read-model-a-new-subscription}

投影的位置就是它的订阅的检查点，所以重建读模型的办法，是给投影一个没有检查点的订阅：

1. 用新的名字（一个新类，或 `@Name`）部署改过的投影，让它写入一张新表或一个新索引。没有检查点的投影从日志的开头开始，用历史填满它的存储；`wow.subscriptions.[<processor.function>].start` 也可以让它从某个时刻开始。
2. 等它追平：它的 `wow.subscription.backlog` 降到零，`wow.subscription.progress` 达到 1。
3. 把读取切到新的存储上。
4. 删除旧的投影，并删除它的存储。

在切换之前，新旧读模型并行运行，所以读取永远不会看到构建了一半的模型。反应器或 Saga 从不以这种方式重放：新的反应器或 Saga 从日志的末尾开始，所以部署它不会为过去重复效果或命令。

## 快照：提高状态修订号 {#snapshots-raise-the-state-revision}

当存储的快照不再能读成状态，或者不再包含状态现在要推出的东西时，提高聚合的 `@AggregateRoot(stateRevision = n)` 并部署（[演进事件](../guide/event-evolution#change-the-state-s-structure)）。服务在后台原地重建存储的快照：

- 新修订号的节点以新修订号声明快照表。滚动升级期间，旧修订号的节点继续以它们的修订号写入，存储会阻止它们覆盖新修订号的快照。
- 同一时刻只有一个节点从日志逐页重写表中的快照，没有剩余的快照时就把表标记为已重建。
- 表被标记为已重建时，重建就完成了；节点记录日志 `The snapshots of <type> in <store> are rebuilt at state revision <n>.`

## 快照：按请求重建部分快照 {#snapshots-rebuild-some-on-request}

因为其他原因出错的快照，例如因为一个后来修复了的缺陷，可以按请求从日志重写：

```bash
curl -X POST "http://<service>/<context>/snapshot/<aggregate>/wow.snapshot.rebuild" \
  -H "Content-Type: application/json" \
  -d '{"ids": ["alice-1", "alice-2"]}'
```

这个路由把系统命令 `wow.snapshot.Rebuild` 发给上下文内置的重建聚合，像任何命令一样经过准入，所以由授权器决定谁能发送它。随后一个内置的订阅从日志重写每个指定的快照，写入每个目标存储。

## 新的快照存储 {#a-new-snapshot-store}

给某个聚合类型增加一个快照目标（`wow.snapshot.stores`，或 `wow.aggregates.[<context>.<aggregate>].snapshot.*`），例如在 PostgreSQL 旁边加一个 Elasticsearch 索引，会由一个它自己的订阅从日志开头填充它，目标追平后这个订阅就停止。切换主目标（即查询读取的那个）不会移动任何检查点。

## 标签 {#tags}

在日志开始记录标签之前追加的流（例如从 9.x 导入的流）没有标签，而基于属性的访问匹配不到没有标签的聚合。`migrate-data backfill-tags` 重放每个聚合，记录每条流之后它所带的标签，然后改写没有标签就被索引的 Elasticsearch 事件文档（[migrate-data 命令行](../reference/migrate-data)）。

## 数仓 {#the-warehouse}

ClickHouse Sink 也是一个订阅：它的表从日志派生，同一次提交到达它两次时，它也能让表保持精确（[ClickHouse](./clickhouse#delivered-once-read-once)）。

## 停放的聚合 {#parked-aggregates}

被订阅停放的聚合不会丢失：恢复它时，从它停下的版本开始重新读取它的流，它的处理函数会按顺序看到它错过的每个事件（[失败与重试](../guide/failures#parked-aggregates)）。
