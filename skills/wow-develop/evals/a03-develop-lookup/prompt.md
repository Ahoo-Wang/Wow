---
name: a03-develop-lookup
tags: [activation, trigger, lookup]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

核对当前 Wow Query DSL 如何按 tenant 和 status 分页查询，并只投影指定字段。
