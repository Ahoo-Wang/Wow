---
title: Elasticsearch
description: Elasticsearch 扩展，支持全文搜索和复杂查询场景。
---

# Elasticsearch

_Elasticsearch_ 扩展提供了对 _Elasticsearch_ 的支持，适用于需要全文搜索和复杂查询的场景。它实现了以下接口：

- `EventStore` - 事件存储
- `EventStreamQueryService` - 事件流查询服务
- `SnapshotStore` - 快照存储
- `SnapshotQueryService` - 快照查询服务

## 架构概述

```mermaid
flowchart TB
    subgraph Application["应用层"]
        AR[聚合根]
        QS[查询服务]
    end
    
    subgraph Elasticsearch["Elasticsearch 集群"]
        subgraph EventIndex["事件索引"]
            ES["wow.*.es"]
        end
        subgraph SnapshotIndex["快照索引"]
            SS["wow.*.snapshot"]
        end
    end
    
    AR -->|追加事件| ES
    AR -->|保存快照| SS
    QS -->|全文搜索| SS
    QS -->|聚合查询| SS

```

## 安装

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-elasticsearch")
implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-elasticsearch'
implementation 'org.springframework.boot:spring-boot-starter-data-elasticsearch'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-elasticsearch</artifactId>
    <version>${wow.version}</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-elasticsearch</artifactId>
</dependency>
```
:::

## 配置

### Spring Data Elasticsearch 配置

```yaml
spring:
  elasticsearch:
    uris:
      - http://localhost:9200
    username: elastic
    password: ${ELASTICSEARCH_PASSWORD}
```

### Wow 配置

```yaml
wow:
  eventsourcing:
    store:
      storage: elasticsearch
    snapshot:
      storage: elasticsearch
```

- 配置类：[ElasticsearchProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchProperties.kt)
- 查询配置类：[ElasticsearchQueryProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchQueryProperties.kt)
- 前缀：`wow.elasticsearch.`

| 名称 | 数据类型 | 默认值 | 描述 |
|---|---|---|---|
| `enabled` | `Boolean` | `true` | 是否启用 Elasticsearch 扩展 |
| `auto-init-template` | `Boolean` | `true` | 在应用启动完成前安装所需的索引模板 |
| `event-store-batch.enabled` | `Boolean` | `false` | 使用 Bulk `create` 批量处理并发的事件流追加 |
| `event-store-batch.max-size` | `Int` | `128` | 每个批量批次的最大事件流数 |
| `event-store-batch.max-delay` | `Duration` | `1ms` | 收集部分批次的等待时长上限 |
| `event-store-batch.max-pending-appends` | `Int` | `4096` | 等待或正在写入的追加请求上限；必须不小于 `max-size` |
| `event-store-batch.lane-count` | `Int` | `1` | 串行写入通道数；同一聚合的追加保持在同一通道 |
| `snapshot-store-batch.enabled` | `Boolean` | `false` | 使用 Bulk 脚本 upsert 批量处理并发快照保存 |
| `snapshot-store-batch.max-size` | `Int` | `128` | 每个批量批次的最大快照数 |
| `snapshot-store-batch.max-delay` | `Duration` | `1ms` | 收集部分快照批次的等待时长上限 |
| `snapshot-store-batch.max-pending-saves` | `Int` | `4096` | 等待或正在写入的保存请求上限；必须不小于 `max-size` |
| `snapshot-store-batch.lane-count` | `Int` | `1` | 串行写入通道数；同一聚合的保存保持在同一通道 |
| `query.batch-size` | `Int` | `10000` | PIT + `search_after` 列表查询的内部批大小，取值范围 `1..10000` |
| `query.keep-alive` | `Duration` | `1m` | PIT 的保持时间，必须大于 `0` |

应用启动完成前会安装所需索引模板；请求失败或未确认会导致启动失败。仅当索引模板由外部系统管理时，才应设置
`wow.elasticsearch.auto-init-template=false`。内置模板不会固定分片数和副本数，这些拓扑参数应由集群或更高优先级的
运维模板管理。

## 写入批处理

EventStore 与 SnapshotStore 的写入批处理默认关闭，可按需启用：

```yaml
wow:
  elasticsearch:
    event-store-batch:
      enabled: true
      max-size: 128
      max-delay: 1ms
      max-pending-appends: 4096
      lane-count: 1
    snapshot-store-batch:
      enabled: true
      max-size: 128
      max-delay: 1ms
      max-pending-saves: 4096
      lane-count: 1
```

EventStore 使用 Bulk `create`，不会覆盖已有事件文档；单项 409 只会作为
对应 append 的事件版本冲突返回，Bulk 中其他成功项仍会返回给各自调用者。

SnapshotStore 使用带 scripted upsert 的 Bulk `update`，direct 路径使用相同的
原子脚本：输入版本大于或等于 `_source.version` 时完整替换已存快照，只有较低
版本为 no-op。同版本覆盖支持快照重建。升级前文档的 Elasticsearch `_version`
是内部写入计数器时同样安全，不需要迁移版本元数据。

## 索引命名规则

| 数据类型 | 索引命名格式 | 示例 |
|---------|------------|------|
| 事件流 | `wow.{contextName}.{aggregateName}.es` | `wow.order-service.order.es` |
| 快照 | `wow.{contextName}.{aggregateName}.snapshot` | `wow.order-service.order.snapshot` |

## 快照查询字段解析

快照查询第一次访问聚合索引时会异步加载当前 Elasticsearch Mapping，并按物理字段能力编译条件和排序；Mapping
是字段能力的唯一来源，不需要维护额外的 `QuerySchema`。缓存按索引隔离且没有 TTL。缓存中的字段缺失或能力不匹配时，
查询直接失败且不会隐式访问 Mapping API；Mapping 变更后通过维护端点显式刷新。

字段选择规则如下：

| Query 操作 | Mapping 要求 |
|---|---|
| `EQ`、`NE`、`IN`、`NOT_IN`、`ALL_IN`、`TRUE`、`FALSE` | 可执行 term 查询，包括受支持的 doc-value-only 字段 |
| `CONTAINS`、`STARTS_WITH`、`ENDS_WITH` | `keyword` 或 `wildcard` |
| 范围操作 | numeric、date、ip、keyword 或 `*_range`，支持适用的 `doc_values=true,index=false` 字段 |
| `MATCH` | `text`、`match_only_text`、`search_as_you_type` 或 `semantic_text` |
| 排序 | 启用 `doc_values` 的可排序字段、启用 `fielddata` 的已索引 `text` 字段，或可排序 runtime field |

`_score`、`_doc` 和 `_shard_doc` 是 Elasticsearch 元数据排序字段，不参与 Mapping 字段解析并保持原样传递。
`text.fielddata=true` 会占用较多堆内存，通常仍应优先使用 `keyword` multi-field；doc-values 字段和 runtime field
排序不要求 `index=true`。

flattened 字段的动态键不会单独出现在 Mapping 中。Resolver 会从 `state.labels.release` 这类具体路径向上查找最近的
flattened 父字段，并保留原路径执行精确和排序操作。flattened 值均按 keyword 处理，排序是字典序；
动态键不自动启用范围操作，显式声明的 typed sub-field 仍使用自身类型能力。

例如，同一逻辑字段可同时支持全文搜索和精确查询：

```json
{
  "properties": {
    "state": {
      "properties": {
        "customerName": {
          "type": "text",
          "fields": {
            "keyword": { "type": "keyword" }
          }
        }
      }
    }
  }
}
```

| Query 操作 | 客户端字段 | 实际字段 |
|---|---|---|
| `MATCH` | `state.customerName` | `state.customerName` |
| `EQ`、`IN`、排序 | `state.customerName` | `state.customerName.keyword` |
| projection | `state.customerName` | `state.customerName`（不重写） |

客户端始终使用逻辑字段，不需要固定写入 `.keyword` 或 `.text`。

对于 multi-field，依次选择当前字段、`.keyword`/`.text`、`.exact`，最后才选择唯一的兼容子字段；存在多个兼容候选时
查询失败，避免静默改变语义。`EXISTS`、`NULL`、`NOT_NULL`、projection 和 `RAW` 不重写，`ELEM_MATCH` 的父路径
必须映射为 `nested`。自定义 `ConditionConverter` 继续负责解释自己的物理字段，不经过上述重写。

Field alias 继承其目标字段的查询与排序能力，并继续使用 alias 名称生成查询。Mapping 中声明的 runtime field 也会按
其类型参与能力解析；runtime 查询会在查询时计算，且当 Elasticsearch 设置 `search.allow_expensive_queries=false` 时
可能被集群拒绝。projection 仍按逻辑路径作用于 `_source`，不会把 field alias 或 runtime field 改写为其目标字段。

每个聚合必须解析到一个物理快照索引。alias 或 data stream 如果解析出多个索引，查询会失败；这种拓扑需要先收敛为
单个物理索引，或由应用自行实现基于 `_field_caps` 的查询方案。

## 主动刷新 Mapping

非 Spring 场景可直接刷新默认构造路径持有的缓存：

```kotlin
queryService.refreshIndexMapping().block()
queryServiceFactory.refreshIndexMapping(namedAggregate).block()
```

Factory 方法刷新该 Factory 创建的查询服务共享的 Resolver；直接构造的 `ElasticsearchSnapshotQueryService`
使用实例方法刷新自身 Resolver。

应用引入 `org.springframework.boot:spring-boot-starter-actuator` 后会注册可选维护端点。端点默认不可访问，必须同时配置
access 和 Web exposure，并由管理端安全策略限制为维护角色：

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("org.springframework.boot:spring-boot-starter-actuator")
```
```xml [Maven]
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```
:::

```yaml
management:
  endpoint:
    wowElasticsearchMapping:
      access: unrestricted
  endpoints:
    web:
      exposure:
        include: health,wowElasticsearchMapping
```

按已注册聚合刷新当前实例的 Mapping：

```bash
curl -X POST \
  http://localhost:8080/actuator/wowElasticsearchMapping/order-service/order
```

响应示例：

```json
{
  "scope": "LOCAL_INSTANCE",
  "indexName": "wow.order-service.order.snapshot",
  "fieldCount": 24,
  "changed": true,
  "refreshedAt": "2026-08-21T09:00:00Z"
}
```

端点不接受任意索引表达式，索引名由聚合元数据计算。`fieldCount` 是解析到的字段路径数，`changed`
表示新能力模型是否与刷新前不同；响应不会返回完整 Mapping。未注册的聚合返回 `400 Bad Request`。

Elasticsearch 凭据需要目标索引的 `view_index_metadata` 权限；权限不足、索引不存在或 Mapping 无法解析时
请求失败，已有成功缓存不受影响。刷新只重新加载查询能力缓存，不修改 Elasticsearch Mapping、
不重建索引，也不回填历史快照。

刷新只作用于收到请求的应用实例。多副本部署需要运维逐 Pod 调用；当前不提供广播、定时刷新或“刷新全部聚合”。
刷新完成后的新查询使用新 Mapping，已经在途的查询继续使用其开始编译时取得的旧 Mapping。

Mapping 发布必须按以下顺序执行：

1. 更新目标索引 Mapping；需要历史数据转换时先完成 reindex 或快照重建。
2. 对每个应用 Pod 调用上述刷新端点，并确认返回的 `fieldCount` 与 `changed` 符合预期。
3. 在每个 Pod 执行代表性精确查询、全文查询和排序查询，核对结果数、顺序与快照版本后再完成发布。

## 配置事件流索引模板

内置模板关闭自动日期探测，业务日期字段应在聚合专用模板中显式映射。事件条目使用 `nested`；`body[].body`
仍保留在 `_source`，但不参与索引，避免任意载荷因 Mapping 冲突阻断持久化。单个事件流最多包含 10,000 个事件，
与 Elasticsearch 默认的 nested-object 上限一致。

Elasticsearch 的 `ELEM_MATCH` 子条件必须使用完整路径：

```kotlin
condition {
    "body" elemMatch {
        "body.name" eq "Created"
    }
}
```

```json
{
  "condition": {
    "field": "body",
    "operator": "ELEM_MATCH",
    "children": [
      {
        "field": "body.name",
        "operator": "EQ",
        "value": "Created"
      }
    ]
  }
}
```

需要检索载荷或自定义 nested 语义时，应用必须提供高优先级模板和匹配的查询转换器。由于 composable template
不会按优先级合并，该模板必须完整复现内置基础 Mapping。

```http request
POST _index_template/wow-event-stream-template
{
  "index_patterns": [
    "wow.*.es"
  ],
  "template": {
    "mappings": {
      "date_detection": false,
      "properties": {
        "aggregateId": {
          "type": "keyword"
        },
        "aggregateName": {
          "type": "keyword"
        },
        "body": {
          "type": "nested",
          "properties": {
            "body": {
              "type": "object",
              "enabled": false
            },
            "bodyType": {
              "type": "keyword"
            },
            "id": {
                "type": "keyword"
            },
            "name": {
              "type": "keyword"
            },
            "revision": {
              "type": "keyword"
            }
          }
        },
        "commandId": {
          "type": "keyword"
        },
        "contextName": {
          "type": "keyword"
        },
        "createTime": {
          "type": "long"
        },
        "header": {
          "properties": {
            "upstream_id": {
              "type": "keyword"
            },
            "upstream_name": {
              "type": "keyword"
            }
          }
        },
        "id": {
          "type": "keyword"
        },
        "requestId": {
          "type": "keyword"
        },
        "tenantId": {
          "type": "keyword"
        },
        "ownerId": {
          "type": "keyword"
        },
        "spaceId": {
          "type": "keyword"
        },
        "version": {
          "type": "integer"
        }
      },
      "dynamic_templates": [
        {
          "string_as_keyword": {
            "match_mapping_type": "string",
            "mapping": {
              "type": "keyword",
              "ignore_above": 8191
            }
          }
        }
      ]
    }
  }
}
```

## 配置快照索引模板

```http request
POST _index_template/wow-snapshot-template
{
  "index_patterns": [
    "wow.*.snapshot"
  ],
  "template": {
    "mappings": {
      "date_detection": false,
      "properties": {
        "contextName": {
          "type": "keyword"
        },
        "aggregateName": {
          "type": "keyword"
        },
        "tenantId": {
          "type": "keyword"
        },
        "aggregateId": {
          "type": "keyword"
        },
        "version": {
          "type": "integer"
        },
        "eventId": {
          "type": "keyword"
        },
        "ownerId": {
          "type": "keyword"
        },
        "spaceId": {
          "type": "keyword"
        },
        "firstOperator": {
          "type": "keyword"
        },
        "operator": {
          "type": "keyword"
        },
        "firstEventTime": {
          "type": "long"
        },
        "eventTime": {
          "type": "long"
        },
        "snapshotTime": {
          "type": "long"
        },
        "deleted": {
          "type": "boolean"
        },
        "tags": {
          "type": "object",
          "dynamic": true
        },
        "state": {
          "properties": {
            "id": {
              "type": "keyword"
            },
            "tenantId": {
              "type": "keyword"
            }
          }
        }
      },
      "dynamic_templates": [
        {
          "tags_strings_as_keyword": {
            "match_mapping_type": "string",
            "path_match": "tags.*",
            "mapping": {
              "type": "keyword",
              "ignore_above": 8191
            }
          }
        },
        {
          "id_string_as_keyword": {
            "match": "id",
            "match_mapping_type": "string",
            "mapping": {
              "type": "keyword",
              "ignore_above": 8191
            }
          }
        },
        {
          "id_suffix_string_as_keyword": {
            "match": "*Id",
            "match_mapping_type": "string",
            "mapping": {
              "type": "keyword",
              "ignore_above": 8191
            }
          }
        }
      ]
    }
  }
}
```

## 全文搜索

利用 Elasticsearch 的全文搜索能力，可以对快照状态进行复杂查询：

### 为状态字段添加全文索引

以下内容只是自定义 `state.properties` 片段，不能作为索引模板直接提交：

```json
{
  "state": {
    "properties": {
      "description": {
        "type": "text",
        "analyzer": "standard"
      },
      "customerName": {
        "type": "text",
        "fields": {
          "keyword": {
            "type": "keyword"
          }
        }
      }
    }
  }
}
```

发布 `wow-order-snapshot-template` 时，先复制上文完整的内置快照模板，将该片段合并到
`mappings.properties`，收窄 `index_patterns`，最后设置 `priority: 100`。高优先级 composable template 会替换而非
合并 Wow 默认模板，绝不能单独发布上述片段。也可以通过运维自有的基础 component template 和自定义 component
template 组合出完整 Mapping。

::: tip
如果需要中文分词支持，可以安装 [IK 分析器插件](https://github.com/medcl/elasticsearch-analysis-ik)，然后使用 `ik_max_word` 和 `ik_smart` 分析器。
:::

### 执行全文搜索

```kotlin
// 使用 QueryService 进行全文搜索
listQuery {
    condition {
        "state.description" match "搜索关键词"
        "state.totalAmount" between 100 to 500
    }
    sort {
        "state.customerName".asc()
    }
    limit(10)
}.dynamicQuery(snapshotQueryService)
```

## 聚合查询

Elasticsearch 提供强大的聚合功能：

### 统计分析

```kotlin
// 按状态统计订单数量
val aggregation = SearchRequest.of { s ->
    s.index("wow.order-service.order.snapshot")
        .aggregations("status_count") { a ->
            a.terms { t ->
                t.field("state.status")
            }
        }
}
```

### 时间范围聚合

```kotlin
// 按天统计订单金额
val aggregation = SearchRequest.of { s ->
    s.index("wow.order-service.order.snapshot")
        .aggregations("daily_amount") { a ->
            a.dateHistogram { d ->
                d.field("eventTime")
                    .calendarInterval(CalendarInterval.Day)
            }
            .aggregations("total") { sa ->
                sa.sum { sum ->
                    sum.field("state.totalAmount")
                }
            }
        }
}
```

## 索引设计建议

### 分片策略

```http request
PUT wow.order-service.order.snapshot
{
  "settings": {
    "number_of_shards": 5,
    "number_of_replicas": 1
  }
}
```

| 数据量 | 推荐分片数 | 推荐副本数 |
|--------|---------|---------|
| < 100万 | 1-3 | 1 |
| 100万-1000万 | 3-5 | 1-2 |
| > 1000万 | 5-10 | 2 |

### 索引生命周期管理 (ILM)

```http request
PUT _ilm/policy/wow-snapshot-policy
{
  "policy": {
    "phases": {
      "hot": {
        "actions": {
          "rollover": {
            "max_size": "50gb",
            "max_age": "30d"
          }
        }
      },
      "warm": {
        "min_age": "30d",
        "actions": {
          "shrink": {
            "number_of_shards": 1
          }
        }
      },
      "cold": {
        "min_age": "90d",
        "actions": {
          "freeze": {}
        }
      }
    }
  }
}
```

## 快照 Elements 聚合

每层 Elements 路径必须在实际索引 Mapping 中声明为 `nested`；普通 `object` 会被拒绝。分组使用原生 field + composite aggregation，不读取 `_source`、不执行 Painless。分组键排序按 composite 顺序分页并可在达到 limit 后停止；指标排序会复用同一 PIT 完整遍历所有 bucket，再以 O(limit) 内存计算精确 Top-N。`DateHistogram` 仅接受 `date`/`date_nanos` Mapping，普通 long epoch 字段不会被隐式接受。

## 性能优化

### 批量索引

Elasticsearch 扩展支持批量操作，优化索引性能：

```yaml
spring:
  elasticsearch:
    connection-timeout: 5s
    socket-timeout: 30s
```

### 查询优化

1. **使用 Filter 替代 Query**：对于精确匹配使用 filter 提高缓存命中率
2. **限制返回字段**：使用 `_source` 过滤只返回需要的字段
3. **全量列表查询**：`ListQuery.limit=0` 使用 PIT + `search_after` 流式返回全部匹配结果；默认情况下，`1..10_000` 使用单次请求，更大的正数 limit 使用同一内部分页器。10,000 只是默认内部批大小，不是结果上限。
4. **分页查询**：`PagedQuery` 保持 `from/size` 语义，仍受 Elasticsearch `index.max_result_window` 限制。需要完整结果集时应使用列表查询。

PIT 列表查询未指定 `sort` 时只按 `_shard_doc` 扫描，结果顺序不属于契约。需要相关性顺序时应显式添加 `_score DESC`。

当目标索引的 `index.max_result_window` 小于 10,000 时，将 `wow.elasticsearch.query.batch-size` 配置为不超过该值；当慢速订阅者消费一批数据可能超过默认 `1m` 时，应增大 `wow.elasticsearch.query.keep-alive`。

## 故障排查

### 常见问题

#### 1. 查询报字段未映射、能力不兼容或 multi-field 存在歧义

使用 `GET /{indexName}/_mapping` 检查当前物理 Mapping，再调用主动刷新端点。多个兼容子字段时，使用
`.keyword`、`.text` 或 `.exact` 的约定名称消除歧义。

#### 2. 刷新端点不可用或刷新失败

`404` 时检查 Actuator 依赖、endpoint access 和 Web exposure；`400` 时检查 `contextName` 和 `aggregateName`
是否已注册。Elasticsearch 错误时检查索引是否存在以及 `view_index_metadata` 权限。刷新失败不会删除旧缓存。

#### 3. alias 或 data stream 无法解析

确认快照别名只指向一个物理索引。当前 Resolver 不合并多索引 Mapping，需要多索引查询时由应用基于
`_field_caps` 实现。

#### 4. 更新索引模板并刷新后，历史数据仍无法查询

索引模板只影响之后创建的索引，Mapping 刷新也只更新 Wow 的能力缓存。根据 Mapping 变更类型执行
`PUT /{indexName}/_mapping`、reindex 或快照重建，并核对结果数、排序和快照版本。

#### 5. runtime field 查询被拒绝

检查集群的 `search.allow_expensive_queries`。保持该限制时，将高频查询字段落到物理 Mapping，不要依赖 runtime field。

## 完整配置示例

```yaml
spring:
  elasticsearch:
    uris:
      - http://es-node-1:9200
      - http://es-node-2:9200
      - http://es-node-3:9200
    username: elastic
    password: ${ELASTICSEARCH_PASSWORD}
    connection-timeout: 5s
    socket-timeout: 30s

wow:
  elasticsearch:
    query:
      batch-size: 10000
      keep-alive: 1m
  eventsourcing:
    store:
      storage: elasticsearch
    snapshot:
      enabled: true
      strategy: all
      storage: elasticsearch
```

## 最佳实践

1. **预定义映射**：在首个快照写入前安装高优先级的聚合索引模板，避免动态映射锁定错误类型
2. **合理分片**：根据数据量设置合适的分片数，避免过多小分片
3. **限制别名拓扑**：Wow 快照查询使用的 alias 或 data stream 必须只解析到一个物理索引
4. **重建后核对**：Mapping 变更需要 reindex 或快照重建时，核对结果数、排序和快照版本
5. **监控集群**：监控集群健康状态、PIT 数量和查询延迟
