---
name: a80-generator-cqrs
tags: [activation, trigger, generation]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

用 wow-generator 从订单服务提交的 Wow Manifest（manifest/baseline.json）生成 TypeScript 的命令客户端和查询客户端，输出到 src/generated。
