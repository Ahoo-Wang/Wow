---
title: Elasticsearch
description: 使用 Elasticsearch 承载事件流、快照和后端感知查询。
---

# Elasticsearch

`wow-elasticsearch` 实现 Elasticsearch `EventStore`、`SnapshotStore` 以及事件流/快照查询后端。适合已经运维 Elasticsearch，且读侧需要全文、聚合或大批量游标查询的场景；不要仅为事件持久化而引入搜索集群。

## 架构概述

Wow 负责索引名、模板、文档形状、版本保护、query schema 与 storage binding；Elasticsearch 负责 mapping、分析器、分片、副本、refresh、PIT、`search_after` 和 bulk 执行。模块在 classpath 上不等于已装配，event 或 snapshot storage 必须实际选择 `elasticsearch`。

## 安装

直接依赖：

```kotlin
implementation("me.ahoo.wow:wow-elasticsearch")
implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")
```

Starter capability：

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.wow:elasticsearch-support") }
}
```

## 配置

```yaml
spring:
  elasticsearch:
    uris: http://localhost:9200

wow:
  eventsourcing:
    store:
      storage: elasticsearch
    snapshot:
      storage: elasticsearch
```

`wow.elasticsearch.enabled=true`、`auto-init-template=true`、`query.batch-size=10000`、`query.keep-alive=1m`；`compatibility-version` 默认为空。event/snapshot batch 默认关闭，启用后默认 `max-size=128`、`max-delay=1ms`、`max-pending-*=4096`、`lane-count=1`。

### Spring Data Elasticsearch 配置

连接、认证、TLS 和 client timeout 由 `spring.elasticsearch.*` 管理。Wow 使用 Spring 创建的 reactive client/operations，不复制这些属性。

### Wow 配置

`query.batch-size` 必须在 `1..10000`，`keep-alive` 至少 `1ms`。只在部署拓扑确实需要 REST compatibility header 时设置 `compatibility-version`，并由目标集群验证该版本。

## 写入批处理

EventStore batch 使用 Bulk `create`；SnapshotStore direct/batch 都以 `_source.version` 做原子保护，旧快照不能覆盖新快照。只有吞吐证据需要时才启用 batch；队列上限、关闭排空和 partial bulk failure 都会成为新的运行边界。

## 索引命名规则

默认事件索引为 `wow.${contextAlias}.${aggregateName}.es`，快照索引为 `wow.${contextAlias}.${aggregateName}.snapshot`。索引名参与存储与查询路由，重命名属于数据迁移。

## 快照查询字段解析

查询 factory 把逻辑 `QuerySchema` 与目标索引 mapping 合并，分别解析 exact match、range、sort、presence、projection 等物理路径。multi-field、runtime field 和禁用 object 服从 Elasticsearch mapping；不要在 HTTP 层猜测 `.keyword`。

## 刷新运行时查询 Schema

mapping 变化后，运行时 schema 必须重新解析。若 WebFlux/OpenAPI capability 注册了 schema refresh 路由，应从候选 runtime 的 OpenAPI 获取实际路径并授权调用；刷新只更新内存 schema，不回填历史文档或修改 mapping。

## 配置事件流索引模板

`auto-init-template=true` 时，`IndexTemplateInitializer` 确认 event template；请求失败、空响应或未确认会使存储装配失败。若平台外部管理模板，关闭自动初始化前要保留模板版本与部署证据。

## 配置快照索引模板

snapshot template 定义系统字段与动态状态映射基线。模板只影响新索引或后续 mapping 行为，不会自动修复已有索引。

## 全文搜索

全文能力来自目标字段的 text mapping 与 analyzer，不是 `wow-elasticsearch` 对所有字符串的默认承诺。

### 为状态字段添加全文索引

在平台拥有的 index template/component template 中声明 analyzer 与 text/multi-field，并确认不会覆盖 Wow 必需系统字段。更新后验证新旧索引 mapping。

### 执行全文搜索

只有运行时 schema 为字段发布相应 query capability 时才通过 Wow 查询 API 使用。原生 Elasticsearch DSL 不自动成为公共 Wow 请求模型。

## 聚合查询

Wow aggregation AST 编译为 Elasticsearch aggregation。嵌套元素、数值/时间类型与缺失值语义由公共合同和 mapping 共同决定；使用真实后端 TCK/集成测试验证。

## 索引设计建议

从查询、写入、保留和恢复目标设计索引，不要为每个状态字段默认增加 text/keyword 双映射。

### 分片策略

分片、副本和 routing 属于 Elasticsearch。上线前以真实 shard size、写入并发和查询 fan-out 验证，不由 Wow 自动选择。

### 索引生命周期管理 (ILM)

EventStore 是权威历史时，ILM 删除事件会破坏重放。只有数据职责与恢复方案明确允许时才配置 rollover/delete；快照索引也要与重建路径一致。

## 性能优化

观察 bulk latency/error、refresh、segment、heap、PIT 数与查询耗时，再调整 batch、mapping 或索引拓扑。

### 批量索引

batch options 必须满足 `max-size>1`、正 `max-delay`、pending 不小于 batch size、`lane-count>0`。同一聚合保持同一 lane；增加 lane 只解决已证明的并发瓶颈。

### 查询优化

全量查询使用 PIT + `search_after`，每批大小和 keep-alive 来自配置。`batch-size` 还不能高于目标索引 `index.max_result_window`；mapping 与查询模式优先于盲目增大批次。

## 故障排查

已验证失败包括 template 请求失败/空/未确认、非法 query/batch 参数、bulk item error、旧快照版本保护和 mapping/schema 冲突。

### 常见问题

保留 index/alias、resolved mapping、请求、响应 item error 和 runtime schema 作为证据。

#### 1. 查询报字段未映射、能力不兼容或 multi-field 存在歧义

检查目标索引实际 mapping 与 runtime schema。不要硬编码 `.keyword` 修补所有字段；修正模板/mapping 或显式公共字段合同后刷新 schema。

#### 2. 刷新端点不可用或刷新失败

确认 webflux/openapi capability、路由授权和 query factory 已装配。mapping 读取失败应保持失败，不应回退为“所有字段都可查”。

#### 3. alias 或 data stream 无法解析

当前 converter 生成具体索引名。若平台改为 alias/data stream，必须提供与读取、写入、mapping resolver 一致的迁移设计。

#### 4. 更新索引模板并刷新后，历史数据仍无法查询

模板不重写历史 mapping/data。需要 reindex 或显式迁移；schema refresh 只重新读取当前后端能力。

#### 5. runtime field 查询被拒绝

runtime field 的 projection 与部分查询能力受 mapping resolver 限制。以 runtime schema 暴露的 capability 为准，不绕过公共查询验证。

## 完整配置示例

```yaml
spring:
  elasticsearch:
    uris: ${ELASTICSEARCH_URIS}

wow:
  elasticsearch:
    auto-init-template: true
    query:
      batch-size: 10000
      keep-alive: 1m
    event-store-batch:
      enabled: false
    snapshot-store-batch:
      enabled: false
  eventsourcing:
    store:
      storage: elasticsearch
    snapshot:
      storage: elasticsearch
```

## 最佳实践

- 显式选择 event/snapshot storage，并核对生成的 binding；
- 由平台管理 mapping、模板、ILM、备份和 reindex；
- 保留快照版本保护与 bulk item 级失败；
- 用真实集群验证 mapping、PIT、aggregation 和升级。

聚焦检查：

```bash
./gradlew :wow-elasticsearch:check
```

下一步阅读[查询](../query.md)和[基础设施配置](../../reference/config/infrastructure.md)。
