---
name: b12-develop-snapshot-aggregation
tags: [behavior, query, aggregation, filter-expression, read-only, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

下游 Wow V9 服务自定义实现 SnapshotQueryBackend；只读设计按 state.status 分组 COUNT 的快照聚合 HTTP 接入，并说明双后端与查询过滤链边界。不要修改文件。 源码目标固定为 Wow commit b9e43876a19655af36ba62f1acf7779175a25a13（9.0.11 候选，不代表已发布）。
