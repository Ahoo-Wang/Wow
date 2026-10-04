---
name: b20-migrate-query-schema
tags: [behavior, migration, read-only, query-schema, runtime-contract, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

只读规划下游服务从 Wow 8.12.2 升级到 8.13.0：应用有自定义 QuerySchemaSource、Mongo 与 Elasticsearch 两套快照后端，并依赖旧查询请求继续工作。说明 Schema 合并、兼容模式、运行时路由、验证和数据边界。
