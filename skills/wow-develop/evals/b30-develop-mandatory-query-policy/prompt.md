---
name: b30-develop-mandatory-query-policy
tags: [behavior, query, query-policy, event-stream, webflux, read-only, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

下游服务的 Wow 源码目标是 b9e43876a19655af36ba62f1acf7779175a25a13（9.0.11 候选）。Snapshot 和 EventStream 都要追加不可被后续请求改写删除的租户及业务限制，JVM 和 WebFlux 均需生效。只读给出最少扩展、Schema/Gateway/Backend 职责和验证方案；不实现、不发布。
