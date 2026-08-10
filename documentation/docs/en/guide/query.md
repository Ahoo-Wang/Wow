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

`PreAdmissionQueryFilter` is only a compatibility hook for rewriting query input. Results written in this phase are
discarded, and appended conditions remain user conditions. Do not use it as a tenant, ABAC, or authorization boundary;
security constraints must be emitted as mandatory conditions by the Query Gateway policy.

```kotlin
@Component
@Order(ORDER_FIRST)
@FilterType(SnapshotQueryHandler::class)
class DataFilterSnapshotQueryFilter : SnapshotQueryFilter, PreAdmissionQueryFilter {

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

The examples below query the `sales-order` aggregate for `tenant-1`. All four requests describe the same synthetic snapshot, so their conditions and response counts stay consistent.

::: warning Query endpoints deny anonymous access by default
The Query Gateway never treats a path, `Wow-Space-Id`, or another request header as trusted identity evidence. An
application must implement `QueryWebAuthorityResolver` and return `Mono<QueryAuthority>` from an authenticated
principal/security context. The default resolver is empty, so a query without authentication integration returns
`403 Query.ACCESS_DENIED.AUTHORITY_REQUIRED`. The curl examples below assume the application resolves the
`Authorization` credential to the corresponding tenant/owner/space grants.

```kotlin
fun interface QueryAuthorityService {
    fun resolveAuthenticated(request: ServerRequest): Mono<QueryAuthority>
}

@Bean
fun queryWebAuthorityResolver(authorityService: QueryAuthorityService): QueryWebAuthorityResolver =
    QueryWebAuthorityResolver { request ->
        authorityService.resolveAuthenticated(request.request)
    }
```

Tenant, owner, and space remain resource selectors. The resolver must compare them with authenticated authority and
deny a conflict instead of silently falling back to personal scope.
:::

Generated Snapshot/Event query, load, and Analytics endpoints declare one Query failure contract: `400` for an
invalid query, cursor, or unsupported capability; `403` for denied access; `408` for an expired deadline; `429` for
an exceeded budget; `502` for an incomplete result; `503` for an unavailable backend; `504` for a backend timeout;
and `500` for mapping or internal failures. Responses continue to use the `DefaultErrorInfo` JSON body and the
`Wow-Error-Code` header; Analytics/Cursor does not introduce a second error envelope.

![Query Service](../../public/images/query/open-api-query.png)

### Paged Query

::: code-group

```shell [OpenAPI]
  curl -X 'POST' \
  'http://localhost:8080/tenant/tenant-1/sales-order/snapshot/paged' \
  -H 'accept: application/json' \
  -H 'Authorization: Bearer <token>' \
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
  -H 'Authorization: Bearer <token>' \
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
  -H 'Authorization: Bearer <token>' \
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
  -H 'Authorization: Bearer <token>' \
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

### Analytics Query

Analytics uses separate `AnalyticsQuery` / `AnalyticsPage` contracts and does not add a method to the seven-method
`QueryService`. Every field is logical; `.keyword`, index names, Mongo/Elasticsearch native queries, and backend options
are not part of the public request. `Int64`, `Decimal`, and `Instant` results use typed canonical strings so JavaScript
and JSON number parsing cannot lose precision.

This release contains an approved upgrade of the public Query, Analytics, and Cursor contracts marked with
`@ExperimentalQueryGatewayApi` / `@ExperimentalQueryCursorApi`. Applications using those experimental APIs must be
recompiled and migrated to the current constructors and budget fields. The stable seven-method `QueryService` and the
seven `QueryType` values remain unchanged. OpenAPI now fixes the conditional Analytics shape with `oneOf`: `GLOBAL`
requires empty dimensions, `limit=1`, and no cursor; `BY` requires at least one dimension; `DOCUMENT_COUNT` has no field,
while every other metric requires one.

```shell
curl -X POST \
  'http://localhost:8080/tenant/tenant-1/sales-order/snapshot/analyze' \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{
    "condition": { "operator": "ALL" },
    "grouping": {
      "kind": "BY",
      "dimensions": [
        { "alias": "status", "field": "state.status", "missingPolicy": "EXCLUDE" }
      ]
    },
    "metrics": [
      { "alias": "count", "kind": "DOCUMENT_COUNT" },
      { "alias": "total", "kind": "SUM", "field": "state.total" }
    ],
    "window": { "limit": 100 },
    "numericPolicy": { "scale": 2 },
    "consistency": "EVENTUAL",
    "completeness": "EXACT"
  }'
```

```json
{
  "buckets": [
    {
      "keys": { "status": { "type": "TEXT", "value": "PAID" } },
      "metrics": {
        "count": { "type": "INT64", "value": "42" },
        "total": { "type": "DECIMAL", "value": "120.50" }
      }
    }
  ],
  "nextCursor": null,
  "consistency": "EVENTUAL",
  "completeness": "EXACT"
}
```

`grouping.kind=GLOBAL` requires `dimensions=[]`, `window.limit=1`, and no cursor. A `BY` query's `nextCursor` is an
opaque URL-safe token of at most 256 characters. A client may only copy it into the next request's `window.cursor`; it
must never parse, modify, or manufacture the token. The server validates the target, plan fingerprint, mapping
generation, authority/security binding, and backend before deleting the lease with CAS. Wrong authority or mapping
does not consume the cursor, and only one request may acquire a cursor page.

When the first page issues a cursor, the server also stores the complete `QueryExecutionBudget` ceiling in the signed
lease. A continuation may only preserve or tighten `maxReturnedRecords`, `maxScannedRecords`, `maxPageWindow`, candidate
and returned bucket limits, `maxCursorPages`, and `allowDiskUse`; it cannot remove or relax an initial bound. An attempted
relaxation returns `CURSOR_BUDGET_RELAXATION_NOT_ALLOWED` without consuming the lease.

Lease expiry is normalized to millisecond precision before HMAC signing and persistence so a Mongo BSON Date round trip
cannot diverge from the signed envelope. Backend continuation state is limited to `4_096` bytes by default, and the public
hard maximum for `QueryCursorLeaseConfiguration.maxBackendStateBytes` is `1 MiB`; runtime validation and the cursor codec
use the same configured value.

A multi-instance deployment must provide a shared `QueryCursorLeaseStore`. The MongoDB implementation uses bounded
slots, a unique lease id, revision CAS, and a grace-delayed TTL. It never creates the collection or indexes implicitly at
application startup; initialize them through an explicit, controlled operation:

```kotlin
val store = MongoQueryCursorLeaseStore(
    cursorDatabase,
    MongoQueryCursorLeaseStoreOptions(
        maxEntries = 65_536,
        retentionGrace = Duration.ofMinutes(5),
    ),
)

// Run once from a migration/operations entry point, never from a normal query request.
store.ensureIndexes().block()

@Bean
fun queryCursorLeaseConfiguration(
    signingKeys: QueryCursorSigningKeys, // Build from a managed Secret; never log or commit key material.
): QueryCursorLeaseConfiguration = QueryCursorLeaseConfiguration(
    store = store,
    signingKeys = signingKeys,
    maxBackendStateBytes = 4_096,
)
```

MongoDB TTL applies to `expiresAt + retentionGrace`: the framework reaper first attempts revision CAS and Backend-state
cleanup, while TTL remains the final safety net for abandoned leases. No scheduler is created by default. After configuring
the shared store, explicitly enable the Starter's single-owner, bounded serial reaper when desired:

```yaml
wow:
  query:
    cursor:
      reaper:
        enabled: true
        initial-delay: 30s
        interval: 1m
        batch-size: 100
        max-batches-per-run: 10
```

Enabling the reaper without a `QueryCursorLeaseConfiguration` fails application startup. Each run processes at most
`batch-size * max-batches-per-run` leases, never overlaps the previous run, and isolates a store/closer failure so the next
scheduled run remains active. Operations code may still invoke `QueryGatewayRuntime.reapExpiredQueryCursors(batchSize)`
explicitly when Starter scheduling is not used. Normal query requests never trigger cleanup or DDL. During HMAC rotation,
issue with the new `current` key and keep the old key in `previous` until `maxCursorTtl` has elapsed.

Mongo Analytics currently declares `EVENTUAL + EXACT`. Elasticsearch grouped Analytics supports `SNAPSHOT + EXACT` when
its mapping/readiness checks pass and a shared cursor store is configured. The PIT id is stored only as opaque server-side
lease state and never appears in the client token; terminal, error, cancellation, capacity rejection, and expired-reaper
paths all attempt to close the PIT. Without a lifecycle closer registered for the exact `QueryTarget + BackendId`, the
request is rejected before storage access.

For record queries, Mongo planned `PAGE` derives the page and exact total from one matched input plus an in-memory sentinel
and window accumulator. It neither rereads the collection nor packs the page into one BSON document; `SAME_INPUT` is not a
point-in-time snapshot claim. Elasticsearch planned direct `STREAM` accepts only `limit=1..10_000`; a larger limit is
rejected before Elasticsearch I/O, while `limit=0` unbounded streaming remains unsupported instead of silently becoming a
fixed result window.

Spring also registers a `<context>.<aggregate>.AnalyticsQueryService` bean. Direct process calls still require trusted
context, while HTTP calls reuse the query route's authenticated authority. If an aggregate has no matching Analytics
schema/backend readiness, the request is rejected before storage is accessed.

Enabling any `SHADOW` profile also requires a bounded `QueryShadowConfiguration`, a `QueryShadowObserver`, and a
`QueryRuntimeHealthObserver`; a missing observer fails runtime startup. A health observation contains only target,
operation, kind, and a stable reason code. It deliberately excludes authority, query values, and backend causes and must
feed bounded low-cardinality metrics/alerts instead of becoming a silent fallback.



## Query Service Registrar

`SnapshotQueryServiceRegistrar` is used to automatically register all local aggregate root query services into the `Spring` container.
Developers can obtain the corresponding `SnapshotQueryService` from the `BeanFactory` using the specified `Bean Name`.

The seven compatibility methods on an aggregate query bean have no explicit context parameter, so they are never
promoted to `System` authority automatically. New code should prefer `QueryGateway` with an explicit `QueryCall`. If a
migration still uses the aggregate bean, register an exact `QueryLegacyGrant` and select it at subscription time with
`withLegacyQueryCaller`. The caller marker can only select a pre-bound
`target + purpose + executionMode + resourceScope`; it cannot broaden the grant:

```kotlin
@Bean
fun queryLegacyContextResolver(): QueryLegacyContextResolver = QueryLegacyContextResolver(
    listOf(
        QueryLegacyGrant(
            callerId = "order-read-model",
            target = QueryTarget(
                MaterializedNamedAggregate("example", "order"),
                QueryDocumentKind.SNAPSHOT,
            ),
            purpose = QueryPurpose("order-read-model"),
            executionMode = QueryExecutionMode.LEGACY,
            resourceScope = QueryResourceScope(tenantId = "tenant-1"),
        ),
    ),
)
```

Without trusted context, the compatibility bean returns `QUERY_CALL_REQUIRED`. The emergency migration switch
`wow.query.gateway.legacy-wiring-rollback=true` is temporary: it bypasses admission, policy, and lifecycle protection,
is supported for one migration version only, and must never be an automatic fallback for authorization or query errors.

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
        }.query(queryService)
            .withLegacyQueryCaller("order-read-model")
            .toState()
            .throwNotFoundIfEmpty()
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
