---
name: b23-develop-v9-cursor
tags: [behavior, develop, read-only, v9, cursor, filter-expression, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

只读设计下游 Wow V9 订单服务的 Snapshot cursor 查询：筛选 state.note 非空字符串，按 updateTime 倒序，每页 20 条，使用 projection，并同时支持 MongoDB 与 Elasticsearch。说明 JVM、HTTP、Gateway、Schema/Mask、token 与验证边界，不要修改文件。 源码目标固定为 Wow commit b9e43876a19655af36ba62f1acf7779175a25a13（9.0.11 候选，不代表已发布）。
