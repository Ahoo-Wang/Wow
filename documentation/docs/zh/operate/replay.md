---
title: 重建与重放
description: 从日志（唯一的事实）重建读模型和快照：从头开始的新订阅、按修订号或按请求重建快照，以及开始记录访问属性之前写入的流的访问属性。
outline: deep
---

# 重建与重放

日志是唯一的事实。快照、读模型、数仓和检索索引都从它派生，所以它们中的任何一个都能从日志重建，不需要迁移脚本，也不用停止服务。本页按最常用的顺序列出这些做法。

## 读模型：一个新订阅 {#a-read-model-a-new-subscription}

投影的位置就是它的订阅的检查点，所以重建读模型的办法，是给投影一个没有检查点的订阅：

1. 用新的名字（一个新类，或 `@Name`）部署改过的投影，让它写入一张新表或一个新索引。没有检查点的投影从日志的开头开始，用历史填满它的存储；`wow.subscriptions.[<context>.<processor.function>].start` 也可以让它从某个时刻开始。
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
curl -X POST "http://<service>/<context>/<aggregate>/wow.snapshot.rebuild" \
  -H "Content-Type: application/json" \
  -d '{"ids": ["SO-261031-1", "SO-261031-2"]}'
```

这个路由把系统命令 `wow.snapshot.Rebuild` 发给上下文内置的重建聚合，像任何命令一样经过准入，所以由授权器决定谁能发送它；没有命令授权器的服务在公开端口上拒绝它（403 `wow.auth.forbidden`），默认从不放行（[授权命令](./security#authorize-commands)）。随后一个内置的订阅从日志重写每个指定的快照，写入每个目标存储。

## 新的快照存储 {#a-new-snapshot-store}

给某个聚合类型增加一个快照目标（`wow.snapshot.stores`，或 `wow.aggregates.[<context>.<aggregate>].snapshot.*`），例如在 PostgreSQL 旁边加一个 Elasticsearch 索引，会从日志开头填充它：新存储自己的快照订阅从开头开始；如果是给一个订阅早已越过开头的存储新增聚合类型，则由一个它自己的订阅填充，目标追平后这个订阅就停止。切换主目标（即查询读取的那个）不会移动任何检查点。

## 访问属性 {#access-attributes}

每条流都记下聚合在这次提交之后的访问属性（属主与空间也在其中），快照和各 Sink 的记录也一样。一次提交改变了它们时，内置订阅 `wow.access` 把新的属性盖到该聚合更早的流上，所以它的整段历史都按聚合现在的样子授权。Elasticsearch 存储的事件索引也同样跟随：它的 Sink 把变化盖到之前写下的文档上，下面的遍历连同日志一起为索引盖章。不论盖章以什么顺序到达，每一份副本都保留版本更晚的属性（`accessVersion`）。

日志开始记录访问属性之前追加的流（10.0 beta 写入的，或者从 9.x 导入的）没有访问属性，beta 写下的快照也没有：盖章之前，访问策略下的事件查询不包含这些流，快照查询不包含这些快照；以所有者身份或在某个空间里行事的调用方，其事件查询也不包含这些流。每个聚合类型的访问遍历为它们盖章：

- **何时运行。** 当类型的日志没有记录遍历，或者记录的是另一种访问模式（模型声明的属性、各自的类型以及是否多值）的遍历、而该模式的节点已一个不剩时，节点为该类型认领遍历。所以记录访问属性的版本的第一个节点会遍历 beta 写下的日志；声明了新属性的滚动部署，会在上一版本的最后一个节点停下之后遍历。日志里还没有任何流的类型没有遍历，遍历也不为它创建集合或表：它的第一次追加创建它们，之后的下一轮再认领遍历。迁移之后，`migrate-data snapshot` 自己为导入的历史盖章，并把每个类型的遍历记为完成，节点就不再遍历它（[访问属性](../migrate/data#_7-the-access-attributes)）。
- **做什么。** 同一时刻只有一个节点逐页取出该类型的聚合，用每个聚合当前状态的属性为它的整段历史盖章，并重写它的快照，每秒最多 `wow.access.walk-rate` 个聚合（默认 500）。遍历到达认领时日志各分区头之前的流；在这之后不带属性的流（仍在运行的 beta 节点写的，或者导入的），由 `wow.access` 读到时为其聚合盖章。
- **续跑。** 每页 `wow.access.walk-batch` 个聚合（默认 100）之后，遍历把进度记在日志的注册表里，所以重启后或换一个节点都从那里继续。
- **观察。** 类型的遍历完成之前，`wow.access.walk.pending` 为 1，还没有遍历的类型不报告它；`wow.access.walked` 计数遍历过的聚合，`wow.access.stamped` 按原因计数盖过章的流（[指标](../reference/observability#metrics)）。注册表保存每个遍历的模式、日志头、游标、计数与是否完成：PostgreSQL 上用 `SELECT context, aggregate, access_walk FROM wow_log_aggregate`，MongoDB 上看 `wow_log_aggregate` 文档的 `access_walk` 字段。完成时，执行遍历的节点记录日志 `The access walk of <type> is completed: <n> aggregates stamped.`

记录标签的版本建的 Sink 表在启动时会被拒绝，并指明是哪张表；它保存的都是日志里有的内容，所以删掉它，再重置这个 Sink 的订阅，它就会从日志重新填充。

## 数仓 {#the-warehouse}

ClickHouse Sink 也是一个订阅：它的表从日志派生，同一次提交到达它两次时，它也能让表保持精确（[ClickHouse](./clickhouse#delivered-once-read-once)）。

## 停放的聚合 {#parked-aggregates}

被订阅停放的聚合不会丢失：恢复它时，从它停下的版本开始重新读取它的流，它的处理函数会按顺序看到它错过的每个事件（[失败与重试](../guide/failures#parked-aggregates)）。
