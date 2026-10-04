---
name: a80-generator-cqrs
tags: [activation, trigger, generation]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

用 wow-generator 从订单服务的 OpenAPI 文档生成 TypeScript 的 Wow 命令客户端和查询客户端，输出到 src/generated。
