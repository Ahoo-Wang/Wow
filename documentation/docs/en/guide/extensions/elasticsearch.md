---
title: Elasticsearch
description: Elasticsearch extension for full-text search and complex query support.
---

# Elasticsearch

The _Elasticsearch_ extension provides support for _Elasticsearch_, suitable for scenarios requiring full-text search and complex queries. It implements the following interfaces:

- `EventStore` - Event storage
- `EventStreamQueryService` - Event stream query service
- `SnapshotStore` - Snapshot store
- `SnapshotQueryService` - Snapshot query service

## Architecture Overview

```mermaid
flowchart TB
    subgraph Application["Application Layer"]
        AR[Aggregate Root]
        QS[Query Service]
    end
    
    subgraph Elasticsearch["Elasticsearch Cluster"]
        subgraph EventIndex["Event Index"]
            ES["wow.*.es"]
        end
        subgraph SnapshotIndex["Snapshot Index"]
            SS["wow.*.snapshot"]
        end
    end
    
    AR -->|Append Events| ES
    AR -->|Save Snapshot| SS
    QS -->|Full-text Search| SS
    QS -->|Aggregation Query| SS

```

## Installation

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

## Configuration

### Spring Data Elasticsearch Configuration

```yaml
spring:
  elasticsearch:
    uris:
      - http://localhost:9200
    username: elastic
    password: your-password
```

### Wow Configuration

```yaml
wow:
  eventsourcing:
    store:
      storage: elasticsearch
    snapshot:
      storage: elasticsearch
```

- Configuration class: [ElasticsearchProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/elasticsearch/ElasticsearchProperties.kt)
- Prefix: `wow.elasticsearch.`

| Name | Data Type | Default Value | Description |
|---|---|---|---|
| `enabled` | `Boolean` | `true` | Whether to enable the Elasticsearch extension |
| `auto-init-template` | `Boolean` | `true` | Install required index templates before startup completes |
| `event-store-batch.enabled` | `Boolean` | `false` | Batch concurrent EventStore appends with Bulk `create` |
| `event-store-batch.max-size` | `Int` | `128` | Maximum event streams per bulk batch |
| `event-store-batch.max-delay` | `Duration` | `1ms` | Maximum wait used to collect a partial batch |
| `event-store-batch.max-pending-appends` | `Int` | `4096` | Maximum accepted appends waiting or being written; must be at least `max-size` |
| `event-store-batch.lane-count` | `Int` | `1` | Number of serial write lanes; appends for the same aggregate stay on one lane |
| `snapshot-store-batch.enabled` | `Boolean` | `false` | Batch concurrent SnapshotStore saves with Bulk scripted upserts |
| `snapshot-store-batch.max-size` | `Int` | `128` | Maximum snapshots per bulk batch |
| `snapshot-store-batch.max-delay` | `Duration` | `1ms` | Maximum wait used to collect a partial snapshot batch |
| `snapshot-store-batch.max-pending-saves` | `Int` | `4096` | Maximum accepted saves waiting or being written; must be at least `max-size` |
| `snapshot-store-batch.lane-count` | `Int` | `1` | Number of serial write lanes; saves for the same aggregate stay on one lane |

Required index templates are installed before application startup completes. A failed or unacknowledged request fails
startup. Set `wow.elasticsearch.auto-init-template=false` only when templates are managed externally. The built-in
templates intentionally leave shard and replica counts to the cluster or to a higher-priority operator template.

## Write Batching

EventStore and SnapshotStore write batching is opt-in:

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

EventStore batches use Bulk `create`, so an existing event document is never
overwritten and an individual 409 remains an event-version conflict for only
that append. Bulk partial successes are returned to their corresponding callers.

SnapshotStore batches use Bulk `update` with scripted upserts. Direct saves use
the same atomic script: an incoming snapshot whose version is greater than or
equal to `_source.version` replaces the complete stored source, while a lower
version is a no-op. Equal-version replacement supports snapshot regeneration.
This also keeps pre-upgrade documents safe when their Elasticsearch
`_version` is an internal write counter rather than the aggregate version; no
version-metadata migration is required.

## Index Naming Rules

| Data Type | Index Naming Format | Example |
|---------|------------|------|
| Event Stream | `wow.{contextName}.{aggregateName}.es` | `wow.order-service.order.es` |
| Snapshot | `wow.{contextName}.{aggregateName}.snapshot` | `wow.order-service.order.snapshot` |

## Configure Event Stream Index Template

```http request
POST _index_template/wow-event-stream-template
{
  "index_patterns": [
    "wow.*.es"
  ],
  "template": {
    "mappings": {
      "properties": {
        "aggregateId": {
          "type": "keyword"
        },
        "aggregateName": {
          "type": "keyword"
        },
        "body": {
          "properties": {
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
        "version": {
          "type": "integer"
        }
      },
      "dynamic_templates": [
        {
          "string_as_keyword": {
            "match_mapping_type": "string",
            "mapping": {
              "type": "keyword"
            }
          }
        }
      ]
    }
  }
}
```

## Configure Snapshot Index Template

```http request
POST _index_template/wow-snapshot-template
{
  "index_patterns": [
    "wow.*.snapshot"
  ],
  "template": {
    "mappings": {
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
        },
        {
          "strings_as_keyword": {
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

The default template prioritizes parity with MongoDB exact-query operators, so dynamic strings in new snapshot indices are mapped as `keyword`. To keep arbitrary UTF-8 values from exceeding Lucene's term limit, values longer than 8191 characters remain in `_source` but are not indexed; exact, string, and `EXISTS` queries do not match those ignored values. Monitor Elasticsearch's `_ignored` metadata when this distinction matters. Arrays of objects are not inferred as `nested`; when using `ELEM_MATCH`, provide a higher-priority template for the aggregate:

```http request
POST _index_template/wow-order-snapshot-template
{
  "index_patterns": ["wow.*.order.snapshot"],
  "priority": 100,
  "template": {
    "mappings": {
      "properties": {
        "state": {
          "properties": {
            "items": {
              "type": "nested",
              "properties": {
                "sku": { "type": "keyword" }
              }
            }
          }
        }
      }
    }
  }
}
```

A higher-priority index template is not automatically merged with Wow's default index template. The example shows only the application-specific delta; a production template must also include the baseline snapshot mappings or compose baseline and application mappings through component templates.

### Migrating existing snapshot indices

Index templates affect only newly created indices; they do not change existing field mappings. Migrate one aggregate at a time:

1. Install the new template and create a new concrete index with explicit mappings, for example `wow.sales.order-v2.snapshot`.
2. Pause snapshot writes for that aggregate, or establish a reliable dual-write path, then `_reindex` from the existing `wow.sales.order.snapshot` index.
3. Verify document counts, representative operator queries, and sampled data. Keep a restorable Elasticsearch snapshot before cutover.
4. Wow uses a fixed logical index name. In one `_aliases` request, `remove_index` the old concrete index and add an alias with the old name pointing to the new index, with `is_write_index: true`.
5. Resume writes and monitor query/save failures. To roll back, restore the pre-migration snapshot into another concrete index and atomically switch the fixed-name alias; reinstalling the old template is not a rollback.

`remove_index` deletes the old concrete index. Do not cut over without a tested snapshot/restore path.

## Full-Text Search

Leverage Elasticsearch's full-text search capabilities for complex queries on snapshot state:

### Add Full-Text Index for State Fields

```http request
POST _index_template/wow-order-snapshot-template
{
  "index_patterns": [
    "wow.*.order.snapshot"
  ],
  "priority": 100,
  "template": {
    "mappings": {
      "properties": {
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
    }
  }
}
```

The higher priority makes the aggregate template win over Wow's default snapshot template. The shortened example
shows only the custom fields; a production replacement must also include the required baseline snapshot mappings, or
compose both baseline and custom mappings from component templates.

### Execute Full-Text Search

```kotlin
// Use QueryService for full-text search
val condition = Condition.all()
    .match("state.description", "phone")
    .range("state.totalAmount", 100, 500)
    .limit(10)

snapshotQueryService.dynamicQuery(condition)
```

## Aggregation Queries

Elasticsearch provides powerful aggregation capabilities:

### Statistical Analysis

```kotlin
// Count orders by status
val aggregation = SearchRequest.of { s ->
    s.index("wow.order-service.order.snapshot")
        .aggregations("status_count") { a ->
            a.terms { t ->
                t.field("state.status")
            }
        }
}
```

### Time Range Aggregation

```kotlin
// Daily order amount statistics
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

## Index Design Recommendations

### Sharding Strategy

```http request
PUT wow.order-service.order.snapshot
{
  "settings": {
    "number_of_shards": 5,
    "number_of_replicas": 1
  }
}
```

| Data Volume | Recommended Shards | Recommended Replicas |
|--------|---------|---------|
| < 1M | 1-3 | 1 |
| 1M-10M | 3-5 | 1-2 |
| > 10M | 5-10 | 2 |

### Index Lifecycle Management (ILM)

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

## Performance Optimization

### Bulk Indexing

The Elasticsearch extension supports bulk operations for optimized indexing performance:

```yaml
spring:
  elasticsearch:
    connection-timeout: 5s
    socket-timeout: 30s
```

### Query Optimization

1. **Use Filter Instead of Query**: Use filter for exact matches to improve cache hit rate
2. **Limit Returned Fields**: Use `_source` filtering to return only needed fields
3. **Pagination Optimization**: Use `search_after` instead of `from/size` for large result sets

## Troubleshooting

### Common Issues

#### 1. Index Mapping Conflict

**Solutions**:
- Check dynamic template configuration
- Use strict mapping mode

#### 2. Cluster Status Yellow or Red

**Solutions**:
- Check node status
- Add replicas or reallocate shards

#### 3. Slow Query Performance

**Solutions**:
- Optimize query statements
- Increase index shards
- Use caching

## Complete Configuration Example

```yaml
spring:
  elasticsearch:
    uris:
      - http://es-node-1:9200
      - http://es-node-2:9200
      - http://es-node-3:9200
    username: elastic
    password: your-password
    connection-timeout: 5s
    socket-timeout: 30s

wow:
  eventsourcing:
    store:
      storage: elasticsearch
    snapshot:
      enabled: true
      strategy: all
      storage: elasticsearch
```

## Best Practices

1. **Pre-define Mappings**: Create index templates in production to avoid dynamic mapping issues
2. **Appropriate Sharding**: Set appropriate shard count based on data volume, avoid too many small shards
3. **Use Aliases**: Use index aliases for zero-downtime migration
4. **Enable ILM**: Use index lifecycle management to automatically manage indexes
5. **Monitor Cluster**: Monitor cluster health status and performance metrics
