---
name: b18-develop-event-stream-aggregation
tags: [behavior, query, aggregation, event-stream, schema, read-only, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

只读设计下游 Wow V9 服务通过 EventStreamQueryGateway 按事件名聚合持久化事件流，并说明 Schema、策略链、双后端与 HTTP/OpenAPI 边界；不要修改文件。 源码目标固定为 Wow commit b9e43876a19655af36ba62f1acf7779175a25a13（9.0.11 候选，不代表已发布）。
