---
name: b15-migrate-aggregation-capability
tags: [behavior, migration, read-only, runtime-contract, query, aggregation, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

只读评估下游服务升级到 Wow V9 的证据：自定义 SnapshotQueryBackend 可以编译、启动，list、paged、count 正常，但生成的 snapshot/aggregation 接口抛出 UnsupportedOperationException。无需数据转换。
