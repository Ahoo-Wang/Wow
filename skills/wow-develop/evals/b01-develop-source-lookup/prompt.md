---
name: b01-develop-source-lookup
tags: [behavior, source-first, lookup, read-only, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

下游应用使用 Wow；告诉我目标版本 @OnSourcing 在哪些方法命名和参数条件下可以省略注解，只核对依赖源码和测试，不修改文件。
