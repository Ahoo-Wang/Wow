---
name: a25-debug-v8-data-cutover
tags: [activation, negative, debug-data-cutover, migration, conflict]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Wow 8.8.1 到 8.9.6 的 Redis EventStore 数据切换演练在恢复后对账失败；按迁移契约定位 checkpoint 或 canonical v2 映射根因，不要修改。
