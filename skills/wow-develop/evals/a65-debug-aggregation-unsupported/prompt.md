---
name: a65-debug-aggregation-unsupported
tags: [activation, trigger, query, aggregation, debug]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

下游 Wow 8.12 服务的普通快照查询正常，但生成的聚合查询接口抛出 UnsupportedOperationException；只定位第一个失败阶段。
