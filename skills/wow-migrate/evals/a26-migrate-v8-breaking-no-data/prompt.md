---
name: a26-migrate-v8-breaking-no-data
tags: [activation, trigger, breaking-no-data, migration, conflict]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

将 Wow 8.7 服务升级到 8.9.6：目标版本删除了正在使用的 API 并改变 starter 配置契约，但已确认存储格式无需数据转换；制定 source/config 破坏性迁移和回滚计划。
