---
name: b121-migrate-v9-state-tags
tags: [behavior, migration, read-only, v9-to-v10, source-rewrite, abac-tags, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

只读规划把下游服务的工单领域从 Wow 9.9.0 迁到 V10（源码目标固定为 Wow `v10` 分支 commit 267683da7d7a9773db417ab12b542d6b3546bf33，尚未发布），只看状态类这一处，给出改写后的代码，不改文件。查询策略按标签 `region` 与 `assignee` 过滤工单：

```kotlin
class TicketState(val id: String) : StatusCapable<TicketStatus>, StateAggregateTagsExtractor<TicketState> {
    override var status: TicketStatus = TicketStatus.OPEN
        private set
    var region: String? = null
        private set
    var assignee: String? = null
        private set

    override fun extract(aggregate: ReadOnlyStateAggregate<TicketState>): AbacTags =
        mapOf("region" to listOfNotNull(region), "assignee" to listOfNotNull(assignee))

    fun onSourcing(event: TicketAssigned) { assignee = event.assignee }
    fun onSourcing(event: TicketMoved) { region = event.region }
}
```

运维还会通过 `ApplyTags` 给工单手工打 `priority` 标签。团队认为 V10 没有从状态推导标签的能力，打算让每个改派命令的 Saga 再发一条 `ApplyTags`。在回答里给出代码和命令。
