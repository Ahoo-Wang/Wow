---
name: b32-debug-query-policy-wiring
tags: [behavior, diagnosis, query-policy, event-stream, webflux, startup, read-only, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

下游升级到 Wow commit b9e43876a19655af36ba62f1acf7779175a25a13 后，wow.query.schema.validation-mode=strict 导致启动失败；删除配置后，JVM 查询有租户隔离，而一条手工创建 Gateway 的 EventStream HTTP 路径返回范围外数据。只诊断，分别给出取证顺序、责任所有者、最小复现与需要排除的错误修复，不改文件或访问生产。
