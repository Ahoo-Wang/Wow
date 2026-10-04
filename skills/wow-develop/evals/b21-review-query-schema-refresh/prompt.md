---
name: b21-review-query-schema-refresh
tags: [behavior, review, read-only, query-schema, elasticsearch, deployment, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

审查下游部署改动：更新 Elasticsearch mapping 后只向一个副本调用 wowQuerySchema actuator 端点重新校验，并据此声称所有副本的查询 Schema 和历史索引 mapping 已更新。只报告合并阻塞项，不修改文件。
