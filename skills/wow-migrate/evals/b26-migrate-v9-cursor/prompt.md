---
name: b26-migrate-v9-cursor
tags: [behavior, migration, read-only, v8-to-v9, cursor, filter-expression, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

只读规划下游服务从 Wow 8.16.3 升级到当前 V9：应用注入 SnapshotQueryService，ConditionDsl 使用 note eq "" 与 note ne ""，PagedQuery 支持总数和跳页。团队计划全部替换成 CursorQuery，并为旧客户端签发兼容 cursor token；已声明无需数据转换，但没有运行时或生产证据。 源码目标固定为 Wow commit b9e43876a19655af36ba62f1acf7779175a25a13（9.0.11 候选，不代表已发布）。
