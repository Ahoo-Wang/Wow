---
name: b13-debug-aggregation-backend
tags: [behavior, diagnosis, read-only, query, aggregation, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

下游 Wow V9 服务的自定义 SnapshotQueryBackend 已支持 list、paged 和 count，生成的聚合查询接口却抛出 UnsupportedOperationException。只定位原因，不要修改文件。
