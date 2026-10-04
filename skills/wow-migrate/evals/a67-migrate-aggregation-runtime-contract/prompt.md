---
name: a67-migrate-aggregation-runtime-contract
tags: [activation, trigger, migration, runtime-contract, query, aggregation]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

升级到 Wow V9 后自定义 SnapshotQueryBackend 编译和普通查询正常，但生成的聚合接口运行时报不支持；评估迁移缺口与最小门禁。
