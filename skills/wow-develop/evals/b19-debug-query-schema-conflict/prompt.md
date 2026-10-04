---
name: b19-debug-query-schema-conflict
tags: [behavior, diagnosis, read-only, query-schema, startup, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

下游服务升级到 Wow 8.13 后启动失败，首个异常是 QuerySchemaConflictException: Conflicting query schema declaration: [state.weight.description]。只定位原因，不要修改文件。
