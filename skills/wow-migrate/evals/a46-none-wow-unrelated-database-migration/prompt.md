---
name: a46-none-wow-unrelated-database-migration
tags: [activation, negative, wow-project, unrelated-database-migration]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

这个 Wow 服务只迁移独立的 Flyway 业务报表表，不改变 Wow 版本、API、配置、EventStore、SnapshotStore 或运行时契约。
