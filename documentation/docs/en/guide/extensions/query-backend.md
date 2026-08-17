---
title: Custom Query Backend
description: Implement QueryBackendFactory, stable descriptors, Plan V1, readiness, and the shared TCK.
---

# Custom Query Backend

`QueryGateway` owns validation, schema, policy, budgets, and planning. A backend declares capabilities, checks readiness, and executes immutable Plan V1 values. Never parse legacy `Condition` again inside a backend or infer the logical schema from database mappings.

## Binding and execution

`QueryBackendFactory.bind(context)` is synchronous and performs no I/O:

```kotlin
val factory = QueryBackendFactory { context ->
    require(context.schema.target == context.target)
    DocumentationBackend
}
```

One invocation receives one immutable `target + schema + securedExpression` snapshot. Network, mapping, index, and template checks belong to `readiness()`. Execution consumes only `SingleQueryPlanV1`, `ListQueryPlanV1`, `PageQueryPlanV1`, or `CountQueryPlanV1`.

## Stable descriptor

The core of this example is compiled by `QueryBackendDocumentationTest`:

```kotlin
override val descriptor = QueryBackendDescriptor(
    backendId = "documentation",
    documentKinds = setOf(QueryDocumentKind.SNAPSHOT),
    planVersions = setOf(QueryPlanVersion.V1),
    portableOperators = PortableOperator.entries.toSet(),
    portableFeatures = QueryPortableFeature.entries.toSet(),
    stringComparisonModes = StringComparisonMode.entries.toSet(),
    capabilities = emptySet(),
    maxBudget = QueryBudgetLimit(maxResults = 1_000)
)
```

The descriptor is a contract, not a readiness result. Gateway rejects unsupported operators, document kinds, versions, string modes, and capabilities before execution. FullText and Native run only when descriptor, application configuration, Policy, and schema binding all permit them; there is no fallback.

## Legacy → canonical operator matrix

| Legacy | Canonical |
|---|---|
| `AND` | `LogicalExpression(AND)` |
| `OR` | `LogicalExpression(OR)` |
| `NOR` | `LogicalExpression(NOR)`, not unary `NOT` |
| `ID` | `EQ` on document identity |
| `IDS` | `IN` on document identity |
| `AGGREGATE_ID` | `aggregateId EQ` |
| `AGGREGATE_IDS` | `aggregateId IN` |
| `TENANT_ID` | `tenantId EQ` plus caller requested scope |
| `OWNER_ID` | `ownerId EQ` plus caller requested scope |
| `SPACE_ID` | `spaceId EQ` plus caller requested scope |
| `DELETED` | `RequestedQueryScope.deletion`; injected only by `SystemQueryPolicy` |
| `ALL` | `MatchAll` |
| `EQ` | `PredicateExpression(EQ)` |
| `NE` | `PredicateExpression(NE)` |
| `GT` | `PredicateExpression(GT)` |
| `LT` | `PredicateExpression(LT)` |
| `GTE` | `PredicateExpression(GTE)` |
| `LTE` | `PredicateExpression(LTE)` |
| `CONTAINS` | `PredicateExpression(CONTAINS)` + string mode |
| `IN` | `PredicateExpression(IN)` |
| `NOT_IN` | `PredicateExpression(NOT_IN)` |
| `BETWEEN` | `PredicateExpression(BETWEEN)` with exactly two values |
| `ALL_IN` | `PredicateExpression(ALL_IN)` |
| `STARTS_WITH` | `PredicateExpression(STARTS_WITH)` + string mode |
| `ENDS_WITH` | `PredicateExpression(ENDS_WITH)` + string mode |
| `MATCH` | `FullTextExpression(full-text)` |
| `ELEM_MATCH` | `ElementMatchExpression` |
| `NULL` | `PredicateExpression(NULL)` |
| `NOT_NULL` | `PredicateExpression(NOT_NULL)` |
| `TRUE` | `PredicateExpression(TRUE)` |
| `FALSE` | `PredicateExpression(FALSE)` |
| `EXISTS` | `PredicateExpression(EXISTS, BooleanValue)` |
| `RAW` | registered typed `NativeExpression`; backendId must match |
| `TODAY` | relative range at the invocation frozen instant |
| `BEFORE_TODAY` | range before the invocation day |
| `TOMORROW` | next-day range |
| `THIS_WEEK` | current week in the invocation zone |
| `NEXT_WEEK` | next week in the invocation zone |
| `LAST_WEEK` | previous week in the invocation zone |
| `THIS_MONTH` | current month in the invocation zone |
| `LAST_MONTH` | previous month in the invocation zone |
| `RECENT_DAYS` | N-day range ending at the frozen instant |
| `EARLIER_DAYS` | upper bound N days before the frozen instant |

Canonical `EMPTY_COLLECTION` supports nullable collections and ABAC but has no legacy operator. A backend must distinguish an empty list from missing and null.

## Readiness

`readiness()` returns only `Ready` or `NotReady` with `DEPENDENCY_UNAVAILABLE`, `INDEX_MISSING`, `MAPPING_INCOMPATIBLE`, or `CONFIGURATION_INVALID`. NotReady never executes a query. Keep diagnostics low-information: never expose mappings, index names, tenants, or expressions.

MongoDB FullText readiness requires the collection and exact text index. Ordinary portable queries must not pretend schema-less metadata proves document types. Elasticsearch validates system/application types, sort doc-values, analyzers, presence metadata, and nested semantics against one mapping/settings snapshot. Managed templates affect new indexes only; incompatible existing indexes remain NotReady and are never mutated automatically.

## TCK and resource lifecycle

Run the shared `SnapshotQueryBackendSpec` / `EventStreamQueryBackendSpec` through `QueryBackendTestKit`. Prove portable operators, projection, sort, exact page totals, capability gates, cold subscription, demand, partial errors, cancellation, MongoDB cursor cleanup, Elasticsearch PIT cleanup, and zero data commands after readiness/schema/policy failure.

Elasticsearch lists use PIT plus `search_after`; every response may rotate the PIT ID, so close the latest ID exactly once. MongoDB uses a bounded batch size; wire batch size is a conservative bound, not a direct JVM heap measurement.

## Mapping and migration

`QuerySchemaCustomizer` declares logical schema and explicit backend bindings. Backend compilers own physical paths. Do not make Mongo `_id`, Elasticsearch multi-fields, or the presence namespace a second business schema.

Validate real indexes/mappings before upgrade. Rebuild and backfill an incompatible Elasticsearch index, then switch an alias. Reconcile MongoDB indexes explicitly. The first phase exposes no public aggregation-analysis API and no runtime `LEGACY`/`SHADOW` fallback.

See [Queries](../query.md) and [Filter migration](../migration/query-filter-to-query-policy.md).
