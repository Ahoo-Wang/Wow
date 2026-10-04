---
name: b17-review-query-factory-bypass
tags: [behavior, review, read-only, query, security, query-gateway, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

审查下游应用 diff：一个面向业务请求的 OrderQueryController 不再注入聚合级 SnapshotQueryGateway<OrderState>，改为注入 SnapshotQueryBackendFactory 并直接 create(namedAggregate).backend.list(query, schema)。作者认为后端相同且少一层。只报告合并阻塞项，不修改文件。 源码目标为 Wow commit b9e43876a19655af36ba62f1acf7779175a25a13。
