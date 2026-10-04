---
name: a97-data-query-vs-debug
tags: [activation, trigger, routed, diagnosis]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

下游 Wow 服务的快照 count 接口对 state.status 的 EQ 条件返回 400 UNSUPPORTED_CAPABILITY，但字段明明是 keyword，帮我定位原因。
