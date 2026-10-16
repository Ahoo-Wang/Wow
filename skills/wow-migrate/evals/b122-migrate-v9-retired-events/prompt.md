---
name: b122-migrate-v9-retired-events
tags: [behavior, migration, read-only, v9-to-v10, source-rewrite, event-evolution, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

只读规划把下游服务的订单领域从 Wow 9.9.0 迁到 V10（源码目标固定为 Wow `v10` 分支 commit 267683da7d7a9773db417ab12b542d6b3546bf33，尚未发布），并写出 `migration-map.json` 中相关的条目，不改文件、不迁移数据。归档功能两年前已删除：`ArchiveOrder` 命令和 `OrderArchived` 事件类都不在了，9.x 用下面的升级器让历史还能加载：

```kotlin
class OrderArchivedUpgrader : EventUpgrader {
    override val eventNamedAggregate: EventNamedAggregate =
        "example-service.order".toNamedAggregate().toEventNamedAggregate("order_archived")

    override fun upgrade(domainEventRecord: DomainEventRecord): DomainEventRecord =
        domainEventRecord.toDroppedEventRecord()
}
```

生产 MongoDB 事件库里约有一万条订单流只含一个 `order_archived` 事件（当年 `archive_order` 命令只产生这一个事件）。团队打算在映射表里写 `{"from": "order_archived", "drop": true}` 了事。
