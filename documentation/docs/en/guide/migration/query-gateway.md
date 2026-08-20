---
title: Snapshot Query Gateway Migration and Production Gates
description: Upgrade legacy query services, verify MongoDB/Elasticsearch semantics, and perform a reversible cutover.
outline: deep
---

# Snapshot Query Gateway Migration and Production Gates

This guide applies to services already using `SnapshotQueryService` and services moving snapshot queries from MongoDB
to Elasticsearch. Compilation, startup, or unit tests are not production admission. Admission requires evidence from
real indices, historical data, authorization, capacity, failure handling, and rollback.

## Compatibility boundary

| Scenario | Behavior |
|---|---|
| Legacy `SnapshotQueryService` / `Condition` | Runs through Gateway policies and result validation, but the original backend converter preserves historical backend semantics |
| `IListQuery.limit == 0` | Remains unlimited by default; a global `maxRecords` ends it with `INCOMPLETE_RESULT` when the budget is reached |
| Legacy projection mixes include and exclude | Returns `INVALID_QUERY` instead of silently dropping exclusions and widening the result |
| `DeletionState.DELETED` / `ALL` | The adapter grants the legacy API's deletion access; new Gateway callers need `query:snapshot:deletion` explicitly |
| A new Gateway storage route has no query backend | The application can start; a Gateway query on that route returns `BACKEND_NOT_READY` |
| Legacy `NoOpSnapshotQueryService` | Preserves the public empty/zero result contract; production checks must verify routes instead of treating an empty result as proof that no data exists |
| Recursive objects, maps, and dynamic state | The schema treats them as non-queryable opaque fields; provide an explicit `QuerySchemaProvider` to query their internals |

`LegacyConditionExpression` is only for the in-process compatibility adapter; it is not part of the remote Query JSON
protocol. New clients should use backend-neutral expressions such as `PredicateExpression`, `SearchExpression`, and
`ElementMatchExpression`.

## Phase 1: Freeze the baseline

Capture and retain this evidence before upgrading:

1. Current Wow version, application commit, aggregate inventory, bounded-context alias, and storage routes.
2. Event and snapshot counts, active/deleted counts, maximum version, and latest event time for every aggregate.
3. MongoDB collection indexes, or mapping, settings, document counts, and health for every real Elasticsearch index/alias.
4. Representative single/list/paged/count requests and their totals, ordering, projections, and result digests.
5. A restorable backup, restore-drill evidence, writer ownership, and the rollback window.

Snapshots are derived data and the event stream remains the source of truth, but that does not remove the backup gate.
A failed rebuild, incorrect route, or mixed old/new writers can still break current queries and rollback.

## Phase 2: Configure safety boundaries

### Authorization

Production callers must translate an authenticated identity into `QueryAuthority` in Reactor Context. Never trust
`tenantId`, `ownerId`, `spaceId`, or permissions supplied in the request body.

```kotlin
gateway.streamRecords(query)
    .contextWrite(
        QueryContexts.withAuthority(
            QueryAuthority(
                subjectId = principal.id,
                tenantId = principal.tenantId,
                ownerId = principal.ownerId,
                spaceIds = principal.spaceIds,
                permissions = principal.permissions
            )
        )
    )
```

Use at least two tenants and an unprivileged subject to prove that cross-tenant requests fail, scope only narrows,
deleted/ALL access is denied without permission, and field policy covers filters, sorts, and projections. If the
application adds `QueryPolicy` implementations, any `DENY` must remain decisive.

### Budgets

The unbounded default only preserves legacy `limit == 0`; it is not a recommended production configuration. Supply a
`QueryLimits` bean based on service capacity, and continue to bound HTTP body size, concurrency, and response size.

```kotlin
@Bean
fun queryLimits() = QueryLimits(
    maxPageSize = 200,
    maximumBudget = QueryBudget(Duration.ofSeconds(5), 10_000)
)
```

Migrate every caller that depends on unlimited streams first. If a budget terminates a stream after records were
emitted, the caller must discard the partial result and restart.

## Phase 3: Verify the backend

### MongoDB

- Create indexes for real equality/range/sort combinations and verify them with `explain`.
- Full-text requests must use exactly the field set of the collection's single text index.
- Object arrays use `$elemMatch`; include a two-level nested-array semantic regression test.
- Do not depend on very large `skip` values. Pages need a stable sort; bulk exports need a bounded stream.
- Test null, missing, empty arrays, and empty strings separately for presence operators.

### Elasticsearch

Resolve the real snapshot index/alias first; naming uses the bounded-context alias. Fetch mapping and settings for every
actual index, not only the template.

- Exact filter/sort fields must be indexed keyword, boolean, number, or date fields; sorting also needs doc values.
- A text field used for exact operations needs exactly one strict keyword subfield, or an explicit `exactSubfields`
  entry in a custom `ElasticsearchSnapshotQueryBackend`.
- Full-text fields must be indexed text; the Gateway currently accepts standard-analyzer semantics only.
- An `ElementMatchExpression` field must be mapped as `nested`; an ordinary object mapping returns `BACKEND_NOT_READY`.
- The new Gateway rejects presence queries that cannot reliably distinguish null from missing with the current mapping.
  Do not downgrade them to expressions such as `must_not exists` that can widen results.
- `from + size` is at most 10000 by default. Use bounded streams for larger sets; streams use PIT and `search_after`
  for a stable read view.

:::warning Existing indices
Updating the `wow.*.snapshot` template only affects indices created later. If an existing field type is wrong, create a
new index/alias with the correct mapping and rebuild or perform a controlled reindex. Elasticsearch cannot change an
existing text field into keyword in place.
:::

To customize PIT page size, keep-alive, result window, or exact subfields, provide your own backend bean. Auto-
configuration backs off instead of creating a second bean of the same backend type.

## Phase 4: Rebuild and reconcile

An in-place upgrade on the same backend does not require new presence metadata when existing legacy mappings already
support the legacy queries. Switching backends, correcting field types, or creating a new index requires rebuilding
historical snapshots.

1. Create the target collection/index and mapping in an isolated environment.
2. Establish one writer policy; do not let old and new stores become uncontrolled primary writers.
3. Use the generated batch snapshot-regeneration route, or an equivalently reviewed event replay, to rebuild from the EventStore.
4. Record each batch's `afterId`, successes, skips, failures, and duration. Failed batches must be repeatable.
5. Reconcile total, active/deleted, tenant, aggregateId, version, state digest, and time fields per aggregate.
6. Run representative legacy queries against old and new backends and compare sets, totals, ordering, projections, and errors.

An existing target index or a 2xx batch response is not reconciliation. `version_offset` can intentionally leave
snapshot queries stale. Use and verify `strategy: all` during and after migration when snapshots are the current-state
read model.

## Phase 5: Canary and rollback

Canary one aggregate or a small percentage of traffic before switching every route. The observation window must cover
peak traffic, PIT lifecycle, slow queries, timeouts, budget termination, and backend reconnects.

Define before cutover:

- whether the old backend remains synchronized or needs catch-up for canary writes;
- when to stop the new writer and how to drain in-flight `SNAPSHOT` stages;
- how to roll back the route, index alias, and application version independently;
- whether restoring the old backup remains valid after new writes;
- who may cut traffic, roll back, and delete old indices.

Do not delete the old index, collection, compatibility code, or rebuild checkpoints before the observation window ends.

## Errors and monitoring

Monitor `QueryException.code` and `stage`. At minimum, report latency, denials, timeouts, budget termination,
backend-not-ready responses, incomplete streams, and materialization failures by aggregate, backend, and operation.
Logs must not contain raw filters, RAW payloads, authorities, complete state, or backend credentials.

| Error | Action |
|---|---|
| `INVALID_QUERY` / `POLICY_DENIED` | Fix the request or permission; do not retry automatically |
| `UNSUPPORTED_QUERY` | Rewrite the query or select a backend with the required semantics |
| `BACKEND_NOT_READY` | Stop cutover and repair the route, index, mapping, or index before retesting |
| `DEADLINE_EXCEEDED` / `BUDGET_EXCEEDED` | Narrow the query or adjust a capacity-tested budget |
| `INCOMPLETE_RESULT` | Discard emitted records and restart from the beginning |
| `RESULT_INVALID` / `MATERIALIZATION_FAILED` | Treat as a schema/data compatibility incident and stop the canary |
| `BACKEND_FAILURE` | Apply bounded retries only after checking backend health and idempotency; partial count shards also use this error |

The Gateway does not define the application's SLO, alerts, or trace/span naming. Framework tests alone cannot grant
production admission without dashboards, alert drills, and an on-call runbook.

## Go / No-Go checklist

Every item needs current-environment evidence:

- [ ] Application commit, dependencies, and configuration are frozen; complete CI is green.
- [ ] Every route resolves to the intended backend; non-queryable routes are explicitly excluded.
- [ ] Custom authorization, scope, deletion permission, and field access have negative tests.
- [ ] `QueryLimits`, HTTP rate limits, and concurrency limits are backed by capacity tests.
- [ ] MongoDB indexes or Elasticsearch mapping/settings were audited on actual collections/indices.
- [ ] Historical snapshots were rebuilt and reconciled against the EventStore/old backend per aggregate.
- [ ] single/list/paged/count, null/missing, nested, full-text, sort, and projection semantics were compared.
- [ ] Peak load, backend timeout, shard failure, disconnect, PIT cancellation, and incomplete streams were exercised.
- [ ] Monitoring, alerts, on-call runbook, canary owner, and rollback owner are confirmed.
- [ ] Rollback was rehearsed; old data and routes remain available through the observation window.

If any item is missing, the verdict is `NO-GO` or `MISSING EVIDENCE`, not conditional approval.
