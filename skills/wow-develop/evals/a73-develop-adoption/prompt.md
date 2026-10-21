---
name: a73-develop-adoption
tags: [activation, trigger]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

新服务直接基于 Wow V10 编写（不是从 9.x 迁移）：请设计库存上下文的 Stock 聚合、订单流上的 Saga，以及 aggregateSpec 测试。
