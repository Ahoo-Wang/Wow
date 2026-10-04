---
name: a64-develop-snapshot-aggregation
tags: [activation, trigger, query, aggregation, source-marker, downstream-application]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

为这个下游 Wow 8.12 服务增加按 state.status 分组计数的快照聚合查询，并核对 HTTP、MongoDB 与 Elasticsearch 行为。

Files from the user's project, shown inline:

`build.gradle.kts`:

```kotlin
dependencies {
    implementation("me.ahoo.wow:wow-spring-boot-starter:8.16.3")
}
```
