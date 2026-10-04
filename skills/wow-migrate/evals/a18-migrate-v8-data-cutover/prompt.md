---
name: a18-migrate-v8-data-cutover
tags: [activation, trigger, migration, data-cutover, conflict]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

将 Wow 8.8.1 服务升级到 Wow 8.9.6，同时切换 Redis EventStore 的 canonical key 格式；需要数据迁移、全量对账、灰度切流和回滚方案。
