---
name: b25-review-v9-cursor
tags: [behavior, review, read-only, v9, cursor, security, mask, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

审查下游 Wow V9 diff：Controller 第一页使用 tenant 路由的 SnapshotQueryGateway；后续页改用 SnapshotQueryBackendFactory.create(namedAggregate).backend.cursor(query, schema)，因为作者认为 token 已保存 tenant 授权；sort 使用带 @Mask 的 state.email，并把 token Base64 解码后记录其中值。只报告合并阻塞项，不修改文件。 源码目标为 Wow commit b9e43876a19655af36ba62f1acf7779175a25a13。
