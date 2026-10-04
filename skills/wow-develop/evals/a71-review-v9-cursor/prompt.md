---
name: a71-review-v9-cursor
tags: [activation, trigger]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

审查下游 Wow V9 cursor 查询 diff，重点检查受管 Gateway、token、稳定排序与字段 Mask；只报告阻塞 findings。
