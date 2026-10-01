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

存储适配器（`ElasticsearchQuerySchemaAdapter`，一个 `QueryStorageAdapter`）读取目标索引 mapping，把它报告为存储事实：为 exact match、range、sort、presence、projection 等绑定的物理路径；`QuerySchemaCatalog` 把这些事实与逻辑模型编译成 `QueryModelSchema`；准入按这些 binding 解析每个字段引用，编译器消费得到的 `ResolvedField`。multi-field、runtime field 和禁用 object 服从 Elasticsearch mapping；不要在 HTTP 层猜测 `.keyword`。

### 带 `ignore_above` 的 keyword {#keyword-ignore-above}

`ignore_above` 不小于 8191 的 `keyword` 视为索引了全部值，拥有无上限 keyword 的全部操作：精确匹配、`in`/`notIn`、前缀与包含、范围、排序和 terms 聚合。Wow 的默认模板正是给 `tags.*`（ABAC 标签）、`id`、`*Id` 和事件流的动态字符串设了这个上限，它是能放进 Lucene 32766 字节词项上限的最多字符数；没有它，一个更长的值会让整个文档写入失败。限制是：长于 `ignore_above` 的值保留在 `_source` 中但不进索引，因此没有过滤条件能匹配它（以该值做 `eq`、`in`、前缀或包含都查不到；`ne` 和 `notIn` 会包含该文档），排序时按缺失处理，也不进入任何 terms 桶。存在性过滤（`exists`、`isNull`、`isEmpty`）仍通过 Elasticsearch 的 `_ignored` 字段看到该值，因此 ABAC 标签超长的资源不会被当作无标签的公开资源；在 `nested` 元素内，这样的值按缺失处理。`_ignored` 同样记录被 `ignore_malformed` 丢弃值的字段（无法解析的数字或日期），因此 `isNull`、`notExists` 和 `isEmpty` 也把这样的值视为存在。`flattened` 字段不在 `_ignored` 中记录任何内容，因此带 `ignore_above` 的 `flattened` 字段不提供存在性过滤。

更小的 `ignore_above`（例如 Elasticsearch 为未映射字符串推断的 `text` + `keyword`，`ignore_above: 256`）可能丢掉查询需要的值，因此该字段没有查询操作；例外是每个声明值都在上限内的字符串枚举。这类字段请显式映射（见[字符串字段的可聚合性](../query/aggregation-query.md#es-string-aggregability)）。Wow 9.1.x 拒绝除这类枚举之外所有带 `ignore_above` 的 keyword。

## 首次写入之前

Wow 在聚合首次写入时创建它的索引，所以新聚合在此之前没有索引。查询不存在的索引不返回任何记录：列表、分页和游标为空，计数为 `0`，没有分组的聚合返回空汇总，与快照加载不存在的索引时一致。不存在的索引的查询 schema 由该索引将被创建时的 mapping 编译：即 Elasticsearch 为该索引名模拟出的匹配索引模板（`POST _index_template/_simulate_index/<index>`），没有模板匹配时则没有字段。这需要 `manage_index_templates` 集群权限，`auto-init-template` 同样需要；没有该权限时，schema 在索引存在前保持不可用（`QuerySchemaUnavailable`，HTTP 503）。

模拟出的 mapping 尚未映射的字段（动态 mapping 将新增的字段，或 ABAC 标签键）按其逻辑类型允许的全部操作放行，因此对它的过滤、排序或分组不返回记录，而不是被拒绝；模板已映射的字段保持其 mapping 所能证明的能力。这个临时 schema 从不发布：它只保留 2 秒（`DefaultQueryModelSchemaProvider.DEFAULT_PROVISIONAL_TTL`），因此从未写入的聚合不会在每次查询时都模拟模板，过期后的第一次查询会重新编译它。首次写入后 2 秒内即编译并发布该索引自身的 mapping（含首批文档新增的字段），无需等待重新校验。有索引定义（见下文）的聚合在启动时即有索引，不会走到这条路径。

## 重新校验运行时查询 Schema

mapping 变化后，运行时 schema 必须重新解析。每个实例按 `wow.query.schema.revalidate-interval` 定期重新校验查询 schema；需要立即生效时，在每个实例上调用 `wowQuerySchema` actuator 端点。重新校验只更新内存 schema，不回填历史文档或修改 mapping。

## 配置事件流索引模板

`auto-init-template=true` 时，`IndexTemplateInitializer` 确认 event template；请求失败、空响应或未确认会使存储装配失败。若平台外部管理模板，关闭自动初始化前要保留模板版本与部署证据。

event template 不索引事件的 `body`（`enabled: false`）。需要查询事件字段（`body.body.*`）的应用为该聚合的事件流提供具体索引定义：`META-INF/wow/elasticsearch/wow.sales.order.es.json` 或 `config/wow/elasticsearch/wow.sales.order.es.json`，规则与下文快照索引定义相同。把 `body.body` 映射为 `dynamic: false` 的 object，只列出要查询的字段，其他事件的 body 不会新增字段。

## 配置快照索引模板

snapshot template 定义系统字段与动态状态映射基线。模板只影响新索引或后续 mapping 行为，不会自动修复已有索引。

通用 snapshot template 是仅存储快照的后备方案。可查询快照应在 `META-INF/wow/elasticsearch/wow.sales.order.snapshot.json` 或 `config/wow/elasticsearch/wow.sales.order.snapshot.json` 提供包含业务 mapping 的具体索引定义：

```json
{
  "mappings": {
    "properties": {
      "state": {
        "properties": {
          "status": { "type": "keyword" }
        }
      }
    }
  }
}
```

资源键是 Wow 计算出的最终索引名。工作目录文件会替换 classpath 文件；没有工作目录文件时，重复的 classpath 文件会导致启动失败。资源缺失时仍使用通用模板行为。已有索引会被跳过，因此 mapping 变更需要显式 reindex 或迁移：已有索引对定义中某些路径的映射与定义不同时（例如定义发布之前仅由模板创建的索引），启动时记录一条列出这些路径的警告，然后继续启动。资源 JSON 遵循 Elasticsearch client 与集群的校验语义。无论 storage routing 如何配置，只要资源存在就会请求创建索引。

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

偏移分页要读 `from + size` 条记录，Elasticsearch 以索引的 `index.max_result_window`（索引未设置时为 10000）为上限。`page × size` 超过它的分页查询在检索前就被拒绝，报 `pagination` 上的 `SIZE_OUT_OF_RANGE`（HTTP 400）；更深的数据请用游标查询。

## 故障排查

已验证失败包括 template 请求失败/空/未确认、非法 query/batch 参数、bulk item error、旧快照版本保护和 mapping/schema 冲突。

### 常见问题

保留 index/alias、resolved mapping、请求、响应 item error 和 runtime schema 作为证据。

#### 1. 查询报字段未映射、能力不兼容或 multi-field 存在歧义

检查目标索引实际 mapping 与 runtime schema。不要硬编码 `.keyword` 修补所有字段；修正模板/mapping 或显式公共字段合同后重新校验 schema。

#### 2. 重新校验端点不可用或重新校验失败

确认 Spring Boot Actuator 在 classpath 上并在管理面暴露 `wowQuerySchema` 端点，且 query factory 已装配，`QuerySchemaCatalog` 才有模型可重新校验；Catalog 的 `wow.query.schema.refresh` 指标记录每次结果。mapping 读取失败应保持失败，不应回退为“所有字段都可查”。

#### 3. alias 或 data stream 无法解析

当前 converter 生成具体索引名。若平台改为 alias/data stream，必须提供与读取、写入、mapping resolver 一致的迁移设计。

#### 4. 更新索引模板并重新校验后，历史数据仍无法查询

模板不重写历史 mapping/data。需要 reindex 或显式迁移；schema 重新校验只重新读取当前后端能力。

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
