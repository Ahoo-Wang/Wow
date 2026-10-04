---
name: b14-review-aggregation-paths
tags: [behavior, review, read-only, query, aggregation, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

审查下游 Wow 8.12 改动：聚合 DSL 依次调用 expand("state.orders")、expand("state.orders.lines")；HTTP 客户端请求 POST /sales/sales-order/snapshot/aggregation。只报告合并阻塞项，不修改文件。
