---
name: b16-migrate-query-gateway
tags: [behavior, migration, read-only, source-contract, spring, query-gateway, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

只读规划一个下游应用升级到当前 Wow V9 查询架构：应用仍使用旧查询入口、Factory 与动态结果类型，直接实现 QueryService 与 QueryGateway/AbstractQueryGateway，Filter 还读取 QueryType.isDynamic；自定义脱敏实现与 Registry 仍依赖 DynamicDocument/DataMasker 家族；测试及 fallback 还引用公开 NoOp QueryService/Factory、SnapshotRepository、SnapshotRepositorySpec、createSnapshotRepository() 与旧 SnapshotRepository Bean qualifier；基础设施扩展还引用 Mongo create*Index、Elasticsearch UNLIMITED_SIZE/searchSize/InitSubscriber 与 EventStoreSpec TIMES/DEFAULT_PARALLELISM。V9 已提供 Query Schema 驱动的静态字段注解 Mask。说明源码、Spring 配置、执行链、HTTP/wire 与数据迁移边界；除 Condition 迁移窗口外不添加 JVM 兼容层，并在 V9.x 保持 JVM 与 REST condition/operator 兼容、标记 10.0.0 移除。 源码目标固定为 Wow commit b9e43876a19655af36ba62f1acf7779175a25a13（9.0.11 候选，不代表已发布）。
