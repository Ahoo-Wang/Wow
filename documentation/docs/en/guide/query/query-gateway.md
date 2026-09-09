---
title: Query Gateway
description: Fixed aggregate-bound preparation, scope, authorization, validation, and response stages.
---

# Query Gateway

`SnapshotQueryGateway<S>` and `EventStreamQueryGateway` are application query entries. Spring resolves a `QueryBackendBinding` during aggregate Gateway registration and retains its Backend and Provider instead of routing each request again.

## Fixed execution order

Each subscription independently:

1. Obtains one Schema from the Provider.
2. Runs ordered `QueryFilter.prepare` stages; each emits one prepared logical Query.
3. Appends the request scope from Reactor Context.
4. Appends configured `QueryPolicy` filters through the shared policy stage for both Snapshot and EventStream queries, after ordinary preparation.
5. Only the Gateway applies model defaults: Snapshot adds `DELETION = ACTIVE` unless explicitly overridden; EventStream adds no deletion predicate. It also appends the model's unique cursor sort field.
6. Validates the final public Query, then calls `backend.operation(query, schema)`.
7. Masks returned query nodes with the captured Schema, then optionally materializes typed results.
8. Notifies `QueryObserver` of completion, error, or cancellation.

```mermaid
flowchart LR
    Provider --> Prepare["QueryFilter.prepare"]
    Prepare --> Scope["Request scope"]
    Scope --> Policy["QueryPolicy.evaluate"]
    Policy --> Validate["Defaults + public validation"]
    Validate --> Backend["Backend query + schema"]
    Backend --> Mask["Mask"]
    Mask --> Result["ObjectNode / typed result"]
    Result --> Observer["Terminal observer"]
```

Preparation, validation, Backend compilation, and Mask share that captured Schema. Schema failure or empty prepare completion fails before Backend execution. Retry/repeat starts a fresh subscription and obtains its Schema again. Count returns Long without result masking. Aggregation rejects protected grouping/metric/expression inputs before execution rather than attempting to conceal them in returned aggregate rows.

## Request preparation extension

`QueryContext<Q>` contains only `query`, `namedAggregate`, and `schema`. A Filter has no continuation, result object, or result-processing authority. It prepares a request and cannot wrap or re-execute the Backend:

```kotlin
interface QueryFilter {
    fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Mono<Q>
}
```

`SnapshotQueryFilter` and `EventStreamQueryFilter` restrict the applicable model; a plain `QueryFilter` can apply to both. `@Order` controls preparation order. Prepared requests use logical fields and still undergo final Schema validation.

## Preparation and mandatory constraints

| Extension | Result | Composition |
| --- | --- | --- |
| `QueryFilter.prepare` | A prepared Query | Later prepare steps may replace its query/filter |
| `QueryPolicy.evaluate` | An additional logical FilterExpression or an error | Gateway applies it with AND after all prepare steps |

Both can construct filter expressions. Use QueryFilter when replacement is allowed; use QueryPolicy when a condition must survive all preparation. For example, mandatory `state.visible = true`, a data lifecycle restriction, or an authorization condition belongs in a Policy. QueryPolicy remains generic in the rules it represents; its authority is limited to adding constraints or rejecting a query. Built-in model defaults, Schema validation, backend execution and result processing retain their existing owners.

## Request scope and policies

A WebFlux Handler uses `QueryRequestScope` to obtain tenant/owner/space scope, places it in Reactor Context, and invokes the Gateway. `HttpQueryGuard` applies HTTP cost and response limits outside the Gateway Filter stages.

A JVM caller can supply trusted scope explicitly:

```kotlin
queryGateway.dynamicList(query)
    .contextWrite { context ->
        context.withQueryScope(TenantIdFilter("tenant-1"))
    }
```

`withQueryScope` combines an existing scope. Authentication remains the application's responsibility; unverified request fields are not identity. Both Snapshot and EventStream Spring registrars inject `QueryPolicy` beans, and the shared Gateway stage evaluates them. A policy uses `QueryContext` to determine applicability and returns `MatchAllFilter` when it does not apply. `AbacQueryPolicy` resolves principal tags and generates tag conditions only for Snapshot; for other models it returns `MatchAllFilter` without reading tags. Other policies implement `QueryPolicy.evaluate` for rules such as data lifecycle or business query constraints without principal-tag lookup.

A policy returns an additional logical filter or an error. The gateway combines filters with AND at the fixed policy stage before model defaults and validation. It cannot replace the query, execute the backend, or transform results. An empty publisher is a protocol error, not a way to signal that a policy does not apply. Policy failures terminate the query before backend execution. See [Data Access Control](../data-access.md).

## Results and observation

The Backend returns independently owned ObjectNodes for each subscription. Framework masking runs before typed materialization, with no general result Filter stage. `QueryObserver` exposes terminal callbacks only and cannot replace a result or error. Ordinary observer failures are logged; they cannot retry the query or invoke the Backend again. The default implementation is `QueryLogObserver`.

Direct Backend access bypasses these stages; see [Query Backend](./query-backend.md), [Field Masking](./masking.md), and [Query Model Schema](./query-model-schema.md).
