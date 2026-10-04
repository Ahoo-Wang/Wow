---
name: a83-generator-vs-client
tags: [activation, trigger, routed, runtime-client, conflict]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

已经生成好了 cartQueryClientFactory，现在要在页面里用它按 state.status 分页查询购物车快照。
