---
name: a66-review-snapshot-aggregation
tags: [activation, trigger, review, query, aggregation]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

审查下游 Wow 8.12 快照聚合 DSL 与 HTTP 调用改动，重点确认 elements 路径和默认路由，按严重度报告 findings。
