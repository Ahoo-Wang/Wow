# Wow Query DSL Reference

## Query Functions

### singleQuery

Query a single result.

```kotlin
singleQuery {
    where { "status" eq "active" }
    projection { "id", "name", "email" }
    orderBy { "createdAt" desc }
    limit(10)
}.query(queryService)
```

### listQuery

Query a list of results.

```kotlin
listQuery {
    where { "age" gt 18 }
    orderBy { "name" asc }
    offset(0)
    limit(20)
}.query(queryService)
```

### pagedQuery

Query with pagination.

```kotlin
pagedQuery {
    where {
        tenantId(tenantId)
        "status" eq "ACTIVE"
    }
    page(1)
    pageSize(20)
    orderBy { "createdAt" desc }
    projection { "id", "status", "totalAmount" }
}.query(queryService)
```

## Condition Operators

### Field Queries

| Operator | Description | Example |
|----------|-------------|---------|
| `eq` / `ne` | Equals / Not equals | `"status" eq "ACTIVE"` |
| `gt` / `lt` | Greater / Less than | `"age" gt 18` |
| `gte` / `lte` | Greater/Less than or equal | `"age" gte 18` |
| `contains` | String contains | `"name" contains "John"` |
| `isIn` / `notIn` | In list / Not in list | `"status" isIn listOf("A", "B")` |
| `between` | In range (inclusive) | `"age" between 18 to 65` |
| `all` | Array contains all | `"tags" all listOf("a", "b")` |
| `startsWith` | String prefix | `"email" startsWith "admin"` |
| `endsWith` | String suffix | `"email" endsWith "@company.com"` |
| `elemMatch` | Array element matches | `"items" elemMatch { "price" gt 100 }` |
| `isNull()` | Is null | `"phone".isNull()` |
| `notNull()` | Is not null | `"email".notNull()` |
| `isTrue()` | Is true | `"active".isTrue()` |
| `isFalse()` | Is false | `"deleted".isFalse()` |
| `exists` | Field exists | `"optionalField".exists()` |
| `raw()` | Raw query | `raw("1=1")` |

### ID Queries

| Operator | Description | Example |
|----------|-------------|---------|
| `id()` | By document ID | `id("order-123")` |
| `ids()` | By multiple IDs | `ids("id1", "id2", "id3")` |
| `aggregateId()` | By aggregate ID | `aggregateId("cart-456")` |
| `aggregateIds()` | By multiple aggregate IDs | `aggregateIds("id1", "id2")` |
| `tenantId()` | By tenant ID | `tenantId("tenant-1")` |
| `ownerId()` | By owner ID | `ownerId("user-1")` |
| `deleted()` | By deletion state | `deleted(DeletionState.ALL)` |

### Date/Time Operators

| Operator | Description | Example |
|----------|-------------|---------|
| `today()` | Within today | `"createdAt".today()` |
| `tomorrow()` | Within tomorrow | `"scheduledAt".tomorrow()` |
| `beforeToday()` | Before today | `"createdAt".beforeToday()` |
| `thisWeek()` | Within this week | `"createdAt".thisWeek()` |
| `nextWeek()` | Within next week | `"startDate".nextWeek()` |
| `lastWeek()` | Within last week | `"createdAt".lastWeek()` |
| `thisMonth()` | Within this month | `"createdAt".thisMonth()` |
| `lastMonth()` | Within last month | `"createdAt".lastMonth()` |
| `recentDays(n)` | Within n recent days | `"updatedAt".recentDays(7)` |
| `earlierDays(n)` | More than n days ago | `"createdAt".earlierDays(30)` |

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
    all()  // Match all documents
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
    nestedState()  // Query on nested state field
}
```

## Projection

Select specific fields to return.

```kotlin
projection {
    include("id", "name", "email")
    exclude("password", "secret")
}
```

Or inline:

```kotlin
projection { "id", "status", "totalAmount" }
```

## Sorting

```kotlin
sort {
    "createdAt".asc()
    "name".desc()
}

orderBy {  // Shorthand
    "createdAt".desc()
}
```

## Pagination

### Page-based (1-indexed)

```kotlin
pagedQuery {
    page(1)      // 1-indexed page number
    pageSize(20) // items per page
}

// Or using pagination {}
pagination {
    index(1)
    size(10)
}
```

### Offset-based

```kotlin
listQuery {
    offset(0)
    limit(20)
}
```

## Query Rewriting

For security/tenant filtering, you can rewrite queries:

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
                val condition = condition {
                    nestedState()
                    WarehouseIdCapable::warehouseId.name eq warehouseId
                }
                query.appendCondition(condition)
            }
            next.filter(context)
        }
    }
}
```

## OpenAPI Auto-generation

Wow automatically generates OpenAPI endpoints for queries:

- `POST /{aggregate}/snapshot/paged` - Paged query
- `POST /{aggregate}/snapshot/list` - List query
- `POST /{aggregate}/snapshot/count` - Count query
- `POST /{aggregate}/snapshot/single` - Single result

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

Bean name convention: `AggregateName.SnapshotQueryService` (e.g., `example.order.SnapshotQueryService`)
