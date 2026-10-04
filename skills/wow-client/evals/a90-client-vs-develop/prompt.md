---
name: a90-client-vs-develop
tags: [activation, negative, server-side, conflict]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

为下游 Wow 服务的 Kotlin 订单聚合增加 CancelOrder 命令和事件，并补齐 aggregate 测试。
