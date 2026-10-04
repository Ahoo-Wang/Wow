---
name: a24-review-v8-data-cutover
tags: [activation, negative, review-data-cutover, migration, conflict]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

审查这个 Wow 8.8.1 到 8.9.6 的 Redis EventStore 数据切换 PR，重点核对 canonical v2 对账、切流和回滚，不要修改。
