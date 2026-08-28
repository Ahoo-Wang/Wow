---
title: Migrating from Traditional Architecture
description: Adopt Wow one bounded context at a time with a single writer, replayable import, reconciliation, and rollback.
---

# Migrating from Traditional Architecture

This page is for a system that does not yet own Wow event history. The safe path is incremental: define one bounded
context, build the model, import and shadow it while the legacy system remains authoritative, move reads, then move
writes. At every moment exactly one system owns business writes.

Existing Wow v6 systems should use [Migrate Wow v6 to v8](./v6-to-v8.md) instead.

## Migration Overview

| Stage | Authoritative writer | Deliverable | Exit evidence |
|---|---|---|---|
| 0. Boundary | Legacy | Aggregate/ID/tenant/invariant map and accepted scenarios | Domain owners approve scope and language |
| 1. Wow model | Legacy | Commands, aggregate behavior, domain events, state sourcing, tests | Success/rejection/idempotency cases pass |
| 2. Import and catch-up | Legacy | Replayable import commands, outbox/CDC feed, source watermark | Lag and per-aggregate reconciliation meet thresholds |
| 3. Read cutover | Legacy | Wow-backed query/read model for a controlled cohort | Business results and latency reconcile; rollback tested |
| 4. Write cutover | Wow | Admission switch, drained legacy writer, Wow command path | One-writer proof, new writes reconciled, rollback procedure live |
| 5. Closure | Wow | Removed legacy writes/synchronizer after observation window | No unresolved drift or rollback dependency |

Do not start by copying tables into an event store. Events record accepted domain decisions. Historical conversion must
go through an explicit, reviewable contract.

## 1. Migrate the Boundary Before the Tables

Choose a low-coupling business capability and write down:

- the stable aggregate identity, tenant/owner/space mapping, and aggregate boundary;
- commands accepted at the boundary and who is authorized to send them;
- invariants that reject a command;
- domain events emitted after acceptance;
- state sourcing rules and deletion semantics;
- external calls that belong in projections/Sagas rather than aggregate state transitions.

Follow the repository example shape: API commands/events live separately from the aggregate implementation, and
`AggregateSpec` verifies behavior without infrastructure.

```kotlin
class OrderSpec : AggregateSpec<Order, OrderState>({
    on {
        whenCommand(CreateOrder(id = "order-1", ...)) {
            expectNoError()
            expectEventType(OrderCreated::class)
            expectState { id.assert().isEqualTo("order-1") }
        }
    }
})
```

Also cover duplicate request IDs, invalid transitions, deleted aggregates, tenant/owner propagation, and serialization
of the exact committed event shape. A compiled aggregate is source evidence; it does not prove historical rows map to
valid domain decisions.

Create an anti-corruption adapter from the legacy contract to Wow commands. Keep legacy column names and sentinel
values out of the new public domain API unless they are real business concepts.

## 2. Import and Catch Up with One Writer

Use a durable, restartable migration manifest. At minimum record source partition/key, source version or update
watermark, target aggregate ID, deterministic request ID, status, source checksum, target version/checksum, error, and
last verified batch.

Recommended flow:

1. take a source snapshot and record its high watermark while legacy writes continue;
2. convert each source entity through an explicit `Import...`/`Synchronize...` command contract;
3. use deterministic request IDs so replaying a completed batch is idempotent;
4. persist manifest progress only after target acknowledgement and verification;
5. consume outbox/CDC changes after the snapshot watermark in source order;
6. repeat reconciliation until lag remains inside the approved threshold.

The source row is a locator; the reviewed mapping decides which Wow command/event/state it represents. Preserve the
source payload/checksum for audit instead of hiding conversion choices in an ad hoc script.

Never dual-write the legacy database and Wow from one request as two independent commits. Use an outbox/CDC source or
another durable handoff. If the synchronizer fails, legacy remains the only writer and the target catches up from the
last manifest/watermark.

Reconciliation should compare, per aggregate and globally:

- source population versus imported IDs, including missing/extra IDs;
- latest source revision/update time versus target aggregate version;
- money/quantity/status/deletion and other business invariants;
- duplicate deterministic request IDs and failed/dead-letter records;
- event and snapshot counts, representative full replay, and read-model rows.

## 3. Reconcile, Then Move Reads and Writes Separately

Cut reads before writes when possible:

1. shadow-query legacy and Wow read models for the same requests;
2. classify differences as mapping defect, expected semantic change, lag, or data corruption;
3. move a controlled read cohort while writes remain legacy-owned;
4. observe error rate, latency, lag, business results, metrics, and traces;
5. roll reads back without changing the writer if a gate fails.

Write cutover is a separate maintenance action:

1. close legacy write admission and drain in-flight transactions/outbox records;
2. record the final source watermark and reach zero approved reconciliation drift;
3. enable the Wow command boundary for one controlled cohort/instance;
4. verify command result, committed event, reconstructed state, projections/Sagas, and external side effects;
5. expand traffic only after the observation gate passes.

Before the first Wow production write, rollback can return reads to legacy and discard/rebuild the shadow target. After
the first write, rollback requires stopping Wow, transferring or reversing those writes into the legacy authority, and
reconciling both sides. Restoring the old application alone would lose accepted decisions.

Local tests, a successful import rehearsal, and a healthy canary are separate evidence. Production admission also
requires the approved image/revision, live routing, monitoring/alerts, and an owned incident path.

## 4. Continue Evolving the Domain Model

After cutover, evolve through new commands/events and explicit serialization contracts. Do not rewrite committed event
history simply to make the current class shape convenient.

For each change:

- prove old events still deserialize and source into the intended current state, or provide a reviewed offline history
  conversion;
- keep new event semantics explicit instead of silently changing an existing field;
- rebuild snapshots and projections from events when their derived shape changes;
- reconcile state/read models before deleting old fields or migration adapters;
- pin the rollback version and decide how it handles events written by the new version.

Snapshot or projection rebuild success does not repair an incorrect event mapping; representative and edge-case replay
must validate business state.

## Completion Checklist

- [ ] bounded context, aggregate identity, tenant/owner mapping, invariants, and exclusions are approved
- [ ] command/event/state contracts and AggregateSpec rejection/idempotency cases pass
- [ ] import/CDC manifest is durable, restartable, and deterministic
- [ ] one authoritative writer is provable throughout every phase
- [ ] source/target counts, versions, checksums, invariants, replay, and read models reconcile
- [ ] read cutover and write cutover were rehearsed independently
- [ ] rollback before and after the first Wow write was exercised
- [ ] deployed revision, live traffic, metrics/traces, alerts, and business checks pass
- [ ] legacy writes and temporary synchronization remain until the observation window closes

## Related Pages

| Page | Relationship |
|---|---|
| [Migration Guide](../migration.md) | Scope and shared evidence gates |
| [Aggregate and Invariants](../domain/aggregate.md) | Aggregate and command/event design |
| [Testing](../test-suite.md) | Domain behavior verification |
| [Business Intelligence](../bi.md) | Rebuildable analytical read models |
| [Migrate Wow v6 to v8](./v6-to-v8.md) | Existing Wow version upgrade |

<!-- Sources: example order API/domain/spec, CommandFactory/CommandGateway idempotency path,
event/snapshot/query contracts, and Wow test DSL -->
