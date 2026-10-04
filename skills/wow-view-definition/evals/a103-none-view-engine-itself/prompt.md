---
name: a103-none-view-engine-itself
tags: [activation, negative, wow-repository, engine]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

修改 typescript/wow-view-engine 的 narrowDefinition，让元素字段的 dateUnits 也按描述收窄，并补单元测试。
