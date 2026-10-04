---
name: a69-develop-v9-cursor
tags: [activation, trigger]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

为下游 Wow V9 订单服务设计并实现 Snapshot cursor 查询：按非空 note 过滤，按 updateTime 倒序，并补齐受管 Gateway、MongoDB 与 Elasticsearch 验证。
