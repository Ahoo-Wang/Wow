# Wow Query DSL Reference

All DSL functions are in package `me.ahoo.wow.query.dsl`.

## Query Functions

### singleQuery

Query a single result:

```kotlin
singleQuery {
    condition {
        "status" eq "active"
    }
    projection {
        include("id", "name", "email")
    }
    sort {
        "createdAt".desc()
    }
}
```

### listQuery

Query a list of results. Has a `limit` method:

```kotlin
listQuery {
    condition {
        "age" gt 18
    }
    sort {
        "name".asc()
    }
    limit(20)
}
```

### pagedQuery

Query with pagination:

```kotlin
pagedQuery {
    condition {
        tenantId(tenantId)
        "status" eq "ACTIVE"
    }
    pagination {
        index(1)
        size(20)
    }
    sort {
        "createdAt".desc()
    }
    projection {
        include("id", "status", "totalAmount")
    }
}
```

### countQuery

Count documents matching a condition:

```kotlin
condition {
    "status" eq "active"
}.count(queryService)
```

## Condition DSL

### Top-Level Functions

| Operator | Description | Example |
|----------|-------------|---------|
| `id` | By document ID | `id("order-123")` |
| `ids` | By multiple IDs | `ids("id1", "id2")` |
| `aggregateId` | By aggregate ID | `aggregateId("cart-456")` |
| `aggregateIds` | By multiple aggregate IDs | `aggregateIds("id1", "id2")` |
| `tenantId` | By tenant ID | `tenantId("tenant-1")` |
| `ownerId` | By owner ID | `ownerId("user-1")` |
| `spaceId` | By space ID | `spaceId("space-1")` |
| `deleted` | By deletion state | `deleted(DeletionState.ALL)` |
| `all` | Match all documents | `all()` |
| `raw` | Raw query value | `raw("1=1")` |

### Field Comparison Operators

| Operator | Description | Example |
|----------|-------------|---------|
| `eq` | Equals | `"status" eq "ACTIVE"` |
| `ne` | Not equals | `"status" ne "DELETED"` |
| `gt` | Greater than | `"age" gt 18` |
| `lt` | Less than | `"age" lt 65` |
| `gte` | Greater than or equal | `"age" gte 18` |
| `lte` | Less than or equal | `"age" lte 65` |

### String Operators

| Operator | Description | Example |
|----------|-------------|---------|
| `contains` | String contains | `"name" contains "John"` |
| `startsWith` | String prefix | `"email" startsWith "admin"` |
| `endsWith` | String suffix | `"email" endsWith "@company.com"` |
| `match` | String match | `"name" match "John"` |

### Collection Operators

| Operator | Description | Example |
|----------|-------------|---------|
| `isIn` | In list | `"status" isIn listOf("A", "B")` |
| `notIn` | Not in list | `"status" notIn listOf("C", "D")` |
| `between` | In range (Pair) | `"age" between (18 to 65)` |
| `between` / `to` | In range (infix) | `"age" between 18 to 65` |
| `all` | Array contains all | `"tags" all listOf("a", "b")` |
| `elemMatch` | Array element match | `"items" elemMatch { "price" gt 100 }` |

### Null / Boolean Operators

| Operator | Description | Example |
|----------|-------------|---------|
| `isNull()` | Is null | `"phone".isNull()` |
| `notNull()` | Is not null | `"email".notNull()` |
| `isTrue()` | Is true | `"active".isTrue()` |
| `isFalse()` | Is false | `"deleted".isFalse()` |
| `exists` | Field exists | `"optionalField".exists()` |
| `exists(false)` | Field does not exist | `"optionalField".exists(false)` |

### Date/Time Operators

| Operator | Description | Example |
|----------|-------------|---------|
| `today()` | Within today | `"createdAt".today()` |
| `beforeToday` | Before today at given time | `"createdAt" beforeToday time` |
| `tomorrow()` | Within tomorrow | `"scheduledAt".tomorrow()` |
| `thisWeek()` | Within this week | `"createdAt".thisWeek()` |
| `nextWeek()` | Within next week | `"startDate".nextWeek()` |
| `lastWeek()` | Within last week | `"createdAt".lastWeek()` |
| `thisMonth()` | Within this month | `"createdAt".thisMonth()` |
| `lastMonth()` | Within last month | `"createdAt".lastMonth()` |
| `recentDays(n)` | Within n recent days | `"updatedAt".recentDays(7)` |
| `earlierDays(n)` | More than n days ago | `"createdAt".earlierDays(30)` |

All date operators accept an optional `datePattern` parameter.

### Logical Combinations

```kotlin
condition {
    deleted(DeletionState.ALL)
    tenantId(tenantId)
    and {
        "status" eq "ACTIVE"
        "amount" gt 100
    }
    or {
        "type" eq "VIP"
        "type" eq "PREMIUM"
    }
    nor {
        "status" eq "SUSPENDED"
    }
    all()
}
```

### Nested Field Queries

```kotlin
condition {
    "state" nested {
        "status" eq "ACTIVE"
        "recoverable" ne "UNRECOVERABLE"
        "child" nested {
            "field" eq "value"
        }
    }
    nestedState()  // extension in package me.ahoo.wow.query.snapshot
}
```

### Using KCallable (Property References)

All field operators also accept `KCallable<*>` for type-safe field access:

```kotlin
condition {
    WarehouseIdCapable::warehouseId eq warehouseId
}
```

## Projection DSL

Select specific fields to return:

```kotlin
projection {
    include("id", "name", "email")
    exclude("password", "secret")
}
```

Or use directly inside query blocks:

```kotlin
singleQuery {
    projection {
        include("id", "status", "totalAmount")
    }
}
```

## Sort DSL

```kotlin
sort {
    "name".asc()
    "createdAt".desc()
}
```

Used inside query blocks:

```kotlin
listQuery {
    sort {
        "field1".asc()
        "field2".desc()
    }
}
```

## Pagination DSL

1-indexed pagination:

```kotlin
pagination {
    index(1)
    size(10)
}
```

Used directly or inside `pagedQuery`:

```kotlin
pagedQuery {
    pagination {
        index(1)
        size(20)
    }
}
```

## Executing Queries

Use the `.query(queryService)` extension function (from `me.ahoo.wow.query.snapshot`):

```kotlin
// Single result - returns Mono<MaterializedSnapshot<S>>
singleQuery {
    condition { id(id) }
}.query(queryService)

// List result - returns Flux<MaterializedSnapshot<S>>
listQuery {
    limit(10)
    sort { "field1".asc() }
    condition { "field1" eq "value1" }
}.query(queryService)

// Paged result - returns Mono<PagedList<MaterializedSnapshot<S>>>
pagedQuery {
    pagination { index(1); size(10) }
    sort { "field1".asc() }
    condition {
        and {
            "field3" eq "value3"
            "field4" startsWith "value4"
        }
    }
}.query(queryService)

// Count - returns Mono<Long>
condition {
    "field1" eq "value1"
}.count(queryService)

// Dynamic queries (runtime document type)
singleQuery { ... }.dynamicQuery(queryService)
listQuery { ... }.dynamicQuery(queryService)
pagedQuery { ... }.dynamicQuery(queryService)
```

## Query Rewriting

For security/tenant filtering:

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
            context.asRewritableQuery().rewriteQuery { query ->
                val warehouseCondition = condition {
                    nestedState()
                    WarehouseIdCapable::warehouseId eq warehouseId
                }
                query.appendCondition(warehouseCondition)
            }
            next.filter(context)
        }
    }
}
```

## Query Service Injection

```kotlin
class OrderService(
    private val queryService: SnapshotQueryService<OrderState>
) {
    fun getById(id: String): Mono<OrderState> {
        return singleQuery {
            condition { id(id) }
        }.query(queryService).toState().throwNotFoundIfEmpty()
    }
}
```

Bean name convention: `{AggregateName}.SnapshotQueryService` (e.g., `example.order.SnapshotQueryService`).

## OpenAPI Auto-generation

Wow automatically generates OpenAPI query endpoints:

- `POST /{aggregate}/snapshot/paged` — Paged query
- `POST /{aggregate}/snapshot/list` — List query
- `POST /{aggregate}/snapshot/count` — Count query
- `POST /{aggregate}/snapshot/single` — Single result
