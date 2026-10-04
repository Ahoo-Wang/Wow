---
name: a13-migrate-failure
tags: [activation, trigger, migration, conflict]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

升级 Wow v6 应用到 v8 后 Saga 不再触发，按迁移契约定位兼容性根因。
