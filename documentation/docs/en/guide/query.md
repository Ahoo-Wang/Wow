---
title: Query Service
description: Query service provides query capabilities through wow-mongo and wow-elasticsearch modules.
---

# Query Service

:::tip
Currently the `wow-mongo` module and `wow-elasticsearch` module support query services.
:::

## Operators

| Operator           | Description                                                                                                                                                                                                                                                             |
|---------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| AND           | Performs logical AND on the provided list of conditions                                                                                                                                                                                                                 |
| OR            | Performs logical OR on the provided list of conditions                                                                                                                                                                                                                  |
| NOR           | Performs logical NOR on the provided list of conditions                                                                                                                                                                                                                 |
| ID            | Matches all documents where the `id` field value equals the specified value                                                                                                                                                                                             |
| IDS           | Matches all documents where the `id` field value equals any value in the specified list                                                                                                                                                                                 |
| AGGREGATE_ID  | Matches documents where the aggregate root ID equals the specified value                                                                                                                                                                                                |
| AGGREGATE_IDS | Matches all documents where the aggregate root ID equals any value in the specified list                                                                                                                                                                                |
| TENANT_ID     | Matches all documents where the `tenantId` field value equals the specified value                                                                                                                                                                                       |
| OWNER_ID      | Matches all documents where the `ownerId` field value equals the specified value                                                                                                                                                                                        |
| SPACE_ID      | Matches all documents where the `spaceId` field value equals the specified value                                                                                                                                                                                        |
| DELETED       | Matches all documents where the `deleted` field value equals the specified value                                                                                                                                                                                        |
| ALL           | Matches all documents                                                                                                                                                                                                                                                   |
| EQ            | Matches all documents where the field name value equals the specified value                                                                                                                                                                                             |
| NE            | Matches all documents where the field name value does not equal the specified value                                                                                                                                                                                     |
| GT            | Matches all documents where the value of the given field is greater than the specified value                                                                                                                                                                            |
| LT            | Matches all documents where the value of the given field is less than the specified value                                                                                                                                                                               |
| GTE           | Matches all documents where the value of the given field is greater than or equal to the specified value                                                                                                                                                                |
| LTE           | Matches all documents where the value of the given field is less than or equal to the specified value                                                                                                                                                                   |
| CONTAINS      | Matches all documents where the value of the given field contains the specified value                                                                                                                                                                                   |
| IN            | Matches all documents where the field value equals any value in the specified list                                                                                                                                                                                      |
| NOT_IN        | Matches all documents where the field value does not equal any specified value or does not exist                                                                                                                                                                        |
| BETWEEN       | Matches all documents where the field value is within the specified range                                                                                                                                                                                               |
| ALL_IN        | Matches all documents where the field value is an array containing all specified values                                                                                                                                                                                 |
| STARTS_WITH   | Matches documents where the field value starts with the specified string                                                                                                                                                                                                |
| ENDS_WITH     | Matches documents where the field value ends with the specified string                                                                                                                                                                                                  |
| MATCH         | Full-text match. Backend-specific: MongoDB uses `text` search over the configured text index; Elasticsearch uses `match` on the specified field                                                                                                                        |
| ELEM_MATCH    | Matches all documents with array fields where at least one member of the array matches the given condition.                                                                                                                                                             |
| NULL          | Matches all documents where the field value is `null`                                                                                                                                                                                                                   |
| NOT_NULL      | Matches all documents where the field value is not `null`                                                                                                                                                                                                               |
| TRUE          | Matches all documents where the field value is `true`                                                                                                                                                                                                                   |
| FALSE         | Matches all documents where the field value is `false`                                                                                                                                                                                                                  |
| EXISTS        | Matches documents where the field exists                                                                                                                                                                                                                                |
| RAW           | Raw operator, uses the condition value directly as the original database query condition                                                                                                                                                                                |
| TODAY         | Matches all documents where the field is within today's range. For example: `today` is `2024-06-06`, matches range `2024-06-06 00:00:00.000` ~ `2024-06-06 23:59:59.999`                                                                                                |
| BEFORE_TODAY  | Matches all documents where the field is before today                                                                                                                                                                                                                   |
| TOMORROW      | Matches all documents where the field is within tomorrow's range. For example: `today` is `2024-06-06`, matches range `2024-06-07 00:00:00.000` ~ `2024-06-07 23:59:59.999`                                                                                            |
| THIS_WEEK     | Matches all documents where the field is within this week's range                                                                                                                                                                                                       |
| NEXT_WEEK     | Matches all documents where the field is within next week's range                                                                                                                                                                                                       |
| LAST_WEEK     | Matches all documents where the field is within last week's range                                                                                                                                                                                                       |
| THIS_MONTH    | Matches all documents where the field is within this month's range. For example: `today`: `2024-06-06`, matches range: `2024-06-01 00:00:00.000` ~ `2024-06-30 23:59:59.999`                                                                                            |
| LAST_MONTH    | Matches all documents where the field is within last month's range. For example: `today`: `2024-06-06`, matches range: `2024-05-01 00:00:00.000` ~ `2024-05-31 23:59:59.999`                                                                                            |
| RECENT_DAYS   | Matches all documents where the field is within the specified number of recent days range. For example: `today`: `2024-06-06`, recent 3 days, matches range: `2024-06-04 00:00:00.000` ~ `2024-06-06 23:59:59.999`. That is: today, yesterday, the day before yesterday |
| EARLIER_DAYS  | Matches all documents where the field is within the specified number of days before the specified value. For example: `today`: `2024-06-06`, 3 days ago, matches range: less than `2024-06-04 00:00:00.000`                                                             |

:::info Elasticsearch string fields
`CONTAINS`, `STARTS_WITH`, and `ENDS_WITH` are literal operations. In Elasticsearch, use them with term-level fields such as `keyword` and `wildcard`; `*`, `?`, and `\` are matched as ordinary characters, and all three support `ignoreCase`. Use `MATCH` for full-text search.
:::

## Query DSL

The `Query DSL` aims to provide a concise and flexible way to build query conditions.

### ConditionDsl

```kotlin
condition {
    deleted(DeletionState.ALL)
    and {
        tenantId("tenantId")
        all()
    }
    nor {
        all()
    }
    id("id")
    ids("id", "id2")
    "field1" eq "value1"
    "field2" ne "value2"
    "filed3" gt 1
    "field4" lt 1
    "field5" gte 1
    "field6" lte 1
    "field7" contains "value7"
    "field8" isIn listOf("value8")
    "field9" notIn listOf("value9")
    "field10" between (1 to 2)
    "field100" between 1 to 2
    "field11" all listOf("value11")
    "field12" startsWith "value12"
    "field12" endsWith "value12"
    "field13" elemMatch {
        "field14" eq "value14"
    }
    "field15".isNull()
    "field16".notNull()
    "field17".isTrue()
    "field18".isFalse()
    and {
        "field3" eq "value3"
        "field4" eq "value4"
    }
    or {
        "field3" eq "value3"
        "field4" eq "value4"
    }
    "field19".today()
    "field20".tomorrow()
    "field21".thisWeek()
    "field22".nextWeek()
    "field23".lastWeek()
    "field24".thisMonth()
    "field25".lastMonth()
    "field26".recentDays(1)
    raw("1=1")
    "state" nested {
        "field27" eq "value27"
        "field28" eq "value28"
        "child" nested {
            "field29" eq "value29"
        }
        nested("")
        "field30" eq "value30"
    }
}
```

### SortDsl

```kotlin
sort {
    "field1".asc()
    "field2".desc()
}
```

### PaginationDsl

```kotlin
pagination {
    index(1)
    size(1)
}
```

### ProjectionDsl

```kotlin
projection {
    include("field1")
    exclude("field2")
}
```

### ListQueryDsl

```kotlin
listQuery {
    limit(1)
    sort {
        "field1".asc()
    }
    condition {
        "field1" eq "value1"
        "field2" eq "value2"
        and {
            "field3" eq "value3"
        }
        or {
            "field4" eq "value4"
        }
    }
}
```

### PagedQueryDsl

```kotlin
pagedQuery {
    pagination {
        index(1)
        size(10)
    }
    sort {
        "field1".asc()
    }
    condition {
        "field1" eq "value1"
        "field2" ne "value2"
        "filed3" gt 1
        "field4" lt 1
        "field5" gte 1
        "field6" lte 1
        "field7" contains "value7"
        "field8" isIn listOf("value8")
        "field9" notIn listOf("value9")
        "field10" between (1 to 2)
        "field11" all listOf("value11")
        "field12" startsWith "value12"
        "field13" elemMatch {
            "field14" eq "value14"
        }
        "field15".isNull()
        "field16".notNull()
        and {
            "field3" eq "value3"
            "field4" eq "value4"
        }
        or {
            "field3" eq "value3"
            "field4" eq "value4"
        }
    }
}
```

## Execute Query

```kotlin
listQuery {
    limit(1)
    sort {
        "field1".asc()
    }
    condition {
        "field1" eq "value1"
        and {
            "field3" eq "value3"
        }
        or {
            "field4" eq "value4"
        }
    }
}.query(queryService)
```

## Execute Paged Query

```kotlin
pagedQuery {
    pagination {
        index(1)
        size(10)
    }
    sort {
        "field1".asc()
    }
    condition {
        and {
            "field3" eq "value3"
            "field4" startsWith "value4"
        }
        or {
            "field3" eq "value3"
            "field4" startsWith "value4"
        }
    }
}.query(queryService)
```

## Rewrite Query

```kotlin
@Component
@Order(ORDER_FIRST)
@FilterType(SnapshotQueryHandler::class)
class DataFilterSnapshotQueryFilter : SnapshotQueryFilter {

    override fun filter(
        context: QueryContext<*, *>,
        next: FilterChain<QueryContext<*, *>>,
    ): Mono<Void> {

        return Mono.deferContextual {
            /**
             * Rewrite query, append warehouse ID to query conditions.
             */
            context.asRewritableQuery().rewriteQuery { query ->
                val warehouseIdCondition = condition {
                    nestedState()
                    WarehouseIdCapable::warehouseId.name eq warehouseId
                }
                query.appendCondition(warehouseIdCondition)
            }
            next.filter(context)
        }
    }
}
```

## OpenAPI

**Wow** not only automatically generates _OpenAPI_ endpoints for commands (`Command`), but also provides query (`Query`) _OpenAPI_ endpoints.
This means developers usually only need to focus on writing domain models to complete service development, without worrying about implementing query logic, greatly improving development efficiency.

The examples below use the `sales-order` aggregate for `tenant-1` to demonstrate five query endpoints.

![Query Service](/images/query/open-api-query.png)

### Paged Query

::: code-group

```shell [OpenAPI]
  curl -X 'POST' \
  'http://localhost:8080/tenant/tenant-1/sales-order/snapshot/paged' \
  -H 'accept: application/json' \
  -H 'Content-Type: application/json' \
  -H 'Wow-Space-Id: space-1' \
  -d '{
  "sort": [
    {
      "field": "_id",
      "direction": "DESC"
    }
  ],
  "pagination": {
    "index": 1,
    "size": 10
  },
  "condition": {
    "field": "state.status",
    "operator": "EQ",
    "value": "CREATED",
    "children": []
  }
}'
```

```json [Response (abridged)]
{
  "total": 1,
  "list": [
    {
      "aggregateId": "order-1",
      "tenantId": "tenant-1",
      "version": 3,
      "state": {
        "id": "order-1",
        "status": "CREATED"
      }
    }
  ]
}
```

```typescript [Typescript]
import { eq } from "@ahoo-wang/fetcher-wow";

eq("state.status", "CREATED")
```

:::

### Query

::: code-group

```shell [OpenAPI]
  curl -X 'POST' \
  'http://localhost:8080/tenant/tenant-1/sales-order/snapshot/list' \
  -H 'accept: application/json' \
  -H 'Content-Type: application/json' \
  -H 'Wow-Space-Id: space-1' \
  -d '{
  "sort": [
    {
      "field": "_id",
      "direction": "DESC"
    }
  ],
  "limit": 1,
  "condition": {
    "field": "state.status",
    "operator": "EQ",
    "value": "CREATED",
    "children": []
  }
}'
```

```json [Response (abridged)]
[
  {
    "aggregateId": "order-1",
    "tenantId": "tenant-1",
    "version": 3,
    "state": {
      "id": "order-1",
      "status": "CREATED"
    }
  }
]
```

:::

### Count

::: code-group

```shell [OpenAPI]
  curl -X 'POST' \
  'http://localhost:8080/tenant/tenant-1/sales-order/snapshot/count' \
  -H 'accept: application/json' \
  -H 'Content-Type: application/json' \
  -H 'Wow-Space-Id: space-1' \
  -d '{
    "field": "state.status",
    "operator": "EQ",
    "value": "CREATED",
    "children": []
  }'
```

```json [Response]
1
```

:::

### Get Single Model

::: code-group

```shell [OpenAPI]
  curl -X 'POST' \
  'http://localhost:8080/tenant/tenant-1/sales-order/snapshot/single' \
  -H 'accept: application/json' \
  -H 'Content-Type: application/json' \
  -H 'Wow-Space-Id: space-1' \
  -d '{
  "sort": [],
  "condition": {
    "field": "_id",
    "operator": "EQ",
    "value": "order-1",
    "children": []
  }
}'
```

```json [Response (abridged)]
{
  "aggregateId": "order-1",
  "tenantId": "tenant-1",
  "version": 3,
  "state": {
    "id": "order-1",
    "status": "CREATED"
  }
}
```

:::



## Snapshot Elements Aggregation

Snapshot aggregation exposes only tabular semantics that MongoDB and Elasticsearch can execute exactly. The HTTP endpoint is
`POST {aggregate-path}/snapshot/aggregation` and supports both `application/json` and
`text/event-stream`. Every group key and metric uses an explicit alias.

### Kotlin DSL

```kotlin
aggregationQuery {
    condition { "state.status" eq "CREATED" }
    expand("state.items") {
        condition { "quantity" gt 0 }
        groupBy("productId", "productId")
        sum("totalPrice", "totalAmount")
        count("lineCount")
        sort { "totalAmount".desc() }
        limit(100)
    }
}
```

Fields inside an `expand` block are relative; the resulting `AggregationQuery` normalizes them to
absolute paths. `groupBy`, metrics, sort, and limit must be declared in the innermost scope, and
each scope has at most one child `expand`.

### HTTP JSON

When constructing the Kotlin model directly or sending JSON, all fields in Elements, conditions,
groups, and metrics must be absolute. `type` is the Jackson discriminator for Group, Metric, and
Expression.
Generated OpenAPI publishes separate Elements, Terms, Numeric, and Temporal field enums so clients
cannot select a field type that runtime validation would always reject.
The following example contains Elements and metric ordering, so set
`wow.webflux.query.allow-expensive-operators=true` before calling it; the default configuration
rejects this request.

::: code-group

```shell [Request]
curl -X POST \
  'http://localhost:8080/tenant/tenant-1/sales-order/snapshot/aggregation' \
  -H 'Accept: application/json' \
  -H 'Content-Type: application/json' \
  -H 'Wow-Space-Id: space-1' \
  -d '{
    "condition": {
      "field": "state.status",
      "operator": "EQ",
      "value": "CREATED",
      "children": []
    },
    "elements": [
      {
        "path": "state.items",
        "condition": {
          "field": "state.items.quantity",
          "operator": "GT",
          "value": 0,
          "children": []
        }
      }
    ],
    "groupBy": [
      {
        "type": "TERMS",
        "field": "state.items.productId",
        "alias": "productId"
      }
    ],
    "metrics": [
      {
        "type": "NUMERIC",
        "function": "SUM",
        "expression": {
          "type": "FIELD",
          "field": "state.items.totalPrice"
        },
        "alias": "totalAmount"
      },
      {
        "type": "COUNT",
        "alias": "lineCount"
      }
    ],
    "sort": [
      {
        "field": "totalAmount",
        "direction": "DESC"
      }
    ],
    "limit": 100
  }'
```

```json [Response]
[
  {
    "productId": "product-1001",
    "totalAmount": 128.0,
    "lineCount": 4
  },
  {
    "productId": "product-1002",
    "totalAmount": 96.5,
    "lineCount": 2
  }
]
```

:::

Set `Accept` to `text/event-stream` to stream one result row at a time. Query semantics are
identical to the JSON response.

### Sources and Field Scope

- `elements=[]` aggregates root snapshots. Otherwise, Elements declare one strict parent-child object-collection chain from outermost to innermost.
- Elements accept only object collections or object arrays. Maps, scalar collections, duplicate paths, skipped parent collections, and sibling Cartesian products are rejected.
- `groupBy`, metric, and expression fields must belong to the innermost source. They cannot implicitly access a parent, sibling, or unexpanded child collection.
- Each `AggregationElement.condition` may reference only scalar fields or non-collection object paths in that element and must not use `ELEM_MATCH`.
- A root `ELEM_MATCH` filters snapshots containing a match; it does not filter rows produced by expansion. Put row filters in the corresponding Element condition.
- Missing, `null`, and empty collections produce no expanded rows. A row with any missing or `null` group field produces no bucket.

### Groups and Metrics

| Type | Input constraint | Result |
|---|---|---|
| `TERMS` | String, enum, UUID, Boolean, or numeric scalar; temporal fields are rejected | Integral keys normalize to `Long`; floating-point/Decimal keys normalize to `Double` |
| `HISTOGRAM` | Numeric scalar; `interval` must be finite and greater than 0; no offset in the first version | `Double` bucket key |
| `DATE_HISTOGRAM` | Supported temporal/`Date` field; unit is `YEAR`, `QUARTER`, `MONTH`, `WEEK`, `DAY`, `HOUR`, `MINUTE`, or `SECOND` | Epoch-millisecond `Long`; `WEEK` starts on Monday |
| `COUNT` | No field | Counts snapshots at the root or innermost expanded rows under Elements; returns `Long` |
| `NUMERIC` | `SUM`, `AVG`, `MIN`, or `MAX` with a numeric `FIELD` expression | `Double?`; missing values are ignored; an empty set yields `SUM=0.0` and `null` for the others |

`DateHistogram.timeZone` defaults to `UTC` and accepts only an IANA ID such as
`Asia/Shanghai` or an `±HH:MM` offset. Non-contract forms such as `Z` and `UTC+08:00`
are rejected. Any non-finite Numeric metric result fails the whole query.

### Results, Ordering, and Empty Sets

- Without `groupBy`, the query always returns one row, rejects sort, and validates limit even though limit cannot change the single result.
- A grouped query with no bucket returns an empty stream.
- The default order is ascending by group declaration order. Explicit sort appends every omitted group alias ascending as a stable tie-breaker.
- Sort fields must be unique and reference output aliases only. `null` sorts first ascending and last descending.
- Group-alias-only ordering can stop at limit. Any metric-alias ordering traverses every bucket and computes exact Top-N.
- Aliases are globally unique across groups and metrics. They must not be blank, contain `.`/NUL, start with `$` or `__wow_`, or equal `_id`.

### Limits and HTTP Guards

| Item | Public model | HTTP default |
|---|---:|---:|
| Elements depth | 5 | 3 |
| Element/group/expression field-path segments | 10 | 10 |
| groupBy entries | 32 | 32 |
| metrics entries | 1..64 | 1..32 |
| limit | Default 100; maximum 10,000 | Grouped queries are also capped by `max-list-size=1000` |
| Root plus all Element conditions | — | At most `max-condition-nodes=64` nodes in total |

Setting `max-aggregation-elements`, `max-aggregation-metrics`, or `max-list-size` to `0`
disables that HTTP cap only; public hard limits still apply. The following requests also require
`allow-expensive-operators=true`:

- any Elements expansion;
- a user-supplied match-all root condition;
- sort referencing any metric alias.

The HTTP guard counts user conditions before tenant/owner/space and ABAC conditions are injected.
Trusted conditions do not consume the user budget and do not turn a user match-all request into a
cheap query. Aggregation fails closed before backend access when a Snapshot masker is configured.
The HTTP layer does not maintain a duplicate field allowlist; the aggregation metadata Validator is
the single authority for collection chains, field ownership, and portable types.
Groups and metrics have no script entry point. HTTP `RAW` conditions still follow the general
`allow-raw` switch and are disabled by default.

### Backend Failures and Performance Boundary

- Elasticsearch requires every Elements path to be `nested` and every `DateHistogram` field to be `date`/`date_nanos`. Plain `object` mappings and epoch `long` fields are rejected.
- MongoDB uses successive `$unwind` stages and forces `simple` collation for string grouping and ordering.
- Timeout, shard failure, missing response structure, conversion failure, or a non-finite metric result fails the whole query; partial results are never returned.

The current single-thread engineering baseline uses 10,000 snapshots with 100 leaf elements per
snapshot. Elements group-key ordering is approximately `393–1,639 ms/op` on MongoDB and
`1–8 ms/op` on Elasticsearch. Exact metric Top-N is approximately `1.61–1.84 s/op` on MongoDB and
`1.79 s/op` on Elasticsearch. These are point-estimate ranges from one JMH run (1 fork and 3
measurements, with high variance in some scenarios). They identify expensive operators; they are
not a production SLA, regression threshold, or cross-backend ranking. See the
[full benchmark report](https://github.com/Ahoo-Wang/Wow/blob/main/wow-benchmarks/results/reports/snapshot-elements.md).

## Query Service Registrar

`SnapshotQueryServiceRegistrar` is used to automatically register all local aggregate root query services into the `Spring` container.
Developers can obtain the corresponding `SnapshotQueryService` from the `BeanFactory` using the specified `Bean Name`.

> `Bean Name` naming convention: `Aggregate Root Name + ".SnapshotQueryService"`.

Usage examples:

::: code-group

```kotlin [Constructor Injection]
class OrderService(
    private val queryService: SnapshotQueryService<OrderState>
) {
    fun getById(id: String): Mono<OrderState> {
        return singleQuery {
            condition {
                id(id)
            }
        }.query(queryService).toState().throwNotFoundIfEmpty()
    }
}
```

```kotlin [Field Injection]
@Autowired
private lateinit var queryService: SnapshotQueryService<OrderState>
```

```kotlin [Manual Retrieval by Bean Name]
val queryService = applicationContext.getBean("example.order.SnapshotQueryService") as SnapshotQueryService<OrderState>
```

:::
